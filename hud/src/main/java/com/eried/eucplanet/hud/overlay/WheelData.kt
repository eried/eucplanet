package com.eried.eucplanet.hud.overlay

/**
 * HUD-side copy of [com.eried.eucplanet.data.model.WheelData]. Kept in
 * the same shape as the phone class so the ported StudioOverlayElements
 * code that takes a WheelData parameter compiles unchanged on the HUD.
 *
 * Populated by adapting [com.eried.eucplanet.hud.protocol.HudState] in
 * [HudStudioElementData.from] each frame.
 */

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
    /** Rider GPS latitude in degrees. 0 when there is no fix / not recorded. */
    val latitude: Double = 0.0,
    /** Rider GPS longitude in degrees. 0 when there is no fix / not recorded. */
    val longitude: Double = 0.0,
    /** Phone IMU acceleration magnitude in g, 0 for trips recorded before this. */
    val gForce: Float = 0f,
    /** Phone IMU lateral acceleration in g (+right). 0 for trips recorded before this. */
    val accelX: Float = 0f,
    /** Phone IMU forward acceleration in g (+forward). 0 for trips recorded before this. */
    val accelY: Float = 0f,
    val batteryPower: Int = 0,
    val motorPower: Int = 0,
    val dynamicSpeedLimit: Float = 0f,
    val dynamicCurrentLimit: Float = 0f,
    val lightOn: Boolean = false,
    val pcMode: Int = -1,  // 0=lock, 1=drive, 2=shutdown, 3=idle (-1=unknown/no telemetry yet)
    val timestamp: Long = System.currentTimeMillis()
)
