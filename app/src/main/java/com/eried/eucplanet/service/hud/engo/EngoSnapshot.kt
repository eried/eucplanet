package com.eried.eucplanet.service.hud.engo

/**
 * The minimal, already-formatted view of state the ENGO layout needs. Kept free
 * of Android + repositories so [EngoLayout] is a pure function and fully
 * unit-testable. The Android glue ([EngoHudRenderer]) maps WheelData /
 * NavState into this.
 */
data class EngoSnapshot(
    val connected: Boolean,
    val speed: Int, // already converted to the display unit
    val speedUnit: String, // "km/h" / "mph"
    val batteryPct: Int,
    val pwmPct: Int,
    val tempC: Int,
    // Navigation takeover (active only while Navigator guidance is running).
    val navActive: Boolean = false,
    val navDistanceM: Int = 0,
    val navManeuver: EngoManeuver = EngoManeuver.STRAIGHT,
    val navStreet: String = "",
)

/** Turn types the nav takeover can draw. */
enum class EngoManeuver { LEFT, SLIGHT_LEFT, RIGHT, SLIGHT_RIGHT, STRAIGHT, UTURN, ARRIVE }

/**
 * What the connected glasses can do. ENGO 3 is RG colour (81 colours); ENGO 2 is
 * 16 grey levels. Font ids are ActiveLook system fonts; the exact ids/sizes are
 * a finish-on-device tuning constant.
 */
data class EngoCaps(
    val colorRYG: Boolean,
    val speedFont: Int = 3,
    val labelFont: Int = 2,
)
