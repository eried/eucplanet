package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The envelope a rider is watching while they ride.
 *
 * The raw percentage on an 84 V pack swings several points under acceleration
 * and hands them back when the rider coasts, so an alarm on the raw value is
 * either useless or a liar. The envelope is the number that only moves when
 * the charge really moved, and only moves one way while riding.
 */
class LiveBatteryEnvelopeTest {

    private val bucket = 30_000L

    /** Feed a steady value for one bucket and close it. */
    private fun LiveBatteryEnvelope.ride(from: Long, pct: Float, charging: Boolean = false): Long {
        sample(from, pct, charging)
        sample(from + bucket, pct, charging)
        return from + bucket
    }

    @Test fun `there is no envelope until the first bucket closes`() {
        val e = LiveBatteryEnvelope()
        assertTrue("a value appeared before any half minute of riding", e.sample(0, 80f).isNaN())
        assertTrue(e.sample(1_000, 79f).isNaN())
    }

    // ---- lightest load -----------------------------------------------------

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

    @Test fun `a ride of nothing but sag and recovery never moves it`() {
        // The rider's own report, and the shape their graph had: twenty four
        // minutes on a pack whose resting level never changed, and the line
        // swung ten points. Twenty seconds on the throttle, ten coasting, so
        // load holds the majority of every half minute, which is what put the
        // sag in the middle of the bucket.
        val e = LiveBatteryEnvelope()
        var t = 0L
        val seen = HashSet<Float>()
        while (t < 24 * 60 * 1000L) {
            val underLoad = (t / 1000L) % 30L < 20L
            e.sample(t, if (underLoad) 76f else 86f)
            if (!e.value.isNaN()) seen += e.value
            t += 1_000L
        }
        assertEquals("the envelope moved on a pack that did not: $seen", setOf(86f), seen)
    }

    @Test fun `a very noisy pack holds a flat line, and never a rising one`() {
        // Sag plus a point and a half of jitter on every frame, four frames a
        // second, twenty minutes. Whatever the first half minute settles on,
        // the line may not climb from it and may not wander more than the
        // jitter itself.
        val e = LiveBatteryEnvelope()
        val random = Random(7)
        var t = 0L
        var first = Float.NaN
        var high = Float.NaN
        var low = Float.NaN
        while (t < 20 * 60 * 1000L) {
            val underLoad = (t / 1000L) % 30L < 20L
            val jitter = (random.nextFloat() * 2f - 1f) * 1.5f
            e.sample(t, (if (underLoad) 76f else 86f) + jitter)
            val v = e.value
            if (!v.isNaN()) {
                if (first.isNaN()) { first = v; high = v; low = v }
                assertTrue("the line rose on noise: $v after $high", v <= high + 0.01f)
                high = maxOf(high, v)
                low = minOf(low, v)
            }
            t += 250L
        }
        assertTrue("never settled", !first.isNaN())
        assertTrue("wandered ${high - low} points on a pack that did not move", high - low <= 1.5f)
    }

    // ---- down: two buckets, to the higher ----------------------------------

    @Test fun `one low bucket alone does not move it`() {
        // A half minute of unbroken climb reads low. Nothing can see the
        // resting level through that, and a drop cannot be taken back, so one
        // such bucket is not evidence.
        val e = LiveBatteryEnvelope()
        var t = e.ride(0, 86f)
        t = e.ride(t, 74f)
        assertEquals("a single low bucket moved the line", 86f, e.value, 0.01f)
    }

    @Test fun `two low buckets move it, to the higher of the two`() {
        val e = LiveBatteryEnvelope()
        var t = e.ride(0, 86f)
        t = e.ride(t, 74f)
        t = e.ride(t, 80f)
        // 74 and 80 are both below 86: the level fell. It fell to at least 80;
        // 74 may well have been sag on top, and an over-drop cannot be undone.
        assertEquals(80f, e.value, 0.01f)
    }

    @Test fun `a real discharge is followed within a minute`() {
        // One point a minute, real, with a ten point sag on the throttle
        // inside every half minute. The line must follow the resting level
        // down and never sit more than the two-bucket lag behind it.
        val e = LiveBatteryEnvelope()
        var t = 0L
        var worstLag = 0f
        while (t < 15 * 60 * 1000L) {
            val rest = 86f - (t / 60_000L)
            val underLoad = (t / 1000L) % 30L < 20L
            e.sample(t, if (underLoad) rest - 10f else rest)
            if (!e.value.isNaN() && t > 2 * 60_000L) {
                assertTrue("the line ran ahead of the pack: ${e.value} vs $rest", e.value >= rest - 0.01f)
                worstLag = maxOf(worstLag, e.value - rest)
            }
            t += 1_000L
        }
        // Fifteen minutes at a point a minute: the pack is at 71, and the
        // line is allowed the one point and one half minute the two-bucket
        // rule costs, no more.
        assertTrue("followed the pack down only to ${e.value}", e.value <= 71f + 2f)
        assertTrue("lagged the pack by $worstLag points", worstLag <= 2f)
    }

    // ---- never up ----------------------------------------------------------

    @Test fun `it never rises while riding, however long the buckets sit high`() {
        // A drop is in. Ten half minutes above it follow: a long regen
        // descent, or a sag that let go. Either way the raw percentage shows
        // the rider that, and this number does not.
        val e = LiveBatteryEnvelope()
        var t = e.ride(0, 86f)
        t = e.ride(t, 74f)
        t = e.ride(t, 74f)
        assertEquals(74f, e.value, 0.01f)
        repeat(10) { t = e.ride(t, 90f) }
        assertEquals("the line rose while riding", 74f, e.value, 0.01f)
    }

    @Test fun `on the charger it follows the pack up`() {
        // The one place the resting level really rises. A line that held at
        // 40 % while the wheel charged to full would be lying to the rider.
        val e = LiveBatteryEnvelope()
        var t = e.ride(0, 40f)
        t = e.ride(t, 55f, charging = true)
        assertEquals(55f, e.value, 0.01f)
        t = e.ride(t, 70f, charging = true)
        assertEquals(70f, e.value, 0.01f)
        // Off the charger, the rule is back: never up.
        t = e.ride(t, 90f)
        assertEquals(70f, e.value, 0.01f)
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

    // ---- hygiene -----------------------------------------------------------

    @Test fun `zero and nonsense readings are dropped`() {
        // Every family leaves the field at zero before its first real frame.
        // Letting that into the bucket starts the ride at the bottom.
        val e = LiveBatteryEnvelope()
        e.sample(0, 0f)
        e.sample(1_000, 0f)
        e.sample(2_000, 120f)
        assertTrue(e.sample(bucket + 1, 0f).isNaN())
        e.ride(bucket + 2, 80f)
        assertEquals(80f, e.value, 0.01f)
    }

    @Test fun `a new wheel starts a new pack`() {
        val e = LiveBatteryEnvelope()
        e.ride(0, 80f)
        e.reset()
        assertTrue(e.value.isNaN())
    }
}
