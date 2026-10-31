package com.eried.eucplanet.nav.offline.map

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Reads tiles from a PMTiles v3 file on the phone: one map square. Tiles
 * come back unzipped, as vector tile bytes.
 */
class PmTiles(file: File) : Closeable {

    private class Entry(val tileId: Long, val run: Long, val offset: Long, val length: Int)

    private val raf = RandomAccessFile(file, "r")
    private val rootOffset: Long
    private val rootLength: Int
    private val leafOffset: Long
    private val dataOffset: Long
    private val gzipTiles: Boolean
    val minZoom: Int
    val maxZoom: Int
    private val root: List<Entry>
    private val leaves = object : LinkedHashMap<Long, List<Entry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?) = size > 64
    }

    init {
        val head = read(0, HEADER)
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        require(String(head, 0, 7, Charsets.US_ASCII) == "PMTiles" && head[7].toInt() == 3) { "not PMTiles v3" }
        rootOffset = b.getLong(8)
        rootLength = b.getLong(16).toInt()
        leafOffset = b.getLong(40)
        dataOffset = b.getLong(56)
        require(head[97].toInt() == GZIP) { "directories not gzip" }
        gzipTiles = head[98].toInt() == GZIP
        minZoom = head[100].toInt()
        maxZoom = head[101].toInt()
        root = parse(unzip(read(rootOffset, rootLength)))
    }

    /** The vector tile at [z]/[x]/[y], or null when the square has none. */
    @Synchronized
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < minZoom || z > maxZoom) return null
        val id = tileId(z, x, y)
        var dir = root
        repeat(4) {
            val e = find(dir, id) ?: return null
            if (e.run > 0) {
                val raw = read(dataOffset + e.offset, e.length)
                return if (gzipTiles) unzip(raw) else raw
            }
            dir = leaves.getOrPut(e.offset) { parse(unzip(read(leafOffset + e.offset, e.length))) }
        }
        return null
    }

    /** Reads every directory and unzips every [sampleEvery]th tile; throws
     *  when any of it is damaged or points past the end of the file. */
    @Synchronized
    fun verify(sampleEvery: Int = 50) {
        val size = raf.length()
        var n = 0
        fun walk(dir: List<Entry>, depth: Int) {
            require(depth < 4) { "directories too deep" }
            for (e in dir) {
                if (e.run == 0L) {
                    require(leafOffset + e.offset + e.length <= size) { "leaf past the end" }
                    walk(parse(unzip(read(leafOffset + e.offset, e.length))), depth + 1)
                } else {
                    require(dataOffset + e.offset + e.length <= size) { "tile past the end" }
                    if (n++ % sampleEvery == 0) {
                        val raw = read(dataOffset + e.offset, e.length)
                        if (gzipTiles) unzip(raw)
                    }
                }
            }
        }
        walk(root, 0)
        require(n > 0) { "no tiles" }
    }

    @Synchronized
    override fun close() = raf.close()

    private fun read(at: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        raf.seek(at)
        raf.readFully(out)
        return out
    }

    companion object {
        private const val HEADER = 127
        private const val GZIP = 2

        /** Hilbert-curve tile id, as PMTiles numbers tiles. */
        fun tileId(z: Int, x: Int, y: Int): Long {
            var acc = ((1L shl (2 * z)) - 1) / 3
            val n = 1L shl z
            var tx = x.toLong()
            var ty = y.toLong()
            var d = 0L
            var s = n shr 1
            while (s > 0) {
                val rx = if (tx and s != 0L) 1L else 0L
                val ry = if (ty and s != 0L) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) { tx = n - 1 - tx; ty = n - 1 - ty }
                    val t = tx; tx = ty; ty = t
                }
                s = s shr 1
            }
            acc += d
            return acc
        }

        private fun unzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

        private fun parse(b: ByteArray): List<Entry> {
            var i = 0
            fun v(): Long {
                var r = 0L
                var sh = 0
                while (true) {
                    val c = b[i++].toInt() and 0xFF
                    r = r or ((c and 0x7F).toLong() shl sh)
                    sh += 7
                    if (c < 0x80) return r
                }
            }
            val n = v().toInt()
            val ids = LongArray(n)
            var last = 0L
            for (k in 0 until n) { last += v(); ids[k] = last }
            val runs = LongArray(n) { v() }
            val lens = IntArray(n) { v().toInt() }
            val offs = LongArray(n)
            for (k in 0 until n) {
                val o = v()
                offs[k] = if (o == 0L && k > 0) offs[k - 1] + lens[k - 1] else o - 1
            }
            return List(n) { Entry(ids[it], runs[it], offs[it], lens[it]) }
        }

        private fun find(dir: List<Entry>, id: Long): Entry? {
            var lo = 0
            var hi = dir.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = dir[mid].tileId.compareTo(id)
                when {
                    c < 0 -> lo = mid + 1
                    c > 0 -> hi = mid - 1
                    else -> return dir[mid]
                }
            }
            if (hi < 0) return null
            val e = dir[hi]
            return if (e.run == 0L || id - e.tileId < e.run) e else null
        }
    }
}
