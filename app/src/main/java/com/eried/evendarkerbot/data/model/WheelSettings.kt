package com.eried.evendarkerbot.data.model

data class WheelSettings(
    val maxSpeedKmh: Float = 50f,
    val alarmSpeedKmh: Float = 40f,
    val pedalAdjustment: Float = 0f,
    val offroadMode: Boolean = false,
    val fancierMode: Boolean = false,
    val comfortSensitivity: Int = 0,
    val classicSensitivity: Int = 0,
    val standbyDelayMinutes: Int = 5,
    val mute: Boolean = false,
    val drl: Boolean = false,
    val transportMode: Boolean = false,
    val lockState: Int = 0  // 0=unlocked, non-zero=locked (from settings byte 21, bits 2-3)
)
