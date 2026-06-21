package com.eried.eucplanet.util

import java.util.Calendar
import java.util.TimeZone

/** Reuses the Android app's `java.util.Calendar` date extraction verbatim. */
actual fun currentDateInfo(): DateInfo {
    val tz = TimeZone.getDefault()
    val cal = Calendar.getInstance(tz)
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) + 1
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val offsetHours = tz.getOffset(cal.timeInMillis) / 3_600_000.0
    val midnight = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return DateInfo(year, month, day, offsetHours, midnight)
}
