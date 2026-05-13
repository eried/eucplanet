package com.eried.eucplanet.ui.dashboard.sources

import androidx.compose.ui.graphics.Color
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
enum class DataSource(val displayName: String, val color: Color) {
    PHONE("Phone", AccentBlue),
    WHEEL("Wheel", AccentGreen),
    RACEBOX("RaceBox", AccentPurple);

    /**
     * Capability flags so each tab can render the right rows without
     * branching deep inside the view code. Update these alongside the
     * underlying repository when a new metric becomes available.
     */
    val hasSpeed: Boolean get() = true                  // all three expose speed
    val hasPosition: Boolean get() = this != WHEEL      // wheel has no GPS
    val hasImu: Boolean get() = this == PHONE || this == RACEBOX
}

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
    val accelZG: Float? = null
) {
    /** Magnitude of horizontal G-force, useful as a single safety number. */
    val horizGMagnitude: Float?
        get() {
            val x = accelXG ?: return null
            val z = accelZG ?: return null
            return kotlin.math.sqrt(x * x + z * z)
        }
}
