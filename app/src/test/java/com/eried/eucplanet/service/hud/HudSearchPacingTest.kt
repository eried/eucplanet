package com.eried.eucplanet.service.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudSearchPacingTest {

    @Test
    fun `nobody waiting searches at the idle interval`() {
        assertEquals(120_000L, HudSearchPacing.waitMs(attending = false, sinceStartMs = 0L, sprintMs = 30_000L, idleMs = 120_000L))
    }

    @Test
    fun `a rider waiting keeps the sprint and then the slow tick`() {
        assertEquals(2_000L, HudSearchPacing.waitMs(attending = true, sinceStartMs = 10_000L, sprintMs = 30_000L, idleMs = 120_000L))
        assertEquals(5_000L, HudSearchPacing.waitMs(attending = true, sinceStartMs = 40_000L, sprintMs = 30_000L, idleMs = 120_000L))
    }

    @Test
    fun `the sweep runs first, then at most once per interval`() {
        assertTrue(HudSearchPacing.sweepDue(nowMs = 1_000L, lastSweepMs = 0L, intervalMs = 60_000L))
        assertFalse(HudSearchPacing.sweepDue(nowMs = 1_000_000L, lastSweepMs = 950_000L, intervalMs = 60_000L))
        assertTrue(HudSearchPacing.sweepDue(nowMs = 1_010_000L, lastSweepMs = 950_000L, intervalMs = 60_000L))
    }
}
