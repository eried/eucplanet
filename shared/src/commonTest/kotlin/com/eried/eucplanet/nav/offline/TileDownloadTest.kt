package com.eried.eucplanet.nav.offline

import btools.util.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Files in memory, as the store and the downloads see them. */
internal class MemoryFiles : OfflineFiles {
    val bytes = HashMap<String, ByteArray>()
    val stamps = HashMap<String, Long>()

    override fun isFile(path: String) = path in bytes
    override fun length(path: String) = bytes[path]?.size?.toLong() ?: 0L
    override fun lastModified(path: String) = if (path in bytes) stamps[path] ?: 1L else 0L
    override fun setLastModified(path: String, ms: Long): Boolean { stamps[path] = ms; return true }
    override fun list(dir: String) = bytes.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }
    override fun delete(path: String) = bytes.remove(path) != null
    override fun rename(from: String, to: String): Boolean {
        val b = bytes.remove(from) ?: return false
        bytes[to] = b
        return true
    }
    override fun mkdirs(dir: String) = Unit
    override fun readText(path: String) = bytes[path]?.decodeToString()
    override fun writeText(path: String, text: String) { bytes[path] = text.encodeToByteArray() }
    override fun writeBytes(path: String, bytes: ByteArray) { this.bytes[path] = bytes }
    override fun copy(from: String, to: String) { bytes[to] = bytes.getValue(from) }
    override fun openWrite(path: String, append: Boolean): FileSink {
        if (!append || path !in bytes) bytes[path] = ByteArray(0)
        return object : FileSink {
            override fun write(buf: ByteArray, off: Int, len: Int) {
                bytes[path] = bytes.getValue(path) + buf.copyOfRange(off, off + len)
            }
            override fun close() = Unit
        }
    }
    override fun freeBytes(dir: String) = 1_000_000_000L
}

/** A server holding [files]; it answers ranges and If-Range as BRouter's and GitHub's do. */
private class FakeServer(val files: Map<String, ByteArray>, val date: String = "Thu, 29 Oct 2026 07:00:50 GMT") : TileHttp {
    val asked = ArrayList<Pair<String, Map<String, String>>>()
    var fail: Int? = null
    var chunk = 4

    override fun get(
        url: String,
        headers: List<Pair<String, String>>,
        onHead: (TileReply) -> Boolean,
        onBody: (buf: ByteArray, len: Int) -> Boolean,
    ) {
        val h = headers.toMap()
        asked += url to h
        fail?.let { code ->
            onHead(TileReply(code, 0, 0) { null })
            return
        }
        val body = files[url] ?: run {
            onHead(TileReply(404, 0, 0) { null })
            return
        }
        val from = h["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toLong()
        if (from != null && from >= body.size) {
            onHead(TileReply(416, 0, MODIFIED) { if (it == "Content-Range") "bytes */${body.size}" else null })
            return
        }
        val partial = from != null && h["If-Range"] == date
        val start = if (partial) from!!.toInt() else 0
        val rest = body.copyOfRange(start, body.size)
        val headerOf = { n: String -> if (n == "Last-Modified") date else null }
        if (!onHead(TileReply(if (partial) 206 else 200, rest.size.toLong(), MODIFIED, headerOf))) return
        var i = 0
        while (i < rest.size) {
            val n = minOf(chunk, rest.size - i)
            if (!onBody(rest.copyOfRange(i, i + n), n)) return
            i += n
        }
    }

    companion object { const val MODIFIED = 1_800_000_000_000L }
}

/** How one square downloads, resumes and gives up, on every phone. */
class TileDownloadTest {
    private val dir = "/tiles"
    private val tileUrl = "https://example.org/E10_N55.rd5"
    private val mapUrl = "https://example.org/E10_N55.pmtiles"
    private val lookupsUrl = "https://example.org/lookups.dat"
    private val tile = ByteArray(40) { it.toByte() }
    private val map = ByteArray(30) { (100 + it).toByte() }

    private fun job(map: Boolean = true, share: Float = 0.5f) =
        TileJob("E10_N55", tileUrl, lookupsUrl, if (map) mapUrl else null, share)

    private fun setup(check: (String) -> String? = { null }): Triple<MemoryFiles, FakeServer, TileDownload> {
        val files = MemoryFiles()
        val server = FakeServer(mapOf(tileUrl to tile, mapUrl to map, lookupsUrl to byteArrayOf(7, 7)))
        val store = OfflineTileStore(dir, files)
        return Triple(files, server, TileDownload(store, server, "EUCPlanet/test", check, check))
    }

