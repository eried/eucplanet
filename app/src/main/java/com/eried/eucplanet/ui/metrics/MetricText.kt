package com.eried.eucplanet.ui.metrics

import com.eried.eucplanet.data.model.MetricValueFormat
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.MetricSanity
import com.eried.eucplanet.util.Units
import kotlin.math.absoluteValue

/**
 * Everything [MetricText.of] needs to turn a metric key into the text the
 * dashboard shows, with no Compose or Android types so any screen can build it.
 */
data class MetricTextInputs(
    val live: Boolean,
    val wheel: WheelData,
    val speedUnit: String,
    val distanceUnit: String,
    val tempUnit: String,
    val pressureUnit: String,
    val currentAsWatts: Boolean,
    val tripMeterKm: Float,
    val phoneBatteryPct: Int,
    val externalGpsBatteryPct: Int?,
    val gpsAltitudeM: Double?,
    val gpsSpeedKmh: Float?,
    val gpsBearingDeg: Float?,
    val gpsAccuracyM: Float?,
)

object MetricText {
    const val PLACEHOLDER = "--"

    fun speedUnitLabel(speedUnit: String): String = when (speedUnit) {
        "mph" -> "mph"
        "ms" -> "m/s"
        "kn" -> "kn"
        else -> "km/h"
    }

    /**
     * Consumption in the rider's distance unit. Stored per km, so dividing by the
     * number of display units in one km converts it: a mile is 0.621371 of a km, so
     * Wh/mi is the km figure over 0.621371, and a Scandinavian mil is 10 km, so it
     * is the km figure over 0.1.
     *
     * Null when there is nothing worth showing yet (no window, or a stretch of
     * regen that makes net energy zero), so each caller supplies its own
     * placeholder rather than this inventing a zero.
     */
    fun whPerDistance(whPerKm: Float, distanceUnit: String): String? {
        if (whPerKm.isNaN() || whPerKm <= 0f) return null
        val unitsPerKm = Units.distance(1f, distanceUnit)
        if (unitsPerKm <= 0f) return null
        return "%.0f Wh/%s".format(
            whPerKm / unitsPerKm,
            Units.distanceUnit(distanceUnit),
        )
    }

