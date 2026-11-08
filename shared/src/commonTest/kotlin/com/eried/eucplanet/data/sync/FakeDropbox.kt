package com.eried.eucplanet.data.sync

import com.eried.eucplanet.platform.HttpExecutor
import com.eried.eucplanet.platform.HttpRequest
import com.eried.eucplanet.platform.HttpResponse
import com.eried.eucplanet.platform.JSONArray
import com.eried.eucplanet.platform.JSONObject

/**
 * Dropbox's v2 file API as far as the app uses it, in memory: list_folder
 * (paged, with continue), upload, download, move_v2 and move_batch_v2, with
 * Dropbox's own rules where the sync depends on them. Paths ignore case and
 * keep the spelling they were created with, a file carries its content_hash
 * (Dropbox's block hash) and server_modified on the server's clock, a
 * missing folder lists as 409 path/not_found, and a move into a taken name
 * autorenames to "name (1).csv".
 *
 * It answers [HttpRequest]s, so the real [com.eried.eucplanet.data.repository.DropboxRepository]
 * builds the requests and parses the answers exactly as it does against
 * Dropbox. Shared by several phones, it is the one account they all sync with.
 */
class FakeDropbox(
    /** Entries per list_folder page; small values exercise list_folder/continue. */
    var pageSize: Int = 1000,
) : HttpExecutor {

    class Entry(val pathDisplay: String, val bytes: ByteArray, val serverModifiedSec: Long)

    private val files = LinkedHashMap<String, Entry>()

    /** The server's clock, in seconds; every write moves it on. */
    var nowSec: Long = 1_787_000_000L

    /** Answer every request with 403, as Dropbox's edge does when it cuts a client off. */
    var refuseAll = false

    /** Uploads to these paths (lowercased) fail with a 500. */
    val failingUploads = HashSet<String>()

    /** Every request's endpoint, in order, for asserting what was sent. */
    val calls = mutableListOf<String>()

    val uploads: List<String> get() = calls.filter { it.startsWith("upload ") }.map { it.removePrefix("upload ") }
    val downloads: List<String> get() = calls.filter { it.startsWith("download ") }.map { it.removePrefix("download ") }

    // --- direct access for arranging and asserting -----------------------

    fun put(path: String, bytes: ByteArray) {
        nowSec += 1
        files[path.lowercase()] = Entry(path, bytes, nowSec)
    }

    fun get(path: String): ByteArray? = files[path.lowercase()]?.bytes

    fun exists(path: String) = files.containsKey(path.lowercase())

    fun modifiedSec(path: String): Long? = files[path.lowercase()]?.serverModifiedSec

    /** The names directly inside [folder], as Dropbox spells them. */
    fun names(folder: String): List<String> = children(folder).map { it.pathDisplay.substringAfterLast('/') }

    private fun children(folder: String): List<Entry> {
        val prefix = folder.lowercase().trimEnd('/') + "/"
        return files.entries
            .filter { it.key.startsWith(prefix) && '/' !in it.key.removePrefix(prefix) }
            .map { it.value }
    }

    private fun folderExists(folder: String): Boolean {
        val prefix = folder.lowercase().trimEnd('/') + "/"
        return folder.isEmpty() || files.keys.any { it.startsWith(prefix) }
    }

    // --- the API ---------------------------------------------------------

    private var cursors = HashMap<String, List<Entry>>()

    override fun execute(request: HttpRequest): HttpResponse {
        val endpoint = request.url.substringAfter("://").substringAfter('/')
        if (refuseAll) {
            calls += "refused $endpoint"
            return json(403, JSONObject().put("error_summary", "other/").put("error", JSONObject().put(".tag", "other")))
        }
        val arg = request.headers.firstOrNull { it.first == "Dropbox-API-Arg" }?.second?.let { JSONObject(it) }
        val body = (request.body as? HttpRequest.Body.Text)?.text?.let { JSONObject(it) }
        return when (endpoint) {
            "2/files/list_folder" -> listFolder(body!!.getString("path"))
            "2/files/list_folder/continue" -> page(body!!.getString("cursor"))
            "2/files/upload" -> upload(arg!!.getString("path"), (request.body as HttpRequest.Body.Bytes).bytes)
            "2/files/download" -> download(arg!!.getString("path"))
            "2/files/move_v2" -> move(body!!.getString("from_path"), body.getString("to_path"))
            "2/files/move_batch_v2" -> moveBatch(body!!.getJSONArray("entries"))
            else -> {
                calls += "unknown $endpoint"
                json(400, JSONObject().put("error_summary", "unknown endpoint $endpoint"))
            }
        }
    }

    private fun listFolder(path: String): HttpResponse {
        calls += "list $path"
        if (!folderExists(path)) return notFound("path")
        val id = "c${cursors.size}"
        cursors[id] = children(path)
        return page(id)
    }

    private fun page(cursor: String): HttpResponse {
        val rest = cursors[cursor] ?: return json(409, JSONObject().put("error_summary", "reset/"))
        val now = rest.take(pageSize)
        val left = rest.drop(pageSize)
        val next = "$cursor+"
        cursors[next] = left
        val entries = JSONArray()
        now.forEach { entries.put(metadata(it)) }
        return json(200, JSONObject()
            .put("entries", entries)
            .put("cursor", next)
            .put("has_more", left.isNotEmpty()))
    }

    private fun upload(path: String, bytes: ByteArray): HttpResponse {
        calls += "upload $path"
        if (path.lowercase() in failingUploads) return json(500, JSONObject().put("error_summary", "internal"))
        nowSec += 1
        // Overwrite keeps the spelling the file already had, as Dropbox does.
        val display = files[path.lowercase()]?.pathDisplay ?: path
        val e = Entry(display, bytes, nowSec)
        files[path.lowercase()] = e
        return json(200, metadata(e))
    }

    private fun download(path: String): HttpResponse {
        calls += "download $path"
        val e = files[path.lowercase()] ?: return notFound("path")
        return HttpResponse(200, mapOf("Dropbox-API-Result" to metadata(e).toString()), e.bytes)
    }

    /** One move with autorename; the landed entry, or null when [from] is not there. */
    private fun moveOne(from: String, to: String): Entry? {
        val e = files.remove(from.lowercase()) ?: return null
        var target = to
        var n = 1
        val stem = to.substringBeforeLast('.')
        val ext = to.substringAfterLast('.', "")
        while (files.containsKey(target.lowercase())) {
            target = "$stem ($n)" + if (ext.isEmpty()) "" else ".$ext"
            n++
        }
        val moved = Entry(target, e.bytes, e.serverModifiedSec)
        files[target.lowercase()] = moved
        return moved
    }

    private fun move(from: String, to: String): HttpResponse {
        calls += "move $from -> $to"
        val moved = moveOne(from, to) ?: return notFound("from_lookup")
        return json(200, JSONObject().put("metadata", metadata(moved)))
    }

    private fun moveBatch(entries: JSONArray): HttpResponse {
        calls += "move_batch ${entries.length()}"
        for (i in 0 until entries.length()) {
            val o = entries.getJSONObject(i)
            // A missing entry fails on its own and leaves the rest moved.
            moveOne(o.getString("from_path"), o.getString("to_path"))
        }
        return json(200, JSONObject().put(".tag", "complete").put("entries", JSONArray()))
    }

    private fun metadata(e: Entry) = JSONObject()
        .put(".tag", "file")
        .put("name", e.pathDisplay.substringAfterLast('/'))
        .put("path_display", e.pathDisplay)
        .put("path_lower", e.pathDisplay.lowercase())
        .put("server_modified", isoUtc(e.serverModifiedSec))
        .put("size", e.bytes.size.toLong())
        .put("content_hash", DropboxContentHash.of(e.bytes))

    private fun notFound(where: String) = json(409, JSONObject()
        .put("error_summary", "$where/not_found/")
        .put("error", JSONObject().put(".tag", where).put(where, JSONObject().put(".tag", "not_found"))))

    private fun json(code: Int, o: JSONObject) =
        HttpResponse(code, mapOf("Content-Type" to "application/json"), o.toString().encodeToByteArray())

    companion object {
        /** "2026-11-08T10:00:00Z" for [sec], as Dropbox writes server_modified. */
        fun isoUtc(sec: Long): String {
            val days = sec.floorDiv(86_400L)
            val rem = sec - days * 86_400L
            // Howard Hinnant's civil_from_days.
            val z = days + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            fun two(v: Long) = v.toString().padStart(2, '0')
            return "$y-${two(m)}-${two(d)}T${two(rem / 3600)}:${two(rem / 60 % 60)}:${two(rem % 60)}Z"
        }
    }
}
