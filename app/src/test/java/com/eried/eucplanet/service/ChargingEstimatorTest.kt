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
 *
 * Note: warm-up requires 3 % gained over 3 min (was 2 % / 30 s); the
 * feedLinear helper defaults to a sample stream long enough to satisfy that.
 */
class ChargingEstimatorTest {

    /** Feed a steady linear charge of [pctPerMin] starting at [startPct] for
     *  [count] samples spaced [stepMs] apart, beginning at t=0. Default is
     *  240 samples × 1 s = 4 min span / +4 % gain at 1 %/min — past the 3 % /
     *  3 min warm-up gate, and dense enough that the median-filter window
     *  (a few hundred ms at this cadence) doesn't add meaningful lag, which
     *  is how the real wheel emits telemetry (~9 Hz on Veteran). */
    private fun ChargingEstimator.feedLinear(
        startPct: Float,
        pctPerMin: Float,
        count: Int = 240,
        stepMs: Long = 1_000,
    ) {
        for (i in 0 until count) {
            val tMs = i * stepMs
            val pct = startPct + pctPerMin * (tMs / 60_000f)
            addSample(tMs, pct)
        }
    }

    @Test
    fun `not warmed up with too little data`() {
        val est = ChargingEstimator()
        est.addSample(0, 50f)
        est.addSample(10_000, 50.1f) // +0.1% over 10s — below warm-up gain AND duration
        val e = est.estimate()
        assertTrue("should not be warmed up yet", !e.warmedUp)
        assertNull("no ETA before warm-up", e.minutesToTarget)
        assertNull("no ETA before warm-up", e.minutesToFull)
    }

    @Test
    fun `steady one percent per minute gives correct rate and eta to 80`() {
        val est = ChargingEstimator()
        // 50% climbing at 1%/min, samples every 1s for 4 min -> reaches 54%.
        // Past the 3% / 3min warm-up gate.
        est.feedLinear(startPct = 50f, pctPerMin = 1f)
        val e = est.estimate()
        assertTrue("warmed up after +4% over 4min", e.warmedUp)
        assertEquals(1.0f, e.ratePctPerMin, 0.05f)
        // current ~54% -> (80-54)/1 * targetTaper(1.05) = 27.3 min
        assertNotNull(e.minutesToTarget)
        assertEquals(27.3f, e.minutesToTarget!!, 0.5f)
    }

    @Test
    fun `eta to full is pessimistic versus naive linear due to cv taper`() {
        val est = ChargingEstimator() // defaults: targetTaper 1.05, cvTaper 2.0
        est.feedLinear(startPct = 50f, pctPerMin = 1f) // -> 54%
        val e = est.estimate()
        val naiveToFull = (100f - 54f) / 1f // 46 min if it never tapered
        assertNotNull(e.minutesToFull)
        assertTrue(
            "full ETA (${e.minutesToFull}) must exceed naive linear ($naiveToFull)",
            e.minutesToFull!! > naiveToFull
        )
        // cc: (80-54)/1 * 1.05 = 27.3 ; cv: (100-80)*2.0/1 = 40 ; total 67.3
        assertEquals(67.3f, e.minutesToFull!!, 1.0f)
    }

    @Test
    fun `past the 80 target reports no eta to target but still to full`() {
        val est = ChargingEstimator()
        est.feedLinear(startPct = 85f, pctPerMin = 1f) // -> 89%
        val e = est.estimate()
        assertTrue(e.warmedUp)
        assertNull("already past 80%", e.minutesToTarget)
        // only cv part: (100-89)*2.0/1 = 22.0
        assertNotNull(e.minutesToFull)
        assertEquals(22.0f, e.minutesToFull!!, 1.0f)
    }

    @Test
    fun `discharging yields no eta`() {
        val est = ChargingEstimator()
        // decreasing percent -> negative slope
        est.feedLinear(startPct = 80f, pctPerMin = -1f) // -> 76%
        val e = est.estimate()
        assertNull(e.minutesToTarget)
        assertNull(e.minutesToFull)
    }

    @Test
    fun `tracks session start and added percent`() {
        val est = ChargingEstimator()
        est.feedLinear(startPct = 60f, pctPerMin = 1f) // -> ~64%
        val e = est.estimate()
        // startPercent is the FILTERED value at the first sample, which for
        // a single-sample input equals the raw value (60.0).
        assertEquals(60f, e.startPercent, 0.01f)
        // The latest filtered % lags raw by half the median window (~3 samples
        // at 1 Hz = ~0.05 % at this rate), so percent / addedPercent come in
        // a fraction below the raw 64 %.
        assertEquals(64f, e.percent, 0.15f)
        assertEquals(4f, e.addedPercent, 0.15f)
    }

    @Test
    fun `reset clears the session`() {
        val est = ChargingEstimator()
        est.feedLinear(startPct = 60f, pctPerMin = 1f)
        est.reset()
        est.addSample(0, 40f)
        val e = est.estimate()
        assertEquals(40f, e.startPercent, 0.01f)
        assertEquals(0f, e.addedPercent, 0.01f)
        assertNull(e.minutesToTarget)
    }

    @Test
    fun `median filter drops a single-frame outlier without skewing the slope`() {
        val est = ChargingEstimator()
        // Steady 1 %/min stream over 4 min at 1 Hz, with every 30th sample
        // dropping 20 percentage points (the noisy-voltage outlier pattern
        // from real Lynx captures: ~1 % of frames carry a wildly low pack-V
        // reading). The median filter should swallow them; slope stays at
        // 1 %/min, matching the clean-stream test above.
        for (i in 0 until 240) {
            val tMs = i.toLong() * 1_000L
            val pctBase = 50f + 1f * (tMs / 60_000f)
            val pct = if (i % 30 == 0 && i > 0) pctBase - 20f else pctBase
            est.addSample(tMs, pct)
        }
        val e = est.estimate()
        assertTrue("warmed up after the long stream", e.warmedUp)
        assertEquals("slope unaffected by outliers", 1.0f, e.ratePctPerMin, 0.1f)
    }
}
