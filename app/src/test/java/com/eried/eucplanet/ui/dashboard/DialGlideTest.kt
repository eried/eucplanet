package com.eried.eucplanet.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialGlideTest {

    /** Frames of a 60 Hz display, in whole milliseconds as the screen has them. */
    private fun frameMs(i: Int) = (i * 1000L) / 60L

    @Test
    fun `with a wheel reporting twenty times a second the dial moves on every second frame`() {
        val dial = DialGlide(StepClock(), start = 10f)
        var target = 10f
        val moved = mutableListOf<Int>()
        for (i in 0 until 240) {                      // four seconds
            // A new speed every 50 ms, landing between frames.
            if (i % 3 == 1) target += 0.4f
            val before = dial.shown
            dial.frame(frameMs(i), target)
            if (dial.shown != before) moved += i
        }
        val gaps = moved.zipWithNext { a, b -> b - a }
        assertTrue("the dial moved ${moved.size} times", moved.size >= 110)
        assertEquals("every repaint two frames after the last, none late", setOf(2), gaps.toSet())
    }

    @Test
    fun `a new speed takes over from where the dial is`() {
        val dial = DialGlide(StepClock(), start = 0f)
        var i = 0
        repeat(6) { dial.frame(frameMs(i++), 30f) }
        val mid = dial.shown
        assertTrue("on its way: $mid", mid > 0f && mid < 30f)
        // The wheel changes its mind: no jump, and it heads the other way.
        dial.frame(frameMs(i++), 0f)
        dial.frame(frameMs(i++), 0f)
        assertTrue("no jump: ${dial.shown} from $mid", dial.shown <= mid && dial.shown > mid - 3f)
    }

    @Test
    fun `it arrives exactly and then rests`() {
        val dial = DialGlide(StepClock(), start = 5f)
        var moving = true
        var i = 0
        while (moving && i < 120) moving = dial.frame(frameMs(i++), 25f)
        assertEquals(25f, dial.shown, 0f)
        assertTrue("arrived within the glide: frame $i", i <= 20)
        // Nothing to do while the speed stands still.
        assertEquals(false, dial.frame(frameMs(i), 25f))
        assertEquals(25f, dial.shown, 0f)
    }
}
