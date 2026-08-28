package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.ApplyWhenIds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate the headlight, the volume and the playback rate all pass through.
 * Every cell of the matrix, because getting one wrong means an automation
 * that silently never runs, or one that runs while the rider is standing
 * still with their phone in their hand.
 */
class ApplyWhenGateTest {

    private fun allows(mode: String, connected: Boolean, speed: Float) =
        ApplyWhenGate.allows(mode, connected, speed)

    @Test fun `never means never, whatever the wheel is doing`() {
        assertFalse(allows(ApplyWhenIds.NEVER, connected = false, speed = 0f))
        assertFalse(allows(ApplyWhenIds.NEVER, connected = true, speed = 0f))
        assertFalse(allows(ApplyWhenIds.NEVER, connected = true, speed = 30f))
    }

    @Test fun `connected ignores speed but needs the link`() {
        assertTrue(allows(ApplyWhenIds.CONNECTED, connected = true, speed = 0f))
        assertTrue(allows(ApplyWhenIds.CONNECTED, connected = true, speed = 25f))
        assertFalse(allows(ApplyWhenIds.CONNECTED, connected = false, speed = 25f))
    }

    @Test fun `riding needs both, and standing still is not riding`() {
        assertFalse("stopped", allows(ApplyWhenIds.RIDING, connected = true, speed = 0f))
        assertFalse("walking it", allows(ApplyWhenIds.RIDING, connected = true, speed = 2f))
        assertTrue("rolling", allows(ApplyWhenIds.RIDING, connected = true, speed = ApplyWhenGate.RIDING_KMH))
        assertTrue("riding", allows(ApplyWhenIds.RIDING, connected = true, speed = 24f))
    }

    @Test fun `a speed reading from a wheel that has gone away never counts`() {
        // The last reading survives a disconnect; riding must not fire on it.
        assertFalse(allows(ApplyWhenIds.RIDING, connected = false, speed = 30f))
    }

    @Test fun `an unknown value is treated as riding, not as always-on`() {
        // A settings file from a future version, or a typo in a restored
        // backup: the safe reading is the strictest condition, never "run
        // unconditionally".
        assertFalse(allows("SOMETHING_ELSE", connected = false, speed = 30f))
        assertFalse(allows("SOMETHING_ELSE", connected = true, speed = 0f))
        assertTrue(allows("SOMETHING_ELSE", connected = true, speed = 20f))
    }
}
