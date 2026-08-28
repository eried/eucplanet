package com.eried.eucplanet.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walking-pace cut-off. Both edges are sticky on purpose: a headlight
 * that blinks at every slow corner is worse than one that stays on.
 */
class HeadlightSlowPolicyTest {

    private val p = HeadlightSlowPolicy
    private val threshold = 4f

    /** Clock base: 0L is this policy family's "not started yet" sentinel
     *  (same convention as MediaControlPolicy), so a test clock starting at
     *  zero would be indistinguishable from one that never started. */
    private val t0 = 100_000L

    private fun run(vararg samples: Pair<Float, Long>): HeadlightSlowPolicy.State {
        var st = HeadlightSlowPolicy.State()
        for ((speed, t) in samples) st = p.step(st, speed, threshold, t0 + t, enabled = true)
        return st
    }

    @Test fun `a slow corner does not kill the light`() {
        // Under the threshold, but back up before the hold elapses.
        val st = run(3f to 0L, 2f to 1_000L, 6f to 2_000L)
        assertFalse(st.forcedOff)
    }

    @Test fun `rolling to a stop turns it off, once the hold has passed`() {
        val almost = run(1f to 0L, 0f to 2_000L)
        assertFalse("held for 2s only", almost.forcedOff)
        val st = run(1f to 0L, 0f to 2_000L, 0f to 3_100L)
        assertTrue(st.forcedOff)
    }

    @Test fun `hovering at the threshold does not strobe`() {
        var st = run(0f to 0L, 0f to 4_000L)
        assertTrue(st.forcedOff)
        // Just over the threshold is not riding again: the re-arm gap holds.
        st = p.step(st, threshold + 1f, threshold, t0 + 6_000L, enabled = true)
        assertTrue(st.forcedOff)
        // Genuinely moving releases it.
        st = p.step(st, threshold + HeadlightSlowPolicy.REARM_GAP_KMH, threshold, t0 + 7_000L, enabled = true)
        assertFalse(st.forcedOff)
    }

    @Test fun `switching the option off hands the light straight back`() {
        val forced = run(0f to 0L, 0f to 5_000L)
        assertTrue(forced.forcedOff)
        val cleared = p.step(forced, 0f, threshold, t0 + 6_000L, enabled = false)
        assertFalse("the schedule must get the beam back immediately", cleared.forcedOff)
    }

    @Test fun `a rider who never slows is never touched`() {
        val st = run(20f to 0L, 25f to 10_000L, 18f to 20_000L)
        assertFalse(st.forcedOff)
    }
}
