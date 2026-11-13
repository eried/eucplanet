package com.eried.eucplanet.data.model

import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.StringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentOrange
import com.eried.eucplanet.ui.theme.AccentPink
import com.eried.eucplanet.ui.theme.AccentPurple

/**
 * Metric layer, single source of truth for every metric the dashboard
 * editor catalogues, the live dashboard renders, and the debug overlay
 * reflects on. Mirrors [ActionCatalog]: adding a new metric is one entry
 * in [MetricCatalog.all]; the editor / live dashboard / debug overlay
 * all pick it up automatically.
 *
 * The catalog stores UI metadata only (accent color, sparkline style,
 * supports-stats flag). Raw value extraction lives in [MetricRegistry].
 * Unit conversion and formatting stay with the consumers.
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
 * Choosing a style is purely visual, corner stats (min/max/avg) compute
 * from the same history regardless of the style picked, and remain
 * computable even when the rider hides the sparkline.
 */
enum class SparklineStyle {
    /** No background sparkline. Used for monotonic counters where a chart isn't meaningful. */
    NONE,

    /** Thin stroke connecting samples. */
    LINE,

    /** Stroke with a Catmull-Rom-ish smoothing pass, softer for slow-moving metrics. */
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
 * custom tiles) don't have entries here, they pull cells from the
 * catalog at render time but have their own per-instance state.
 */
