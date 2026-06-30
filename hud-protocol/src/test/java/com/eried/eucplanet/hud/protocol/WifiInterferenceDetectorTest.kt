package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone-side "is my own Wi-Fi interrupting the HUD?" detector.
 *
 * Background: on a single-radio phone, every time the home-Wi-Fi STA changes
 * state while the hotspot is up, the shared radio re-tunes and the live HUD
 * link drops. The HUD can't see this (it only knows the hotspot), so the PHONE
 * has to detect it and tell the HUD. The fingerprint is a HUD link drop that
 * lands within a few seconds of one of the phone's OWN Wi-Fi transitions; a
 * drop with no nearby Wi-Fi event (rode out of hotspot range, HUD glitch) is
 * NOT this. We require it twice before speaking up, and auto-clear once stable.
 *
 * Pure (caller passes the clock) so it is unit-testable.
 */
class WifiInterferenceDetectorTest {

    private fun det() = WifiInterferenceDetector(
        correlationWindowMs = 20_000L,
        minCorrelatedDrops = 2,
        rollingWindowMs = 300_000L,
        clearAfterStableMs = 60_000L,
    )

    @Test fun single_correlated_drop_does_not_trigger() {
        val d = det()
        d.onStaTransition(1_000L)
        assertTrue("drop 4s after STA event is correlated", d.onHudDrop(5_000L))
        assertFalse("one correlated drop must not raise the advisory", d.advisoryActive)
    }

    @Test fun two_correlated_drops_raise_the_advisory() {
        val d = det()
        d.onStaTransition(1_000L); d.onHudDrop(5_000L)
        d.onStaTransition(30_000L); d.onHudDrop(40_000L)
        assertTrue(d.advisoryActive)
    }

    @Test fun drop_without_a_recent_sta_transition_is_not_correlated() {
        val d = det()
        d.onStaTransition(1_000L)
        assertFalse("drop 25s later is outside the 20s window", d.onHudDrop(26_000L))
        assertFalse(d.onHudDrop(60_000L))
        assertFalse(d.advisoryActive)
    }

    @Test fun advisory_clears_after_a_stable_period() {
        val d = det()
        d.onStaTransition(1_000L); d.onHudDrop(5_000L)
        d.onStaTransition(10_000L); d.onHudDrop(15_000L)
        assertTrue(d.advisoryActive)
        d.onStableTick(15_000L + 60_000L + 1L)
        assertFalse("stable for longer than clearAfterStableMs clears it", d.advisoryActive)
    }

    @Test fun advisory_persists_while_drops_keep_coming() {
        val d = det()
        d.onStaTransition(1_000L); d.onHudDrop(5_000L)
        d.onStaTransition(10_000L); d.onHudDrop(15_000L)
        assertTrue(d.advisoryActive)
        d.onStableTick(20_000L) // only 5s since last drop -> not yet stable
        assertTrue(d.advisoryActive)
    }
}
