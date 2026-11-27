package com.eried.eucplanet.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPacingTest {

    /** Steps taken out of the display frames of [seconds] at [frameMs] apart. */
    private fun steps(frameMs: Double, seconds: Int = 2): Int {
        val clock = StepClock()
        var t = 10_000.0
        var n = 0
        while (t < 10_000.0 + seconds * 1000) {
            if (clock.isStep(t.toLong())) n++
            t += frameMs
        }
        return n / seconds
    }

    @Test
    fun `a 60 Hz display steps on every second frame`() {
        assertTrue("${steps(16.67)} steps a second", steps(16.67) in 29..31)
    }

    @Test
    fun `a 120 Hz display steps on every fourth frame`() {
        assertTrue("${steps(8.33)} steps a second", steps(8.33) in 29..31)
    }

    @Test
    fun `a 90 Hz display steps on every third frame`() {
        assertTrue("${steps(11.11)} steps a second", steps(11.11) in 29..31)
    }

    @Test
    fun `everyone asking in the same frame gets the same answer`() {
        val clock = StepClock()
        assertTrue(clock.isStep(1_000L))
        assertTrue("the dial asked after the data, same frame", clock.isStep(1_000L))
        assertFalse(clock.isStep(1_016L))
        assertFalse(clock.isStep(1_016L))
        assertTrue(clock.isStep(1_033L))
    }

    @Test
    fun `after a pause the very next frame is a step`() {
        val clock = StepClock()
        clock.isStep(1_000L)
        assertTrue(clock.isStep(9_000L))
    }

    @Test
    fun `a clock that goes back does not hold the steps off`() {
        val clock = StepClock()
        clock.isStep(50_000L)
        assertTrue(clock.isStep(1_000L))
    }

    @Test
    fun `a glide runs straight from one speed to the next and ends on it`() {
        val g = Glide(10f, 20f, startMs = 1_000L)
        assertEquals(10f, g.valueAt(1_000L), 0.001f)
        assertEquals(15f, g.valueAt(1_125L), 0.001f)
        assertFalse(g.isOver(1_249L))
        assertTrue(g.isOver(1_250L))
        assertEquals(20f, g.valueAt(1_250L), 0f)
        assertEquals(20f, g.valueAt(9_999L), 0f)
    }

    @Test
    fun `a glide down, and one to the same speed`() {
        assertEquals(15f, Glide(30f, 0f, startMs = 0L).valueAt(125L), 0.001f)
        assertTrue(Glide(12f, 12f, startMs = 0L).isOver(0L))
    }
}
