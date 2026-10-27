package com.eried.eucplanet.platform

/**
 * `String.format` for shared code, same call shape as the JVM's
 * (`"%.1f".format(x)`, `String.format(fmt, x)`, `String.format(Locale.US, fmt, x)`),
 * so a moved file only adds imports. Android formats with `java.lang.String.format`
 * itself; iOS uses [JavaFormat], which follows the JVM's rules and is pinned
 * against it by JavaFormatTest.
 */
expect fun String.format(vararg args: Any?): String

expect fun String.Companion.format(format: String, vararg args: Any?): String

expect fun String.Companion.format(locale: Locale, format: String, vararg args: Any?): String

/** The formatting locales shared code asks for by name. */
expect class Locale {
    companion object {
        val US: Locale
        val ROOT: Locale
        val ENGLISH: Locale
        fun getDefault(): Locale
    }
}
