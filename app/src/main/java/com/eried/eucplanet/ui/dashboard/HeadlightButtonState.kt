package com.eried.eucplanet.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.HeadlightReadback
import com.eried.eucplanet.data.model.WheelData
import kotlinx.coroutines.delay

internal data class HeadlightButtonState(val labelRes: Int, val active: Boolean)

internal fun headlightButtonState(
    data: WheelData, connected: Boolean, nowNanos: Long, maxAgeMs: Int,
): HeadlightButtonState {
    val readback = data.headlightReadback
        ?: return HeadlightButtonState(R.string.action_light, data.lightOn)
    val level = if (connected) readback.freshLevel(nowNanos, maxAgeMs) else null
    return HeadlightButtonState(
        when (level) {
            HeadlightReadback.Level.OFF -> R.string.headlight_state_off
            HeadlightReadback.Level.LOW -> R.string.headlight_state_low
            HeadlightReadback.Level.MEDIUM -> R.string.headlight_state_medium
            HeadlightReadback.Level.HIGH -> R.string.headlight_state_high
            null -> R.string.headlight_state_unknown
        },
        level != null && level != HeadlightReadback.Level.OFF,
    )
}

@Composable
internal fun rememberHeadlightButtonState(
    data: WheelData, connected: Boolean, maxAgeMs: Int,
): HeadlightButtonState {
    val readback = data.headlightReadback
    // Schedule expiry even if BLE goes silent. Unrelated pages retain the same readback.
    val now by produceState(System.nanoTime(), readback, connected, maxAgeMs) {
        value = System.nanoTime()
        if (connected && readback?.freshLevel(value, maxAgeMs) != null) {
            val remainingNanos = maxAgeMs.toLong() * 1_000_000L - (value - readback.receivedAtNanos)
            delay(remainingNanos / 1_000_000L + 1L)
            value = System.nanoTime()
        }
    }
    return headlightButtonState(data, connected, maxOf(now, System.nanoTime()), maxAgeMs)
}
