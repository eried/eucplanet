package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsPowerPolicyTest {

    private fun tier(
        recording: Boolean = false,
        navigating: Boolean = false,
        connected: Boolean = false,
        appVisible: Boolean = false,
        sharing: Boolean = false,
    ) = GpsPowerPolicy.tierFor(recording, navigating, connected, appVisible, sharing)

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
    fun sharing_background_disconnected_is_high() {
        // The defect this covers: a rider sharing their live location switched
        // to the browser to watch the group. The app went background with no
        // wheel connected, GPS went OFF, and their own dot froze for everyone
        // else while their phone still said Connected.
        assertEquals(GpsTier.HIGH, tier(sharing = true, appVisible = false, connected = false))
    }

    @Test
    fun sharing_visible_is_high() {
        // Sharing outranks the idle balanced tier: the group sees 1 Hz either way.
        assertEquals(GpsTier.HIGH, tier(sharing = true, appVisible = true, connected = false))
        assertEquals(GpsTier.HIGH, tier(sharing = true, appVisible = true, connected = true))
    }

    @Test
    fun not_sharing_is_unchanged() {
        // The new input only ever adds demand; every old answer stands with it off.
        assertEquals(GpsTier.OFF, tier(sharing = false, appVisible = false, connected = false))
        assertEquals(GpsTier.HIGH, tier(sharing = false, appVisible = false, connected = true))
        assertEquals(GpsTier.BALANCED, tier(sharing = false, appVisible = true, connected = false))
        assertEquals(GpsTier.HIGH, tier(sharing = false, recording = true, appVisible = false))
    }

    @Test
    fun screen_off_disconnected_is_off() {
        // Ultra battery saving: app not visible, no wheel, not recording/navigating
        // is the pure-idle state -> GPS fully off (was LOW keep-warm before).
        assertEquals(GpsTier.OFF, tier(appVisible = false, connected = false))
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
