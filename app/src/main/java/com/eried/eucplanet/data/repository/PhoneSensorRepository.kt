package com.eried.eucplanet.data.repository

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
) {
    /** Overall acceleration magnitude in g (gravity already removed). */
    val magnitude: Float
        get() = kotlin.math.sqrt(xG * xG + yG * yG + zG * zG)
}

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

    // Reference count of active consumers (a connected wheel, the data-sources
    // sheet, the Overlay Studio). The listener is registered while it is > 0.
    private var refCount = 0
    private var registered = false
    // Periodic re-register heartbeat — Android can quietly stop delivering
    // events to a non-wake-up sensor while the AP sleeps (screen off mid-ride);
    // re-registering every 15 s self-heals it as long as the AP has been woken
    // at least once in the meantime (BLE polling during a trip wakes it).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeat: Job? = null

    // Delivery instrumentation — counts events between heartbeat ticks so we can
    // see in logcat (tag PhoneIMU) whether the sensor stream is actually flowing.
    // Zero events between two ticks means Android has stopped delivering — that's
    // the dropout we are hunting.
    @Volatile private var eventsSinceLastTick = 0L
    @Volatile private var lastEventElapsedMs = 0L
    @Volatile private var lastRegisterElapsedMs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
            eventsSinceLastTick++
            lastEventElapsedMs = SystemClock.elapsedRealtime()
            // m/s² → g (9.80665). x/y/z follow the SensorManager axis convention:
            // x = device right, y = device up, z = out of screen toward user.
            _imu.value = PhoneImuSample(
                xG = event.values[0] / 9.80665f,
                yG = event.values[1] / 9.80665f,
                zG = event.values[2] / 9.80665f
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.i(TAG, "accuracyChanged accuracy=$accuracy")
        }
    }

    private fun register() {
        if (registered) return
        val sm = sensorManager ?: return
        val s = linearAccel ?: return
        // SENSOR_DELAY_GAME (~20 ms) gives a smooth trail in the crosshair
        // without pegging the CPU. SENSOR_DELAY_UI (~60 ms) felt choppy on
        // a Pixel 6 during a tilt test.
        val ok = sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME)
        registered = ok
        lastRegisterElapsedMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "register ok=$ok sensor=${s.name} wakeUp=${s.isWakeUpSensor} reportingMode=${s.reportingMode}")
    }

    private fun unregister() {
        if (!registered) return
        sensorManager?.unregisterListener(listener)
        registered = false
        Log.i(TAG, "unregister")
    }

    /**
     * Marks one consumer as needing g-force. Reference-counted: the sensor
     * stays registered while at least one consumer is active, so a connected
     * wheel disconnecting never starves the Studio (or the data-sources sheet,
     * or vice versa). Every [start] must be balanced by exactly one [stop].
     */
    @Synchronized
    fun start() {
        refCount++
        Log.i(TAG, "start refCount=$refCount")
        if (refCount == 1) {
            register()
            heartbeat = scope.launch {
                while (isActive) {
                    delay(15_000L)
                    val count = eventsSinceLastTick
                    eventsSinceLastTick = 0
                    val now = SystemClock.elapsedRealtime()
                    val sinceLastEvent = if (lastEventElapsedMs == 0L) -1L else (now - lastEventElapsedMs)
                    val sinceRegister = if (lastRegisterElapsedMs == 0L) -1L else (now - lastRegisterElapsedMs)
                    Log.i(
                        TAG,
                        "tick events/15s=$count sinceLastEventMs=$sinceLastEvent registered=$registered sinceRegisterMs=$sinceRegister refCount=$refCount"
                    )
                    // If we got zero events but we are supposed to be registered,
                    // try to self-heal by re-registering. This is the actual fix;
                    // the log line above tells us how often we hit it.
                    if (count == 0L && registered) {
                        Log.w(TAG, "no events in 15s while registered — forcing refresh")
                        refresh()
                    }
                }
            }
        }
    }

    /** Releases one consumer; unregisters once the last one is gone. The last
     *  sample stays in [latest] so the UI can still show it after dismissing. */
    @Synchronized
    fun stop() {
        if (refCount <= 0) return
        refCount--
        Log.i(TAG, "stop refCount=$refCount")
        if (refCount == 0) {
            heartbeat?.cancel()
            heartbeat = null
            unregister()
        }
    }

    /**
     * Re-registers the listener. Android can stop delivering sensor events to
     * a backgrounded app and not resume them for the existing listener; call
     * this on a foreground return to re-arm it.
     */
    @Synchronized
    fun refresh() {
        if (refCount <= 0) return
        Log.i(TAG, "refresh refCount=$refCount registered=$registered")
        unregister()
        register()
    }

    companion object {
        private const val TAG = "PhoneIMU"
    }
}
