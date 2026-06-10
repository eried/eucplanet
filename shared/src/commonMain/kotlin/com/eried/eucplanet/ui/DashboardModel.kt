package com.eried.eucplanet.ui

import com.eried.eucplanet.data.model.WheelData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Reactive telemetry source for the shared dashboard. For now it simulates a
 * live ride so the dashboard updates on both platforms before the real BLE
 * transport exists — the architecture (a [StateFlow] of [WheelData] that the
 * Compose UI collects) is exactly what the real `WheelRepository` will expose,
 * so swapping the source later is a one-line change.
 */
class DashboardModel(scope: CoroutineScope) {
    private val _data = MutableStateFlow(WheelData())
    val data: StateFlow<WheelData> = _data.asStateFlow()

    init {
        scope.launch {
            var t = 0.0
            while (true) {
                t += 0.12
                val temp = (41 + 3 * sin(t * 0.5)).toFloat()
                _data.value = WheelData(
                    speed = (30 + 12 * sin(t)).toFloat().coerceAtLeast(0f),
                    voltage = 94.1f + sin(t).toFloat(),
                    current = (8 + 5 * sin(t * 2)).toFloat(),
                    batteryPercent = 78 - (t.toInt() % 14),
                    pwm = (35 + 22 * sin(t)).toFloat().coerceIn(0f, 100f),
                    temperatures = listOf(temp),
                    maxTemperature = temp,
                    tripDistance = (t * 0.05).toFloat(),
                )
                delay(180)
            }
        }
    }
}
