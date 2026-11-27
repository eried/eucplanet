package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedDetectorTest {

    private val still = 120_000L

    @Test
    fun `a wheel is parked once it has stood still for the delay, not before`() {
        val d = ParkedDetector()
        assertFalse(d.update(0L, moving = false, charging = false, stillMs = still))
        assertFalse(d.update(119_000L, moving = false, charging = false, stillMs = still))
        assertTrue(d.update(120_000L, moving = false, charging = false, stillMs = still))
    }

    @Test
    fun `the first frame that moves ends parked, and the delay starts over`() {
        val d = ParkedDetector()
        d.update(0L, moving = false, charging = false, stillMs = still)
        assertTrue(d.update(200_000L, moving = false, charging = false, stillMs = still))
        assertFalse(d.update(200_100L, moving = true, charging = false, stillMs = still))
        assertFalse(d.update(200_200L, moving = false, charging = false, stillMs = still))
        assertFalse(d.update(300_000L, moving = false, charging = false, stillMs = still))
        assertTrue(d.update(320_200L, moving = false, charging = false, stillMs = still))
    }

    @Test
    fun `a charging wheel is parked at once`() {
        val d = ParkedDetector()
        assertTrue(d.update(0L, moving = false, charging = true, stillMs = still))
    }

    @Test
    fun `taking the charger off ends parked even after hours on it`() {
        val d = ParkedDetector()
        d.update(0L, moving = false, charging = true, stillMs = still)
        assertTrue(d.update(3 * 3_600_000L, moving = false, charging = true, stillMs = still))
        // Unplugged: the rider is about to leave, so the hours do not count.
        val t = 3 * 3_600_000L + 500L
        assertFalse(d.update(t, moving = false, charging = false, stillMs = still))
        assertFalse(d.update(t + 60_000L, moving = false, charging = false, stillMs = still))
        // Nobody rode it after all.
        assertTrue(d.update(t + 120_000L, moving = false, charging = false, stillMs = still))
    }

    @Test
    fun `a wheel rolled while it charges is not parked`() {
        val d = ParkedDetector()
        assertFalse(d.update(0L, moving = true, charging = true, stillMs = still))
    }

    @Test
    fun `zero switches it off`() {
        val d = ParkedDetector()
        assertFalse(d.update(0L, moving = false, charging = true, stillMs = 0L))
        assertFalse(d.update(999_999L, moving = false, charging = false, stillMs = 0L))
    }

    @Test
    fun `a new connection starts from nothing`() {
        val d = ParkedDetector()
        d.update(0L, moving = false, charging = false, stillMs = still)
        assertTrue(d.update(500_000L, moving = false, charging = false, stillMs = still))
        d.reset()
        assertFalse(d.update(500_100L, moving = false, charging = false, stillMs = still))
    }

    @Test
    fun `a clock that steps back does not read as a long stand`() {
        val d = ParkedDetector()
        d.update(500_000L, moving = false, charging = false, stillMs = still)
        assertFalse(d.update(100L, moving = false, charging = false, stillMs = still))
        assertFalse(d.update(60_000L, moving = false, charging = false, stillMs = still))
        assertTrue(d.update(120_100L, moving = false, charging = false, stillMs = still))
    }

    @Test
    fun `the policy slows GPS only for a hidden, connected, parked wheel`() {
        fun tier(appVisible: Boolean = false, connected: Boolean = true, recording: Boolean = false, parked: Boolean = true) =
            GpsPowerPolicy.tierFor(
                recording = recording, navigating = false, connected = connected,
                appVisible = appVisible, parked = parked,
            )
        assertEquals(GpsTier.PARKED, tier())
        assertEquals(GpsTier.HIGH, tier(parked = false))
        // Recording wins: a parked wheel in a running recording keeps 1 Hz.
        assertEquals(GpsTier.HIGH, tier(recording = true))
        // No wheel: parked means nothing, the idle rule decides.
        assertEquals(GpsTier.OFF, tier(connected = false))
        // On screen the app already asks for the balanced stream.
        assertEquals(GpsTier.BALANCED, tier(appVisible = true))
    }
}
