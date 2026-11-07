package com.eried.eucplanet.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.compressedDataUsingAlgorithm
import platform.Foundation.create

/**
 * Apple's "zlib" compression is raw DEFLATE (RFC 1951), so this adds the gzip
 * header and trailer (CRC-32 and length, little endian) that GZIPOutputStream
 * writes around it. The header is GZIPOutputStream's: no name, no time.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun gzip(bytes: ByteArray): ByteArray {
    val input = if (bytes.isEmpty()) NSData() else bytes.usePinned {
        NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
    }
    val deflated = input.compressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, null)?.toByteArray()
        ?: throw IllegalStateException("deflate failed")
    val out = ByteArrayOutputStream(deflated.size + 18)
    out.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0))
    // An empty input deflates to nothing here; GZIPOutputStream writes the
    // empty final block (03 00), which every reader expects.
    if (deflated.isEmpty()) out.write(byteArrayOf(3, 0)) else out.write(deflated)
    fun le32(v: Long) { for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
    le32(crc32(bytes))
    le32(bytes.size.toLong() and 0xFFFFFFFFL)
    return out.toByteArray()
}
