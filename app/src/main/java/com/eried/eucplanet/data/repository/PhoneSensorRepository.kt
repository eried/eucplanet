package com.eried.eucplanet.data.repository

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One sample from the phone's IMU. Linear acceleration is gravity-subtracted
 * via Android's TYPE_LINEAR_ACCELERATION sensor, then converted from m/s² to
 * g so the values line up with what RaceBox reports.
 *
 *  x = lateral  (right positive)
 *  y = vertical (up positive — usually ~0 in linear-accel mode)
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
)

/**
 * Singleton listener over [SensorManager] that re-emits linear acceleration as
 * a [PhoneImuSample] StateFlow. Registers lazily on first observer (start) and
 * unregisters when no longer needed (stop). The repo retains the last sample
 * so consumers can read a snapshot via [latest].
 */
@Singleton
class PhoneSensorRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val linearAccel: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val _imu = MutableStateFlow<PhoneImuSample?>(null)
    val imu: StateFlow<PhoneImuSample?> = _imu.asStateFlow()

    val latest: PhoneImuSample? get() = _imu.value
    val isAvailable: Boolean get() = linearAccel != null

    private var listening = false
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
            // m/s² → g (9.80665). x/y/z follow the SensorManager axis convention:
            // x = device right, y = device up, z = out of screen toward user.
            _imu.value = PhoneImuSample(
                xG = event.values[0] / 9.80665f,
                yG = event.values[1] / 9.80665f,
                zG = event.values[2] / 9.80665f
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* unused */ }
    }

    /** Idempotent — second call while listening is a no-op. Safe to invoke
     *  every time the dialog opens. */
    fun start() {
        if (listening) return
        val sm = sensorManager ?: return
        val s = linearAccel ?: return
        // SENSOR_DELAY_GAME (~20 ms) gives a smooth trail in the crosshair
        // without pegging the CPU. SENSOR_DELAY_UI (~60 ms) felt choppy on
        // a Pixel 6 during a tilt test.
        sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME)
        listening = true
    }

    /** Stop callback registration. Sample stays in [latest] until the next
     *  start, so the UI can still show the last reading after dismissing. */
    fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(listener)
        listening = false
    }
}
