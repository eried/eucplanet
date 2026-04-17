package com.eried.eucplanet.data.model

data class WheelData(
    val speed: Float = 0f,
    val voltage: Float = 0f,
    val current: Float = 0f,
    val batteryPercent: Int = 0,
    val battery1Percent: Float = 0f,
    val battery2Percent: Float = 0f,
    val pwm: Float = 0f,
    val torque: Float = 0f,
    val temperatures: List<Float> = emptyList(),
    val maxTemperature: Float = 0f,
    val tripDistance: Float = 0f,        // km
    val totalDistance: Float = 0f,       // km
    val pitchAngle: Float = 0f,
    val rollAngle: Float = 0f,
    val batteryPower: Int = 0,
    val motorPower: Int = 0,
    val dynamicSpeedLimit: Float = 0f,
    val dynamicCurrentLimit: Float = 0f,
    val lightOn: Boolean = false,
    val pcMode: Int = -1,  // 0=lock, 1=drive, 2=shutdown, 3=idle (-1=unknown/no telemetry yet)
    val timestamp: Long = System.currentTimeMillis()
)
