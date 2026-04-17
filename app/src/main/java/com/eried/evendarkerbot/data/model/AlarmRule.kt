package com.eried.evendarkerbot.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

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

enum class AlarmMetric(val label: String, val unit: String) {
    SPEED("Speed", "km/h"),
    BATTERY("Battery", "%"),
    TEMPERATURE("Temperature", "°C"),
    PWM("Load (PWM)", "%"),
    VOLTAGE("Voltage", "V"),
    CURRENT("Current", "A")
}

enum class AlarmComparator(val label: String, val symbol: String) {
    GREATER_THAN("Greater than", ">"),
    LESS_THAN("Less than", "<"),
    GREATER_EQUAL("Greater or equal", "≥"),
    LESS_EQUAL("Less or equal", "≤")
}
