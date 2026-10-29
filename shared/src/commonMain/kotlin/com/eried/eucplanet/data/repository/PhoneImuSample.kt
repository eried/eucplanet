package com.eried.eucplanet.data.repository

import com.eried.eucplanet.platform.System

/**
 * One sample from the phone's IMU. Linear acceleration is gravity-subtracted
 * via Android's TYPE_LINEAR_ACCELERATION sensor, then converted from m/s² to
 * g so the values line up with what RaceBox reports.
 *
 *  x = lateral  (right positive)
 *  y = vertical (up positive, usually ~0 in linear-accel mode)
 *  z = forward  (forward positive when the phone is screen-up on the wheel)
 *
 * On a phone held by the rider in a pocket the axes don't perfectly line up
 * with the wheel's motion, so the values are useful as a relative reading
 * (spikes, magnitudes) but shouldn't be treated as absolute lateral / longitudinal
 * G the way a RaceBox mounted to the stem would.
 */
data class PhoneImuSample(
    val xG: Float,
    val yG: Float,
    val zG: Float,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Overall acceleration magnitude in g (gravity already removed). */
    val magnitude: Float
        get() = kotlin.math.sqrt(xG * xG + yG * yG + zG * zG)
}
