package com.eried.eucplanet.platform

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.decimalSeparator
import platform.Foundation.groupingSeparator

actual fun String.format(vararg args: Any?): String =
    JavaFormat.format(Locale.getDefault().symbols, this, *args)

actual fun String.Companion.format(format: String, vararg args: Any?): String =
    JavaFormat.format(Locale.getDefault().symbols, format, *args)

actual fun String.Companion.format(locale: Locale, format: String, vararg args: Any?): String =
    JavaFormat.format(locale.symbols, format, *args)

actual class Locale(val symbols: JavaFormat.Symbols) {
    actual companion object {
        actual val US: Locale = Locale(JavaFormat.Symbols.US)
        actual val ROOT: Locale = Locale(JavaFormat.Symbols.US)
        actual val ENGLISH: Locale = Locale(JavaFormat.Symbols.US)
        actual fun getDefault(): Locale {
            val l = NSLocale.currentLocale
            return Locale(
                JavaFormat.Symbols(
                    decimal = l.decimalSeparator.firstOrNull() ?: '.',
                    grouping = l.groupingSeparator.firstOrNull() ?: ',',
                )
            )
        }
    }
}
