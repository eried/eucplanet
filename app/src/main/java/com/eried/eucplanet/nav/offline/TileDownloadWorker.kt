package com.eried.eucplanet.nav.offline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eried.eucplanet.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads one square in WorkManager: the shared [TileDownload] (resume,
 * checks, the tag table) over [AndroidTileHttp], its outcome as the work's.
 */
class TileDownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val store: OfflineTileStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val name = inputData.getString(KEY_NAME) ?: return@withContext Result.failure()
        if (!RoutingTiles.isTileName(name)) return@withContext Result.failure()
        // Links from the list the rider saw; a run queued by an older build
        // only has the server.
        val legacy = inputData.getString(KEY_SERVER)?.let { TileSource.of(it) }
        val tileUrl = inputData.getString(KEY_URL) ?: legacy?.tileUrl(name) ?: return@withContext Result.failure()
        val lookupsUrl = inputData.getString(KEY_LOOKUPS_URL) ?: legacy?.lookupsUrl ?: return@withContext Result.failure()
        val job = TileJob(
            name = name,
            url = tileUrl,
            lookupsUrl = lookupsUrl,
            mapUrl = inputData.getString(KEY_MAP_URL),
            routeShare = inputData.getFloat(KEY_ROUTE_SHARE, 1f),
        )
        val outcome = TileDownload(store, AndroidTileHttp, USER_AGENT).run(
            job,
            attempt = runAttemptCount,
            isStopped = { isStopped },
            progress = { setProgressAsync(workDataOf(KEY_PROGRESS to it)) },
        )
        when (outcome) {
            TileDownload.Outcome.SUCCESS -> Result.success()
            TileDownload.Outcome.RETRY -> Result.retry()
            TileDownload.Outcome.FAILURE -> Result.failure()
        }
    }

    companion object {
        private val USER_AGENT = "EUCPlanet/${BuildConfig.VERSION_NAME}"
        const val KEY_NAME = "name"
        const val KEY_SERVER = "server"
        const val KEY_URL = "url"
        const val KEY_LOOKUPS_URL = "lookupsUrl"
        const val KEY_MAP_URL = "mapUrl"
        const val KEY_ROUTE_SHARE = "routeShare"
        const val KEY_PROGRESS = "progress"
        const val TAG_TILE = "offline-routing-tile"
    }
}

/** A tile GET over HttpURLConnection, with the timeouts the downloads always had. */
object AndroidTileHttp : TileHttp {
    override fun get(
        url: String,
        headers: List<Pair<String, String>>,
        onHead: (TileReply) -> Boolean,
        onBody: (buf: ByteArray, len: Int) -> Boolean,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val reply = TileReply(
                code = conn.responseCode,
                contentLength = conn.contentLengthLong,
                lastModifiedMs = conn.getHeaderFieldDate("Last-Modified", 0L),
                headers = conn::getHeaderField,
            )
            if (!onHead(reply)) return
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (!onBody(buf, n)) break
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
