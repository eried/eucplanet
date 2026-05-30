package com.eried.eucplanet.data.model

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentOrange
import com.eried.eucplanet.ui.theme.AccentPink
import com.eried.eucplanet.ui.theme.AccentPurple

/**
 * Metric layer — single source of truth for every metric the dashboard
 * editor catalogues, the live dashboard renders, and the debug overlay
 * reflects on. Mirrors [ActionCatalog]: adding a new metric is one entry
 * in [MetricCatalog.all]; the editor / live dashboard / debug overlay
 * all pick it up automatically.
 *
 * The catalog deliberately stores UI-affecting metadata (accent color,
 * sparkline style, supports-stats flag) but NOT raw value extraction or
 * unit conversion. Those stay in the live dashboard because they need
 * AppSettings (for imperial/metric) and WheelData (for the wheel
 * snapshot) — keeping the catalog free of those references means it
 * stays a pure metadata declaration that compiles with no dependencies
 * beyond Compose colour primitives.
 *
 * The PER-METRIC sparkline style is what gives each tile its identity
 * on the live dashboard: CURRENT renders as a bipolar area (regen vs
 * draw on either side of zero), counter-style metrics like TRIP /
 * ODOMETER skip the sparkline entirely, the rest pick a line, smoothed
 * line, or filled area as appropriate.
 */

/**
 * How the rolling sparkline behind a metric tile should be drawn. The
 * live dashboard reads this when [MetricSlotStats.sparkline] is enabled.
 * Choosing a style is purely visual — corner stats (min/max/avg) compute
 * from the same history regardless of the style picked, and remain
 * computable even when the rider hides the sparkline.
 */
enum class SparklineStyle {
    /** No background sparkline. Used for monotonic counters where a chart isn't meaningful. */
    NONE,

    /** Thin stroke connecting samples. */
    LINE,

    /** Stroke with a Catmull-Rom-ish smoothing pass — softer for slow-moving metrics. */
    SMOOTH_LINE,

    /** Stroke + faint fill underneath; the dashboard's default treatment historically. */
    AREA,

    /**
     * Stroke + fill above OR below a baseline (usually 0). Used for
     * bipolar metrics like CURRENT (regen vs draw), TORQUE (drive vs
     * brake), PITCH/ROLL angles. Renderer fills the positive lobe in
     * the accent colour and the negative lobe in a secondary tint.
     */
    AREA_BIPOLAR
}

/**
 * Metadata for a single dashboard metric. Catalog declares one of these
 * per static metric key. Dynamic instances (`M:uuid` composites, `C:uuid`
 * custom tiles) don't have entries here — they pull cells from the
 * catalog at render time but have their own per-instance state.
 */
data class MetricSpec(
    /** Stable identifier persisted in settings (composites/custom tiles reference these by key too). */
    val key: String,
    @StringRes val labelRes: Int,
    /** Optional explainer surfaced in the slot-sheet info box. */
    @StringRes val descriptionRes: Int? = null,
    /** Accent colour the tile's value text + sparkline tint pick up. */
    val accent: Color = AccentBlue,
    /** Sparkline drawing style. [SparklineStyle.NONE] for monotonic counters. */
    val sparkline: SparklineStyle = SparklineStyle.AREA,
    /**
     * True when min / max / avg / percentiles compute meaningfully for
     * this metric. False for monotonic counters (TRIP, ODOMETER, trip-
     * time, energy totals) and circular values (GPS_HEADING wraps at
     * 360°).
     */
    val supportsStats: Boolean = true,
    /**
     * Baseline for [SparklineStyle.AREA_BIPOLAR]. Usually 0 — separates
     * the positive lobe (drawn in [accent]) from the negative lobe
     * (drawn in [bipolarNegativeAccent], or a darker tint of [accent]
     * if null).
     */
    val bipolarBaseline: Float = 0f,
    /** Colour for the negative lobe when [sparkline] is [SparklineStyle.AREA_BIPOLAR]. */
    val bipolarNegativeAccent: Color? = null
)

object MetricCatalog {

