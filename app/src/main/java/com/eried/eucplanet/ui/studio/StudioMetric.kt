package com.eried.eucplanet.ui.studio

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.Units
import kotlin.math.absoluteValue

/** Whether a metric needs unit conversion, and against which unit setting. */
enum class StudioMetricKind { SPEED, DISTANCE, TEMPERATURE, PLAIN }

/**
 * The live telemetry values a DATA_VALUE / DATA_GRAPH overlay element can show.
 * [extract] pulls the raw value (always in the wheel's base unit: km/h, °C,
 * km, …) out of a [WheelData] tick; unit conversion happens in [formatted].
 */
enum class StudioMetric(
    val key: String,
    val label: String,
    val kind: StudioMetricKind,
    val plainUnit: String,
    val decimals: Int,
    /** Sensible full-scale value for a new dial / bar gauge of this metric. */
    val defaultMax: Float,
    val extract: (WheelData) -> Float
) {
    SPEED("SPEED", "Speed", StudioMetricKind.SPEED, "", 1, 60f, { it.speed.absoluteValue }),
    BATTERY("BATTERY", "Battery", StudioMetricKind.PLAIN, "%", 0, 100f, { it.batteryPercent.toFloat() }),
    TEMPERATURE("TEMP", "Temperature", StudioMetricKind.TEMPERATURE, "", 0, 100f, { it.maxTemperature }),
    VOLTAGE("VOLTAGE", "Voltage", StudioMetricKind.PLAIN, "V", 1, 100f, { it.voltage }),
    CURRENT("CURRENT", "Current", StudioMetricKind.PLAIN, "A", 1, 80f, { it.current }),
    POWER("POWER", "Power", StudioMetricKind.PLAIN, "W", 0, 3000f, { it.motorPower.toFloat() }),
    PWM("PWM", "PWM", StudioMetricKind.PLAIN, "%", 0, 100f, { it.pwm.absoluteValue }),
    TRIP("TRIP", "Trip distance", StudioMetricKind.DISTANCE, "", 2, 50f, { it.tripDistance }),
    ODOMETER("ODOMETER", "Odometer", StudioMetricKind.DISTANCE, "", 1, 5000f, { it.totalDistance }),
    PITCH("PITCH", "Pitch", StudioMetricKind.PLAIN, "°", 1, 30f, { it.pitchAngle }),
    ROLL("ROLL", "Roll", StudioMetricKind.PLAIN, "°", 1, 30f, { it.rollAngle }),
    G_FORCE("G-FORCE", "G-Force", StudioMetricKind.PLAIN, "g", 2, 2f, { it.gForce }),
    EXTERNAL_GPS_BATTERY("EXT_GPS_BATTERY", "GPS box battery", StudioMetricKind.PLAIN, "%", 0, 100f, { it.externalGpsBatteryPercent.toFloat() }),
    // A lat/lng pair shown as text (not a scalar), so it only makes sense on a
    // text value element. extract is a placeholder; formatted() renders the pair.
    GPS("GPS", "GPS coordinates", StudioMetricKind.PLAIN, "", 0, 1f, { 0f });

    /** The raw value converted into the rider's chosen display unit. */
    fun displayValue(data: WheelData, speedUnit: String, distUnit: String, tempUnit: String): Float {
        val raw = extract(data)
        return when (kind) {
            StudioMetricKind.SPEED -> Units.speed(raw, speedUnit)
            StudioMetricKind.DISTANCE -> Units.distance(raw, distUnit)
            StudioMetricKind.TEMPERATURE -> Units.temperature(raw, tempUnit)
            StudioMetricKind.PLAIN -> raw
        }
    }

    /** The unit label shown beside the value (already locale-aware). */
    fun unitText(context: Context, speedUnit: String, distUnit: String, tempUnit: String): String =
        when (kind) {
            StudioMetricKind.SPEED -> Units.speedUnit(context, speedUnit)
            StudioMetricKind.DISTANCE -> Units.distanceUnit(distUnit)
            StudioMetricKind.TEMPERATURE -> Units.tempUnit(tempUnit)
            StudioMetricKind.PLAIN -> plainUnit
        }

    /** The value formatted to [decimals] decimal places. */
    fun formatted(data: WheelData, speedUnit: String, distUnit: String, tempUnit: String): String {
        if (this == GPS) {
            // A lat/lng pair rendered as text; 5 dp is ~1 m. Both 0 means no fix.
            return if (data.latitude == 0.0 && data.longitude == 0.0) "--"
            else String.format(java.util.Locale.US, "%.5f, %.5f", data.latitude, data.longitude)
        }
        val v = displayValue(data, speedUnit, distUnit, tempUnit)
        return if (decimals == 0) v.toInt().toString()
        else String.format(java.util.Locale.US, "%.${decimals}f", v)
    }

    companion object {
        fun fromKey(key: String): StudioMetric = entries.firstOrNull { it.key == key } ?: SPEED
    }
}

/** Returns the localised display name for the metric, for use in Composable contexts only. */
@Composable
fun StudioMetric.displayName(): String = when (this) {
    StudioMetric.SPEED -> stringResource(R.string.studio_metric_speed)
    StudioMetric.BATTERY -> stringResource(R.string.studio_metric_battery)
    StudioMetric.TEMPERATURE -> stringResource(R.string.studio_metric_temperature)
    StudioMetric.VOLTAGE -> stringResource(R.string.studio_metric_voltage)
    StudioMetric.CURRENT -> stringResource(R.string.studio_metric_current)
    StudioMetric.POWER -> stringResource(R.string.studio_metric_power)
    StudioMetric.PWM -> stringResource(R.string.studio_metric_pwm)
    StudioMetric.TRIP -> stringResource(R.string.studio_metric_trip_distance)
    StudioMetric.ODOMETER -> stringResource(R.string.studio_metric_odometer)
    StudioMetric.PITCH -> stringResource(R.string.studio_metric_pitch)
    StudioMetric.ROLL -> stringResource(R.string.studio_metric_roll)
    StudioMetric.G_FORCE -> stringResource(R.string.studio_metric_g_force)
    StudioMetric.EXTERNAL_GPS_BATTERY -> stringResource(R.string.studio_metric_external_gps_battery)
    StudioMetric.GPS -> stringResource(R.string.studio_metric_gps)
}
