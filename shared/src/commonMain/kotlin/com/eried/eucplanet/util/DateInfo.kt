package com.eried.eucplanet.util

/**
 * The platform's current local date + timezone, the small piece of
 * [SunCalculator] that needs a real calendar. Keeping only this behind an
 * expect/actual lets the NOAA solar math stay shared (same approach as
 * [nowEpochMillis]). Android reuses `java.util.Calendar` exactly as the Android
 * app does; iOS uses Foundation's `NSCalendar`.
 */
data class DateInfo(
    val year: Int,
    val month: Int,            // 1..12
    val day: Int,              // 1..31
    val tzOffsetHours: Double, // local offset from UTC, in hours
    val localMidnightMillis: Long, // epoch ms of local 00:00 today
)

expect fun currentDateInfo(): DateInfo
