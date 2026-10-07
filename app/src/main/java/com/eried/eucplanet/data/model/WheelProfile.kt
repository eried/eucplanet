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
 * advertised name from the factory, rare, but in that case both wheels share
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
     * negative speed (and a backward-emitting voice cue). Equivalent to
     * the well-known `gotwayNegative` toggle. Hidden in the UI for
     * protocols that don't suffer from this (InMotion / KingSong /
     * Ninebot).
     */
    @ColumnInfo(defaultValue = "0")
    val reverseSpeedDirection: Boolean = false,

    /**
     * Cells in series in this wheel's pack, used to turn pack voltage into a
     * per-cell voltage for the display-only battery estimate.
     *
     * Per wheel rather than app-wide because it describes the pack, not a
     * preference: a rider with two wheels would otherwise carry one wheel's
     * count over to the other and read a confident, wrong percentage. Only
     * consulted for wheels whose model does not state its own pack voltage;
     * when the model states one, the wheel wins and this is left alone.
     */
    @ColumnInfo(defaultValue = "20")
    val seriesCells: Int = 20,

    /**
     * Whether the on-screen percentage for this wheel is overridden with the
     * voltage-based estimate. Per wheel so a calibration set for one pack never
     * carries to another; a wheel we've never calibrated shows its own number.
     * Mirrors BatteryPercentSettings.mode: "WHEEL" off, "CURVE" / "CUSTOM" on.
     */
    @ColumnInfo(defaultValue = "WHEEL")
    val batteryMode: String = "WHEEL",

    /**
     * Pack energy in watt-hours for this wheel, or 0 when unset. Per wheel so a
     * two-pack rider's range estimate seeds from the right size on each; 0 keeps
     * the estimate learning from the ride alone, as before.
     */
    @ColumnInfo(defaultValue = "0")
    val batteryCapacityWh: Int = 0,

    /** Wall-clock of the last connect to this wheel. Used to keep the most
     *  recently used profile easy to find if we ever expose a profile list. */
    val lastConnectedAt: Long = System.currentTimeMillis()
)
