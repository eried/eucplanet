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
import kotlinx.coroutines.withContext

/**
 * Downloads one routing tile into `<name>.rd5.part` and renames it once it
 * is whole. A stopped or failed run keeps the part and the next one resumes
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
        val server = inputData.getString(KEY_SERVER)?.trimEnd('/') ?: return@withContext Result.failure()
        if (!RoutingTiles.isTileName(name)) return@withContext Result.failure()
        try {
            // The tag table must match the tiles: fetch it with the first
            // tile and refresh it when a month old.
            val lookups = store.lookupsFile()
            if (!lookups.isFile || System.currentTimeMillis() - lookups.lastModified() > LOOKUPS_MAX_AGE_MS) {
                fetch("$server/profiles2/lookups.dat", File(store.dir, "lookups.dat.part"), store.lookupsFile())
            }
            val done = fetch("$server/segments4/$name.rd5", store.partFile(name), store.finalFile(name))
            if (done) Result.success() else Result.retry()
        } catch (e: IOException) {
            Log.w(TAG, "$name: ${e.message}")
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    /** Fetches [url] into [part], resuming what is there, and renames it to
     *  [target] when the length matches. False when stopped first. */
    private suspend fun fetch(url: String, part: File, target: File): Boolean {
        val meta = File(part.path + ".date")
        var have = if (part.isFile && meta.isFile) part.length() else 0L
        if (have == 0L) part.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "EUCPlanet/${BuildConfig.VERSION_NAME}")
            if (have > 0) {
                setRequestProperty("Range", "bytes=$have-")
                // Only resume the same build; a newer one starts over.
                setRequestProperty("If-Range", meta.readText())
            }
        }
        try {
            val code = conn.responseCode
            if (code == 416 && have > 0) {
                // Asked past the end: the part is the whole file.
                return finish(part, target, meta, conn.getHeaderFieldDate("Last-Modified", 0L))
            }
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            if (code == 200) have = 0L
            val total = have + conn.contentLengthLong.coerceAtLeast(0L)
            conn.getHeaderField("Last-Modified")?.let { meta.writeText(it) }
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
                            setProgress(workDataOf(KEY_PROGRESS to got.toFloat() / total))
                        }
                    }
                }
            }
            if (total > 0 && part.length() != total) throw IOException("short: ${part.length()} of $total")
            return finish(part, target, meta, modified)
        } finally {
            conn.disconnect()
        }
    }

    private fun finish(part: File, target: File, meta: File, modified: Long): Boolean {
        if (part.length() == 0L) throw IOException("empty")
        target.delete()
        if (!part.renameTo(target)) throw IOException("rename failed")
        if (modified > 0) target.setLastModified(modified)
        meta.delete()
        return true
    }

    companion object {
        private const val TAG = "TileDownload"
        const val KEY_NAME = "name"
        const val KEY_SERVER = "server"
        const val KEY_PROGRESS = "progress"
        const val TAG_TILE = "offline-routing-tile"
        private const val MAX_ATTEMPTS = 8
        private const val PROGRESS_EVERY_MS = 500L
        private const val LOOKUPS_MAX_AGE_MS = 30L * 86_400_000L
    }
}
