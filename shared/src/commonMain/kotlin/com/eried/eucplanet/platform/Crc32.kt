package com.eried.eucplanet.platform

/** CRC-32 (zlib polynomial) of [length] bytes from [offset], as `java.util.zip.CRC32.value`. */
expect fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Long
