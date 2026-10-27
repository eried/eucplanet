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