    fun of(key: String, i: MetricTextInputs): String {
        if (!i.live) return PLACEHOLDER
        val wheelData = i.wheel
        val placeholder = PLACEHOLDER
        val speedUnit = i.speedUnit
        val distanceUnit = i.distanceUnit
        val tempUnit = i.tempUnit
        val pressureUnit = i.pressureUnit
        val speedUnitLabel = speedUnitLabel(speedUnit)
        val tempUnitLabel = Units.tempUnit(tempUnit)
        val distUnit = Units.distanceUnit(distanceUnit)
        return when (key) {
            "BATTERY" -> if (wheelData.batteryPercent > 0) "${wheelData.batteryPercent}%" else placeholder
            // NaN for the first half minute of a ride, because the
            // line needs a little of the ride before it means
            // anything. A placeholder says so; a zero would not.
            "BATTERY_ENVELOPE" -> wheelData.batteryEnvelope
                .takeIf { !it.isNaN() }
                ?.let { "%.0f%%".format(it) }
                ?: placeholder
            "TEMPERATURE" -> if (wheelData.maxTemperature > 0f)
                "%.0f%s".format(Units.temperature(wheelData.maxTemperature, tempUnit), tempUnitLabel) else placeholder
            "VOLTAGE" -> if (wheelData.voltage > 0f) "%.1fV".format(wheelData.voltage) else placeholder
            "CURRENT" -> if (i.currentAsWatts) "%.0fW".format(wheelData.voltage * wheelData.current)
                else "%.1fA".format(wheelData.current)
            "LOAD" -> "%.0f%%".format(wheelData.pwm.absoluteValue)
            "TRIP" -> "%.1f %s".format(Units.distance(wheelData.tripDistance, distanceUnit), distUnit)
            "TRIP_METER" -> "%.1f %s".format(
                Units.distance(i.tripMeterKm, distanceUnit),
                distUnit
            )
            "SPEED" -> "%.0f %s".format(
                Units.speed(wheelData.speed, speedUnit),
                speedUnitLabel
            )
            "BATTERY_POWER", "POWER" -> "${wheelData.batteryPower}W"
            "MOTOR_POWER" -> "${wheelData.motorPower}W"
            // Composite-friendly extras (also work as standalone
            // tiles -- the small numbers fit fine in a 61 dp box).
            "LAT_LONG" -> if (wheelData.latitude != 0.0 || wheelData.longitude != 0.0)
                "%.4f, %.4f".format(wheelData.latitude, wheelData.longitude) else placeholder
            "WHEEL_MAX_SPEED" -> if (wheelData.wheelMaxSpeedKmh > 0f)
                "%.0f %s".format(
                    Units.speed(wheelData.wheelMaxSpeedKmh, speedUnit),
                    speedUnitLabel
                ) else placeholder
            "WHEEL_ALARM_SPEED" -> if (wheelData.wheelAlarmSpeedKmh > 0f)
                "%.0f %s".format(
                    Units.speed(wheelData.wheelAlarmSpeedKmh, speedUnit),
                    speedUnitLabel
                ) else placeholder
            "PC_MODE" -> when (wheelData.pcMode) {
                0 -> "LOCK"
                1 -> "DRIVE"
                2 -> "OFF"
                3 -> "IDLE"
                else -> placeholder
            }
            "LIGHT_ON" -> if (wheelData.lightOn) "ON" else "OFF"
            "ODOMETER" -> "%.1f %s".format(
                Units.distance(wheelData.totalDistance, distanceUnit),
                distUnit
            )
            "BATTERY_1" -> if (wheelData.battery1Percent > 0f) "%.0f%%".format(wheelData.battery1Percent) else placeholder
            "BATTERY_2" -> if (wheelData.battery2Percent > 0f) "%.0f%%".format(wheelData.battery2Percent) else placeholder
            "PITCH" -> "%.1f°".format(wheelData.pitchAngle)
            "ROLL" -> "%.1f°".format(wheelData.rollAngle)
            "G_FORCE" -> "%.2fg".format(wheelData.gForce)
            "LATERAL_G" -> "%.2fg".format(wheelData.accelX)
            "FORWARD_G" -> "%.2fg".format(wheelData.forwardGFromSpeed)
            "TORQUE" -> "%.1fNm".format(wheelData.torque)
            "PHASE_CURRENT" -> "%.1fA".format(wheelData.phaseCurrent)
            "DYN_SPEED_LIMIT" -> if (wheelData.dynamicSpeedLimit > 0f)
                "%.0f %s".format(
                    Units.speed(wheelData.dynamicSpeedLimit, speedUnit),
                    speedUnitLabel
                ) else placeholder
            "DYN_CURRENT_LIMIT" -> if (wheelData.dynamicCurrentLimit > 0f)
                "%.1fA".format(wheelData.dynamicCurrentLimit) else placeholder
            "AUTO_OFF" -> if (wheelData.autoOffSeconds >= 0)
                MetricValueFormat.autoOffClock(wheelData.autoOffSeconds)
                else placeholder
            "MOTOR_TEMP" -> wheelData.temperatures.getOrNull(0)
                ?.takeIf { MetricSanity.isPlausibleTempC(it) }
                ?.let { "%.0f%s".format(Units.temperature(it, tempUnit), tempUnitLabel) }
                ?: placeholder
            "CONTROLLER_TEMP" -> wheelData.temperatures.getOrNull(1)
                ?.takeIf { MetricSanity.isPlausibleTempC(it) }
                ?.let { "%.0f%s".format(Units.temperature(it, tempUnit), tempUnitLabel) }
                ?: placeholder
            "BATTERY_TEMP" -> wheelData.temperatures.getOrNull(2)
                ?.takeIf { MetricSanity.isPlausibleTempC(it) }
                ?.let { "%.0f%s".format(Units.temperature(it, tempUnit), tempUnitLabel) }
                ?: placeholder
            // Guarded on "is anything measuring", not on "> 0", so
            // a cap reporting a flat tyre shows 0.00 bar rather than a
            // dash that reads as "no sensor".
            "TIRE_PRESSURE" -> if (wheelData.hasTirePressure)
                Units.formatPressure(
                    wheelData.tirePressureKpa, pressureUnit)
            else placeholder
            "PHONE_BATTERY" -> if (i.phoneBatteryPct in 0..100) "${i.phoneBatteryPct}%" else placeholder
            "EXTERNAL_GPS_BATTERY" -> i.externalGpsBatteryPct?.let { "$it%" } ?: placeholder
            "GPS_ALTITUDE" -> i.gpsAltitudeM?.let { alt ->
                // Imperial users see feet; everyone else meters.
                if (distanceUnit == "mi") "%.0fft".format(alt.toFloat() * 3.28084f)
                else "%.0fm".format(alt.toFloat())
            } ?: placeholder
            // Same conversion path as every other speed tile: store
            // km/h, convert to the rider's unit at render time.
            "GPS_SPEED" -> i.gpsSpeedKmh?.let { kmh ->
                "%.1f %s".format(Units.speed(kmh, speedUnit), speedUnitLabel)
            } ?: placeholder
            "GPS_HEADING" -> i.gpsBearingDeg?.let { "%.0f°".format(it) } ?: placeholder
            "GPS_ACCURACY" -> i.gpsAccuracyM?.let { acc ->
                if (distanceUnit == "mi") "%.0fft".format(acc * 3.28084f)
                else "%.0fm".format(acc)
            } ?: placeholder
            // Link RSSI from the GATT layer (0 = not yet read).
            "BT_RSSI" -> if (wheelData.rssiDbm != 0) "${wheelData.rssiDbm} dBm" else placeholder
            // Ride energy since connect (the Battery screen's running
            // integral). Wh/km divides by trip distance once there's a
            // little of it, so a tiny denominator can't spike the number.
            "WH_CONSUMED" -> if (wheelData.whConsumed > 0f) "%.0f Wh".format(wheelData.whConsumed) else placeholder
            "REGEN_WH" -> if (wheelData.whRegen > 0f) "%.0f Wh".format(wheelData.whRegen) else placeholder
            "WH_PER_KM" -> whPerDistance(wheelData.whPerKmRecent, distanceUnit)
                ?: placeholder
            "RANGE_ESTIMATE" -> wheelData.rangeKmEstimate.takeIf { !it.isNaN() }?.let {
                "%.0f %s".format(
                    Units.distance(it, distanceUnit),
                    Units.distanceUnit(distanceUnit),
                )
            } ?: placeholder
            // SLOPE / ASCENT / DESCENT need integrated altitude
            // history (not yet wired). MOTOR_RPM isn't surfaced on
            // WheelData today - that needs adapter-side plumbing.
            // Placeholder for now.
            else -> placeholder
        }
    }
}
