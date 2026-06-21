package com.eried.eucplanet.util

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

/** Foundation (`NSCalendar`) equivalent of the Android `Calendar` extraction. */
actual fun currentDateInfo(): DateInfo {
    val now = NSDate()
    val cal = NSCalendar.currentCalendar
    val comps = cal.components(
        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay,
        fromDate = now,
    )
    val tz = NSTimeZone.localTimeZone
    val offsetHours = tz.secondsFromGMTForDate(now).toDouble() / 3600.0
    val midnight = cal.startOfDayForDate(now)
    val midnightMillis = (midnight.timeIntervalSince1970 * 1000.0).toLong()
    return DateInfo(
        year = comps.year.toInt(),
        month = comps.month.toInt(),
        day = comps.day.toInt(),
        tzOffsetHours = offsetHours,
        localMidnightMillis = midnightMillis,
    )
}
