package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AccelSplitSettings
import com.eried.eucplanet.data.repository.AccelSplitRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Switching the splits off is a pause, not a reset.
 *
 * The dashboard button cycles through Off mid-ride, and the old behaviour on
 * `enabled = false` was a hard reset of the session: every tap through Off
 * would have thrown away the rider's bests. Off now drops only the run in
 * flight and the last sample, so the gap until samples resume cannot be read
 * as one very slow step, and keeps everything the session has recorded.
 */
class AccelSplitPauseTest {

    /** Drive the tracker through 20 -> 30 -> 40 at a steady rate, from [t0]. */
    private fun run(tracker: AccelSplitTracker, t0: Long, stepMs: Long): List<AccelSplitTracker.Split> {
        val out = ArrayList<AccelSplitTracker.Split>()
        var t = t0
        var v = 15.0
        while (v <= 45.0) {
            out += tracker.onSample(t, v)
            t += 250L
            v += 10.0 * 250L / stepMs
        }
        // Coast back down to end the run cleanly.
        repeat(20) { t += 250L; out += tracker.onSample(t, 10.0) }
        return out
    }

    @Test fun `a pause keeps the session's best and last times`() {
        val tracker = AccelSplitTracker(increment = 10, minSpeed = 20)
        run(tracker, 0L, stepMs = 2_000L)
        val before = tracker.snapshot()
        assertTrue("the run should have recorded steps", before.accel.isNotEmpty())

        tracker.pause()
        assertEquals("pause must not touch the session", before, tracker.snapshot())
    }

    @Test fun `samples resuming after a pause do not fabricate a slow step`() {
        val tracker = AccelSplitTracker(increment = 10, minSpeed = 20)
        // Mid-run at 25, then the splits go off for five minutes of riding.
        tracker.onSample(0L, 15.0)
        tracker.onSample(250L, 25.0)
        tracker.pause()
        // Back on at 35: without the pause dropping the last sample, this
        // would pair 25 at t=250 ms with 35 five minutes later and announce
        // "20 to 30" in three hundred seconds.
        val splits = tracker.onSample(300_000L, 35.0)
        assertTrue("a resumed stream invented a split: $splits", splits.isEmpty())
    }

    @Test fun `a fresh run after the pause still compares against the old one`() {
        val tracker = AccelSplitTracker(increment = 10, minSpeed = 20)
        run(tracker, 0L, stepMs = 2_000L)
        tracker.pause()
        val second = run(tracker, 600_000L, stepMs = 1_500L)
        val step = second.first { it.fromSpeed == 20 && it.toSpeed == 30 }
        assertNotNull("the second run must know about the first", step.deltaVsPrevious)
        assertTrue("faster run must read as faster", step.deltaVsPrevious!! < 0)
        assertTrue("and as a new best", step.isNewBest)
    }

    @Test fun `the repository pauses when the settings say off, and resets only when asked`() {
        val repo = AccelSplitRepository()
        val on = AccelSplitSettings(enabled = true, direction = "ACCEL", increment = 10, minSpeed = 20)
        var t = 0L
        var v = 15.0
        while (v <= 45.0) { repo.onSample(on, t, v); t += 250L; v += 1.25 }
        repeat(20) { t += 250L; repo.onSample(on, t, 10.0) }
        assertTrue(repo.session.value.accel.isNotEmpty())

        val kept = repo.session.value
        repo.pause()
        assertEquals("off must keep the session", kept, repo.session.value)

        repo.reset()
        assertTrue("reset is the one thing that clears it", repo.session.value.isEmpty)
    }

    @Test fun `the snapshot lists accelerating steps upward and braking steps downward`() {
        val tracker = AccelSplitTracker(increment = 10, minSpeed = 20, trackAccel = true, trackDecel = true)
        // Off the grid lines on purpose: a sample that lands exactly on 40.0
        // is a pre-existing edge the tracker arms nothing on, and real
        // telemetry does not oblige with round numbers either.
        var t = 0L
        var v = 15.3
        while (v <= 45.0) { tracker.onSample(t, v); t += 250L; v += 1.25 }
        while (v >= 15.0) { tracker.onSample(t, v); t += 250L; v -= 1.25 }
        repeat(20) { t += 250L; tracker.onSample(t, 10.0) }
        val s = tracker.snapshot()
        assertEquals(listOf(20, 30), s.accel.map { it.fromSpeed })
        assertEquals(listOf(30, 40), s.accel.map { it.toSpeed })
        assertEquals(listOf(40, 30), s.brake.map { it.fromSpeed })
        assertEquals(listOf(30, 20), s.brake.map { it.toSpeed })
    }
}
