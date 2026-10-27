package com.eried.eucplanet.platform

actual fun crc32(bytes: ByteArray, offset: Int, length: Int): Long =
    java.util.zip.CRC32().apply { update(bytes, offset, length) }.value
