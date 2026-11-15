package com.eried.eucplanet.nav.offline

import btools.util.IOException
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The plain GET a tile download makes, read as it arrives. Android's is
 * HttpURLConnection (AndroidTileHttp), the iPhone's an NSURLSession data
 * task (IosTileHttp). Blocking; throws [IOException] when no reply comes.
 */
interface TileHttp {
    /**
     * Asks for [url] with [headers]. [onHead] sees the reply first and
     * returns false to stop before the body; [onBody] then gets each chunk
     * (the first [len] bytes of the buffer) and returns false to stop.
     */
    fun get(
        url: String,
        headers: List<Pair<String, String>>,
        onHead: (TileReply) -> Boolean,
        onBody: (buf: ByteArray, len: Int) -> Boolean,
    )
}

/** The status and headers of a [TileHttp] reply. */
class TileReply(
    val code: Int,
    /** The body's length, -1 when the server does not say. */
    val contentLength: Long,
    /** Last-Modified as milliseconds since 1970, 0 without one. */
    val lastModifiedMs: Long,
    private val headers: (String) -> String?,
) {
    fun header(name: String): String? = headers(name)
}

/** One square to fetch: the links from the list the rider saw. */
data class TileJob(
    val name: String,
    val url: String,
    val lookupsUrl: String,
    val mapUrl: String?,
    /** Share of the progress bar the routing tile takes; 0 when only the map is missing. */
    val routeShare: Float,
)

/**
 * Downloads one square: its routing tile into `<name>.rd5.part`, then its
 * offline map into `<name>.pmtiles.part`, each renamed once it is whole. A
 * stopped or failed run keeps the part and the next one resumes with an
 * HTTP range, so a 250 MB tile survives the app closing, the phone sleeping
 * and the network dropping. The tag table comes along with the first tile.
 * Android runs it in a WorkManager worker (TileDownloadWorker), the iPhone
 * in its download queue (IosTileJobs).
 */
