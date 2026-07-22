package com.eried.eucplanet.service.hud

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in the subnet-probe first-hit behaviour: the scan returns the moment a
 * host answers and cancels the rest, instead of waiting for all ~500 connects
 * (the old awaitAll cost ~1-2 s after the HUD had already answered).
 */
class HudSubnetProbeTest {

    @Test fun `returns the first host that answers`() = runBlocking {
        val hit = HudSubnetProbe.raceToFirstHit((1..9).map { it.toString() }, 4) { it == "3" }
        assertEquals("3", hit)
    }

    @Test fun `returns null when no host answers`() = runBlocking {
        val hit = HudSubnetProbe.raceToFirstHit(listOf("a", "b", "c"), 4) { false }
        assertNull(hit)
    }

    @Test fun `cancels the slow non-matching probes once a match is found`() = runBlocking {
        var slowFinished = false
        val candidates = listOf("fast") + (1..50).map { "slow$it" }
        val hit = HudSubnetProbe.raceToFirstHit(candidates, 8) { ip ->
            if (ip == "fast") true
            else { delay(500); slowFinished = true; false }
        }
        assertEquals("fast", hit)
        // If the winner didn't cancel the rest, a slow probe would have finished.
        assertFalse("slow probes must be cancelled, not awaited", slowFinished)
    }
}
