package com.eried.eucplanet.service.hud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * From a real shop-floor capture: `10.240.` was saved as the manual HUD
 * address, and a beacon that arrived mid-cycle lost the race to it.
 */
class ManualHintTest {

    private val freshness = 10_000L

    @Test
    fun `the half-typed address from the capture is never dialled`() {
        assertEquals(
            ManualHintDecision.IGNORE_INCOMPLETE,
            ManualHint.decide("10.240.", beaconAgeMs = null, freshnessMs = freshness),
        )
    }

    @Test
    fun `a blank address just means auto-discovery`() {
        assertEquals(
            ManualHintDecision.IGNORE_INCOMPLETE,
            ManualHint.decide("", beaconAgeMs = null, freshnessMs = freshness),
        )
    }

    @Test
    fun `a live beacon beats what the rider typed`() {
        // The exact moment from the capture: the HUD announced itself 1.2s
        // before the manual hint was due, and the typed value was dialled anyway.
        assertEquals(
            ManualHintDecision.HOLD_FOR_BEACON,
            ManualHint.decide("10.223.143.99", beaconAgeMs = 1_200L, freshnessMs = freshness),
        )
    }

    @Test
    fun `a stale beacon does not hold the hint back`() {
        // The HUD was heard, then went quiet. The typed address is all we have.
        assertEquals(
            ManualHintDecision.USE,
            ManualHint.decide("10.223.143.99", beaconAgeMs = 30_000L, freshnessMs = freshness),
        )
    }

    @Test
    fun `with no beacon at all the typed address is dialled`() {
        assertEquals(
            ManualHintDecision.USE,
            ManualHint.decide("192.168.43.42", beaconAgeMs = null, freshnessMs = freshness),
        )
    }
}
