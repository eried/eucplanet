package com.eried.eucplanet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rate policy decides when a new playback rate is worth sending. Every
 * send crosses into another app's media session, so the interesting cases are
 * the ones where it must stay quiet.
 */
class PlaybackRatePolicyTest {

    private val p = PlaybackRatePolicy

    @Test fun `the first rate goes out immediately`() {
        val step = p.step(PlaybackRatePolicy.State(), 1.2f, 1_000L)
        assertEquals(1.2f, step.rate!!, 0.001f)
        assertEquals(1.2f, step.state.lastSent, 0.001f)
    }

    @Test fun `wheel jitter at a held speed sends nothing`() {
        // A rider holding 24 km/h produces a stream of nearly equal targets.
        var st = p.step(PlaybackRatePolicy.State(), 1.15f, 0L).state
        listOf(1.151f, 1.147f, 1.153f, 1.149f).forEachIndexed { i, target ->
            val step = p.step(st, target, 3_000L * (i + 1))
            assertNull("jitter $target should send nothing", step.rate)
            st = step.state
        }
    }

    @Test fun `a real change still waits out the quiet time`() {
        val st = p.step(PlaybackRatePolicy.State(), 1.0f, 10_000L).state
        // Big jump, but too soon after the last send.
        assertNull(p.step(st, 1.4f, 10_500L).rate)
        // Same jump once the quiet time has passed.
        assertEquals(1.4f, p.step(st, 1.4f, 12_000L).rate!!, 0.001f)
    }

    @Test fun `rates snap to the step grid and clamp to what players accept`() {
        assertEquals(1.15f, p.snap(1.1573f), 0.001f)
        assertEquals(1.15f, p.snap(1.1421f), 0.001f)
        // A curve a rider dragged to an extreme still lands inside the range.
        assertEquals(PlaybackRatePolicy.MAX_RATE, p.snap(4.0f), 0.001f)
        assertEquals(PlaybackRatePolicy.MIN_RATE, p.snap(0.1f), 0.001f)
    }

    @Test fun `snapping is stable, so the same speed never re-sends`() {
        val once = p.snap(1.2749f)
        assertEquals(once, p.snap(once), 0.0001f)
        val st = p.step(PlaybackRatePolicy.State(), 1.2749f, 0L).state
        assertNull(p.step(st, 1.2749f, 60_000L).rate)
    }

    @Test fun `a slowing ride steps the rate back down`() {
        var st = p.step(PlaybackRatePolicy.State(), 1.45f, 0L).state
        val down = p.step(st, 1.0f, 5_000L)
        assertEquals(1.0f, down.rate!!, 0.001f)
        st = down.state
        // ...and stays there while the rider is stopped.
        assertNull(p.step(st, 1.0f, 20_000L).rate)
    }
}
