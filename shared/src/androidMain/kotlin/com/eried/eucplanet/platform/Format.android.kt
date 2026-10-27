package com.eried.eucplanet.platform

actual fun String.format(vararg args: Any?): String = java.lang.String.format(this, *args)

actual fun String.Companion.format(format: String, vararg args: Any?): String =
    java.lang.String.format(format, *args)

actual fun String.Companion.format(locale: Locale, format: String, vararg args: Any?): String =
    java.lang.String.format(locale.jvm, format, *args)

actual class Locale(val jvm: java.util.Locale) {
    actual companion object {
        actual val US: Locale = Locale(java.util.Locale.US)
        actual val ROOT: Locale = Locale(java.util.Locale.ROOT)
        actual val ENGLISH: Locale = Locale(java.util.Locale.ENGLISH)
        actual fun getDefault(): Locale = Locale(java.util.Locale.getDefault())
    }
}
