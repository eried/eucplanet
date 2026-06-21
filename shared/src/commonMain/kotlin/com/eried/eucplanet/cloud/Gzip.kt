package com.eried.eucplanet.cloud

import com.eried.eucplanet.util.Crc32

/**
 * Minimal gzip using **STORED** (uncompressed) DEFLATE blocks — a valid gzip stream
 * the server can `gunzip`, with zero compression dependency (no zlib / Apple
 * Compression cinterop on iOS). Trips are small CSVs, so skipping compression is
 * fine; this keeps the whole upload path in pure commonMain.
 *
 * Format: 10-byte gzip header + DEFLATE stored blocks (≤65535 B each) + CRC-32 +
 * ISIZE, all per RFC 1951/1952. CRC reuses the shared zlib-identical [Crc32].
 */
fun gzip(data: ByteArray): ByteArray {
    val out = ArrayList<Byte>(data.size + 32)
    fun b(v: Int) = out.add(v.toByte())
    // Header: magic 1f 8b, CM=8 (deflate), FLG=0, MTIME=0, XFL=0, OS=255 (unknown).
    b(0x1f); b(0x8b); b(0x08); b(0x00); b(0); b(0); b(0); b(0); b(0x00); b(0xff)
    if (data.isEmpty()) {
        b(0x01); b(0x00); b(0x00); b(0xff); b(0xff) // one empty final stored block
    } else {
        var pos = 0
        while (pos < data.size) {
            val len = minOf(65535, data.size - pos)
            val last = pos + len >= data.size
            b(if (last) 0x01 else 0x00)            // BFINAL bit, BTYPE=00 (stored)
            b(len and 0xff); b((len ushr 8) and 0xff)            // LEN (LE)
            val nlen = len.inv()
            b(nlen and 0xff); b((nlen ushr 8) and 0xff)          // NLEN = ~LEN (LE)
            for (i in 0 until len) out.add(data[pos + i])
            pos += len
        }
    }
    val crc = Crc32.compute(data)
    b((crc and 0xff).toInt()); b(((crc ushr 8) and 0xff).toInt())
    b(((crc ushr 16) and 0xff).toInt()); b(((crc ushr 24) and 0xff).toInt())
    val isize = data.size.toLong() and 0xffffffffL
    b((isize and 0xff).toInt()); b(((isize ushr 8) and 0xff).toInt())
    b(((isize ushr 16) and 0xff).toInt()); b(((isize ushr 24) and 0xff).toInt())
    return out.toByteArray()
}
