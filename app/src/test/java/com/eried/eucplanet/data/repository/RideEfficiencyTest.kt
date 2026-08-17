package com.eried.eucplanet.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live consumption and range readings.
 *
 * The case that matters most here is the one that used to be wrong: a rider who
 * rode before opening the app. Energy counted from connect over a wheel trip
 * meter that had been running for 8 km reported a fifth of the real figure, and
 * nothing on screen suggested it. Measuring both sides across the same window
 * is what fixes it, so these tests pin the window arithmetic.
 */
class RideEfficiencyTest {

    @Test
    fun `consumption is energy over distance across the window`() {
        // 20 Wh over 1 km.
        assertEquals(20f, RideEfficiency.whPerKm(spanWh = 20f, spanKm = 1f), 0.001f)
        assertEquals(25f, RideEfficiency.whPerKm(spanWh = 50f, spanKm = 2f), 0.001f)
    }

    @Test
    fun `distance the rider covered before connecting cannot leak in`() {
        // The old bug in one line: the window sees the 2 km ridden with the app
        // open, never the 8 km before it, because both ends of the subtraction
        // come from the same two samples.
        val cumulativeKmAtWindowStart = 8f
        val cumulativeKmNow = 10f
        val whAtWindowStart = 0f
        val whNow = 40f
        assertEquals(
            20f,
            RideEfficiency.whPerKm(whNow - whAtWindowStart, cumulativeKmNow - cumulativeKmAtWindowStart),
            0.001f,
        )
    }

    @Test
    fun `too little distance says nothing rather than a wild number`() {
        // 5 Wh over 10 m is 500 Wh/km, arithmetically true and useless.
        assertTrue(RideEfficiency.whPerKm(spanWh = 5f, spanKm = 0.01f).isNaN())
        assertTrue(RideEfficiency.whPerKm(spanWh = 5f, spanKm = 0f).isNaN())
    }

    @Test
    fun `a window of net regen is not zero consumption`() {
        // Coasting down a hill. Zero would read as a free ride; NaN reads as
        // "no number yet", which is the truth.
        assertTrue(RideEfficiency.whPerKm(spanWh = -8f, spanKm = 1f).isNaN())
        assertTrue(RideEfficiency.whPerKm(spanWh = 0f, spanKm = 1f).isNaN())
    }

    @Test
    fun `energy per percent is learned from the ride`() {
        // 90 Wh spent while the pack dropped 10 points is 9 Wh per point.
        assertEquals(
            9f,
            RideEfficiency.whPerPercent(netWh = 90f, baselineWh = 0f, pct = 80, baselinePct = 90),
            0.001f,
        )
        // Mid-ride connect: the baseline is wherever the pack was, not 100%.
        assertEquals(
            10f,
            RideEfficiency.whPerPercent(netWh = 250f, baselineWh = 100f, pct = 45, baselinePct = 60),
            0.001f,
        )
    }

    @Test
    fun `a drop too small to trust says nothing`() {
        // One percent of a rounded reading is a coin flip between half a
        // percent and one and a half.
        assertTrue(
            RideEfficiency.whPerPercent(netWh = 10f, baselineWh = 0f, pct = 99, baselinePct = 100)
                .isNaN()
        )
        assertTrue(
            RideEfficiency.whPerPercent(netWh = 10f, baselineWh = 0f, pct = 98, baselinePct = 100)
                .isNaN()
        )
        // Three is the floor, so three answers.
        assertEquals(
            5f,
            RideEfficiency.whPerPercent(netWh = 15f, baselineWh = 0f, pct = 97, baselinePct = 100),
            0.001f,
        )
    }

    @Test
    fun `range is what is left over what it is costing`() {
        // 9 Wh per point with 50 points left is 450 Wh, at 18 Wh/km = 25 km.
        assertEquals(25f, RideEfficiency.rangeKm(whPerKm = 18f, whPerPercent = 9f, pct = 50), 0.01f)
    }

    @Test
    fun `range says nothing until both halves are known`() {
        assertTrue(RideEfficiency.rangeKm(Float.NaN, 9f, 50).isNaN())
        assertTrue(RideEfficiency.rangeKm(18f, Float.NaN, 50).isNaN())
        // A wheel that reports no percentage cannot have a range worked out.
        assertTrue(RideEfficiency.rangeKm(18f, 9f, 0).isNaN())
        assertTrue(RideEfficiency.rangeKm(18f, 9f, 101).isNaN())
    }

    @Test
    fun `a long descent cannot promise a range no wheel has`() {
        // Barely consuming anything for a while would otherwise read four
        // figures, which a rider might plan a route on.
        val absurd = RideEfficiency.rangeKm(whPerKm = 0.05f, whPerPercent = 9f, pct = 100)
        assertEquals(RideEfficiency.MAX_RANGE_KM, absurd, 0.01f)
    }

    @Test
    fun `a full pack at a normal rate lands where a rider would expect`() {
        // S22-ish: 1800 Wh pack is 18 Wh per point, at 20 Wh/km that is 90 km.
        assertEquals(90f, RideEfficiency.rangeKm(whPerKm = 20f, whPerPercent = 18f, pct = 100), 0.1f)
    }
}
