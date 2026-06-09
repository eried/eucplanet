package com.eried.eucplanet.util

/**
 * Pure-Kotlin CRC-32 (IEEE 802.3 / zlib polynomial 0xEDB88320), byte-identical
 * to java.util.zip.CRC32 and CommonCrypto-free, so frame validation (e.g. the
 * Veteran long-frame trailer) runs unchanged on Android and iOS.
 */
object Crc32 {
    private val TABLE: IntArray = IntArray(256) { n ->
        var c = n
        repeat(8) {
            c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1
        }
        c
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size): Long {
        var crc = 0.inv() // 0xFFFFFFFF
        for (i in offset until offset + length) {
            crc = TABLE[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv().toLong() and 0xFFFFFFFFL
    }
}
