package com.eried.eucplanet.platform

/** The charsets shared code names. Mirrors `kotlin.text.Charsets` on the JVM. */
enum class Charset { US_ASCII, UTF_8, ISO_8859_1 }

object Charsets {
    val US_ASCII = Charset.US_ASCII
    val UTF_8 = Charset.UTF_8
    val ISO_8859_1 = Charset.ISO_8859_1
}

/** `String(bytes, charset)` as on the JVM. Android decodes with the JVM charset. */
expect fun String(bytes: ByteArray, charset: Charset): String

expect fun String(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String

expect fun ByteArray.toString(charset: Charset): String

expect fun String.toByteArray(charset: Charset): ByteArray
