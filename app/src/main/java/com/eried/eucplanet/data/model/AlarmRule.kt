package com.eried.eucplanet.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.eried.eucplanet.R

@Entity(tableName = "alarm_rules")
data class AlarmRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,

    // Condition
    val metric: String = AlarmMetric.SPEED.name,
    val comparator: String = AlarmComparator.GREATER_EQUAL.name,
    val threshold: Float = 30f,

    // Beep action
    val beepEnabled: Boolean = false,
    val beepFrequency: Int = 1000,   // Hz: 400-3000
    val beepDurationMs: Int = 300,   // per beep
    val beepCount: Int = 1,          // 1=single, 2=double, 3=triple

    // Voice action
    val voiceEnabled: Boolean = false,
    val voiceText: String = "Warning! {metric} at {value}",

    // Vibrate action
    val vibrateEnabled: Boolean = false,
    val vibrateDurationMs: Int = 500,
    /**
     * Where the buzz fires when [vibrateEnabled] is true: "PHONE", "WATCH",
     * or "BOTH". Defaults to BOTH so a rider gets the haptic on whichever
     * device they're paying attention to, if no watch is paired, the WATCH
     * branch in [com.eried.eucplanet.wear.WatchVibrator] silently no-ops.
     * The storage key stays "BOTH" for backup/sync compatibility even though
     * the UI label is "All".
     */
    val vibrateTarget: String = "BOTH",

    // Timing
    val cooldownSeconds: Int = 5,
    val repeatWhileActive: Boolean = false
)

enum class AlarmMetric(
    val labelRes: Int,
    val unit: String,
    /** Name spoken by voice alarms, defaults to the on-screen label. */
    val voiceLabelRes: Int = labelRes
) {
    SPEED(R.string.alarm_metric_speed, "km/h"),
    BATTERY(R.string.alarm_metric_battery, "%"),
    TEMPERATURE(R.string.alarm_metric_temperature, "°C"),
    PWM(R.string.alarm_metric_pwm, "%", R.string.alarm_metric_pwm_voice),
    VOLTAGE(R.string.alarm_metric_voltage, "V"),
    CURRENT(R.string.alarm_metric_current, "A"),

    /**
     * Distance in metres to the closest tracked vehicle from the rear-view
     * radar. Pair with the LESS_THAN comparator: "trigger when the closest
     * car is closer than 40 m". Only fires when at least one threat is
     * present in the latest frame, an empty frame is treated as "no value"
     * rather than "0 metres" so a clear lane doesn't keep tripping the rule.
     */
    RADAR_DISTANCE(R.string.alarm_metric_radar_distance, "m"),

    /**
     * Approach speed in km/h of the fastest closing vehicle. Pair with
     * GREATER_EQUAL: "trigger when a car is closing at 60 km/h or more".
     */
    RADAR_APPROACH_SPEED(R.string.alarm_metric_radar_approach, "km/h")
}

enum class AlarmComparator(val labelRes: Int, val symbol: String) {
    GREATER_EQUAL(R.string.alarm_cmp_ge, "≥"),
    LESS_THAN(R.string.alarm_cmp_lt, "<");

    companion object {
        // Map legacy stored strings to the reduced set; safe fallback to GREATER_EQUAL.
        fun parse(raw: String?): AlarmComparator = when (raw) {
            "GREATER_EQUAL", "GREATER_THAN" -> GREATER_EQUAL
            "LESS_THAN", "LESS_EQUAL" -> LESS_THAN
            else -> GREATER_EQUAL
        }
    }
}
