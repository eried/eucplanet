package com.eried.eucplanet.platform

/** [bytes] as a gzip file: `GZIPOutputStream` on Android, zlib's deflate in a gzip wrapper on iOS. */
expect fun gzip(bytes: ByteArray): ByteArray

/**
 * Raw DEFLATE (RFC 1951) unpacked, as a zip entry holds it: `Inflater(nowrap)`
 * on Android, Apple's "zlib" algorithm (which is raw DEFLATE) on iOS. Throws
 * on damaged data.
 */
expect fun inflateRaw(bytes: ByteArray): ByteArray
