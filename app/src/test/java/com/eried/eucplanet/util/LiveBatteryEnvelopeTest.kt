package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope a rider is watching while they ride.
 *
 * The raw percentage on an 84 V pack swings several points under
 * acceleration and hands them back when the rider coasts, so an alarm on the
 * raw value is either useless or a liar. The envelope is the number that only
 * moves when the charge really moved.
 */
class LiveBatteryEnvelopeTest {

    private val bucket = 30_000L

    /** Feed a steady value for one bucket and close it. */
    private fun LiveBatteryEnvelope.ride(from: Long, pct: Float): Long {
        sample(from, pct)
        sample(from + bucket, pct)
        return from + bucket
    }

    @Test fun `there is no envelope until the first bucket closes`() {
        val e = LiveBatteryEnvelope()
        assertTrue("a value appeared before any half minute of riding", e.sample(0, 80f).isNaN())
        assertTrue(e.sample(1_000, 79f).isNaN())
    }

    @Test fun `a sag does not move it, which is the whole point`() {
        // Accelerate hard: the raw percentage dives and comes straight back.
        // An alarm at 70 must not fire on this.
        val e = LiveBatteryEnvelope()
        var t = e.ride(0, 80f)
        assertEquals(80f, e.value, 0.01f)
        e.sample(t + 1_000, 68f)      // sag
        e.sample(t + 2_000, 80f)      // recovered
        e.sample(t + 3_000, 80f)
        t = e.ride(t + 4_000, 80f)
        assertTrue("a momentary sag pulled the envelope down: ${e.value}", e.value >= 79f)
    }

    @Test fun `a real drop is believed at once`() {
        // Down needs no confirmation: a pack that really fell does not come
        // back, and a late low-battery warning is worse than none.
        val e = LiveBatteryEnvelope()
        var t = e.ride(0, 80f)
        t = e.ride(t, 71f)
        assertEquals(71f, e.value, 0.01f)
    }

    @Test fun `a single high bucket does not raise it`() {
        // One bucket above the line is a sag letting go, not a descent.
        val e = LiveBatteryEnvelope()
        var t = e.ride(0, 70f)
        t = e.ride(t, 76f)
        assertEquals("one high bucket raised the envelope", 70f, e.value, 0.01f)
    }

    @Test fun `two agreeing buckets do raise it, which is regen`() {
        val e = LiveBatteryEnvelope()
        var t = e.ride(0, 70f)
        t = e.ride(t, 76f)
        t = e.ride(t, 77f)
        assertTrue("a sustained regen climb was ignored: ${e.value}", e.value > 70f)
    }

    @Test fun `zero and nonsense readings are dropped`() {
        // Every family leaves the field at zero before its first real frame.
        // Letting that into the median starts the ride at the bottom.
        val e = LiveBatteryEnvelope()
        e.sample(0, 0f)
        e.sample(1_000, 0f)
        e.sample(2_000, 120f)
        assertTrue(e.sample(bucket + 1, 0f).isNaN())
        var t = e.ride(bucket + 2, 80f)
        assertEquals(80f, e.value, 0.01f)
    }

    @Test fun `a new wheel starts a new pack`() {
        val e = LiveBatteryEnvelope()
        e.ride(0, 80f)
        e.reset()
        assertTrue(e.value.isNaN())
    }

    @Test fun `a whole descent trends down without ever stepping back up`() {
        // The property that makes it alarmable: it never rises on noise.
        val e = LiveBatteryEnvelope()
        var t = 0L
        var last = Float.NaN
        var pct = 90f
        repeat(20) {
            // Each bucket: a true value plus a nasty sag inside it.
            e.sample(t, pct)
            e.sample(t + 5_000, pct - 12f)
            e.sample(t + 10_000, pct)
            t += bucket
            e.sample(t, pct)
            if (!e.value.isNaN() && !last.isNaN()) {
                assertTrue("envelope rose during a steady descent: $last -> ${e.value}",
                    e.value <= last + 0.01f)
            }
            last = e.value
            pct -= 2f
        }
        assertTrue("envelope never followed the pack down: ${e.value}", e.value < 60f)
    }
}
