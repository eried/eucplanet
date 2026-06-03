package com.eried.eucplanet.ui.dashboard.sources

import androidx.compose.ui.graphics.Color
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentPurple

/**
 * One of the three live data sources the dashboard knows about. The enum is
 * stable across the UI: same icon, same colour, same string label everywhere
 * the dialog shows it, so users build a mental map of "blue = phone, green =
 * wheel, purple = RaceBox" that carries through to the overlay dot on the
 * speed dial and the lines on the trip-detail chart.
 */
enum class DataSource(val labelRes: Int, val color: Color) {
    PHONE(R.string.sources_phone, AccentBlue),
    WHEEL(R.string.sources_wheel, AccentGreen),
    // Label string is "External" (not "RaceBox") so the dialog stays
    // accurate when the user has a Draggy / VBox / other GPS box paired
    // instead of a literal RaceBox. The enum name stays RACEBOX for
    // backward compat with the pairing column.
    RACEBOX(R.string.sources_external, AccentPurple);

    /**
     * Capability flags so each tab can render the right rows without
     * branching deep inside the view code. Update these alongside the
     * underlying repository when a new metric becomes available.
     */
    val hasSpeed: Boolean get() = true                  // all three expose speed
    val hasPosition: Boolean get() = this != WHEEL      // wheel has no GPS
    val hasImu: Boolean get() = this == PHONE || this == RACEBOX
}

/*
 * FUTURE (separate branch): Garmin Varia rear-view radar as a dashboard
 * data source.
 *
 * The plumbing is already in place; only the dashboard wiring is missing.
 *
 *   data : com.eried.eucplanet.data.repository.RadarRepository
 *            .currentFrame                                : StateFlow<RadarFrame?>
 *            .connectionState                             : StateFlow<ConnectionState>
 *          RadarFrame fields a tile / pill would read:
 *            threats: List<RadarThreat>      -- one entry per detected vehicle
 *              .distanceM            (Int, metres)
 *              .approachSpeedKmh     (Int, km/h)
 *              .threatLevel          (NONE / APPROACHING / FAST_APPROACH)
 *            batteryPercent          (Int?, radar device battery)
 *            closest                 (RadarThreat?, computed accessor)
 *            maxLevel                (ThreatLevel, computed accessor)
 *
 * Suggested tiles (open to redesign):
 *   - "Closest car"            : closest?.distanceM, dimmed when null
 *   - "Approach speed"         : closest?.approachSpeedKmh
 *   - "Vehicles behind"        : threats.size, with maxLevel as tint
 *
 * For a dashboard-source-style integration:
 *   1. Add a RADAR entry to this enum (suggested AccentRed, since the
 *      whole feature is hazard-coded that colour already).
 *   2. Decide which capability flag fits (probably a new `hasThreats`
 *      rather than reusing hasSpeed/hasImu).
 *   3. Extend [SourceSnapshot] with a `radarThreats: List<RadarThreat>?`
 *      or wrap the radar in its own snapshot type; the existing GPS
 *      snapshot shape doesn't fit cleanly because radar has no position
 *      or IMU.
 *
 * The radar overlay (ui/radar/RadarOverlay.kt) and the alarm engine
 * already subscribe to RadarRepository the way a tile would, so the
 * data path is proven.
 */

/**
 * Snapshot fed to the source dialog. Each field is nullable so the UI can
 * dash out rows the source doesn't currently provide (e.g. RaceBox accel
 * stays null until the device sends an extended-frame, even though the
 * source claims hasImu = true at the enum level).
 */
data class SourceSnapshot(
    val speedKmh: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** True when speed/position values are flowing live; UI dims when false. */
    val isLive: Boolean = false,
    /** Accel in g, axes aligned to the source's reference frame. */
    val accelXG: Float? = null,
    val accelYG: Float? = null,
    val accelZG: Float? = null,
    /** Heading of motion in degrees [0..360), or null. Phone source fills
     *  this in from [android.location.Location.getBearing] when speed > 0. */
    val headingDeg: Float? = null,
    /** Vertical speed in m/s (+up), or null. */
    val verticalSpeedMps: Float? = null,
    /** GPS quality indicator, only filled for sources with a GPS receiver. */
    val numSatellites: Int? = null,
    /** Horizontal positional accuracy in metres, or null. */
    val accuracyMeters: Float? = null,
    /** Wall-clock time of the most recent data tick from this source, in ms,
     *  or null if the source has never sent anything. The dashboard renders
     *  a "Last update Xs ago" row off this; the ticker recomposes the row
     *  every second so the elapsed time stays accurate without re-emitting
     *  the snapshot. */
    val lastUpdateMs: Long? = null
) {
    /** Magnitude of horizontal G-force, useful as a single safety number. */
    val horizGMagnitude: Float?
        get() {
            val x = accelXG ?: return null
            val z = accelZG ?: return null
            return kotlin.math.sqrt(x * x + z * z)
        }
}
