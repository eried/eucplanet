package com.eried.eucplanet.service.hud.engo

/**
 * Synthetic snapshots for previewing / testing the ENGO layout without glasses
 * (mirrors the network HUD's demo source). Fed through [EngoLayout] to inspect
 * the exact command stream, or to a connected unit for a "test HUD" button.
 */
object EngoDemoSource {

    /** A representative telemetry page. */
    fun telemetry(): EngoSnapshot = EngoSnapshot(
        connected = true,
        speed = 28,
        speedUnit = "km/h",
        batteryPct = 78,
        pwmPct = 61,
        temp = 38,
        tempUnit = "C",
    )

    /** A representative nav takeover (left turn in 120 m). */
    fun nav(): EngoSnapshot = EngoSnapshot(
        connected = true,
        speed = 24,
        speedUnit = "km/h",
        batteryPct = 74,
        pwmPct = 40,
        temp = 37,
        tempUnit = "C",
        navActive = true,
        navDistanceText = "120 m",
        navManeuver = EngoManeuver.LEFT,
        navStreet = "Main Street",
    )
}
