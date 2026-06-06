package com.eried.eucplanet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the within-session charging estimator. All ETAs are driven purely
 * by the observed %-climb rate (no pack capacity), so these feed synthetic
 * (timestamp, percent) samples and assert on the derived rate / ETAs.
 */
class ChargingEstimatorTest {

    /** Feed a steady linear charge of [pctPerMin] starting at [startPct] for
     *  [count] samples spaced [stepS] seconds apart, beginning at t=0. */
    private fun ChargingEstimator.feedLinear(
        startPct: Float,
        pctPerMin: Float,
        count: Int,
        stepS: Long = 30,
    ) {
        for (i in 0 until count) {
            val tMs = i * stepS * 1000L
            val pct = startPct + pctPerMin * (tMs / 60000f)
            addSample(tMs, pct)
        }
    }

    @Test
    fun `not warmed up with too little data`() {
        val est = ChargingEstimator()
        est.addSample(0, 50f)
        est.addSample(10_000, 50.1f) // +0.1% over 10s — below warm-up gain
        val e = est.estimate()
        assertTrue("should not be warmed up yet", !e.warmedUp)
        assertNull("no ETA before warm-up", e.minutesToTarget)
        assertNull("no ETA before warm-up", e.minutesToFull)
    }

    @Test
    fun `steady one percent per minute gives correct rate and eta to 80`() {
        val est = ChargingEstimator()
        // 50% climbing at 1%/min, samples every 30s for 2 min -> reaches 52%.
        est.feedLinear(startPct = 50f, pctPerMin = 1f, count = 5)
        val e = est.estimate()
        assertTrue("warmed up after +2% over 2min", e.warmedUp)
        assertEquals(1.0f, e.ratePctPerMin, 0.05f)
        // current 52% -> (80-52)/1 = 28 min
        assertNotNull(e.minutesToTarget)
        assertEquals(28f, e.minutesToTarget!!, 0.5f)
    }

    @Test
    fun `eta to full is pessimistic versus naive linear due to cv taper`() {
        val est = ChargingEstimator() // default target 80, taper 2.2
        est.feedLinear(startPct = 50f, pctPerMin = 1f, count = 5) // -> 52%
        val e = est.estimate()
        val naiveToFull = (100f - 52f) / 1f // 48 min if it never tapered
        assertNotNull(e.minutesToFull)
        assertTrue(
            "full ETA (${e.minutesToFull}) must exceed naive linear ($naiveToFull)",
            e.minutesToFull!! > naiveToFull
        )
        // cc: (80-52)/1 = 28 ; cv: (100-80)*2.2/1 = 44 ; total 72
        assertEquals(72f, e.minutesToFull!!, 1.0f)
    }

    @Test
    fun `past the 80 target reports no eta to target but still to full`() {
        val est = ChargingEstimator()
        est.feedLinear(startPct = 85f, pctPerMin = 1f, count = 5) // -> 87%
        val e = est.estimate()
        assertTrue(e.warmedUp)
        assertNull("already past 80%", e.minutesToTarget)
        // only cv part: (100-87)*2.2/1 = 28.6
        assertNotNull(e.minutesToFull)
        assertEquals(28.6f, e.minutesToFull!!, 1.0f)
    }

    @Test
    fun `discharging yields no eta`() {
        val est = ChargingEstimator()
        // decreasing percent -> negative slope
        est.feedLinear(startPct = 80f, pctPerMin = -1f, count = 5) // -> 78%
        val e = est.estimate()
        assertNull(e.minutesToTarget)
        assertNull(e.minutesToFull)
    }

    @Test
    fun `tracks session start and added percent`() {
        val est = ChargingEstimator()
        est.feedLinear(startPct = 60f, pctPerMin = 1f, count = 5) // -> 62%
        val e = est.estimate()
        assertEquals(60f, e.startPercent, 0.01f)
        assertEquals(62f, e.percent, 0.01f)
        assertEquals(2f, e.addedPercent, 0.05f)
    }

    @Test
    fun `reset clears the session`() {
        val est = ChargingEstimator()
        est.feedLinear(startPct = 60f, pctPerMin = 1f, count = 5)
        est.reset()
        est.addSample(0, 40f)
        val e = est.estimate()
        assertEquals(40f, e.startPercent, 0.01f)
        assertEquals(0f, e.addedPercent, 0.01f)
        assertNull(e.minutesToTarget)
    }
}
