package com.eried.eucplanet.ui.studio

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.UnitFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Telemetry metrics an overlay element can bind to — faithful to Android's
 * `StudioMetric`. [key] is the wire token stored in `OverlayElement.metric`
 * (the HUD matches on it), so the strings here MUST stay exact ("TEMP",
 * "G-FORCE", …). Drives the editor's metric picker + the live preview values.
 */
enum class StudioMetric(val key: String, val label: String, val defaultMax: Float) {
    SPEED("SPEED", "Speed", 60f),
    BATTERY("BATTERY", "Battery", 100f),
    TEMPERATURE("TEMP", "Temperature", 100f),
    VOLTAGE("VOLTAGE", "Voltage", 100f),
    CURRENT("CURRENT", "Current", 80f),
    POWER("POWER", "Power", 3000f),
    PWM("PWM", "PWM", 100f),
    TRIP("TRIP", "Trip distance", 50f),
    ODOMETER("ODOMETER", "Odometer", 5000f),
    PITCH("PITCH", "Pitch", 30f),
    ROLL("ROLL", "Roll", 30f),
    G_FORCE("G-FORCE", "G-Force", 2f);

    /** Raw (canonical-unit) value for gauges. */
    fun raw(d: WheelData): Float = when (this) {
        SPEED -> abs(d.speed)
        BATTERY -> d.batteryPercent.toFloat()
        TEMPERATURE -> d.maxTemperature
        VOLTAGE -> d.voltage
        CURRENT -> d.current
        POWER -> d.motorPower.toFloat()
        PWM -> abs(d.pwm)
        TRIP -> d.tripDistance
        ODOMETER -> d.totalDistance
        PITCH -> d.pitchAngle
        ROLL -> d.rollAngle
        G_FORCE -> d.gForce
    }

    /** (value text, unit label) converted to the rider's display units. */
    fun display(d: WheelData, unitSpeed: String, unitDistance: String, unitTemp: String): Pair<String, String> = when (this) {
        SPEED -> UnitFormat.speed(abs(d.speed), unitSpeed).roundToInt().toString() to UnitFormat.speedLabel(unitSpeed)
        BATTERY -> d.batteryPercent.toString() to "%"
        TEMPERATURE -> UnitFormat.temperature(d.maxTemperature, unitTemp).roundToInt().toString() to UnitFormat.tempLabel(unitTemp)
        VOLTAGE -> oneDp(d.voltage) to "V"
        CURRENT -> oneDp(d.current) to "A"
        POWER -> d.motorPower.toString() to "W"
        PWM -> abs(d.pwm).roundToInt().toString() to "%"
        TRIP -> oneDp(UnitFormat.distance(d.tripDistance, unitDistance)) to UnitFormat.distanceLabel(unitDistance)
        ODOMETER -> oneDp(UnitFormat.distance(d.totalDistance, unitDistance)) to UnitFormat.distanceLabel(unitDistance)
        PITCH -> oneDp(d.pitchAngle) to "°"
        ROLL -> oneDp(d.rollAngle) to "°"
        G_FORCE -> twoDp(d.gForce) to "g"
    }

    companion object {
        fun byKey(k: String): StudioMetric = entries.firstOrNull { it.key == k } ?: SPEED
    }
}

private fun oneDp(v: Float): String {
    val r = (v * 10).roundToInt(); val neg = r < 0; val a = if (neg) -r else r
    return "${if (neg) "-" else ""}${a / 10}.${a % 10}"
}

private fun twoDp(v: Float): String {
    val r = (v * 100).roundToInt(); val neg = r < 0; val a = if (neg) -r else r
    return "${if (neg) "-" else ""}${a / 100}.${(a % 100).toString().padStart(2, '0')}"
}
