package com.eried.eucplanet.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

// zlib itself, which is what java.util.zip wraps on Android, so the bytes
// match. Apple's NSData compression has no level and no gzip reader.

@OptIn(ExperimentalForeignApi::class)
actual fun deflateRaw(bytes: ByteArray): ByteArray = memScoped {
    val strm = alloc<z_stream>()
    check(deflateInit2_(strm.ptr, 9, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK) {
        "deflateInit failed"
    }
    val out = ByteArrayOutputStream(1024)
    val buf = ByteArray(1024)
    val input = if (bytes.isEmpty()) ByteArray(1) else bytes
    try {
        input.usePinned { inPin ->
            buf.usePinned { outPin ->
                strm.next_in = inPin.addressOf(0).reinterpret()
                strm.avail_in = bytes.size.toUInt()
                while (true) {
                    strm.next_out = outPin.addressOf(0).reinterpret()
                    strm.avail_out = buf.size.toUInt()
                    val rc = deflate(strm.ptr, Z_FINISH)
                    val n = buf.size - strm.avail_out.toInt()
                    out.write(buf, 0, n)
                    if (rc == Z_STREAM_END) break
                    check(rc == Z_OK || rc == Z_BUF_ERROR) { "deflate failed: $rc" }
                }
            }
        }
    } finally {
        deflateEnd(strm.ptr)
    }
    out.toByteArray()
}

/** Inflates with [windowBits] (-15 raw, 31 gzip), at most [maxBytes] out; [strict] throws on short data. */
@OptIn(ExperimentalForeignApi::class)
private fun zInflate(bytes: ByteArray, windowBits: Int, maxBytes: Int, strict: Boolean): ByteArray = memScoped {
    if (bytes.isEmpty()) {
        if (strict) throw IllegalArgumentException("no data")
        return@memScoped ByteArray(0)
    }
    val strm = alloc<z_stream>()
    check(inflateInit2_(strm.ptr, windowBits, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK) { "inflateInit failed" }
    val out = ByteArrayOutputStream(1024)
    val buf = ByteArray(16 * 1024)
    try {
        bytes.usePinned { inPin ->
            buf.usePinned { outPin ->
                strm.next_in = inPin.addressOf(0).reinterpret()
                strm.avail_in = bytes.size.toUInt()
                while (true) {
                    strm.next_out = outPin.addressOf(0).reinterpret()
                    strm.avail_out = buf.size.toUInt()
                    val rc = inflate(strm.ptr, Z_NO_FLUSH)
                    val n = buf.size - strm.avail_out.toInt()
                    out.write(buf, 0, n)
                    if (rc == Z_STREAM_END) break
                    if (rc != Z_OK) {
                        if (strict) throw IllegalArgumentException("inflate failed: $rc")
                        break
                    }
                    if (n == 0 && strm.avail_in == 0u) {
                        if (strict) throw IllegalArgumentException("truncated data")
                        break
                    }
                    if (out.size() > maxBytes) break
                }
            }
        }
    } finally {
        inflateEnd(strm.ptr)
    }
    out.toByteArray()
}

actual fun gunzip(bytes: ByteArray): ByteArray = zInflate(bytes, 31, Int.MAX_VALUE, strict = true)

actual fun inflateRawCapped(bytes: ByteArray, maxBytes: Int): ByteArray =
    zInflate(bytes, -15, maxBytes, strict = false)
