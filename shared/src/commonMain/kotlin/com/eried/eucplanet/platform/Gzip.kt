package com.eried.eucplanet.platform

/** [bytes] as a gzip file: `GZIPOutputStream` on Android, zlib's deflate in a gzip wrapper on iOS. */
expect fun gzip(bytes: ByteArray): ByteArray

/**
 * Raw DEFLATE (RFC 1951) unpacked, as a zip entry holds it: `Inflater(nowrap)`
 * on Android, Apple's "zlib" algorithm (which is raw DEFLATE) on iOS. Throws
 * on damaged data.
 */
expect fun inflateRaw(bytes: ByteArray): ByteArray

/** A gzip file unpacked, as `GZIPInputStream(...).readBytes()` reads it. Throws on damaged data. */
expect fun gunzip(bytes: ByteArray): ByteArray

/**
 * [bytes] as raw DEFLATE (no zlib header) at the best compression:
 * `Deflater(BEST_COMPRESSION, true)` on Android, zlib's own deflate at level
 * 9 on iOS. [inflateRawCapped] reads it back.
 */
expect fun deflateRaw(bytes: ByteArray): ByteArray

/**
 * Raw DEFLATE unpacked, stopping once more than [maxBytes] came out (a link
 * from a stranger must not unpack into a bomb) and returning what came out
 * so far when the data ends early, as an `Inflater(true)` loop that breaks
 * on `needsInput` does.
 */
expect fun inflateRawCapped(bytes: ByteArray, maxBytes: Int): ByteArray
