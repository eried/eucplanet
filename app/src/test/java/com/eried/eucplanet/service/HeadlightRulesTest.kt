package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.ApplyWhenIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four headlight rules together, since they only misbehave together.
 *
 * Both bugs that reached a rider came from the interaction rather than from any
 * one rule: "when riding" switched the automation off at walking pace, which is
 * the speed the cutoff is about, and the cutoff waited on a sun schedule it
 * does not need. So the cases here are rides, not single calls.
 */
class HeadlightRulesTest {

    private val t0 = 100_000L
    private val threshold = 5f

    private fun input(
        speed: Float,
        now: Long,
        applyWhen: String = ApplyWhenIds.RIDING,
        connected: Boolean = true,
        rode: Boolean = true,
        offWhenSlow: Boolean = true,
        dark: Boolean? = true,
    ) = HeadlightRules.Input(
        applyWhen = applyWhen,
        connected = connected,
        rodeThisSession = rode,
        offWhenSlow = offWhenSlow,
        thresholdKmh = threshold,
        speedKmh = speed,
        darkEnough = dark,
        nowMs = now,
    )

    /** Ride a sequence of (speed, time) and return what the beam was told. */
    private fun ride(
        frames: List<Pair<Float, Long>>,
        applyWhen: String = ApplyWhenIds.RIDING,
        connected: Boolean = true,
        rode: Boolean = true,
        offWhenSlow: Boolean = true,
        dark: Boolean? = true,
    ): List<Boolean?> {
        var state = HeadlightSlowPolicy.State()
        return frames.map { (speed, t) ->
            val d = HeadlightRules.decide(
                state, input(speed, t, applyWhen, connected, rode, offWhenSlow, dark),
            )
            state = d.slow
            d.beamOn
        }
    }

    @Test fun `riding after dark asks for the light`() {
        assertEquals(
            listOf(true, true),
            ride(listOf(20f to t0, 20f to t0 + 1000)),
        )
    }

    @Test fun `slowing to a walk takes the light off, after the hold`() {
        val out = ride(
            listOf(
                20f to t0,
                2f to t0 + 100,
                2f to t0 + 2_000,
                2f to t0 + 3_200,
                0f to t0 + 5_000,
            ),
        )
        assertEquals(listOf(true, true, true, false, false), out)
    }

    @Test fun `the cutoff beats the schedule, which is the whole point`() {
        // Dark, so the schedule wants the light; slowed, so it goes off anyway.
        var state = HeadlightSlowPolicy.State()
        listOf(t0, t0 + 3_500L).forEach { t ->
            state = HeadlightRules.decide(state, input(1f, t)).slow
        }
        assertTrue(state.forcedOff)
        assertEquals(false, HeadlightRules.decide(state, input(1f, t0 + 4_000)).beamOn)
    }

    @Test fun `riding on brings it back without any further rule`() {
        var state = HeadlightSlowPolicy.State()
        listOf(t0, t0 + 3_500L).forEach { t ->
            state = HeadlightRules.decide(state, input(1f, t)).slow
        }
        // Just over the threshold is not enough: the rearm gap stops a rider
        // hovering at walking pace from strobing the beam.
        var d = HeadlightRules.decide(state, input(threshold + 0.5f, t0 + 5_000))
        assertEquals(false, d.beamOn)
        // Genuinely riding again does it.
        d = HeadlightRules.decide(d.slow, input(threshold + HeadlightSlowPolicy.REARM_GAP_KMH, t0 + 6_000))
        assertEquals(true, d.beamOn)
    }

    @Test fun `stopped at a crossing is still inside the ride`() {
        // The bug a rider reported: with "when riding", the plain gate answers
        // "not riding" at the exact speed the cutoff acts on, and nothing
        // happened at all.
        val out = ride(listOf(0f to t0, 0f to t0 + 4_000))
        assertEquals("the automation must still be running while stopped", 2, out.size)
        assertEquals(false, out.last())
    }

    @Test fun `a rider who has not ridden yet is left alone in riding mode`() {
        // Wheel on in a garage, app connected. Nothing should touch the light.
        val out = ride(listOf(0f to t0, 0f to t0 + 4_000), rode = false)
        assertEquals(listOf<Boolean?>(null, null), out)
    }

    @Test fun `connected mode does not wait for a ride`() {
        val out = ride(
            listOf(0f to t0, 0f to t0 + 4_000),
            applyWhen = ApplyWhenIds.CONNECTED, rode = false,
        )
        assertEquals(listOf(true, false), out)
    }

    @Test fun `never means the automation keeps its hands off entirely`() {
        val out = ride(listOf(20f to t0, 0f to t0 + 4_000), applyWhen = ApplyWhenIds.NEVER)
        assertEquals(listOf<Boolean?>(null, null), out)
    }

    @Test fun `a wheel that has gone away is left alone`() {
        val out = ride(listOf(20f to t0, 0f to t0 + 4_000), connected = false)
        assertEquals(listOf<Boolean?>(null, null), out)
    }

    @Test fun `with the cutoff switched off the schedule rules alone`() {
        val out = ride(
            listOf(20f to t0, 0f to t0 + 4_000, 0f to t0 + 9_000),
            offWhenSlow = false,
        )
        assertEquals("stopping must not touch the beam", listOf(true, true, true), out)
    }

    @Test fun `switching the cutoff off mid-stop hands the light straight back`() {
        var state = HeadlightSlowPolicy.State()
        listOf(t0, t0 + 3_500L).forEach { t ->
            state = HeadlightRules.decide(state, input(1f, t)).slow
        }
        assertTrue(state.forcedOff)
        val d = HeadlightRules.decide(state, input(1f, t0 + 4_000, offWhenSlow = false))
        assertEquals("the schedule takes over at once", true, d.beamOn)
    }

    @Test fun `daylight wants no light, slowed or not`() {
        assertEquals(
            listOf(false, false),
            ride(listOf(20f to t0, 0f to t0 + 9_000), dark = false),
        )
    }

    @Test fun `with no fix the cutoff still acts, and nothing else does`() {
        // No location has arrived, so the schedule has no answer. Riding leaves
        // the beam alone; slowing still takes it off.
        val out = ride(
            listOf(20f to t0, 1f to t0 + 100, 1f to t0 + 4_000),
            dark = null,
        )
        assertEquals(listOf(null, null, false), out)
    }

    @Test fun `leaving the ride keeps the cutoff state for the next frame`() {
        // The gate closing must not silently rearm the beam: a rider whose
        // wheel drops for a second and comes back should not get a flash.
        var state = HeadlightSlowPolicy.State()
        listOf(t0, t0 + 3_500L).forEach { t ->
            state = HeadlightRules.decide(state, input(1f, t)).slow
        }
        val gone = HeadlightRules.decide(state, input(1f, t0 + 4_000, connected = false))
        assertNull(gone.beamOn)
        assertTrue("the cutoff is remembered", gone.slow.forcedOff)
    }
}
