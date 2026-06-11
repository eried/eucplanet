package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.WheelData
import kotlinx.serialization.Serializable

/**
 * A customizable ride alarm — a faithful port of Android's `AlarmRule`. Each rule
 * is a condition (a [metric] compared against a [threshold]) plus the actions to
 * take when it trips (speak / vibrate) and timing ([cooldownSeconds] /
 * [repeatWhileActive]).
 *
 * NOTE: this lives in `com.eried.eucplanet.data` (not `data.model`) ON PURPOSE —
 * Android already has `com.eried.eucplanet.data.model.AlarmRule` as a Room @Entity,
 * and reusing that FQN here collides on the shared classpath (Room's generated DAO
 * then fails to compile). Same reason `UnitFormat` isn't named `Units`.
 *
 * Trimmed vs Android to what iOS supports: the **beep-tone synthesizer**, the
 * **radar** metrics, and the per-rule **watch vibrate target** are omitted —
 * vibrate fires the phone's haptic.
 */
@Serializable
data class AlarmRule(
    val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,

    // Condition
    val metric: String = AlarmMetric.SPEED.name,
    val comparator: String = AlarmComparator.GREATER_EQUAL.name,
    val threshold: Float = 30f,

    // Actions
    val voiceEnabled: Boolean = true,
    val voiceText: String = "Warning! {metric} at {value}",
    val vibrateEnabled: Boolean = true,

    // Timing
    val cooldownSeconds: Int = 5,
    val repeatWhileActive: Boolean = false,
)

/** Telemetry metrics a rule can watch. Mirrors Android's `AlarmMetric` minus radar. */
enum class AlarmMetric(val label: String, val unit: String, val range: ClosedFloatingPointRange<Float>) {
    SPEED("Speed", "km/h", 0f..120f),
    BATTERY("Battery", "%", 0f..100f),
    TEMPERATURE("Temperature", "°C", 0f..120f),
    PWM("PWM", "%", 0f..100f),
    VOLTAGE("Voltage", "V", 0f..150f),
    CURRENT("Current", "A", 0f..150f);

    /** Pull this metric's current value out of a telemetry frame. */
    fun valueOf(d: WheelData): Float = when (this) {
        SPEED -> d.speed
        BATTERY -> d.batteryPercent.toFloat()
        TEMPERATURE -> d.maxTemperature
        PWM -> d.pwm
        VOLTAGE -> d.voltage
        CURRENT -> d.current
    }

    companion object {
        fun parse(raw: String): AlarmMetric = entries.firstOrNull { it.name == raw } ?: SPEED
    }
}

/** ≥ or < — mirrors Android's reduced `AlarmComparator` set. */
enum class AlarmComparator(val symbol: String) {
    GREATER_EQUAL("≥"),
    LESS_THAN("<");

    fun test(value: Float, threshold: Float): Boolean = when (this) {
        GREATER_EQUAL -> value >= threshold
        LESS_THAN -> value < threshold
    }

    companion object {
        fun parse(raw: String): AlarmComparator = entries.firstOrNull { it.name == raw } ?: GREATER_EQUAL
    }
}

/** Starter rules — reproduce the app's previous fixed alarms (speed / temp / PWM). */
val defaultAlarmRules: List<AlarmRule> = listOf(
    AlarmRule(id = 1, name = "Overspeed", metric = "SPEED", threshold = 45f),
    AlarmRule(id = 2, name = "Overheat", metric = "TEMPERATURE", threshold = 65f),
    AlarmRule(id = 3, name = "High PWM", metric = "PWM", threshold = 80f),
)
