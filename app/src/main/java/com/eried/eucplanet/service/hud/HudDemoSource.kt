package com.eried.eucplanet.service.hud

import android.util.Log
import com.eried.eucplanet.hud.protocol.HudState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug-only fake-telemetry source for the HUD companion.
 *
 * Activated by setting `debug.eucplanet.demo=true` before the WheelService
 * starts. Replaces the HudServer snapshot's wheel/nav data with a scripted
 * riding session: speed sweeps 0 → 40 mph and back, battery drips down,
 * PWM oscillates, temperature creeps up, GPS slowly orbits the phone's
 * starting position. Every screen has something visibly moving.
 *
 * This is NOT a general-purpose mock — it never writes to the real
 * repositories, and the rider's actual wheel (if connected) is ignored
 * while demo mode is on. Strictly for emulator development.
 */
class HudDemoSource {

    companion object {
        private const val TAG = "HudDemoSource"
        // Loop period (s). One full speed sweep + nav cycle. Shorter than
        // a real ride so a tester sees the full range (warm-up → cruise →
        // PWM-orange → PWM-red → coast → stop) inside a minute.
        private const val PERIOD_S: Double = 24.0
        // Origin: anywhere; HudServer.snapshot will replace lat/lng with the
        // real phone GPS plus an orbit offset, so the map pans visibly.
        private const val ORBIT_RADIUS_DEG: Double = 0.001
    }

    @Volatile var active: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Most recent synthetic frame. HudServer.snapshot reads this when active. */
    @Volatile var frame: Frame = Frame()
        private set

    fun start() {
        if (job != null) return
        active = true
        Log.i(TAG, "Demo telemetry source started")
        job = scope.launch {
            val startMs = System.currentTimeMillis()
            while (true) {
                val tSec = (System.currentTimeMillis() - startMs) / 1000.0
                frame = compute(tSec)
                delay(100L)
            }
        }
    }

    fun stop() {
        active = false
        job?.cancel(); job = null
    }

    private fun compute(tSec: Double): Frame {
        val phase = (tSec % PERIOD_S) / PERIOD_S          // 0..1
        val twoPi = 2.0 * PI

        // Speed: bell-shaped sweep through ~46 mph (75 km/h), zero at the
        // boundary. Peak chosen so PWM blows past the default red threshold
        // (85%) for ~3 seconds in the middle of the cycle, exercising the
        // gauge band colour-flip code without staying pinned there.
        val speedKmh = (75.0 * sin(phase * PI)).coerceAtLeast(0.0).toFloat()

        // Battery slow drain: 100% at t=0, -1% per minute. Loops via mod to
        // keep numbers in 20..100 range so it's always plausible.
        val battery = (100.0 - (tSec / 60.0) % 80.0).toInt()

        // Voltage proxies battery with a small load sag during accel.
        val voltage = (75.0 + battery * 0.25 - speedKmh * 0.05).toFloat()

        // PWM tracks speed roughly: idle 15-20%, peaks ~75% at top speed.
        val pwm = (15.0 + speedKmh * 1.0).coerceIn(0.0, 95.0).toFloat()

        // Current peaks during acceleration: derivative of speedKmh.
        val accel = (32.0 * PI / PERIOD_S * cos(phase * PI)).toFloat()
        val current = (accel * 1.5f).coerceIn(-20f, 30f)

        // Temperature creeps up while riding, drifts down when idle.
        val tempC = (25.0 + speedKmh * 0.4).coerceAtMost(65.0).toFloat()

        // Trip integrates speed in km. Reset every PERIOD_S so it stays small.
        val tripKm = (speedKmh / 3600f) * (tSec % PERIOD_S).toFloat()

        // GPS orbit so the map pans visibly. dLat / dLng are tiny but enough
        // to see at zoom 15.
        val angle = phase * twoPi
        val dLat = ORBIT_RADIUS_DEG * cos(angle)
        val dLng = ORBIT_RADIUS_DEG * sin(angle)

        // Navigation cycle: arrow rotates clockwise once per period; primary/
        // distance change as we approach a synthetic waypoint.
        val turnAngle = (phase * 360.0 - 180.0).toFloat()
        val distM = (250.0 * (1.0 - phase)).toInt()
        val navPrimary = when {
            phase < 0.20 -> "Continue straight"
            phase < 0.55 -> "Turn left onto Storgata"
            phase < 0.85 -> "Bear right onto Bridge Rd"
            else -> "Arriving at destination"
        }
        val arrived = phase > 0.97

        return Frame(
            speedKmh = speedKmh,
            batteryPct = battery,
            voltage = voltage,
            current = current,
            pwm = pwm,
            tempC = tempC,
            tripKm = tripKm,
            dLat = dLat, dLng = dLng,
            navActive = true,
            navAngleDeg = turnAngle,
            navPrimary = navPrimary,
            navDistance = if (arrived) "" else "${distM} m",
            navArrived = arrived
        )
    }

    data class Frame(
        val speedKmh: Float = 0f,
        val batteryPct: Int = 100,
        val voltage: Float = 84f,
        val current: Float = 0f,
        val pwm: Float = 0f,
        val tempC: Float = 25f,
        val tripKm: Float = 0f,
        val dLat: Double = 0.0,
        val dLng: Double = 0.0,
        val navActive: Boolean = false,
        val navAngleDeg: Float = 0f,
        val navPrimary: String = "",
        val navDistance: String = "",
        val navArrived: Boolean = false
    )
}
