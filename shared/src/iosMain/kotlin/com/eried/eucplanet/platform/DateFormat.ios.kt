package com.eried.eucplanet.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun formatLocalDate(pattern: String, epochMs: Long): String {
    val f = NSDateFormatter()
    f.locale = NSLocale.currentLocale
    f.dateFormat = pattern
    return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
}
