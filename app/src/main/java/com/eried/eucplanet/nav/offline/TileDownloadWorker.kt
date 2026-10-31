package com.eried.eucplanet.nav.offline

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eried.eucplanet.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Downloads one square: its routing tile into `<name>.rd5.part`, then its
 * offline map into `<name>.pmtiles.part`, each renamed once it is whole. A stopped or failed run keeps the part and the next one resumes
 * with an HTTP range, so a 250 MB tile survives the app closing, the phone
 * sleeping and the network dropping. The tag table comes along with the
 * first tile.
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
        val mapUrl = inputData.getString(KEY_MAP_URL)
        // Share of the progress bar the routing tile takes; 0 when only the
        // map is missing.
        val routeShare = inputData.getFloat(KEY_ROUTE_SHARE, 1f).coerceIn(0f, 1f)
        try {
            if (routeShare > 0f || !store.has(name)) {
                // The tag table must match the tiles: fetch it with the first
                // tile and refresh it when a month old.
                // Several tiles download at once; one of them fetches it.
                lookupsLock.withLock {
                    val lookups = store.lookupsFile()
                    if (!lookups.isFile || System.currentTimeMillis() - lookups.lastModified() > LOOKUPS_MAX_AGE_MS) {
                        fetch(lookupsUrl, File(store.dir, "lookups.dat.part"), lookups)
                    }
                }
                val span = if (mapUrl == null) 1f else routeShare
                if (!fetch(tileUrl, store.partFile(name), store.finalFile(name), TileCheck::problem, 0f, span)) {
                    return@withContext Result.retry()
                }
            }
            if (mapUrl != null) {
                val from = if (routeShare > 0f) routeShare else 0f
                if (!fetch(mapUrl, store.mapPartFile(name), store.mapFile(name), MapCheck::problem, from, 1f - from)) {
                    return@withContext Result.retry()
                }
            }
            Result.success()
        } catch (e: MissingOnServer) {
            Log.w(TAG, "$name: not on the server")
            Result.failure()
        } catch (e: IOException) {
            Log.w(TAG, "$name: ${e.message}")
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    /** Fetches [url] into [part], resuming what is there, and renames it to
     *  [target] when the length matches and [check] finds it whole. Progress
     *  fills [from] to [from] + [span] of the bar. False when stopped first. */
    private suspend fun fetch(
        url: String,
        part: File,
        target: File,
        check: ((File) -> String?)? = null,
        from: Float = 0f,
        span: Float = 0f,
    ): Boolean {
        val meta = File(part.path + ".date")
        val resumeFrom = if (meta.isFile) resumeDate(meta.readText(), url) else null
        var have = if (part.isFile && resumeFrom != null) part.length() else 0L
        if (have == 0L) part.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "EUCPlanet/${BuildConfig.VERSION_NAME}")
            if (have > 0) {
                setRequestProperty("Range", "bytes=$have-")
                // Only resume the same build; a newer one starts over.
                setRequestProperty("If-Range", resumeFrom!!)
            }
        }
        try {
            val code = conn.responseCode
            if (code == 416 && have > 0) {
                // Asked past the end: the part is the whole file, if its
                // length is the server's. Anything else starts over.
                if (totalFromContentRange(conn.getHeaderField("Content-Range")) == have) {
                    return finish(part, target, meta, conn.getHeaderFieldDate("Last-Modified", 0L), check)
                }
                part.delete()
                meta.delete()
                throw IOException("stale part")
            }
            if (isPermanent(code)) throw MissingOnServer()
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            if (code == 200) have = 0L
            val total = have + conn.contentLengthLong.coerceAtLeast(0L)
            conn.getHeaderField("Last-Modified")?.let { meta.writeText(partMeta(url, it)) }
            val modified = conn.getHeaderFieldDate("Last-Modified", 0L)
            conn.inputStream.use { input ->
                FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = have
                    var lastReport = 0L
                    while (true) {
                        if (isStopped) return false
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastReport > PROGRESS_EVERY_MS) {
                            lastReport = now
                            if (span > 0f) setProgress(workDataOf(KEY_PROGRESS to from + span * got.toFloat() / total))
                        }
                    }
                }
            }
            if (total > 0 && part.length() != total) throw IOException("short: ${part.length()} of $total")
            return finish(part, target, meta, modified, check)
        } finally {
            conn.disconnect()
        }
    }

    private fun finish(part: File, target: File, meta: File, modified: Long, check: ((File) -> String?)?): Boolean {
        if (part.length() == 0L) throw IOException("empty")
        if (check != null) {
            // Damaged bytes start over: resuming would keep them.
            check(part)?.let {
                part.delete()
                meta.delete()
                throw IOException("damaged: $it")
            }
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("rename failed")
        if (modified > 0) target.setLastModified(modified)
        meta.delete()
        return true
    }

    private class MissingOnServer : IOException()

    companion object {
        private val lookupsLock = kotlinx.coroutines.sync.Mutex()

        /** The server says the file is not there: open sea has no square. */
        fun isPermanent(code: Int): Boolean = code == 404 || code == 410

        /** The whole length from a Content-Range header ("bytes *" + "/123",
         *  "bytes 0-99/100"), or null when the server does not say. */
        fun totalFromContentRange(header: String?): Long? =
            header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

        /** What a part keeps beside it: the link it came from and that
         *  build's date. */
        fun partMeta(url: String, lastModified: String): String = "$url\n$lastModified"

        /** The date to resume [url] with, or null to start over. GitHub
         *  ignores If-Range, so a part only resumes from the link it came
         *  from: a newer mirror build has a new link. */
        fun resumeDate(meta: String?, url: String): String? {
            val lines = meta?.lines() ?: return null
            if (lines.size != 2 || lines[0] != url) return null
            return lines[1].takeIf { it.isNotBlank() }
        }

        private const val TAG = "TileDownload"
        const val KEY_NAME = "name"
        const val KEY_SERVER = "server"
        const val KEY_URL = "url"
        const val KEY_LOOKUPS_URL = "lookupsUrl"
        const val KEY_MAP_URL = "mapUrl"
        const val KEY_ROUTE_SHARE = "routeShare"
        const val KEY_PROGRESS = "progress"
        const val TAG_TILE = "offline-routing-tile"
        private const val MAX_ATTEMPTS = 8
        private const val PROGRESS_EVERY_MS = 500L
        private const val LOOKUPS_MAX_AGE_MS = 30L * 86_400_000L
    }
}
