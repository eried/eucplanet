package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import kotlin.math.roundToInt

enum class AlarmKind { SPEED, TEMPERATURE, CURRENT, PWM }

/** A currently-tripped ride alarm. */
data class RideAlarm(val kind: AlarmKind, val label: String)

/**
 * Pure alarm evaluation: which thresholds in [s] the current telemetry [d]
 * exceeds. The dashboard surfaces these as a banner; the (platform) actuators —
 * sound, haptics, TTS — hang off the same result once those actuals land.
 */
fun activeAlarms(d: WheelData, s: AppSettings): List<RideAlarm> {
    val out = ArrayList<RideAlarm>(4)
    if (s.speedAlarmEnabled && d.speed >= s.speedAlarmKmh) {
        out += RideAlarm(AlarmKind.SPEED, "Overspeed ${d.speed.roundToInt()} km/h")
    }
    if (s.tempAlarmEnabled && d.maxTemperature >= s.tempAlarmC) {
        out += RideAlarm(AlarmKind.TEMPERATURE, "Temp ${d.maxTemperature.roundToInt()}°C")
    }
    if (s.currentAlarmEnabled && d.current >= s.currentAlarmA) {
        out += RideAlarm(AlarmKind.CURRENT, "Current ${d.current.roundToInt()} A")
    }
    if (s.pwmAlarmEnabled && d.pwm >= s.pwmAlarmPct) {
        out += RideAlarm(AlarmKind.PWM, "PWM ${d.pwm.roundToInt()}%")
    }
    return out
}
