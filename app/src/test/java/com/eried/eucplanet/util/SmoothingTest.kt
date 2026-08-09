package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothingTest {

    @Test fun windowBelowTwo_returnsTheInputUntouched() {
        val v = listOf(1f, 5f, 2f)
        assertSame(v, Smoothing.movingAverage(v, 1))
        assertSame(v, Smoothing.movingAverage(v, 0))
    }

    @Test fun aFlatSeries_isUnchanged() {
        val out = Smoothing.movingAverage(List(10) { 50f }, 5)
        out.forEach { assertEquals(50f, it, 0.001f) }
    }

    @Test fun aSpikeIsFlattenedButTheMeanIsKept() {
        // A single 100 in a field of zeros, window 5, spreads over 5 samples.
        val v = List(11) { if (it == 5) 100f else 0f }
        val out = Smoothing.movingAverage(v, 5)
        assertTrue("the spike must come down", out[5] < 100f)
        assertTrue("its neighbours must come up", out[4] > 0f)
        assertEquals("total energy is preserved", v.sum(), out.sum(), 0.01f)
    }

    @Test fun nanIsSkippedRatherThanPoisoningTheWindow() {
        val v = listOf(10f, Float.NaN, 20f)
        val out = Smoothing.movingAverage(v, 3)
        // The centre sample spans all three and must read the mean of the two
        // real values, not NaN. The ends see a shrunken window: one real value
        // each, since the NaN neighbour contributes nothing.
        assertEquals(10f, out[0], 0.001f)
        assertEquals(15f, out[1], 0.001f)
        assertEquals(20f, out[2], 0.001f)
    }

    @Test fun aWindowOfOnlyNan_staysNanSoTheLineBreaks() {
        val v = listOf(Float.NaN, Float.NaN, Float.NaN)
        Smoothing.movingAverage(v, 3).forEach { assertTrue(it.isNaN()) }
    }

    @Test fun theWindowShrinksAtTheEndsInsteadOfPadding() {
        val v = listOf(0f, 10f, 20f, 30f, 40f)
        val out = Smoothing.movingAverage(v, 3)
        // First sample averages only itself and its one neighbour.
        assertEquals(5f, out.first(), 0.001f)
        assertEquals(35f, out.last(), 0.001f)
    }

    @Test fun outputLengthAlwaysMatchesInput() {
        assertEquals(7, Smoothing.movingAverage(List(7) { it.toFloat() }, 5).size)
    }

    @Test fun trailingAverage_coversOnlyTheWindow() {
        val s = listOf(0L to 100f, 1_000L to 200f, 9_000L to 300f, 10_000L to 400f)
        // Last 2 s of a 10 s timeline: only the 9 s and 10 s samples.
        assertEquals(350f, Smoothing.trailingAverage(s, 10_000L, 2_000L)!!, 0.001f)
    }

    @Test fun trailingAverage_ignoresSamplesFromTheFuture() {
        val s = listOf(0L to 10f, 5_000L to 999f)
        assertEquals(10f, Smoothing.trailingAverage(s, 1_000L, 60_000L)!!, 0.001f)
    }

    @Test fun trailingAverage_withNothingInTheWindow_isNull() {
        assertNull(Smoothing.trailingAverage(listOf(0L to 10f), 100_000L, 1_000L))
        assertNull(Smoothing.trailingAverage(emptyList(), 0L, 1_000L))
    }

    @Test fun trailingAverage_skipsNan() {
        val s = listOf(0L to Float.NaN, 500L to 20f)
        assertEquals(20f, Smoothing.trailingAverage(s, 1_000L, 5_000L)!!, 0.001f)
    }
}