class TileDownload(
    private val store: OfflineTileStore,
    private val http: TileHttp,
    private val userAgent: String,
    private val routeCheck: (String) -> String? = TileCheck::problem,
    private val mapCheck: (String) -> String? = MapCheck::problem,
) {
    enum class Outcome { SUCCESS, RETRY, FAILURE }

    private val files get() = store.files

    /**
     * Runs [job]. [attempt] counts the runs before this one; [isStopped]
     * ends it early (the part stays); [progress] gets 0 to 1, from the
     * thread reading the reply.
     */
    suspend fun run(
        job: TileJob,
        attempt: Int,
        isStopped: () -> Boolean,
        progress: (Float) -> Unit,
    ): Outcome {
        val name = job.name
        if (!RoutingTiles.isTileName(name)) return Outcome.FAILURE
        val routeShare = job.routeShare.coerceIn(0f, 1f)
        val mapUrl = job.mapUrl
        return try {
            if (routeShare > 0f || !store.has(name)) {
                // The tag table must match the tiles: fetch it with the first
                // tile and refresh it when a month old.
                // Several tiles download at once; one of them fetches it.
                lookupsLock.withLock {
                    val lookups = store.lookupsFile()
                    if (!files.isFile(lookups) || System.currentTimeMillis() - files.lastModified(lookups) > LOOKUPS_MAX_AGE_MS) {
                        fetch(job.lookupsUrl, "$lookups.part", lookups, isStopped, progress)
                    }
                }
                val span = if (mapUrl == null) 1f else routeShare
                if (!fetch(job.url, store.partFile(name), store.finalFile(name), isStopped, progress, routeCheck, 0f, span)) {
                    return Outcome.RETRY
                }
            }
            if (mapUrl != null) {
                val from = if (routeShare > 0f) routeShare else 0f
                if (!fetch(mapUrl, store.mapPartFile(name), store.mapFile(name), isStopped, progress, mapCheck, from, 1f - from)) {
                    return Outcome.RETRY
                }
            }
            Outcome.SUCCESS
        } catch (e: MissingOnServer) {
            Log.w(TAG, "$name: not on the server")
            Outcome.FAILURE
        } catch (e: IOException) {
            Log.w(TAG, "$name: ${e.message}")
            if (attempt >= MAX_ATTEMPTS) Outcome.FAILURE else Outcome.RETRY
        }
    }

    /** Fetches [url] into [part], resuming what is there, and renames it to
     *  [target] when the length matches and [check] finds it whole. Progress
     *  fills [from] to [from] + [span] of the bar. False when stopped first. */
    private fun fetch(
        url: String,
        part: String,
        target: String,
        isStopped: () -> Boolean,
        progress: (Float) -> Unit,
        check: ((String) -> String?)? = null,
        from: Float = 0f,
        span: Float = 0f,
    ): Boolean {
        val meta = "$part.date"
        val resumeFrom = if (files.isFile(meta)) resumeDate(files.readText(meta), url) else null
        var have = if (files.isFile(part) && resumeFrom != null) files.length(part) else 0L
        if (have == 0L) files.delete(part)
        val headers = buildList {
            add("User-Agent" to userAgent)
            if (have > 0) {
                add("Range" to "bytes=$have-")
                // Only resume the same build; a newer one starts over.
                add("If-Range" to resumeFrom!!)
            }
        }
        var reply: TileReply? = null
        var stopped = false
        var total = 0L
        var sink: FileSink? = null
        var got = 0L
        var lastReport = 0L
        try {
            http.get(
                url, headers,
                onHead = { r ->
                    reply = r
                    val code = r.code
                    when {
                        code == 416 && have > 0 -> false
                        isPermanent(code) -> false
                        code != 200 && code != 206 -> false
                        else -> {
                            if (code == 200) have = 0L
                            total = have + r.contentLength.coerceAtLeast(0L)
                            validator(r.header("Last-Modified"), r.header("ETag"))?.let { files.writeText(meta, partMeta(url, it)) }
                            sink = files.openWrite(part, have > 0)
                            got = have
                            true
                        }
                    }
                },
                onBody = { buf, n ->
                    if (isStopped()) {
                        stopped = true
                        false
                    } else {
                        sink!!.write(buf, 0, n)
                        got += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastReport > PROGRESS_EVERY_MS) {
                            lastReport = now
                            if (span > 0f) progress(from + span * got.toFloat() / total)
                        }
                        true
                    }
                },
            )
        } finally {
            sink?.close()
        }
        val r = reply ?: throw IOException("no reply")
        val code = r.code
        if (code == 416 && have > 0) {
            // Asked past the end: the part is the whole file, if its
            // length is the server's. Anything else starts over.
            if (totalFromContentRange(r.header("Content-Range")) == have) {
                return finish(part, target, meta, r.lastModifiedMs, check)
            }
            files.delete(part)
            files.delete(meta)
            throw IOException("stale part")
        }
        if (isPermanent(code)) throw MissingOnServer()
        if (code != 200 && code != 206) throw IOException("HTTP $code")
        if (stopped) return false
        if (total > 0 && files.length(part) != total) throw IOException("short: ${files.length(part)} of $total")
        return finish(part, target, meta, r.lastModifiedMs, check)
    }

    private fun finish(part: String, target: String, meta: String, modified: Long, check: ((String) -> String?)?): Boolean {
        if (files.length(part) == 0L) throw IOException("empty")
        if (check != null) {
            // Damaged bytes start over: resuming would keep them.
            check(part)?.let {
                files.delete(part)
                files.delete(meta)
                throw IOException("damaged: $it")
            }
        }
        files.delete(target)
        if (!files.rename(part, target)) throw IOException("rename failed")
        if (modified > 0) files.setLastModified(target, modified)
        files.delete(meta)
        return true
    }

    private class MissingOnServer : IOException("not on the server")

    companion object {
        private val lookupsLock = Mutex()

        /** The server says the file is not there: open sea has no square. */
        fun isPermanent(code: Int): Boolean = code == 404 || code == 410

        /** The whole length from a Content-Range header ("bytes *" + "/123",
         *  "bytes 0-99/100"), or null when the server does not say. */
        fun totalFromContentRange(header: String?): Long? =
            header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

        /** What a part keeps beside it: the link it came from and that
         *  build's date. */
        fun partMeta(url: String, lastModified: String): String = "$url\n$lastModified"

        /** What a part resumes with in If-Range: the build's date, or its
         *  strong ETag where the server sends no date (Hugging Face). */
        fun validator(lastModified: String?, etag: String?): String? =
            lastModified?.takeIf { it.isNotBlank() }
                ?: etag?.trim()?.takeIf { it.startsWith("\"") }

        /** The date to resume [url] with, or null to start over. GitHub
         *  ignores If-Range, so a part only resumes from the link it came
         *  from: a newer mirror build has a new link. */
        fun resumeDate(meta: String?, url: String): String? {
            val lines = meta?.lines() ?: return null
            if (lines.size != 2 || lines[0] != url) return null
            return lines[1].takeIf { it.isNotBlank() }
        }

        private const val TAG = "TileDownload"
        const val MAX_ATTEMPTS = 8
        private const val PROGRESS_EVERY_MS = 500L
        private const val LOOKUPS_MAX_AGE_MS = 30L * 86_400_000L
    }
}
