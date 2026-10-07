package com.eried.eucplanet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun `a policy sampled once a minute cannot hold anything`() {
        // Why the light never went out: the step used to run under the
        // check-interval throttle, a minute by default, and the hold is three
        // seconds. Two samples a minute apart do satisfy it, but a rider is
        // long past the crossing by then. Fed at telemetry rate it fires
        // within the hold, which is the behaviour the screen promises.
        val t0 = 100_000L
        var slow = HeadlightSlowPolicy.State()
        // Once a minute: the first slow sample only starts the clock.
        slow = HeadlightSlowPolicy.step(slow, 1f, 4f, t0, enabled = true)
        assertFalse("nothing can fire on the first sample", slow.forcedOff)
        slow = HeadlightSlowPolicy.step(slow, 1f, 4f, t0 + 60_000L, enabled = true)
        assertTrue("a minute later is far too late to be useful", slow.forcedOff)

        // At telemetry rate, it goes out just after the hold.
        var fast = HeadlightSlowPolicy.State()
        var t = t0
        while (t < t0 + HeadlightSlowPolicy.HOLD_MS) {
            fast = HeadlightSlowPolicy.step(fast, 1f, 4f, t, enabled = true)
            assertFalse("fired before the hold elapsed at t=" + (t - t0), fast.forcedOff)
            t += 200L
        }
        fast = HeadlightSlowPolicy.step(fast, 1f, 4f, t0 + HeadlightSlowPolicy.HOLD_MS, enabled = true)
        assertTrue("should have fired once the hold elapsed", fast.forcedOff)
    }

    @Test fun `the cutoff beats the sunset schedule`() {
        // The point of the feature: after dark the schedule wants a light, and
        // slowing to a walk takes it off anyway.
        assertEquals(false, HeadlightSlowPolicy.beamOn(darkEnough = true, forcedOff = true))
        // Riding again after dark puts it back, with no extra rule.
        assertEquals(true, HeadlightSlowPolicy.beamOn(darkEnough = true, forcedOff = false))
        // Daylight wants no light either way.
        assertEquals(false, HeadlightSlowPolicy.beamOn(darkEnough = false, forcedOff = false))
    }

    @Test fun `the cutoff does not wait for a location fix`() {
        // With no fix the schedule has no answer, and the light was being left
        // on: the cutoff has nothing to do with the sun and should not be held
        // up by it.
        assertEquals(false, HeadlightSlowPolicy.beamOn(darkEnough = null, forcedOff = true))
        // Without the cutoff there is genuinely nothing to say yet.
        assertNull(HeadlightSlowPolicy.beamOn(darkEnough = null, forcedOff = false))
    }
}