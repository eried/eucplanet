package com.eried.eucplanet.data

import com.eried.eucplanet.flic.LockdownGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalLockdownGateTest {

    @Test
    fun `only horn and light are allowed while armed`() {
        assertEquals(setOf("HORN", "LIGHT_TOGGLE"), LockdownGate.ALLOWED_ACTIONS)
        assertTrue(LockdownGate.isAllowed("HORN"))
        assertTrue(LockdownGate.isAllowed("LIGHT_TOGGLE"))
    }

    @Test
    fun `every legal mode action is blocked`() {
        assertFalse(LockdownGate.isAllowed("SAFETY_TOGGLE"))
        assertFalse(LockdownGate.isAllowed("SAFETY_ON"))
        assertFalse(LockdownGate.isAllowed("SAFETY_OFF"))
    }

    @Test
    fun `legal mode actions raise the unlock prompt, others are silent`() {
        assertTrue(LockdownGate.raisesUnlockPrompt("SAFETY_TOGGLE"))
        assertTrue(LockdownGate.raisesUnlockPrompt("SAFETY_ON"))
        assertTrue(LockdownGate.raisesUnlockPrompt("SAFETY_OFF"))
        assertFalse(LockdownGate.raisesUnlockPrompt("LOCK_TOGGLE"))
        assertFalse(LockdownGate.raisesUnlockPrompt("RECORD_TOGGLE"))
    }

    @Test
    fun `lock, record, voice and custom ble are blocked`() {
        assertFalse(LockdownGate.isAllowed("LOCK_TOGGLE"))
        assertFalse(LockdownGate.isAllowed("RECORD_TOGGLE"))
        assertFalse(LockdownGate.isAllowed("VOICE_ANNOUNCE"))
        assertFalse(LockdownGate.isAllowed("B:0000-1111"))
        assertFalse(LockdownGate.isAllowed("OPEN_SETTINGS"))
    }

    /**
     * The gate is an allowlist so a future ActionCatalog entry is blocked by
     * default instead of silently becoming a hole in the lock. This asserts the
     * property rather than a fixed list of today's keys.
     */
    @Test
    fun `an action nobody has heard of is blocked`() {
        assertFalse(LockdownGate.isAllowed("SOME_FUTURE_ACTION"))
        assertFalse(LockdownGate.isAllowed(""))
    }
}
