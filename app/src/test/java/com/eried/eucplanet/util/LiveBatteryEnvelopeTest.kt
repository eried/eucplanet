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

    @Test fun `a ride of nothing but sag and recovery never moves it`() {
        // The rider's own report, and the shape their graph had: twenty four
        // minutes on a pack whose resting level never changed, and the line
        // swung ten points. Accelerate five seconds, coast ten, over and over,
        // so every half minute holds both states the way a real one does.
        //
        // Reduced to its median, such a bucket reports a sagging number, and
        // the walk believes every fall at once: down hard on the launch, back
        // up a minute later when the sag let go. Read at its lightest load it
        // reports the resting level, and the line does not move at all.
        // Working the wheel: twenty seconds on the throttle, ten coasting.
        // Load holds the MAJORITY of every half minute, which is what puts the
        // sag in the middle of the bucket and is the state a rider is in on a
        // climb or a fast road. A gentler duty cycle hid this, because a
        // median survives being a third sag.
        val e = LiveBatteryEnvelope()
        var t = 0L
        val seen = HashSet<Float>()
        while (t < 24 * 60 * 1000L) {
            val underLoad = (t / 1000L) % 30L < 20L
            e.sample(t, if (underLoad) 76f else 86f)
            if (!e.value.isNaN()) seen += e.value
            t += 1_000L
        }
        assertEquals("the envelope moved on a pack that did not: $seen",
            setOf(86f), seen)
    }

    @Test fun `each half minute is read at its lightest load, not its middle`() {
        // Two thirds of this bucket is sag. The median would report it; the
        // lightest-loaded moment is the one that says what the pack holds.
        val e = LiveBatteryEnvelope()
        e.sample(0, 70f)
        e.sample(5_000, 70f)
        e.sample(10_000, 70f)
        e.sample(15_000, 70f)
        e.sample(20_000, 82f)
        e.sample(25_000, 82f)
        e.sample(bucket, 82f)
        assertEquals(82f, e.value, 0.01f)
    }

    @Test fun `a sustained climb steps down, and comes back when it lets up`() {
        // Four minutes of unbroken load. There is no light-load moment to
        // read, so the honest answer is the sagging one: nothing can see the
        // resting level while the rider never stops asking for power. It
        // recovers once they do, through the usual two-bucket confirmation.
        val e = LiveBatteryEnvelope()
        var t = 0L
        while (t < 60_000L) { e.sample(t, 86f); t += 1_000L }
        val beforeClimb = e.value
        while (t < 300_000L) { e.sample(t, 74f); t += 1_000L }
        val duringClimb = e.value
        while (t < 480_000L) { e.sample(t, 86f); t += 1_000L }
        assertEquals(86f, beforeClimb, 0.01f)
        assertEquals("a climb with no let-up must read low, not hold", 74f, duringClimb, 0.01f)
        assertEquals("the top of the climb must give it back", 86f, e.value, 0.01f)
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
