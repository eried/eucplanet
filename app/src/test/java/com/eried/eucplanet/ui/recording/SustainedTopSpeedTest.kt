package com.eried.eucplanet.ui.recording

import com.eried.eucplanet.util.Units
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the trip-summary formatting: the top speed must be a SUSTAINED value
 * (not a lone GPS/sensor spike), and the duration must read human-friendly.
 */
class SustainedTopSpeedTest {

    // --- sustained top speed (anti-spike) ---

    @Test fun `a lone one-sample spike is rejected`() {
        // Held 10, one 50 spike, back to 10. Over a 3-sample window every window
        // touching the spike also touches a 10, so the sustained top is 10.
        val speeds = listOf(10f, 10f, 10f, 50f, 10f, 10f, 10f)
        assertEquals(10f, sustainedTopSpeed(speeds, windowSamples = 3), 0.001f)
    }

    @Test fun `a genuinely held speed counts`() {
        // Three consecutive 30s fill a 3-sample window, so 30 is sustained.
        val speeds = listOf(10f, 10f, 30f, 30f, 30f, 10f)
        assertEquals(30f, sustainedTopSpeed(speeds, windowSamples = 3), 0.001f)
    }

    @Test fun `realistic ramp with a spike returns the held peak, not the spike`() {
        // Sustains 25 (three samples), then a lone 60 spike. Expect 25.
        val speeds = listOf(0f, 5f, 10f, 15f, 20f, 25f, 25f, 25f, 60f, 25f, 25f, 20f)
        assertEquals(25f, sustainedTopSpeed(speeds, windowSamples = 3), 0.001f)
    }

    @Test fun `window of one is the plain peak, empty is zero`() {
        assertEquals(60f, sustainedTopSpeed(listOf(10f, 60f, 10f), windowSamples = 1), 0.001f)
        assertEquals(0f, sustainedTopSpeed(emptyList(), windowSamples = 5), 0.001f)
    }

    @Test fun `window larger than the trip falls back without crashing`() {
        // 2 samples, window 10: coerced to the series length; min of both = 10.
        assertEquals(10f, sustainedTopSpeed(listOf(10f, 40f), windowSamples = 10), 0.001f)
    }

    // --- human-readable duration ---

    @Test fun `duration formats by magnitude`() {
        assertEquals("45s", Units.humanDuration(45))
        assertEquals("1m 30s", Units.humanDuration(90))
        assertEquals("23m 45s", Units.humanDuration(23 * 60 + 45))
        assertEquals("1h 00m", Units.humanDuration(3600))
        assertEquals("1h 23m", Units.humanDuration(1L * 3600 + 23 * 60 + 45))
        assertEquals("2h 05m", Units.humanDuration(2L * 3600 + 5 * 60))
        assertEquals("0s", Units.humanDuration(0))
    }
}
