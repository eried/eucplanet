package com.eried.eucplanet.data.store

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.sync.SyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NewDefaultsTest {

    private fun withCheck(min: Int) =
        AppSettings().let { it.copy(advanced = it.advanced.copy(pendingUploadIntervalMin = min)) }

    @Test
    fun `a new install checks for stranded uploads every six hours`() {
        assertEquals(360, AppSettings().pendingUploadIntervalMin)
    }

    @Test
    fun `an install still on the old quarter hour is moved to six hours, once`() {
        val moved = NewDefaults.upgrade(withCheck(15), fromRev = 0)
        assertEquals(360, moved.pendingUploadIntervalMin)
        // Already taken: a rider who set 15 again afterwards keeps it.
        val kept = withCheck(15)
        assertSame(kept, NewDefaults.upgrade(kept, fromRev = NewDefaults.REV))
    }

    @Test
    fun `a value the rider chose is left alone`() {
        for (chosen in listOf(30, 60, 120, 360)) {
            assertEquals(chosen, NewDefaults.upgrade(withCheck(chosen), fromRev = 0).pendingUploadIntervalMin)
        }
    }

    @Test
    fun `nothing else in the settings is touched`() {
        val before = withCheck(15).copy(tiltbackSpeedKmh = 42f)
        val after = NewDefaults.upgrade(before, fromRev = 0)
        assertEquals(before.copy(advanced = before.advanced.copy(pendingUploadIntervalMin = 360)), after)
    }

    @Test
    fun `retries keep their first hour and a half and slow down after it`() {
        // As before: immediate, then doubling from 15 s to the 5 minute ceiling.
        assertEquals(0L, SyncManager.delayForAttempt(0))
        assertEquals(15L, SyncManager.delayForAttempt(1))
        assertEquals(240L, SyncManager.delayForAttempt(5))
        assertEquals(300L, SyncManager.delayForAttempt(6))
        assertEquals(300L, SyncManager.delayForAttempt(SyncManager.WATCHDOG_ATTEMPT))
        assertEquals(300L, SyncManager.delayForAttempt(24))
        // A long outage: twice an hour.
        assertEquals(1800L, SyncManager.delayForAttempt(25))
        assertEquals(1800L, SyncManager.delayForAttempt(500))
        // All the 5 minute tries together are about an hour and a half.
        val before = (1..24).sumOf { SyncManager.delayForAttempt(it) }
        assertTrue("$before s", before in 5_400L..6_600L)
    }
}
