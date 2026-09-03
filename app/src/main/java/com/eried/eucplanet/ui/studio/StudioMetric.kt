package com.eried.eucplanet.ui.studio

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.Units
import kotlin.math.absoluteValue

/** Whether a metric needs unit conversion, and against which unit setting. */
enum class StudioMetricKind { SPEED, DISTANCE, ALTITUDE, TEMPERATURE, PRESSURE, CONSUMPTION, PLAIN }

/**
 * The pressure unit to print in.
 *
 * The rider's setting when there is one. Blank falls back to deriving it from
 * the distance unit, which is what [Units.effectivePressureUnit] does with an
 * unset setting and what every caller here used to do unconditionally.
 */
private fun pressureUnitFor(distUnit: String, pressureUnit: String): String =
    pressureUnit.ifBlank { if (distUnit == "mi") "psi" else "bar" }

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
    val extract: (WheelData) -> Float,
    /** True for metrics that are text, not a scalar (GPS coordinates), so they
     *  are offered only on a text value element, never a dial / bar / graph. */
    val textOnly: Boolean = false
) {
    SPEED("SPEED", "Speed", StudioMetricKind.SPEED, "", 1, 60f, { it.speed.absoluteValue }),
    BATTERY("BATTERY", "Battery", StudioMetricKind.PLAIN, "%", 0, 100f, { it.batteryPercent.toFloat() }),
    TEMPERATURE("TEMP", "Temperature", StudioMetricKind.TEMPERATURE, "", 0, 100f, { it.maxTemperature }),
    VOLTAGE("VOLTAGE", "Voltage", StudioMetricKind.PLAIN, "V", 1, 100f, { it.voltage }),
    CURRENT("CURRENT", "Current", StudioMetricKind.PLAIN, "A", 1, 80f, { it.current }),
    TORQUE("TORQUE", "Torque", StudioMetricKind.PLAIN, "Nm", 1, 150f, { it.torque }),
    PHASE_CURRENT("PHASE_CURRENT", "Phase current", StudioMetricKind.PLAIN, "A", 1, 200f, { it.phaseCurrent }),
    POWER("POWER", "Power", StudioMetricKind.PLAIN, "W", 0, 3000f, { it.motorPower.toFloat() }),
    PWM("PWM", "PWM", StudioMetricKind.PLAIN, "%", 0, 100f, { it.pwm.absoluteValue }),
    TRIP("TRIP", "Trip distance", StudioMetricKind.DISTANCE, "", 2, 50f, { it.tripDistance }),
    TRIP_METER("TRIP_METER", "Trip meter", StudioMetricKind.DISTANCE, "", 1, 50f, { it.tripMeterKm.coerceAtLeast(0f) }),
    ODOMETER("ODOMETER", "Odometer", StudioMetricKind.DISTANCE, "", 1, 5000f, { it.totalDistance }),
    // Energy is a running total since connect; consumption and range are rates
    // over the rider's rolling window. NaN means "not enough ridden to say", and
    // 0 is the honest stand-in for a gauge that has to draw something.
    WH_CONSUMED("WH_CONSUMED", "Energy", StudioMetricKind.PLAIN, "Wh", 0, 1000f, { it.whConsumed }),
    WH_PER_KM("WH_PER_KM", "Consumption", StudioMetricKind.CONSUMPTION, "", 0, 60f,
        { if (it.whPerKmRecent.isNaN()) 0f else it.whPerKmRecent }),
    RANGE_ESTIMATE("RANGE_ESTIMATE", "Range", StudioMetricKind.DISTANCE, "", 0, 100f,
        { if (it.rangeKmEstimate.isNaN()) 0f else it.rangeKmEstimate }),
    PITCH("PITCH", "Pitch", StudioMetricKind.PLAIN, "°", 1, 30f, { it.pitchAngle }),
    ROLL("ROLL", "Roll", StudioMetricKind.PLAIN, "°", 1, 30f, { it.rollAngle }),
    G_FORCE("G-FORCE", "G-Force", StudioMetricKind.PLAIN, "g", 2, 2f, { it.gForce }),
    EXTERNAL_GPS_BATTERY("EXT_GPS_BATTERY", "Ext GPS battery", StudioMetricKind.PLAIN, "%", 0, 100f, { it.externalGpsBatteryPercent.toFloat() }),
    EXTERNAL_GPS_SPEED("EXT_GPS_SPEED", "Ext GPS speed", StudioMetricKind.SPEED, "", 1, 60f, { it.externalGpsSpeedKmh.coerceAtLeast(0f) }),
    TIRE_PRESSURE("TIRE_PRESSURE", "Tire pressure", StudioMetricKind.PRESSURE, "", 1, 50f, { it.tirePressureKpa }),
    GPS_SPEED("GPS_SPEED", "GPS speed", StudioMetricKind.SPEED, "", 1, 60f, { it.gpsSpeedKmh.coerceAtLeast(0f) }),
    // Altitude is a DISTANCE so it follows the rider's distance unit the way
    // the dashboard tile does (metres, or feet on miles). NaN means no fix
    // yet; 0 would draw a rider at sea level who is not.
    GPS_ALTITUDE("GPS_ALTITUDE", "Altitude", StudioMetricKind.ALTITUDE, "", 0, 1000f,
        { if (it.gpsAltitudeM.isNaN()) 0f else it.gpsAltitudeM }),
    // A lat/lng pair shown as text (not a scalar), so it only makes sense on a
    // text value element. extract is a placeholder; formatted() renders the pair.
    GPS("GPS", "GPS coordinates", StudioMetricKind.PLAIN, "", 0, 1f, { 0f }, textOnly = true);

    /** True when this metric renders a unit beside its value (so the config
     *  sheet's unit-position control is only meaningful for these). */
    val hasUnit: Boolean get() = kind != StudioMetricKind.PLAIN || plainUnit.isNotEmpty()

    /** The raw value converted into the rider's chosen display unit. */
    fun displayValue(
        data: WheelData,
        speedUnit: String,
        distUnit: String,
        tempUnit: String,
        pressureUnit: String = "",
    ): Float {
        val raw = extract(data)
        return when (kind) {
            StudioMetricKind.SPEED -> Units.speed(raw, speedUnit)
            StudioMetricKind.DISTANCE -> Units.distance(raw, distUnit)
            // Held in metres; feet for riders on miles, matching the dashboard tile.
            StudioMetricKind.ALTITUDE -> if (distUnit == "mi") raw * 3.28084f else raw
            StudioMetricKind.TEMPERATURE -> Units.temperature(raw, tempUnit)
            StudioMetricKind.PRESSURE -> Units.pressure(raw, pressureUnitFor(distUnit, pressureUnit))
            // Held per km. Dividing by the display units in one km inverts the
            // conversion the way a rate needs: Wh/mi is the km figure over
            // 0.621371, Wh/mil over 0.1.
            StudioMetricKind.CONSUMPTION -> {
                val unitsPerKm = Units.distance(1f, distUnit)
                if (raw.isNaN() || unitsPerKm <= 0f) 0f else raw / unitsPerKm
            }
            StudioMetricKind.PLAIN -> raw
        }
    }

    /** The unit label shown beside the value (already locale-aware). */
    fun unitText(
        context: Context,
        speedUnit: String,
        distUnit: String,
        tempUnit: String,
        pressureUnit: String = "",
    ): String =
        when (kind) {
            StudioMetricKind.SPEED -> Units.speedUnit(context, speedUnit)
            StudioMetricKind.DISTANCE -> Units.distanceUnit(distUnit)
            StudioMetricKind.ALTITUDE -> if (distUnit == "mi") "ft" else "m"
            StudioMetricKind.TEMPERATURE -> Units.tempUnit(tempUnit)
            StudioMetricKind.PRESSURE -> Units.pressureUnit(pressureUnitFor(distUnit, pressureUnit))
            StudioMetricKind.CONSUMPTION -> "Wh/${Units.distanceUnit(distUnit)}"
            StudioMetricKind.PLAIN -> plainUnit
        }

    /** The value formatted to [decimals] decimal places. */
    fun formatted(
        data: WheelData,
        speedUnit: String,
        distUnit: String,
        tempUnit: String,
        pressureUnit: String = "",
    ): String {
        if (this == GPS) {
            // A lat/lng pair rendered as text; 5 dp is ~1 m. Both 0 means no fix.
            return if (data.latitude == 0.0 && data.longitude == 0.0) "--"
            else String.format(java.util.Locale.US, "%.5f, %.5f", data.latitude, data.longitude)
        }
        val v = displayValue(data, speedUnit, distUnit, tempUnit, pressureUnit)
        // A pressure needs as many decimals as its unit does: 1 for psi, 2 for
        // bar, 3 for MPa. The metric's own constant cannot be right for all
        // three, so the unit decides.
        val dp =
            if (kind == StudioMetricKind.PRESSURE)
                Units.pressureDecimals(pressureUnitFor(distUnit, pressureUnit))
            else decimals
        return if (dp == 0) v.toInt().toString()
        else String.format(java.util.Locale.US, "%.${dp}f", v)
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
    StudioMetric.PHASE_CURRENT -> stringResource(R.string.studio_metric_phase_current)
    StudioMetric.TORQUE -> stringResource(R.string.studio_metric_torque)
    StudioMetric.POWER -> stringResource(R.string.studio_metric_power)
    StudioMetric.PWM -> stringResource(R.string.studio_metric_pwm)
    StudioMetric.TRIP -> stringResource(R.string.studio_metric_trip_distance)
    StudioMetric.TRIP_METER -> stringResource(R.string.studio_metric_trip_meter)
    StudioMetric.ODOMETER -> stringResource(R.string.studio_metric_odometer)
    StudioMetric.WH_CONSUMED -> stringResource(R.string.studio_metric_wh_consumed)
    StudioMetric.WH_PER_KM -> stringResource(R.string.studio_metric_wh_per_km)
    StudioMetric.RANGE_ESTIMATE -> stringResource(R.string.studio_metric_range)
    StudioMetric.PITCH -> stringResource(R.string.studio_metric_pitch)
    StudioMetric.ROLL -> stringResource(R.string.studio_metric_roll)
    StudioMetric.G_FORCE -> stringResource(R.string.studio_metric_g_force)
    StudioMetric.EXTERNAL_GPS_BATTERY -> stringResource(R.string.studio_metric_external_gps_battery)
    StudioMetric.EXTERNAL_GPS_SPEED -> stringResource(R.string.studio_metric_external_gps_speed)
    StudioMetric.TIRE_PRESSURE -> stringResource(R.string.studio_metric_tire_pressure)
    StudioMetric.GPS_SPEED -> stringResource(R.string.studio_metric_gps_speed)
    StudioMetric.GPS_ALTITUDE -> stringResource(R.string.studio_metric_gps_altitude)
    StudioMetric.GPS -> stringResource(R.string.studio_metric_gps)
}
