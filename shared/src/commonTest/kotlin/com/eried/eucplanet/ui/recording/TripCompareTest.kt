package com.eried.eucplanet.ui.recording

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.math.sin

class TripCompareTest {

    private fun ms(vararg s: Long) = LongArray(s.size) { s[it] * 1000 }

    @Test fun fitPlacesBOnAsClockWithTheOffset() {
        val a = ms(0, 1, 2, 3, 4, 5)
        val b = ms(0, 1, 2)
        val v = floatArrayOf(10f, 20f, 30f)
        val out = TripCompare.fit(a, b, v, offsetMs = 2000)
        assertTrue(out[0].isNaN())
        assertTrue(out[1].isNaN())
        floatArrayOf(10f, 20f, 30f).forEachIndexed { i, v -> assertEquals(v, out[2 + i], 0.001f) }
        assertTrue(out[5].isNaN())
    }

    @Test fun fitInterpolatesBetweenBsSamples() {
        val out = TripCompare.fit(longArrayOf(500), ms(0, 1), floatArrayOf(0f, 10f), 0)
        assertEquals(5f, out[0], 0.001f)
    }

    @Test fun fitBreaksAcrossALongGapInB() {
        val out = TripCompare.fit(ms(0, 5, 20), ms(0, 10, 20), floatArrayOf(1f, 2f, 3f), 0)
        assertEquals(1f, out[0], 0.001f)
        assertTrue(out[1].isNaN())
        assertEquals(3f, out[2], 0.001f)
    }

    @Test fun nearestIndexFollowsTheOffsetAndStaysInsideB() {
        val b = ms(0, 1, 2, 3)
        assertEquals(1, TripCompare.nearestIndex(b, 11_200, 10_000))
        assertNull(TripCompare.nearestIndex(b, 9_000, 10_000))
        assertNull(TripCompare.nearestIndex(b, 14_000, 10_000))
    }

    @Test fun overlapIsTheStretchBothTripsCover() {
        assertEquals(2000L..5000L, TripCompare.overlapMs(5000, 9000, 2000))
        assertEquals(0L..3000L, TripCompare.overlapMs(10000, 5000, -2000))
        assertNull(TripCompare.overlapMs(5000, 5000, 6000))
    }

    @Test fun autoFindsWhereBWasCutFromA() {
        val n = 600
        val aMs = LongArray(n) { it * 1000L }
        val aV = FloatArray(n) { (20 + 10 * sin(it / 9.0) + 4 * sin(it / 2.3)).toFloat() }
        val start = 137
        val bMs = LongArray(200) { it * 1000L }
        val bV = FloatArray(200) { aV[start + it] }
        assertEquals(start * 1000L, TripCompare.autoOffsetMs(aMs, aV, bMs, bV))
    }

    @Test fun autoFindsANegativeOffset() {
        val n = 400
        val bMs = LongArray(n) { it * 1000L }
        val bV = FloatArray(n) { (15 + 8 * sin(it / 7.0) + 3 * sin(it / 1.7)).toFloat() }
        val cut = 52
        val aMs = LongArray(150) { it * 1000L }
        val aV = FloatArray(150) { bV[cut + it] }
        assertEquals(-cut * 1000L, TripCompare.autoOffsetMs(aMs, aV, bMs, bV))
    }

    @Test fun autoGivesUpOnAFlatTrip() {
        val t = LongArray(60) { it * 1000L }
        assertNull(TripCompare.autoOffsetMs(t, FloatArray(60) { 5f }, t, FloatArray(60) { it.toFloat() }))
    }

    @Test fun offsetTextRoundTrips() {
        assertEquals("+36:18", TripCompare.formatOffset(2_178_000))
        assertEquals("-1:05", TripCompare.formatOffset(-65_000))
        assertEquals(2_178_000L, TripCompare.parseOffset("+36:18"))
        assertEquals(-65_000L, TripCompare.parseOffset("−1:05"))
        assertEquals(90_000L, TripCompare.parseOffset("90"))
        assertEquals(3_723_000L, TripCompare.parseOffset("1:02:03"))
        assertNull(TripCompare.parseOffset("abc"))
    }

    @Test fun typedDigitsReadLikeTheTrimClock() {
        assertEquals(36 * 60 + 18, TripCompare.parseTypedSeconds("3618"))
        assertEquals(-(1 * 60 + 5), TripCompare.parseTypedSeconds("-105"))
        assertEquals(45, TripCompare.parseTypedSeconds("45"))
        assertNull(TripCompare.parseTypedSeconds("-"))
    }
}
