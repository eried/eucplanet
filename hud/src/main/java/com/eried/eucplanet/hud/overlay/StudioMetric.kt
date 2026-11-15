package com.eried.eucplanet.hud.overlay

import android.content.Context
import androidx.compose.runtime.Composable
import com.eried.eucplanet.hud.ui.HudUnits
import kotlin.math.absoluteValue

/**
 * HUD-side copy of [com.eried.eucplanet.ui.studio.StudioMetric], with the
 * same keys and structure so the ported renderer code compiles unchanged.
 * A key missing here falls back to SPEED in [StudioMetric.fromKey], so every
 * phone key must have an entry (StudioMetricKeysTest holds the two together).
 * Unit conversion goes through [HudUnits] (which already lives
 * in :hud), and metric display names are hard-coded English here -- adding
 * proper localisation later is one stringResource swap.
 */
enum class StudioMetricKind { SPEED, DISTANCE, ALTITUDE, TEMPERATURE, PRESSURE, CONSUMPTION, PLAIN }

/**
 * The pressure unit to print in: the rider's own when the phone sent one,
 * otherwise derived from the distance unit as this always used to be.
 */
private fun pressureUnitFor(distUnit: String, pressureUnit: String): String =
    pressureUnit.ifBlank { if (distUnit == "mi") "psi" else "bar" }

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
    // The load-free battery line. NaN until the ride has enough behind it;
    // 0 is what a dial can draw, and it is where the scale starts anyway.
    BATTERY_ENVELOPE("BATTERY_ENVELOPE", "Battery (est)", StudioMetricKind.PLAIN, "%", 0, 100f,
        { if (it.batteryEnvelope.isNaN()) 0f else it.batteryEnvelope }),
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
    // Energy is a running total since connect; consumption and range are
    // rates the phone works out. NaN means it cannot say yet, and 0 is the
    // stand-in a gauge can draw.
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
    // Metres, or feet for a rider on miles. NaN means no fix yet and reads
    // as 0, the same stand-in as Consumption and Range.
    GPS_ALTITUDE("GPS_ALTITUDE", "Altitude", StudioMetricKind.ALTITUDE, "", 0, 1000f,
        { if (it.gpsAltitudeM.isNaN()) 0f else it.gpsAltitudeM }),
    GPS("GPS", "GPS coordinates", StudioMetricKind.PLAIN, "", 0, 1f, { 0f }, textOnly = true);

    /** True when this metric renders a unit beside its value. */
    val hasUnit: Boolean get() = kind != StudioMetricKind.PLAIN || plainUnit.isNotEmpty()

    fun displayValue(
        data: WheelData,
        speedUnit: String,
        distUnit: String,
        tempUnit: String,
        pressureUnit: String = "",
    ): Float {
        val raw = extract(data)
        return when (kind) {
            StudioMetricKind.SPEED -> HudUnits.speed(raw, speedUnit)
            StudioMetricKind.DISTANCE -> HudUnits.distance(raw, distUnit)
            // Held in metres; feet for riders on miles, as on the phone.
            StudioMetricKind.ALTITUDE -> if (distUnit == "mi") raw * 3.28084f else raw
            StudioMetricKind.TEMPERATURE -> HudUnits.temperature(raw, tempUnit)
            StudioMetricKind.PRESSURE ->
                HudUnits.pressure(raw, pressureUnitFor(distUnit, pressureUnit))
            // Held per km. A rate converts the other way round: divide by
            // how many display units one km is.
            StudioMetricKind.CONSUMPTION -> {
                val unitsPerKm = HudUnits.distance(1f, distUnit)
                if (raw.isNaN() || unitsPerKm <= 0f) 0f else raw / unitsPerKm
            }
            StudioMetricKind.PLAIN -> raw
        }
    }

    fun unitText(
        context: Context,
        speedUnit: String,
        distUnit: String,
        tempUnit: String,
        pressureUnit: String = "",
    ): String =
        when (kind) {
            StudioMetricKind.SPEED -> HudUnits.speedSuffix(speedUnit)
            StudioMetricKind.DISTANCE -> HudUnits.distanceSuffix(distUnit)
            StudioMetricKind.ALTITUDE -> if (distUnit == "mi") "ft" else "m"
            StudioMetricKind.TEMPERATURE -> HudUnits.temperatureSuffix(tempUnit)
            StudioMetricKind.PRESSURE ->
                HudUnits.pressureSuffix(pressureUnitFor(distUnit, pressureUnit))
            StudioMetricKind.CONSUMPTION -> "Wh/${HudUnits.distanceSuffix(distUnit)}"
            StudioMetricKind.PLAIN -> plainUnit
        }

    fun formatted(
        data: WheelData,
        speedUnit: String,
        distUnit: String,
        tempUnit: String,
        pressureUnit: String = "",
    ): String {
        if (this == GPS) {
            return if (data.latitude == 0.0 && data.longitude == 0.0) "--"
            else String.format(java.util.Locale.US, "%.5f, %.5f", data.latitude, data.longitude)
        }
        val v = displayValue(data, speedUnit, distUnit, tempUnit, pressureUnit)
        // A pressure needs as many decimals as its unit does: 1 for psi, 2 for
        // bar, 3 for MPa. One constant per metric cannot be right for all
        // three, so the unit decides.
        val dp =
            if (kind == StudioMetricKind.PRESSURE)
                HudUnits.pressureDecimals(pressureUnitFor(distUnit, pressureUnit))
            else decimals
        return if (dp == 0) v.toInt().toString()
        else String.format(java.util.Locale.US, "%.${dp}f", v)
    }

    companion object {
        fun fromKey(key: String): StudioMetric = entries.firstOrNull { it.key == key } ?: SPEED
    }
}

@Composable
fun StudioMetric.displayName(): String = when (this) {
    StudioMetric.SPEED -> "Speed"
    StudioMetric.BATTERY -> "Battery"
    // Short because these labels sit in a picker on glasses; the phone
    // spells it out.
    StudioMetric.BATTERY_ENVELOPE -> "Batt (est)"
    StudioMetric.TEMPERATURE -> "Temp"
    StudioMetric.VOLTAGE -> "Voltage"
    StudioMetric.CURRENT -> "Current"
    StudioMetric.TORQUE -> "Torque"
    StudioMetric.PHASE_CURRENT -> "Phase A"
    StudioMetric.POWER -> "Power"
    StudioMetric.PWM -> "PWM"
    StudioMetric.TRIP -> "Trip"
    StudioMetric.TRIP_METER -> "Trip mtr"
    StudioMetric.ODOMETER -> "Odo"
    StudioMetric.WH_CONSUMED -> "Energy"
    StudioMetric.WH_PER_KM -> "Consump"
    StudioMetric.RANGE_ESTIMATE -> "Range"
    StudioMetric.PITCH -> "Pitch"
    StudioMetric.ROLL -> "Roll"
    StudioMetric.G_FORCE -> "G-Force"
    StudioMetric.EXTERNAL_GPS_BATTERY -> "Ext GPS bat"
    StudioMetric.EXTERNAL_GPS_SPEED -> "Ext GPS spd"
    StudioMetric.TIRE_PRESSURE -> "Tire"
    StudioMetric.GPS_SPEED -> "GPS spd"
    StudioMetric.GPS_ALTITUDE -> "Altitude"
    StudioMetric.GPS -> "GPS coord"
}
