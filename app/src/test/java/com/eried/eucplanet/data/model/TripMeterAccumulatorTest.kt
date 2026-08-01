package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM guard for the trip-meter split accumulator: crossing 10 km boundaries
 * must append correctly-indexed splits with the right segment time / avg / max /
 * battery, the in-progress tail must stay out of the split log, and reset must
 * wipe everything.
 */
class TripMeterAccumulatorTest {

    /** Feed one segment of ten 1 km ticks; returns the accumulator so callers chain. */
    private fun feedSegment(
        acc: TripMeterAccumulator,
        dtPerTickMs: Long,
        speeds: List<Float>,
        batteryOf: (tick: Int) -> Int,
        startTick: Int,
        nowBaseMs: Long,
    ) {
        for (i in 0 until 10) {
            val tick = startTick + i
            acc.onTick(
                distanceDeltaKm = 1.0f,
                dtActiveMs = dtPerTickMs,
                speedKmh = speeds[i],
                batteryPct = batteryOf(tick),
                nowMs = nowBaseMs + tick * 1000L,
            )
        }
    }

    @Test
    fun `crossing 10km boundaries produces indexed splits with correct segment stats`() {
        val acc = TripMeterAccumulator(intervalKm = 10f)

        // Segment 1: 10 x 1 km at 36 s each -> 360 s active -> 100 km/h avg. Max 55.
        feedSegment(
            acc,
            dtPerTickMs = 36_000L,
            speeds = listOf(10f, 20f, 30f, 40f, 50f, 55f, 50f, 40f, 30f, 20f),
            batteryOf = { 100 - it },   // tick 10 -> 90
            startTick = 1,
            nowBaseMs = 0L,
        )
        // Segment 2: 10 x 1 km at 72 s each -> 720 s active -> 50 km/h avg. Max 42.
        feedSegment(
            acc,
            dtPerTickMs = 72_000L,
            speeds = listOf(10f, 42f, 20f, 30f, 15f, 25f, 35f, 20f, 10f, 22f),
            batteryOf = { 100 - it },   // tick 20 -> 80
            startTick = 11,
            nowBaseMs = 0L,
        )

        val state = acc.snapshot()
        assertEquals(2, state.splits.size)

        val s1 = state.splits[0]
        assertEquals(1, s1.index)
        assertEquals(10f, s1.markDistanceKm, 0.001f)
        assertEquals(360_000L, s1.cumulativeMs)
        assertEquals(360_000L, s1.segmentMs)
        assertEquals(100f, s1.segmentAvgKmh, 0.05f)
        assertEquals(55f, s1.segmentMaxKmh, 0.001f)
        assertEquals(90, s1.batteryPctAtMark)

        val s2 = state.splits[1]
        assertEquals(2, s2.index)
        assertEquals(20f, s2.markDistanceKm, 0.001f)
        assertEquals(1_080_000L, s2.cumulativeMs)
        assertEquals(720_000L, s2.segmentMs)
        assertEquals(50f, s2.segmentAvgKmh, 0.05f)
        assertEquals(42f, s2.segmentMaxKmh, 0.001f)
        assertEquals(80, s2.batteryPctAtMark)

        assertEquals(20f, state.distanceKm, 0.001f)
        assertEquals(1_080_000L, state.activeMs)
        assertTrue("startedAtMs stamped on first motion", state.startedAtMs > 0L)
    }

    @Test
    fun `in-progress partial segment does not create a split`() {
        val acc = TripMeterAccumulator(intervalKm = 10f)
        feedSegment(
            acc,
            dtPerTickMs = 36_000L,
            speeds = List(10) { 40f },
            batteryOf = { 100 - it },
            startTick = 1,
            nowBaseMs = 0L,
        )
        // Five more km into the second segment: still only one split, but the
        // running total shows the partial 5 km.
        repeat(5) { acc.onTick(1.0f, 30_000L, 30f, 88, 1_000_000L) }

        val state = acc.snapshot()
        assertEquals(1, state.splits.size)
        assertEquals(15f, state.distanceKm, 0.001f)
    }

    @Test
    fun `reset clears totals and split log`() {
        val acc = TripMeterAccumulator(intervalKm = 10f)
        feedSegment(
            acc,
            dtPerTickMs = 36_000L,
            speeds = List(10) { 40f },
            batteryOf = { 100 - it },
            startTick = 1,
            nowBaseMs = 0L,
        )
        assertEquals(1, acc.snapshot().splits.size)

        acc.reset()

        val state = acc.snapshot()
        assertEquals(0f, state.distanceKm, 0.0001f)
        assertEquals(0L, state.activeMs)
        assertEquals(0L, state.startedAtMs)
        assertTrue(state.splits.isEmpty())
    }

    @Test
    fun `imperial interval marks at 10 mi worth of km`() {
        // 10 mi = ~16.09344 km; a mi rider's first boundary lands there.
        val acc = TripMeterAccumulator(intervalKm = 16.09344f)
        repeat(17) { acc.onTick(1.0f, 60_000L, 60f, 70, 0L) }
        val state = acc.snapshot()
        assertEquals(1, state.splits.size)
        assertEquals(16.09344f, state.splits[0].markDistanceKm, 0.01f)
    }
}
