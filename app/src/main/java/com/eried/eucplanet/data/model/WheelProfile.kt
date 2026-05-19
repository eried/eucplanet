package com.eried.eucplanet.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-wheel saved parameters keyed by the BLE-advertised device name. When the
 * rider connects to a wheel by name we look it up here and restore their
 * preferred speed limits + speed-calibration offset. When they tweak any of
 * those values while connected, we mirror the new values back so the next
 * connect to the same wheel restores them.
 *
 * The BLE name is a string the manufacturer programs into the wheel (e.g.
 * "Adventure-12345", "RS_5012"). Two physical wheels can ship with the same
 * advertised name from the factory — rare, but in that case both wheels share
 * the profile and the user can rename one through the wheel's own app to
 * disambiguate.
 */
@Entity(tableName = "wheel_profile")
data class WheelProfile(
    @PrimaryKey val bleName: String,

    val tiltbackSpeedKmh: Float,
    val alarmSpeedKmh: Float,
    val safetyTiltbackKmh: Float,
    val safetyAlarmKmh: Float,

    /**
     * Percentage adjustment applied to the raw speed coming from the wheel.
     * Positive values inflate the reading, negative deflate it. The adjustment
     * is applied at the source (where the adapter publishes WheelData) so
     * alarms, voice, the dashboard and the trip log all see the calibrated
     * value. Range is bounded in the UI to -20..+20.
     */
    @ColumnInfo(defaultValue = "0")
    val speedCalibrationOffsetPct: Float = 0f,

    /**
     * Multiply the wheel's reported speed by -1 before publishing it
     * downstream. Useful for Begode / Veteran units whose motor phase
     * wiring or sensor mount is rotated so that forward riding reports
     * negative speed (and a backward-emitting voice cue). WheelLog ships
     * the same toggle as `gotwayNegative`. Hidden in the UI for protocols
     * that don't suffer from this (InMotion / KingSong / Ninebot).
     */
    @ColumnInfo(defaultValue = "0")
    val reverseSpeedDirection: Boolean = false,

    /** Wall-clock of the last connect to this wheel. Used to keep the most
     *  recently used profile easy to find if we ever expose a profile list. */
    val lastConnectedAt: Long = System.currentTimeMillis()
)
