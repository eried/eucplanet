package com.eried.eucplanet.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

actual fun formatLocalDate(pattern: String, epochMs: Long): String {
    val f = NSDateFormatter()
    f.locale = NSLocale.currentLocale
    f.dateFormat = pattern
    return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
}

actual fun formatLocalDateUs(pattern: String, epochMs: Long): String {
    val f = NSDateFormatter()
    f.locale = NSLocale(localeIdentifier = "en_US_POSIX")
    f.dateFormat = pattern
    return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
}

actual class LocalDateParserUs actual constructor(pattern: String) {
    private val fmt = NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        dateFormat = pattern
        lenient = false
    }

    actual fun parse(text: String): Long? =
        fmt.dateFromString(text)?.let { (it.timeIntervalSince1970 * 1000.0).toLong() }
}
