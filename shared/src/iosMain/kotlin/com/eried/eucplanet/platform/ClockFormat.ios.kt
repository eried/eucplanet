package com.eried.eucplanet.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970

@Composable
actual fun rememberShortTimeFormatter(): (Long) -> String = remember {
    val f = NSDateFormatter().apply {
        locale = NSLocale.currentLocale
        dateStyle = NSDateFormatterNoStyle
        timeStyle = NSDateFormatterShortStyle
    }
    val format: (Long) -> String = { ms -> f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)) }
    format
}

@Composable
actual fun rememberClockAxisFormatter(): (Long) -> String = remember {
    // "j" is the locale's hour symbol with the phone's 12/24-hour choice
    // applied; an "a" in the result means 12-hour.
    val hourPattern = NSDateFormatter.dateFormatFromTemplate("j", 0u, NSLocale.currentLocale) ?: "HH"
    val is24h = !hourPattern.contains('a')
    val f = NSDateFormatter().apply {
        locale = NSLocale.currentLocale
        dateFormat = if (is24h) "HH:mm" else "h:mm a"
    }
    val format: (Long) -> String = { ms -> f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)) }
    format
}
