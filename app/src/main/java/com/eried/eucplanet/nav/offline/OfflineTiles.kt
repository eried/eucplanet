package com.eried.eucplanet.nav.offline

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.data.model.OfflineRoutingSettings
import com.eried.eucplanet.data.repository.SettingsRepository
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * What the offline data screen works with: the server's tiles, downloads
 * running in WorkManager, and the tiles on the phone.
 */
class OfflineTiles(
    private val context: Context,
    val store: OfflineTileStore,
    private val settings: SettingsRepository,
) {
    private val work get() = WorkManager.getInstance(context)

    /** Tile name to download progress (0 to 1) for tiles queued or downloading. */
    val progress: Flow<Map<String, Float>> =
        work.getWorkInfosByTagFlow(TileDownloadWorker.TAG_TILE).map { infos ->
            infos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                .mapNotNull { info ->
                    val name = info.tags.firstOrNull { it.startsWith(NAME_TAG) }?.removePrefix(NAME_TAG)
                        ?: return@mapNotNull null
                    name to info.progress.getFloat(TileDownloadWorker.KEY_PROGRESS, 0f)
                }.toMap()
        }

    /** The last list read, with each tile's link and the tag table's. */
    @Volatile private var known: Listing? = null

    private class Listing(val tiles: Map<String, RemoteTile>, val source: TileSource, val lookupsUrl: String)

    /** The server's tiles, or null when it cannot be reached. When the
     *  chosen server fails, brouter.de's list stands in. */
    suspend fun remote(): List<RemoteTile>? = withContext(Dispatchers.IO) {
        val chosen = TileSource.of(settings.currentOrLoad().offlineRouting.serverUrl)
        val listing = read(chosen)
            ?: TileSource.of(OfflineRoutingSettings.BROUTER_URL).takeIf { it.base != chosen.base }?.let { read(it) }
        listing?.let { known = it }
        listing?.tiles?.values?.toList()
    }

    private fun read(source: TileSource): Listing? = try {
        val conn = (URL(source.listUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "EUCPlanet/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != 200) {
                null
            } else {
                val body = conn.inputStream.bufferedReader().readText()
                if (source.isMirror) {
                    TileIndex.parseMirror(body)?.let { m -> Listing(m.tiles.associateBy { it.name }, source, m.lookupsUrl) }
                } else {
                    TileIndex.parseListing(body).takeIf { it.isNotEmpty() }
                        ?.let { Listing(it.associateBy { t -> t.name }, source, source.lookupsUrl) }
                }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "tile list ${source.listUrl}: ${e.message}")
        null
    }

    /** Downloads (or updates) [names]; a tile already queued keeps its place. */
    suspend fun download(names: Collection<String>, unmeteredOnly: Boolean = false) {
        val listing = known ?: run { remote(); known } ?: return
        val net = if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        for (name in names) {
            val url = listing.tiles[name]?.url ?: listing.source.tileUrl(name)
            val req = OneTimeWorkRequestBuilder<TileDownloadWorker>()
                .setInputData(workDataOf(
                    TileDownloadWorker.KEY_NAME to name,
                    TileDownloadWorker.KEY_URL to url,
                    TileDownloadWorker.KEY_LOOKUPS_URL to listing.lookupsUrl,
                ))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(net).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TileDownloadWorker.TAG_TILE)
                .addTag(NAME_TAG + name)
                .build()
            work.enqueueUniqueWork(uniqueName(name), ExistingWorkPolicy.KEEP, req)
        }
    }

    /** Stops a download and drops what it had. */
    fun cancel(name: String) {
        work.cancelUniqueWork(uniqueName(name))
        store.partFile(name).delete()
    }

    fun delete(name: String) {
        work.cancelUniqueWork(uniqueName(name))
        store.delete(name)
    }

    /** Downloaded tiles the server has a newer build of. */
    fun outdated(remote: List<RemoteTile>): List<String> =
        remote.filter { TileIndex.needsUpdate(store.finalFile(it.name), it) }.map { it.name }

    /** Weekly check on Wi-Fi that updates downloaded tiles, or not. */
    fun scheduleAutoUpdate(enabled: Boolean) {
        if (!enabled) {
            work.cancelUniqueWork(AUTO_UPDATE)
            return
        }
        val req = PeriodicWorkRequestBuilder<TileUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        work.enqueueUniquePeriodicWork(AUTO_UPDATE, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun uniqueName(name: String) = "offline-tile-$name"

    companion object {
        private const val TAG = "OfflineTiles"
        private const val NAME_TAG = "tile:"
        private const val AUTO_UPDATE = "offline-routing-auto-update"
    }
}
