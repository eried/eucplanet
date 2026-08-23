package com.eried.eucplanet.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only evidence an InMotion wheel gives that it is on a charger: the pack
 * percentage going up while it stands still.
 *
 * Everything here feeds whole-number percentages, because that is what these
 * wheels report and what makes the naive fixed-horizon rate flicker.
 */
class ChargeRiseDetectorTest {

    /** Feed a climb at [pctPerMin] for [minutes] and report the final state. */
    private fun climb(
        detector: ChargeRiseDetector,
        startPct: Float,
        pctPerMin: Float,
        minutes: Int,
        startMs: Long = 1_000_000L,
    ): Boolean {
        var rising = false
        for (second in 0..(minutes * 60)) {
            val pct = startPct + pctPerMin * (second / 60f)
            rising = detector.update(
                nowMs = startMs + second * 1000L,
                // Whole numbers, as the wheel reports them.
                percent = pct.toInt().toFloat(),
                applicable = true,
            )
        }
        return rising
    }

    @Test
    fun `a normal charge is detected`() {
        // 20 % to 100 % over two hours is about 0.67 % a minute: the percentage
        // steps once every ninety seconds.
        assertTrue(climb(ChargeRiseDetector(), startPct = 20f, pctPerMin = 0.67f, minutes = 10))
    }

    @Test
    fun `it stays detected rather than flickering`() {
        // A fixed 45-second horizon against a whole-number percentage reads
        // either 0 or 1.33 %/min depending on where the step lands, so it
        // alternated on and off every window all through the charge.
        val detector = ChargeRiseDetector()
        var detectedAtS = -1
        var droppedAfterDetection = 0
        for (second in 0..(60 * 60)) {
            val pct = 20f + 0.67f * (second / 60f)
            val rising = detector.update(1_000_000L + second * 1000L, pct.toInt().toFloat(), true)
            if (rising && detectedAtS < 0) detectedAtS = second
            if (!rising && detectedAtS >= 0) droppedAfterDetection++
        }
        // Under ten minutes. The level is a mean over the last minute, so it
        // trails the pack by about half that before the first window can close.
        assertTrue("took $detectedAtS s to call it a charge", detectedAtS in 0..(10 * 60))
        assertEquals("dropped $droppedAfterDetection times mid-charge",
            0, droppedAfterDetection)
    }

    @Test
    fun `a charge is still a charge when the percentage wobbles`() {
        // What a real V8S reports. The percentage is worked out from pack
        // voltage, so it dips a point and comes back every few seconds all the
        // way up a charge: on a rider's own charge graph the voltage trace is a
        // clean line and the percentage beside it is a band. Reading each dip as
        // the pack discharging restarted the confirmation every few seconds, and
        // three hours on the charger were never confirmed at all.
        val detector = ChargeRiseDetector()
        var rising = false
        var detectedAtS = -1
        for (second in 0..(20 * 60)) {
            val trend = 32f + 0.30f * (second / 60f)      // the rider's own rate
            val wobble = if ((second / 7) % 2 == 0) 0f else -1f
            rising = detector.update(
                nowMs = 1_000_000L + second * 1000L,
                percent = (trend + wobble).toInt().toFloat(),
                applicable = true,
            )
            if (rising && detectedAtS < 0) detectedAtS = second
        }
        assertTrue("never called it a charge in twenty minutes", rising)
        assertTrue("took $detectedAtS s to call it a charge", detectedAtS in 0..(12 * 60))
    }

    @Test
    fun `a parked wheel is not charging`() {
        assertFalse(climb(ChargeRiseDetector(), startPct = 60f, pctPerMin = 0f, minutes = 30))
    }

    @Test
    fun `a pack losing charge is not charging`() {
        assertFalse(climb(ChargeRiseDetector(), startPct = 60f, pctPerMin = -0.5f, minutes = 20))
    }

    @Test
    fun `the rebound after a ride does not read as a charge`() {
        // On the V1 family the percentage is worked out from pack voltage, and
        // voltage rebounds steeply for about a minute once the load comes off.
        // Steep, but over long before the climb has held up for three minutes.
        val detector = ChargeRiseDetector()
        var rising = false
        for (second in 0..(15 * 60)) {
            // 8 points back in the first 90 s, then flat.
            val pct = 25f + 8f * kotlin.math.min(second / 90f, 1f)
            rising = detector.update(1_000_000L + second * 1000L, pct.toInt().toFloat(), true)
            if (rising) break
        }
        assertFalse("a post-ride rebound was read as a charge", rising)
    }

    @Test
    fun `unplugging drops it`() {
        val detector = ChargeRiseDetector()
        var t = 1_000_000L
        var pct = 20f
        // Charge until it latches.
        for (second in 0..(10 * 60)) {
            pct = 20f + 0.67f * (second / 60f)
            detector.update(t, pct.toInt().toFloat(), true)
            t += 1000L
        }
        assertTrue(detector.update(t, pct.toInt().toFloat(), true))

        // Charger out: the percentage stops climbing.
        var rising = true
        for (second in 0..(6 * 60)) {
            rising = detector.update(t, pct.toInt().toFloat(), true)
            t += 1000L
        }
        assertFalse("still reading as charging six minutes after unplugging", rising)
    }

    @Test
    fun `riding clears the state instead of leaving it latched`() {
        val detector = ChargeRiseDetector()
        var t = 1_000_000L
        for (second in 0..(10 * 60)) {
            detector.update(t, (20f + 0.67f * (second / 60f)).toInt().toFloat(), true)
            t += 1000L
        }
        // The wheel rolls away: the question no longer applies.
        assertFalse(detector.update(t, 27f, applicable = false))
        // And the next frame while parked starts from nothing, not from latched.
        assertFalse(detector.update(t + 1000L, 27f, applicable = true))
    }
}
