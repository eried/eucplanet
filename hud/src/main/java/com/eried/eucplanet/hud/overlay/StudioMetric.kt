package com.eried.eucplanet.hud.overlay

import android.content.Context
import androidx.compose.runtime.Composable
import com.eried.eucplanet.hud.ui.HudUnits
import kotlin.math.absoluteValue

/**
 * HUD-side copy of [com.eried.eucplanet.ui.studio.StudioMetric]. Identical
 * structure so the ported renderer code compiles unchanged. The only
 * divergence: unit conversion goes through [HudUnits] (which already lives
 * in :hud), and metric display names are hard-coded English here -- adding
 * proper localisation later is one stringResource swap.
 */
enum class StudioMetricKind { SPEED, DISTANCE, TEMPERATURE, PRESSURE, PLAIN }

enum class StudioMetric(
    val key: String,
    val label: String,
    val kind: StudioMetricKind,
    val plainUnit: String,
    val decimals: Int,
    val defaultMax: Float,
    val extract: (WheelData) -> Float,
    /** Text metrics (GPS coordinates) - offered only on a text value element. */
    val textOnly: Boolean = false
) {
    SPEED("SPEED", "Speed", StudioMetricKind.SPEED, "", 1, 60f, { it.speed.absoluteValue }),
    BATTERY("BATTERY", "Battery", StudioMetricKind.PLAIN, "%", 0, 100f, { it.batteryPercent.toFloat() }),
    TEMPERATURE("TEMP", "Temperature", StudioMetricKind.TEMPERATURE, "", 0, 100f, { it.maxTemperature }),
    VOLTAGE("VOLTAGE", "Voltage", StudioMetricKind.PLAIN, "V", 1, 100f, { it.voltage }),
    CURRENT("CURRENT", "Current", StudioMetricKind.PLAIN, "A", 1, 80f, { it.current }),
    POWER("POWER", "Power", StudioMetricKind.PLAIN, "W", 0, 3000f, { it.motorPower.toFloat() }),
    PWM("PWM", "PWM", StudioMetricKind.PLAIN, "%", 0, 100f, { it.pwm.absoluteValue }),
    TRIP("TRIP", "Trip distance", StudioMetricKind.DISTANCE, "", 2, 50f, { it.tripDistance }),
    TRIP_METER("TRIP_METER", "Trip meter", StudioMetricKind.DISTANCE, "", 1, 50f, { it.tripMeterKm.coerceAtLeast(0f) }),
    ODOMETER("ODOMETER", "Odometer", StudioMetricKind.DISTANCE, "", 1, 5000f, { it.totalDistance }),
    PITCH("PITCH", "Pitch", StudioMetricKind.PLAIN, "°", 1, 30f, { it.pitchAngle }),
    ROLL("ROLL", "Roll", StudioMetricKind.PLAIN, "°", 1, 30f, { it.rollAngle }),
    G_FORCE("G-FORCE", "G-Force", StudioMetricKind.PLAIN, "g", 2, 2f, { it.gForce }),
    EXTERNAL_GPS_BATTERY("EXT_GPS_BATTERY", "Ext GPS battery", StudioMetricKind.PLAIN, "%", 0, 100f, { it.externalGpsBatteryPercent.toFloat() }),
    EXTERNAL_GPS_SPEED("EXT_GPS_SPEED", "Ext GPS speed", StudioMetricKind.SPEED, "", 1, 60f, { it.externalGpsSpeedKmh.coerceAtLeast(0f) }),
    TIRE_PRESSURE("TIRE_PRESSURE", "Tire pressure", StudioMetricKind.PRESSURE, "", 1, 50f, { it.tirePressureKpa }),
    GPS_SPEED("GPS_SPEED", "GPS speed", StudioMetricKind.SPEED, "", 1, 60f, { it.gpsSpeedKmh.coerceAtLeast(0f) }),
    GPS("GPS", "GPS coordinates", StudioMetricKind.PLAIN, "", 0, 1f, { 0f }, textOnly = true);

    /** True when this metric renders a unit beside its value. */
    val hasUnit: Boolean get() = kind != StudioMetricKind.PLAIN || plainUnit.isNotEmpty()

    fun displayValue(data: WheelData, speedUnit: String, distUnit: String, tempUnit: String): Float {
        val raw = extract(data)
        return when (kind) {
            StudioMetricKind.SPEED -> HudUnits.speed(raw, speedUnit)
            StudioMetricKind.DISTANCE -> HudUnits.distance(raw, distUnit)
            StudioMetricKind.TEMPERATURE -> HudUnits.temperature(raw, tempUnit)
            StudioMetricKind.PRESSURE -> HudUnits.pressure(raw, distUnit)
            StudioMetricKind.PLAIN -> raw
        }
    }

    fun unitText(context: Context, speedUnit: String, distUnit: String, tempUnit: String): String =
        when (kind) {
            StudioMetricKind.SPEED -> HudUnits.speedSuffix(speedUnit)
            StudioMetricKind.DISTANCE -> HudUnits.distanceSuffix(distUnit)
            StudioMetricKind.TEMPERATURE -> HudUnits.temperatureSuffix(tempUnit)
            StudioMetricKind.PRESSURE -> HudUnits.pressureSuffix(distUnit)
            StudioMetricKind.PLAIN -> plainUnit
        }

    fun formatted(data: WheelData, speedUnit: String, distUnit: String, tempUnit: String): String {
        if (this == GPS) {
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

@Composable
fun StudioMetric.displayName(): String = when (this) {
    StudioMetric.SPEED -> "Speed"
    StudioMetric.BATTERY -> "Battery"
    StudioMetric.TEMPERATURE -> "Temp"
    StudioMetric.VOLTAGE -> "Voltage"
    StudioMetric.CURRENT -> "Current"
    StudioMetric.POWER -> "Power"
    StudioMetric.PWM -> "PWM"
    StudioMetric.TRIP -> "Trip"
    StudioMetric.TRIP_METER -> "Trip mtr"
    StudioMetric.ODOMETER -> "Odo"
    StudioMetric.PITCH -> "Pitch"
    StudioMetric.ROLL -> "Roll"
    StudioMetric.G_FORCE -> "G-Force"
    StudioMetric.EXTERNAL_GPS_BATTERY -> "Ext GPS bat"
    StudioMetric.EXTERNAL_GPS_SPEED -> "Ext GPS spd"
    StudioMetric.TIRE_PRESSURE -> "Tire"
    StudioMetric.GPS_SPEED -> "GPS spd"
    StudioMetric.GPS -> "GPS coord"
}
