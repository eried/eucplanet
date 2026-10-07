package com.eried.eucplanet.ui.dashboard

import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.HeadlightReadback
import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.*
import org.junit.Test

class HeadlightButtonStateTest {
    private val received = 10_000_000_000L
    private val maxAgeMs = 8000

    @Test fun `measured levels control the label and highlight independently of command intent`() {
        val labels = listOf(R.string.headlight_state_off, R.string.headlight_state_low,
            R.string.headlight_state_medium, R.string.headlight_state_high)
        HeadlightReadback.Level.entries.forEachIndexed { index, level ->
            for (on in listOf(false, true)) {
                val data = WheelData(lightOn = on, headlightReadback = HeadlightReadback(level, received))
                assertEquals(HeadlightButtonState(labels[index], level != HeadlightReadback.Level.OFF),
                    headlightButtonState(data, true, received, maxAgeMs))
            }
        }
    }

    @Test fun `unknown disconnected old and future measurements display unknown`() {
        val data = WheelData(lightOn = true,
            headlightReadback = HeadlightReadback(HeadlightReadback.Level.HIGH, received))
        val unknown = HeadlightButtonState(R.string.headlight_state_unknown, false)
        assertEquals(unknown, headlightButtonState(data, false, received, maxAgeMs))
        assertEquals(unknown, headlightButtonState(data, true, received + 8_000_000_001L, maxAgeMs))
        assertEquals(unknown, headlightButtonState(data, true, received - 1, maxAgeMs))
        assertEquals(unknown, headlightButtonState(data.copy(headlightReadback = HeadlightReadback()),
            true, received, maxAgeMs))
        assertTrue(headlightButtonState(data, true, received + 8_000_000_000L, maxAgeMs).active)
    }

    @Test fun `freshness follows the configured timeout without changing the sample`() {
        val data = WheelData(headlightReadback = HeadlightReadback(HeadlightReadback.Level.LOW, received))
        assertFalse(headlightButtonState(data, true, received + 2_000_000_000L, 1000).active)
        assertTrue(headlightButtonState(data, true, received + 2_000_000_000L, 3000).active)
    }

    @Test fun `models without level readback retain the Light label and on off highlight`() {
        for (on in listOf(false, true)) {
            for (connected in listOf(false, true)) {
                assertEquals(HeadlightButtonState(R.string.action_light, on),
                    headlightButtonState(WheelData(lightOn = on), connected, received, maxAgeMs))
            }
        }
    }
}
