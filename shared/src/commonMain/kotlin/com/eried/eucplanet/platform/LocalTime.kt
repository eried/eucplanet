package com.eried.eucplanet.platform

/** A wall-clock reading in the device's time zone (what `java.util.Calendar` gave). */
data class LocalTimeParts(
    val year: Int,
    /** 1..12, not Calendar's 0..11. */
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val millis: Int,
)

/** [epochMs] in the device's current time zone. */
expect fun localTimeParts(epochMs: Long): LocalTimeParts

/** The device's time zone id, "Europe/Oslo": `TimeZone.getDefault().id`. */
expect fun localTimeZoneId(): String

/** The device's time zone offset from UTC at [epochMs], in ms: `TimeZone.getDefault().getOffset(epochMs)`. */
expect fun localTimeZoneOffsetMs(epochMs: Long): Int
