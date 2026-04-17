package com.eried.evendarkerbot.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.eried.evendarkerbot.R

@Entity(tableName = "alarm_rules")
data class AlarmRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,

    // Condition
    val metric: String = AlarmMetric.SPEED.name,
    val comparator: String = AlarmComparator.GREATER_THAN.name,
    val threshold: Float = 30f,

    // Beep action
    val beepEnabled: Boolean = true,
    val beepFrequency: Int = 1000,   // Hz: 400-3000
    val beepDurationMs: Int = 300,   // per beep
    val beepCount: Int = 1,          // 1=single, 2=double, 3=triple

    // Voice action
    val voiceEnabled: Boolean = false,
    val voiceText: String = "Warning! {metric} at {value}",

    // Vibrate action
    val vibrateEnabled: Boolean = false,
    val vibrateDurationMs: Int = 500,

    // Timing
    val cooldownSeconds: Int = 10,
    val repeatWhileActive: Boolean = false
)

enum class AlarmMetric(val labelRes: Int, val unit: String) {
    SPEED(R.string.alarm_metric_speed, "km/h"),
    BATTERY(R.string.alarm_metric_battery, "%"),
    TEMPERATURE(R.string.alarm_metric_temperature, "°C"),
    PWM(R.string.alarm_metric_pwm, "%"),
    VOLTAGE(R.string.alarm_metric_voltage, "V"),
    CURRENT(R.string.alarm_metric_current, "A")
}

enum class AlarmComparator(val labelRes: Int, val symbol: String) {
    GREATER_THAN(R.string.alarm_cmp_gt, ">"),
    LESS_THAN(R.string.alarm_cmp_lt, "<"),
    GREATER_EQUAL(R.string.alarm_cmp_ge, "≥"),
    LESS_EQUAL(R.string.alarm_cmp_le, "≤")
}
