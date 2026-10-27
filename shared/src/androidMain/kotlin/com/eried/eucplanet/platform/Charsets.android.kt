package com.eried.eucplanet.platform

private fun Charset.jvm(): java.nio.charset.Charset = when (this) {
    Charset.US_ASCII -> kotlin.text.Charsets.US_ASCII
    Charset.UTF_8 -> kotlin.text.Charsets.UTF_8
    Charset.ISO_8859_1 -> kotlin.text.Charsets.ISO_8859_1
}

actual fun String(bytes: ByteArray, charset: Charset): String = kotlin.text.String(bytes, charset.jvm())

actual fun String(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String =
    kotlin.text.String(bytes, offset, length, charset.jvm())

actual fun ByteArray.toString(charset: Charset): String = kotlin.text.String(this, charset.jvm())

actual fun String.toByteArray(charset: Charset): ByteArray = (this as java.lang.String).getBytes(charset.jvm())
