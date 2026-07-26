package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsPowerPolicyTest {

    private fun tier(
        recording: Boolean = false,
        navigating: Boolean = false,
        connected: Boolean = false,
        appVisible: Boolean = false,
    ) = GpsPowerPolicy.tierFor(recording, navigating, connected, appVisible)

    @Test
    fun recording_always_high() {
        // Recording forces 1 Hz regardless of visibility / connection.
        assertEquals(GpsTier.HIGH, tier(recording = true, appVisible = false, connected = false))
        assertEquals(GpsTier.HIGH, tier(recording = true, appVisible = true, connected = true))
    }

    @Test
    fun navigating_always_high() {
        assertEquals(GpsTier.HIGH, tier(navigating = true, appVisible = false, connected = false))
    }

    @Test
    fun screen_off_disconnected_is_low_power() {
        // The battery-leak case: app not visible and no wheel -> cheapest keep-warm.
        assertEquals(GpsTier.LOW, tier(appVisible = false, connected = false))
    }

    @Test
    fun screen_off_connected_is_high() {
        // Riding with the phone pocketed: precise track even with the screen off.
        assertEquals(GpsTier.HIGH, tier(appVisible = false, connected = true))
    }

    @Test
    fun visible_idle_is_balanced() {
        // Looking at the app but not recording/navigating: balanced keeps a warm
        // fix without spinning the GPS chip, connected or not.
        assertEquals(GpsTier.BALANCED, tier(appVisible = true, connected = false))
        assertEquals(GpsTier.BALANCED, tier(appVisible = true, connected = true))
    }
}
