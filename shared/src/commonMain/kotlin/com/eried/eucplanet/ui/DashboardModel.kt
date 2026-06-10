package com.eried.eucplanet.ui

import com.eried.eucplanet.data.model.WheelData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Reactive telemetry source for the shared dashboard. For now it simulates a
 * live ride so the dashboard updates on both platforms before the real BLE
 * transport exists — the architecture (a [StateFlow] of [WheelData] that the
 * Compose UI collects) is exactly what the real `WheelSession` exposes, so
 * swapping the source later is a one-line change. Call [stop] when the demo
 * session ends so the loop doesn't leak into the parent scope.
 */
class DashboardModel(scope: CoroutineScope) {
    private val _data = MutableStateFlow(WheelData())
    val data: StateFlow<WheelData> = _data.asStateFlow()

    private val job = scope.launch {
        var t = 0.0
        while (isActive) {
            t += 0.12
            val temp = (42 + 5 * sin(t * 0.5)).toFloat() // ~37-47 °C, below alarm
            // A relaxed demo cruise that stays in normal ranges, so it doesn't
            // constantly trip the alarms (those fire on real over-threshold data).
            _data.value = WheelData(
                speed = (24 + 12 * sin(t)).toFloat().coerceAtLeast(0f),       // ~12-36 km/h
                voltage = 94.1f + 1.2f * sin(t).toFloat(),
                current = (10 + 6 * sin(t * 2)).toFloat().coerceAtLeast(0f),  // ~4-16 A
                batteryPercent = 78 - (t.toInt() % 16),
                pwm = (38 + 16 * sin(t + 0.4)).toFloat().coerceIn(0f, 100f),  // ~22-54 %, below warn
                temperatures = listOf(temp),
                maxTemperature = temp,
                tripDistance = (t * 0.05).toFloat(),
                totalDistance = 1240f + (t * 0.05).toFloat(),
            )
            delay(180)
        }
    }

    /** Stop the simulation loop so the coroutine doesn't leak into the parent scope. */
    fun stop() {
        job.cancel()
    }
}
