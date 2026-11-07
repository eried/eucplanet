package com.eried.eucplanet.platform

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun localTimeParts(epochMs: Long): LocalTimeParts {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val c = NSCalendar.currentCalendar.components(
        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
            NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond,
        fromDate = date,
    )
    return LocalTimeParts(
        year = c.year.toInt(),
        month = c.month.toInt(),
        day = c.day.toInt(),
        hour = c.hour.toInt(),
        minute = c.minute.toInt(),
        second = c.second.toInt(),
        millis = epochMs.mod(1000L).toInt(),
    )
}

actual fun localTimeZoneId(): String = NSTimeZone.localTimeZone.name

actual fun localTimeZoneOffsetMs(epochMs: Long): Int =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)).toInt() * 1000