data class MetricSpec(
    /** Stable identifier persisted in settings (composites/custom tiles reference these by key too). */
    val key: String,
    val labelRes: StringResource,
    /**
     * What to call this out loud, when the tile label is an abbreviation.
     *
     * Tile labels are sized for a dashboard cell: "Phase A", "BT signal",
     * "Battery (est)". Voice takes the labels as the words a rider says, so
     * those became things nobody says, and "Phase A" is worse than unsayable:
     * the "A" is a single letter, which the matcher requires to be spoken
     * exactly, so "phase amps" missed it and landed on Amps instead.
     *
     * Null for the great majority, whose label is already a word.
     */
    val spokenLabelRes: StringResource? = null,
    /** Optional explainer surfaced in the slot-sheet info box. */
    val descriptionRes: StringResource? = null,
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
     * Baseline for [SparklineStyle.AREA_BIPOLAR]. Usually 0, separates
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
        // ---- Core tiles (the default grid is AppSettings.dashboardMetricOrder) ----
        MetricSpec(
            key = "BATTERY",
            labelRes = Res.string.metric_chip_battery,
            accent = AccentGreen,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "BATTERY_ENVELOPE",
            labelRes = Res.string.metric_chip_battery_envelope,
            spokenLabelRes = Res.string.metric_spoken_battery_envelope,
            descriptionRes = Res.string.metric_desc_battery_envelope,
            // The trip chart's envelope colour, not the battery tile's green.
            // Same family, different reading, and two greens side by side read
            // as one number printed twice.
            accent = AccentBlue,
            // Not smoothed. This only moves when the charge moved, so a curve
            // would draw motion that did not happen.
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "TEMPERATURE",
            labelRes = Res.string.metric_chip_temperature,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "VOLTAGE",
            labelRes = Res.string.metric_chip_voltage,
            accent = AccentBlue,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "CURRENT",
            labelRes = Res.string.metric_chip_current,
            accent = AccentBlue,
            sparkline = SparklineStyle.AREA_BIPOLAR,
            bipolarNegativeAccent = AccentGreen   // regen reads as positive feedback
        ),
        MetricSpec(
            key = "LOAD",
            labelRes = Res.string.metric_chip_load,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "TRIP",
            labelRes = Res.string.metric_chip_trip,
            accent = AccentGreen,
            sparkline = SparklineStyle.NONE,       // monotonic counter
            supportsStats = false
        ),
        MetricSpec(
            // Car-odometer-style running distance, counts while connected and
            // persists across restarts. Tapping opens the distance-split detail
            // view, not the generic min/max/avg one, so it needs no stat buffer.
            key = "TRIP_METER",
            labelRes = Res.string.metric_chip_trip_meter,
            accent = AccentPurple,
            sparkline = SparklineStyle.NONE,       // monotonic counter
            supportsStats = false
        ),

        // ---- Pool (already-buffered) ----
        MetricSpec(
            key = "SPEED",
            labelRes = Res.string.metric_chip_speed,
            accent = AccentGreen,
            sparkline = SparklineStyle.SMOOTH_LINE
        ),
        // No POWER entry: it duplicated BATTERY_POWER. Saved layouts with
        // POWER are rewritten in DashboardViewModel.migrateLegacyMetricKeys,
        // and MetricRegistry keeps POWER as an alias.
        MetricSpec(
            key = "ODOMETER",
            labelRes = Res.string.metric_chip_odometer,
            accent = AccentGreen,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "MOTOR_POWER",
            labelRes = Res.string.metric_chip_motor_power,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA_BIPOLAR,
            bipolarNegativeAccent = AccentGreen
        ),
        MetricSpec(
            key = "BATTERY_POWER",
            labelRes = Res.string.metric_chip_battery_power,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA_BIPOLAR,
            bipolarNegativeAccent = AccentGreen
        ),
        MetricSpec(
            key = "BATTERY_1",
            labelRes = Res.string.metric_chip_battery_1,
            accent = AccentGreen,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "BATTERY_2",
            labelRes = Res.string.metric_chip_battery_2,
            accent = AccentGreen,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "PITCH",
            labelRes = Res.string.metric_chip_pitch,
            accent = AccentPurple,
            sparkline = SparklineStyle.AREA_BIPOLAR
        ),
        MetricSpec(
            key = "ROLL",
            labelRes = Res.string.metric_chip_roll,
            accent = AccentPurple,
            sparkline = SparklineStyle.AREA_BIPOLAR
        ),
        MetricSpec(
            key = "G_FORCE",
            labelRes = Res.string.metric_chip_g_force,
            accent = AccentPink,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "LATERAL_G",
            labelRes = Res.string.metric_chip_lateral_g,
            accent = AccentPink,
            sparkline = SparklineStyle.AREA_BIPOLAR
        ),
        MetricSpec(
            key = "FORWARD_G",
            labelRes = Res.string.metric_chip_forward_g,
            descriptionRes = Res.string.metric_desc_forward_g,
            accent = AccentPink,
            sparkline = SparklineStyle.AREA_BIPOLAR
        ),
        MetricSpec(
            key = "TORQUE",
            labelRes = Res.string.metric_chip_torque,
            accent = AccentBlue,
            sparkline = SparklineStyle.AREA_BIPOLAR,
            bipolarNegativeAccent = AccentGreen
        ),
        MetricSpec(
            key = "PHASE_CURRENT",
            labelRes = Res.string.metric_chip_phase_current,
            spokenLabelRes = Res.string.metric_spoken_phase_current,
            accent = AccentBlue,
            sparkline = SparklineStyle.AREA_BIPOLAR,
            bipolarNegativeAccent = AccentGreen
        ),
        MetricSpec(
            key = "DYN_SPEED_LIMIT",
            labelRes = Res.string.metric_chip_dyn_speed_limit,
            accent = AccentBlue,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "DYN_CURRENT_LIMIT",
            labelRes = Res.string.metric_chip_dyn_current_limit,
            accent = AccentBlue,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "AUTO_OFF",
            labelRes = Res.string.metric_chip_auto_off,
            accent = AccentBlue,
            // A countdown that resets when the wheel moves: a trend line and
            // min / avg stats say nothing about it. Same treatment as TRIP_TIME.
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),

        // ---- Individual temperature sensors ----
        MetricSpec(
            key = "MOTOR_TEMP",
            labelRes = Res.string.metric_chip_motor_temp,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "CONTROLLER_TEMP",
            labelRes = Res.string.metric_chip_controller_temp,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "BATTERY_TEMP",
            labelRes = Res.string.metric_chip_battery_temp,
            accent = AccentOrange,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "TIRE_PRESSURE",
            labelRes = Res.string.metric_chip_tire_pressure,
            descriptionRes = Res.string.metric_desc_tire_pressure,
            accent = AccentBlue,
            // Tracked like the other "extra" metrics (EXTRA_HISTORY_METRICS):
            // slow-drifting line with min/max/avg in the tap-to-graph detail.
            sparkline = SparklineStyle.SMOOTH_LINE,
            supportsStats = true
        ),

        // ---- Derived trip aggregates ----
        MetricSpec(
            key = "HEADROOM",
            labelRes = Res.string.metric_chip_headroom,
            descriptionRes = Res.string.metric_desc_headroom,
            accent = AccentBlue,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "TRIP_TIME",
            labelRes = Res.string.metric_chip_trip_time,
            accent = AccentBlue,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "TRIP_MAX_SPEED",
            labelRes = Res.string.metric_chip_trip_max_speed,
            descriptionRes = Res.string.metric_desc_trip_max_speed,
            accent = AccentGreen,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "AVG_TRIP_SPEED",
            labelRes = Res.string.metric_chip_avg_trip_speed,
            spokenLabelRes = Res.string.metric_spoken_avg_trip_speed,
            descriptionRes = Res.string.metric_desc_avg_trip_speed,
            accent = AccentGreen,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "WH_CONSUMED",
            labelRes = Res.string.metric_chip_wh_consumed,
            descriptionRes = Res.string.metric_desc_wh_consumed,
            accent = AccentOrange,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "RANGE_ESTIMATE",
            labelRes = Res.string.metric_chip_range_estimate,
            descriptionRes = Res.string.metric_desc_range_estimate,
            accent = AccentGreen,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "WH_PER_KM",
            labelRes = Res.string.metric_chip_wh_per_km,
            descriptionRes = Res.string.metric_desc_wh_per_km,
            accent = AccentOrange,
            sparkline = SparklineStyle.LINE
        ),

        // ---- Phone + GPS feeds ----
        MetricSpec(
            key = "PHONE_BATTERY",
            labelRes = Res.string.metric_chip_phone_battery,
            accent = AccentGreen,
            sparkline = SparklineStyle.AREA
        ),
        MetricSpec(
            key = "GPS_ALTITUDE",
            labelRes = Res.string.metric_chip_gps_altitude,
            accent = AccentPurple,
            sparkline = SparklineStyle.SMOOTH_LINE
        ),
        MetricSpec(
            key = "GPS_SPEED",
            labelRes = Res.string.metric_chip_gps_speed,
            accent = AccentGreen,
            sparkline = SparklineStyle.SMOOTH_LINE
        ),
        MetricSpec(
            key = "GPS_HEADING",
            labelRes = Res.string.metric_chip_gps_heading,
            accent = AccentPurple,
            // GPS_HEADING wraps at 360°; sparkline + stats are misleading for circular values.
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "GPS_ACCURACY",
            labelRes = Res.string.metric_chip_gps_accuracy,
            descriptionRes = Res.string.metric_desc_gps_accuracy,
            accent = AccentPurple,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "EXTERNAL_GPS_BATTERY",
            labelRes = Res.string.metric_chip_external_gps_battery,
            descriptionRes = Res.string.metric_desc_external_gps_battery,
            accent = AccentGreen,
            sparkline = SparklineStyle.AREA
        ),

        // ---- Derived motion + pack health ----
        MetricSpec(
            key = "SLOPE",
            labelRes = Res.string.metric_chip_slope,
            descriptionRes = Res.string.metric_desc_slope,
            accent = AccentPurple,
            sparkline = SparklineStyle.AREA_BIPOLAR
        ),
        MetricSpec(
            key = "ASCENT",
            labelRes = Res.string.metric_chip_ascent,
            accent = AccentPurple,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "DESCENT",
            labelRes = Res.string.metric_chip_descent,
            accent = AccentPurple,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "MOTOR_RPM",
            labelRes = Res.string.metric_chip_motor_rpm,
            accent = AccentOrange,
            sparkline = SparklineStyle.LINE
        ),
        MetricSpec(
            key = "REGEN_WH",
            labelRes = Res.string.metric_chip_regen_wh,
            descriptionRes = Res.string.metric_desc_regen_wh,
            accent = AccentGreen,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),

        // ---- Connectivity diagnostic ----
        MetricSpec(
            key = "BT_RSSI",
            labelRes = Res.string.metric_chip_bt_rssi,
            spokenLabelRes = Res.string.metric_spoken_bt_rssi,
            descriptionRes = Res.string.metric_desc_bt_rssi,
            accent = AccentBlue,
            sparkline = SparklineStyle.LINE
        ),

        // ---- Composite-friendly extras (small static text, no sparkline) ----
        // These work as standalone tiles too but were sized + worded for
        // composite cells. GPS coords as one "lat, long" string; firmware-
        // reported wheel limits so the rider can see what their wheel will
        // enforce; current ride mode (lock/drive/idle); light on/off mirror.
        MetricSpec(
            key = "LAT_LONG",
            labelRes = Res.string.metric_chip_lat_long,
            spokenLabelRes = Res.string.metric_spoken_lat_long,
            accent = AccentBlue,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "WHEEL_MAX_SPEED",
            labelRes = Res.string.metric_chip_wheel_max_speed,
            accent = AccentOrange,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "WHEEL_ALARM_SPEED",
            labelRes = Res.string.metric_chip_wheel_alarm_speed,
            accent = AccentOrange,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "PC_MODE",
            labelRes = Res.string.metric_chip_pc_mode,
            accent = AccentBlue,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        ),
        MetricSpec(
            key = "LIGHT_ON",
            labelRes = Res.string.metric_chip_light_on,
            // The tile says "Light" because it sits under a number that is
            // the answer. Spoken, the bare word is what a rider says when
            // they want the light switched, so the reading has to ask for
            // itself by a longer name. The matcher prefers the longest match,
            // which is what keeps "light status" off the toggle and off the
            // "status" report.
            spokenLabelRes = Res.string.metric_spoken_light_on,
            accent = AccentGreen,
            sparkline = SparklineStyle.NONE,
            supportsStats = false
        )
    )

    private val byKeyMap: Map<String, MetricSpec> = all.associateBy { it.key }

    fun byKey(key: String): MetricSpec? = byKeyMap[key]

    val keys: List<String> = all.map { it.key }
}
