package com.eried.eucplanet.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.crc32 as zlibCrc32

@OptIn(ExperimentalForeignApi::class)
actual fun crc32(bytes: ByteArray, offset: Int, length: Int): Long {
    require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
    if (length == 0) return 0L
    return bytes.usePinned { pinned ->
        zlibCrc32(0u, pinned.addressOf(offset).reinterpret(), length.toUInt())
    }.toLong()
}
