package com.eried.eucplanet.platform

/** [bytes] as a gzip file: `GZIPOutputStream` on Android, zlib's deflate in a gzip wrapper on iOS. */
expect fun gzip(bytes: ByteArray): ByteArray
