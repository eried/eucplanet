package com.eried.eucplanet.app

import btools.util.joinPath
import com.eried.eucplanet.nav.offline.OfflineTileStore
import com.eried.eucplanet.nav.offline.OfflineTiles
import com.eried.eucplanet.nav.offline.TileDownload
import com.eried.eucplanet.nav.offline.TileJob
import com.eried.eucplanet.nav.offline.TileJobs
import com.eried.eucplanet.platform.JSONArray
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * The iPhone's tile download queue, standing in for Android's WorkManager
 * (WorkManagerTileJobs): each square once (queued again, it keeps its
 * place), two at a time, retried after 30 s doubling up to five hours, on
 * Wi-Fi only when asked. The queue is a file beside the tiles, so a
 * download the app was closed in starts again (resuming its part) at the
 * next launch. iOS lets an app run on for a short while in the background:
 * a download carries on that long, then stops and resumes when the app is
 * back in front. The weekly Wi-Fi update check runs at launch and on each
 * return to the front once it is due.
 */
internal class IosTileJobs(
    private val store: OfflineTileStore,
    private val userAgent: String,
) : TileJobs {
    private class Entry(val job: TileJob, val unmeteredOnly: Boolean, var attempts: Int) {
        /** Cancelled: the run ends for good. */
        @Volatile var stopped = false
        /** The background time ran out: the run ends and starts again in front. */
        @Volatile var paused = false
        var runner: Job? = null
    }

    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(PARALLEL)
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val queueFile = joinPath(store.dir, "queue.json")
    private val checkedFile = joinPath(store.dir, "update-check")

    /** Set once the graph is built: the weekly check asks it what is outdated. */
    var tiles: OfflineTiles? = null

    override val progress: Flow<Map<String, Float>> = _progress.asStateFlow()

    /** The queue left from the last run, and the weekly check, from now on. */
    fun start() {
        val saved = runCatching { store.files.readText(queueFile)?.let { JSONArray(it) } }.getOrNull()
        if (saved != null) {
            for (i in 0 until saved.length()) {
                val o = saved.optJSONObject(i) ?: continue
                val job = TileJob(
                    name = o.optString("name"),
                    url = o.optString("url"),
                    lookupsUrl = o.optString("lookupsUrl"),
                    mapUrl = o.optString("mapUrl").ifBlank { null },
                    routeShare = o.optDouble("routeShare", 1.0).toFloat(),
                )
                add(job, o.optBoolean("unmeteredOnly"), o.optInt("attempts"))
            }
        }
        scope.launch {
            IosForeground.visible.collect { front -> if (front) checkForUpdates() }
        }
    }

    override fun enqueue(job: TileJob, unmeteredOnly: Boolean) = add(job, unmeteredOnly, 0)

    private fun add(job: TileJob, unmeteredOnly: Boolean, attempts: Int) {
        val entry = synchronized(lock) {
            if (job.name in entries) return
            Entry(job, unmeteredOnly, attempts).also { entries[job.name] = it }
        }
        publish(job.name, 0f)
        save()
        entry.runner = scope.launch { run(entry) }
    }

    override fun cancel(name: String) {
        val e = synchronized(lock) { entries.remove(name) } ?: return
        e.stopped = true
        e.runner?.cancel()
        drop(name)
        save()
    }

    override fun scheduleAutoUpdate(enabled: Boolean) {
        if (enabled) scope.launch { checkForUpdates() }
    }

    private suspend fun run(e: Entry) {
        val name = e.job.name
        val download = TileDownload(store, IosTileHttp, userAgent)
        while (!e.stopped) {
            // WorkManager's constraints: a network, Wi-Fi when asked.
            IosNetwork.online.first { it && !IosNetwork.forcedOffline }
            if (e.unmeteredOnly) IosNetwork.unmetered.first { it }
            val outcome = slots.withPermit {
                if (e.stopped) return
                e.paused = false
                val background = keepRunning(e)
                try {
                    download.run(e.job, e.attempts, { e.stopped || e.paused }) { publish(name, it) }
                } finally {
                    endBackground(background)
                }
            }
            if (e.stopped) return
            when (outcome) {
                TileDownload.Outcome.SUCCESS, TileDownload.Outcome.FAILURE -> {
                    Log.i(TAG, "$name: ${outcome.name.lowercase()}")
                    synchronized(lock) { entries.remove(name) }
                    drop(name)
                    save()
                    IosOfflineMap.drawer.reset()
                    return
                }
                TileDownload.Outcome.RETRY -> if (e.paused) {
                    // Cut short by the background: again once the app is in front.
                    IosForeground.visible.first { it }
                } else {
                    e.attempts++
                    save()
                    val wait = (BACKOFF_MS shl (e.attempts - 1).coerceIn(0, 20)).coerceAtMost(MAX_BACKOFF_MS)
                    Log.i(TAG, "$name: again in ${wait / 1000} s")
                    delay(wait)
                }
            }
        }
    }

    /** Asks iOS for time to finish when the app goes to the background; when it runs out, the run stops and keeps its part. */
    private fun keepRunning(e: Entry): UIBackgroundTaskIdentifier {
        var id: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
        id = UIApplication.sharedApplication.beginBackgroundTaskWithName("tile ${e.job.name}") {
            Log.i(TAG, "${e.job.name}: background time is up, resuming in front")
            e.paused = true
            UIApplication.sharedApplication.endBackgroundTask(id)
        }
        return id
    }

    private fun endBackground(id: UIBackgroundTaskIdentifier) {
        if (id != UIBackgroundTaskInvalid) UIApplication.sharedApplication.endBackgroundTask(id)
    }

    /** The weekly check, when the rider has it on, a week has passed and the phone is on Wi-Fi. */
    private suspend fun checkForUpdates() {
        val t = tiles ?: return
        if (!IosAppGraph.settings.currentOrLoad().offlineRouting.wifiAutoUpdate) return
        if (!IosNetwork.onWifi()) return
        val last = store.files.readText(checkedFile)?.trim()?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < WEEK_MS) return
        if (t.autoUpdate()) store.files.writeText(checkedFile, System.currentTimeMillis().toString())
    }

    private fun publish(name: String, p: Float) {
        synchronized(lock) { if (name !in entries) return }
        _progress.value = _progress.value + (name to p)
    }

    private fun drop(name: String) {
        _progress.value = _progress.value - name
    }

    private fun save() {
        val arr = JSONArray()
        synchronized(lock) {
            for (e in entries.values) {
                arr.put(JSONObject().apply {
                    put("name", e.job.name)
                    put("url", e.job.url)
                    put("lookupsUrl", e.job.lookupsUrl)
                    put("mapUrl", e.job.mapUrl ?: "")
                    put("routeShare", e.job.routeShare.toDouble())
                    put("unmeteredOnly", e.unmeteredOnly)
                    put("attempts", e.attempts)
                })
            }
        }
        runCatching { store.files.writeText(queueFile, arr.toString()) }
            .onFailure { Log.w(TAG, "queue not saved: ${it.message}") }
    }

    private companion object {
        const val TAG = "TileJobs"
        const val PARALLEL = 2
        const val BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 5L * 3_600_000L
        const val WEEK_MS = 7L * 86_400_000L
    }
}