    @Test fun aSquare_arrivesWithItsMapAndTheTagTable() = runBlocking {
        val (files, server, dl) = setup()
        val seen = ArrayList<Float>()
        assertEquals(TileDownload.Outcome.SUCCESS, dl.run(job(), 0, { false }, { seen += it }))
        assertContentEquals(tile, files.bytes["$dir/E10_N55.rd5"])
        assertContentEquals(map, files.bytes["$dir/E10_N55.pmtiles"])
        assertContentEquals(byteArrayOf(7, 7), files.bytes["$dir/lookups.dat"])
        assertEquals(FakeServer.MODIFIED, files.stamps["$dir/E10_N55.rd5"])
        assertTrue(files.bytes.keys.none { it.endsWith(".part") || it.endsWith(".date") })
        assertEquals(listOf(lookupsUrl, tileUrl, mapUrl), server.asked.map { it.first })
        assertTrue(server.asked.all { it.second["User-Agent"] == "EUCPlanet/test" })
    }

    @Test fun aStoppedDownload_keepsItsPart_andTheNextRunResumesIt() = runBlocking {
        val (files, server, dl) = setup()
        var reads = 0
        val first = dl.run(job(map = false), 0, { ++reads > 3 }, { })
        assertEquals(TileDownload.Outcome.RETRY, first)
        val part = files.bytes.getValue("$dir/E10_N55.rd5.part")
        assertTrue(part.isNotEmpty() && part.size < tile.size)
        assertEquals(TileDownload.Outcome.SUCCESS, dl.run(job(map = false), 1, { false }, { }))
        val resumed = server.asked.last().second
        assertEquals("bytes=${part.size}-", resumed["Range"])
        assertEquals(server.date, resumed["If-Range"])
        assertContentEquals(tile, files.bytes["$dir/E10_N55.rd5"])
    }

    @Test fun aWholePart_finishesOnTheServersRangeError() = runBlocking {
        val (files, server, dl) = setup()
        files.bytes["$dir/E10_N55.rd5.part"] = tile.copyOf()
        files.writeText("$dir/E10_N55.rd5.part.date", TileDownload.partMeta(tileUrl, server.date))
        assertEquals(TileDownload.Outcome.SUCCESS, dl.run(job(map = false), 0, { false }, { }))
        assertContentEquals(tile, files.bytes["$dir/E10_N55.rd5"])
        assertNull(files.bytes["$dir/E10_N55.rd5.part"])
    }

    @Test fun openSea_failsWithoutRetrying() = runBlocking {
        val (_, server, dl) = setup()
        server.fail = 404
        assertEquals(TileDownload.Outcome.FAILURE, dl.run(job(), 0, { false }, { }))
    }

    @Test fun aServerError_retries_untilTheLastAttempt() = runBlocking {
        val (_, server, dl) = setup()
        server.fail = 503
        assertEquals(TileDownload.Outcome.RETRY, dl.run(job(), 0, { false }, { }))
        assertEquals(TileDownload.Outcome.FAILURE, dl.run(job(), TileDownload.MAX_ATTEMPTS, { false }, { }))
    }

    @Test fun aDamagedTile_startsOver() = runBlocking {
        val (files, _, dl) = setup(check = { "bad crc" })
        assertEquals(TileDownload.Outcome.RETRY, dl.run(job(map = false), 0, { false }, { }))
        assertFalse(files.isFile("$dir/E10_N55.rd5"))
        assertFalse(files.isFile("$dir/E10_N55.rd5.part"))
    }

    @Test fun aCurrentSquare_onlyFetchesItsMap() = runBlocking {
        val (files, server, dl) = setup()
        files.bytes["$dir/E10_N55.rd5"] = tile.copyOf()
        files.bytes["$dir/lookups.dat"] = byteArrayOf(7, 7)
        assertEquals(TileDownload.Outcome.SUCCESS, dl.run(job(share = 0f), 0, { false }, { }))
        assertEquals(listOf(mapUrl), server.asked.map { it.first })
        assertContentEquals(map, files.bytes["$dir/E10_N55.pmtiles"])
    }

    @Test fun aNetworkError_retries() = runBlocking {
        val files = MemoryFiles()
        val broken = object : TileHttp {
            override fun get(
                url: String,
                headers: List<Pair<String, String>>,
                onHead: (TileReply) -> Boolean,
                onBody: (buf: ByteArray, len: Int) -> Boolean,
            ) = throw IOException("no route to host")
        }
        val dl = TileDownload(OfflineTileStore(dir, files), broken, "ua", { null }, { null })
        assertEquals(TileDownload.Outcome.RETRY, dl.run(job(), 0, { false }, { }))
    }

    @Test fun theStore_countsOnlyWholeSquares() {
        val files = MemoryFiles()
        val store = OfflineTileStore(dir, files)
        files.bytes["$dir/E10_N55.rd5"] = ByteArray(10)
        files.bytes["$dir/E10_N55.pmtiles"] = ByteArray(5)
        files.bytes["$dir/E15_N55.rd5.part"] = ByteArray(10)
        files.bytes["$dir/E20_N55.rd5"] = ByteArray(0)
        files.bytes["$dir/lookups.dat"] = ByteArray(3)
        assertEquals(mapOf("E10_N55" to 15L), store.downloaded())
        assertTrue(store.covers(setOf("E10_N55")))
        assertFalse(store.covers(setOf("E10_N55", "E15_N55")))
        store.delete("E10_N55")
        assertTrue(store.downloaded().isEmpty())
    }
}