    val all: List<MetricSpec> = listOf(
        // ---- Default active grid (first 6 on a fresh install) ----
        MetricSpec(
            key = "BATTERY",
            labelRes = R.string.metric_chip_battery,
            accent = AccentGreen,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "TEMPERATURE",
            labelRes = R.string.metric_chip_temperature,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "VOLTAGE",
            labelRes = R.string.metric_chip_voltage,
            accent = AccentBlue,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "CURRENT",
            labelRes = R.string.metric_chip_current,
            accent = AccentBlue,
            sparkline = SparklineStyle.AREA_BIPOLAR,
            bipolarNegativeAccent = AccentGreen   // regen reads as positive feedback
        ),
        MetricSpec(
            key = "LOAD",
            labelRes = R.string.metric_chip_load,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "TRIP",
            labelRes = R.string.metric_chip_trip,
            accent = AccentGreen,
            sparkline = SparklineStyle.NONE,       // monotonic counter
            supportsStats = false
        ),

        // ---- Pool (already-buffered) ----
        MetricSpec(
            key = "SPEED",
            labelRes = R.string.metric_chip_speed,
            accent = AccentGreen,
            sparkline = SparklineStyle.SMOOTH_LINE
        ),
        // POWER was a duplicate of BATTERY_POWER (both read
        // wheelData.batteryPower in displayValueFor). Removed from the
        // catalog so the editor only surfaces one "power" tile that
        // names what it actually measures. Existing dashboards that
        // still reference "POWER" fall through to placeholder until the
        // rider re-adds BATTERY_POWER from the pool.
        MetricSpec(
            key = "ODOMETER",
            labelRes = R.string.metric_chip_odometer,
            accent = AccentGreen,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "MOTOR_POWER",
            labelRes = R.string.metric_chip_motor_power,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA_BIPOLAR,
            bipolarNegativeAccent = AccentGreen
        ),
        MetricSpec(
            key = "BATTERY_POWER",
            labelRes = R.string.metric_chip_battery_power,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA_BIPOLAR,
            bipolarNegativeAccent = AccentGreen
        ),
        MetricSpec(
            key = "BATTERY_1",
            labelRes = R.string.metric_chip_battery_1,
            accent = AccentGreen,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "BATTERY_2",
            labelRes = R.string.metric_chip_battery_2,
            accent = AccentGreen,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "PITCH",
            labelRes = R.string.metric_chip_pitch,
            accent = AccentPurple,
            sparkline = SparklineStyle.AREA_BIPOLAR
        ),
        MetricSpec(
            key = "ROLL",
            labelRes = R.string.metric_chip_roll,
            accent = AccentPurple,
            sparkline = SparklineStyle.AREA_BIPOLAR
        ),
        MetricSpec(
            key = "G_FORCE",
            labelRes = R.string.metric_chip_g_force,
            accent = AccentPink,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "LATERAL_G",
            labelRes = R.string.metric_chip_lateral_g,
            accent = AccentPink,
            sparkline = SparklineStyle.AREA_BIPOLAR
        ),
        MetricSpec(
            key = "FORWARD_G",
            labelRes = R.string.metric_chip_forward_g,
            accent = AccentPink,
            sparkline = SparklineStyle.AREA_BIPOLAR
        ),
        MetricSpec(
            key = "TORQUE",
            labelRes = R.string.metric_chip_torque,
            accent = AccentBlue,
            sparkline = SparklineStyle.AREA_BIPOLAR,
            bipolarNegativeAccent = AccentGreen
        ),
        MetricSpec(
            key = "DYN_SPEED_LIMIT",
            labelRes = R.string.metric_chip_dyn_speed_limit,
            accent = AccentBlue,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "DYN_CURRENT_LIMIT",
            labelRes = R.string.metric_chip_dyn_current_limit,
            accent = AccentBlue,
            sparkline = SparklineStyle.LINE
        ),

        // ---- Individual temperature sensors ----
        MetricSpec(
            key = "MOTOR_TEMP",
            labelRes = R.string.metric_chip_motor_temp,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "CONTROLLER_TEMP",
            labelRes = R.string.metric_chip_controller_temp,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "BATTERY_TEMP",
            labelRes = R.string.metric_chip_battery_temp,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA
        ),

        // ---- Derived trip aggregates ----
        MetricSpec(
            key = "HEADROOM",
            labelRes = R.string.metric_chip_headroom,
            descriptionRes = R.string.metric_desc_headroom,
            accent = AccentBlue,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "TRIP_TIME",
            labelRes = R.string.metric_chip_trip_time,
            accent = AccentBlue,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "TRIP_MAX_SPEED",
            labelRes = R.string.metric_chip_trip_max_speed,
            descriptionRes = R.string.metric_desc_trip_max_speed,
            accent = AccentGreen,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "AVG_TRIP_SPEED",
            labelRes = R.string.metric_chip_avg_trip_speed,
            descriptionRes = R.string.metric_desc_avg_trip_speed,
            accent = AccentGreen,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "WH_CONSUMED",
            labelRes = R.string.metric_chip_wh_consumed,
            descriptionRes = R.string.metric_desc_wh_consumed,
            accent = AccentOrange,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "RANGE_ESTIMATE",
            labelRes = R.string.metric_chip_range_estimate,
            descriptionRes = R.string.metric_desc_range_estimate,
            accent = AccentGreen,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "WH_PER_KM",
            labelRes = R.string.metric_chip_wh_per_km,
            descriptionRes = R.string.metric_desc_wh_per_km,
            accent = AccentOrange,
            sparkline = SparklineStyle.LINE
        ),

        // ---- Phone + GPS feeds ----
        MetricSpec(
            key = "PHONE_BATTERY",
            labelRes = R.string.metric_chip_phone_battery,
            accent = AccentGreen,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "GPS_ALTITUDE",
            labelRes = R.string.metric_chip_gps_altitude,
            accent = AccentPurple,
            sparkline = SparklineStyle.SMOOTH_LINE
        ),
        MetricSpec(
            key = "GPS_SPEED",
            labelRes = R.string.metric_chip_gps_speed,
            accent = AccentGreen,
            sparkline = SparklineStyle.SMOOTH_LINE
        ),
        MetricSpec(
            key = "GPS_HEADING",
            labelRes = R.string.metric_chip_gps_heading,
            accent = AccentPurple,
            // GPS_HEADING wraps at 360°; sparkline + stats are misleading for circular values.
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "GPS_ACCURACY",
            labelRes = R.string.metric_chip_gps_accuracy,
            descriptionRes = R.string.metric_desc_gps_accuracy,
            accent = AccentPurple,
            sparkline = SparklineStyle.LINE
        ),

        // ---- Derived motion + pack health ----
        MetricSpec(
            key = "SLOPE",
            labelRes = R.string.metric_chip_slope,
            descriptionRes = R.string.metric_desc_slope,
            accent = AccentPurple,
            sparkline = SparklineStyle.AREA_BIPOLAR
        ),
        MetricSpec(
            key = "ASCENT",
            labelRes = R.string.metric_chip_ascent,
            accent = AccentPurple,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "DESCENT",
            labelRes = R.string.metric_chip_descent,
            accent = AccentPurple,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "MOTOR_RPM",
            labelRes = R.string.metric_chip_motor_rpm,
            accent = AccentOrange,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "REGEN_WH",
            labelRes = R.string.metric_chip_regen_wh,
            descriptionRes = R.string.metric_desc_regen_wh,
            accent = AccentGreen,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),

        // ---- Connectivity diagnostic ----
        MetricSpec(
            key = "BT_RSSI",
            labelRes = R.string.metric_chip_bt_rssi,
            descriptionRes = R.string.metric_desc_bt_rssi,
            accent = AccentBlue,
            sparkline = SparklineStyle.LINE
        )
    )

    private val byKeyMap: Map<String, MetricSpec> = all.associateBy { it.key }

    fun byKey(key: String): MetricSpec? = byKeyMap[key]

    val keys: List<String> = all.map { it.key }
}
