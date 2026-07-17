package com.eried.eucplanet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [AccelSplitTracker]: band-crossing detection, sub-sample
 * crossing-time interpolation, multi-step segments, run summaries, and the
 * previous-run / session-best comparison math.
 */
class AccelSplitTrackerTest {

    private fun feed(tracker: AccelSplitTracker, vararg samples: Pair<Long, Double>): List<AccelSplitTracker.Split> {
        val out = ArrayList<AccelSplitTracker.Split>()
        for ((t, v) in samples) out += tracker.onSample(t, v)
        return out
    }

    private fun splits(events: List<AccelSplitTracker.Split>) = events

    @Test
    fun `single step interpolates crossing time between samples`() {
        val t = AccelSplitTracker(increment = 10, minSpeed = 20)
        // Arm at 20 (crossed at 500ms), then cross 30 at 1800ms.
        val ev = feed(t, 0L to 18.0, 1000L to 22.0, 2000L to 32.0)
        val s = splits(ev)
        assertEquals(1, s.size)
        assertEquals(20, s[0].fromSpeed)
        assertEquals(30, s[0].toSpeed)
        assertEquals(1.3, s[0].seconds, 0.001)
        assertNull(s[0].deltaVsPrevious)
        assertNull(s[0].deltaVsBest)
        assertTrue(s[0].isNewBest)
    }

    @Test
    fun `one fast segment emits every step it spans`() {
        val t = AccelSplitTracker(increment = 10, minSpeed = 20)
        val s = splits(feed(t, 0L to 15.0, 1000L to 45.0))
        assertEquals(listOf(20 to 30, 30 to 40), s.map { it.fromSpeed to it.toSpeed })
        // 15 -> 45 over 1000ms is 30 units/s, so each 10-unit step is ~0.333s.
        assertEquals(0.333, s[0].seconds, 0.005)
        assertEquals(0.333, s[1].seconds, 0.005)
    }

    @Test
    fun `speed staying below minimum produces nothing`() {
        val t = AccelSplitTracker(increment = 10, minSpeed = 20)
        val ev = feed(t, 0L to 5.0, 1000L to 12.0, 2000L to 18.0, 3000L to 10.0)
        assertTrue(ev.isEmpty())
    }

    @Test
    fun `decel produces no split and ends the run`() {
        val t = AccelSplitTracker(increment = 10, minSpeed = 20)
        // Two steps up, then a decel sample. The decel itself must not emit a
        // split, and it must not fabricate one for a step that was not crossed.
        val ev = feed(
            t,
            0L to 18.0, 1000L to 22.0, 2000L to 32.0, 3000L to 42.0, 4000L to 25.0,
        )
        assertEquals(listOf(20 to 30, 30 to 40), ev.map { it.fromSpeed to it.toSpeed })
    }

    @Test
    fun `second run compares each step against the previous run`() {
        val t = AccelSplitTracker(increment = 10, minSpeed = 20)
        // Run 1: 20->30 in 1.3s, 30->40 in 1.0s, then decel ends the run.
        feed(t, 0L to 18.0, 1000L to 22.0, 2000L to 32.0, 3000L to 42.0, 4000L to 25.0)
        // Reset the previous-sample memory with a dip below minSpeed, then run 2.
        val ev = feed(t, 5000L to 18.0, 6000L to 22.0, 7000L to 34.0)
        val s = splits(ev)
        assertEquals(1, s.size)
        assertEquals(20, s[0].fromSpeed)
        // Run 2's 20->30 crossed 30 at ~6667ms, armed 20 at 5500ms => ~1.167s,
        // which is 0.133s faster than run 1's 1.3s.
        assertEquals(-0.133, s[0].deltaVsPrevious!!, 0.01)
        assertTrue(s[0].deltaVsBest!! < 0.0)
        assertTrue(s[0].isNewBest)
    }

    @Test
    fun `slower repeat is not a new best`() {
        val t = AccelSplitTracker(increment = 10, minSpeed = 20)
        // Fast run: 20->30 in ~0.5s.
        feed(t, 0L to 18.0, 500L to 22.0, 1000L to 40.0)
        feed(t, 1500L to 10.0) // decel below minSpeed ends + disarms
        // Slow run: 20->30 in ~2.0s.
        val ev = feed(t, 2000L to 18.0, 3000L to 22.0, 5000L to 32.0)
        val s = splits(ev)
        assertEquals(20, s[0].fromSpeed)
        assertTrue("slower split should not beat the session best", !s[0].isNewBest)
        assertTrue("delta vs best should be positive when slower", s[0].deltaVsBest!! > 0.0)
    }
}
