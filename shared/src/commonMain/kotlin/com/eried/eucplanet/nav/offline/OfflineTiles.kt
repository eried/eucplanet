package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.nav.NavHttp
import com.eried.eucplanet.platform.Log
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Where tile downloads run and wait: Android's WorkManager
 * (WorkManagerTileJobs), the iPhone's own queue (IosTileJobs). A job keeps
 * its place when queued twice, retries on failure and survives the app
 * closing.
 */
interface TileJobs {
    /** Tile name to download progress (0 to 1) for tiles queued or downloading. */
    val progress: Flow<Map<String, Float>>

    /** Queues [job], on Wi-Fi only when [unmeteredOnly]; a tile already queued keeps its place. */
    fun enqueue(job: TileJob, unmeteredOnly: Boolean)

    /** Stops [name]'s download, queued or running. */
    fun cancel(name: String)

    /** The weekly check on Wi-Fi that runs [OfflineTiles.autoUpdate], on or off. */
    fun scheduleAutoUpdate(enabled: Boolean)
}

/**
 * What the offline data screen works with: the server's tiles, downloads
 * running in [jobs], and the tiles on the phone.
 */
class OfflineTiles(
    val store: OfflineTileStore,
    private val settings: SettingsRepository,
    private val http: NavHttp,
    private val jobs: TileJobs,
    private val userAgent: String,
) {
    /** Tile name to download progress (0 to 1) for tiles queued or downloading. */
    val progress: Flow<Map<String, Float>> get() = jobs.progress

    /** The last list read, with each tile's link and the tag table's. */
    @Volatile private var known: Listing? = null

    private class Listing(val tiles: Map<String, RemoteTile>, val source: TileSource, val lookupsUrl: String)

    /** The squares the server listed last time it was read, or null when
     *  it has not been read since the app started. */
    fun served(): Set<String>? = known?.tiles?.keys

    /** The server's tiles, or null when it cannot be reached. When the
     *  chosen server fails, the next of [TileSource.fallbacks] stands in.
     *  A BRouter server has no maps, so those come from our mirror when one
     *  can be read. */
    suspend fun remote(): List<RemoteTile>? = withContext(Dispatchers.IO) {
        val tried = mutableSetOf<String>()
        val listing = TileSource.fallbacks(settings.currentOrLoad().offlineRouting.serverUrl)
            .firstNotNullOfOrNull { tried += it.base; read(it) }
            ?.let { l ->
                // Asking a mirror again after it just failed only adds a wait.
                val maps = if (l.source.isMirror) null
                    else TileSource.mirrors.filter { it.base !in tried }.firstNotNullOfOrNull { read(it) }
                if (maps == null) l
                else Listing(TileIndex.withMaps(l.tiles.values.toList(), maps.tiles.values.toList()).associateBy { it.name }, l.source, l.lookupsUrl)
            }
        listing?.let { known = it }
        listing?.tiles?.values?.toList()
    }

    private fun read(source: TileSource): Listing? = try {
        val reply = http.get(source.listUrl, listOf("User-Agent" to userAgent), 15_000, 30_000)
        val body = reply.body
        if (reply.code != 200 || body == null) {
            null
        } else if (source.isMirror) {
            TileIndex.parseMirror(body)?.let { m -> Listing(m.tiles.associateBy { it.name }, source, m.lookupsUrl) }
        } else {
            TileIndex.parseListing(body).takeIf { it.isNotEmpty() }
                ?.let { Listing(it.associateBy { t -> t.name }, source, source.lookupsUrl) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "tile list ${source.listUrl}: ${e.message}")
        null
    }

    /** Downloads (or updates) [names]; a tile already queued keeps its place. */
    suspend fun download(names: Collection<String>, unmeteredOnly: Boolean = false) {
        val listing = known ?: run { remote(); known } ?: return
        for (name in names) {
            val remote = listing.tiles[name]
            val url = remote?.url ?: listing.source.tileUrl(name)
            // A square whose routing is current only fetches its map.
            val routeCurrent = store.has(name) && remote != null && !TileIndex.needsUpdate(store.finalFile(name), remote)
            // A current map is not fetched again with new routing.
            val map = remote?.map?.takeIf { TileIndex.mapWanted(store.mapFile(name), remote) }
            jobs.enqueue(
                TileJob(
                    name = name,
                    url = url,
                    lookupsUrl = listing.lookupsUrl,
                    mapUrl = map?.url,
                    routeShare = when {
                        routeCurrent -> 0f
                        map == null -> 1f
                        else -> remote.bytes.toFloat() / (remote.bytes + map.bytes).coerceAtLeast(1L)
                    },
                ),
                unmeteredOnly,
            )
        }
    }

    /** Stops a download and drops what it had. */
    fun cancel(name: String) {
        jobs.cancel(name)
        store.files.delete(store.partFile(name))
        store.files.delete(store.mapPartFile(name))
    }

    fun delete(name: String) {
        jobs.cancel(name)
        store.delete(name)
    }

    /** Downloaded tiles the server has a newer build of. */
    fun outdated(remote: List<RemoteTile>): List<String> =
        remote.filter {
            TileIndex.needsUpdate(store.finalFile(it.name), it) ||
                TileIndex.mapNeedsUpdate(store.finalFile(it.name), store.mapFile(it.name), it)
        }.map { it.name }

    /** Weekly check on Wi-Fi that updates downloaded tiles, or not. */
    fun scheduleAutoUpdate(enabled: Boolean) = jobs.scheduleAutoUpdate(enabled)

    /**
     * The weekly Wi-Fi check: queues a download for every tile the server
     * has a newer build of, on Wi-Fi only. Runs only while the rider has it
     * on. False when the server could not be read, to try again later.
     */
    suspend fun autoUpdate(): Boolean {
        if (!settings.currentOrLoad().offlineRouting.wifiAutoUpdate) return true
        if (store.downloaded().isEmpty()) return true
        val remote = remote() ?: return false
        download(outdated(remote), unmeteredOnly = true)
        return true
    }

    private companion object {
        const val TAG = "OfflineTiles"
    }
}
