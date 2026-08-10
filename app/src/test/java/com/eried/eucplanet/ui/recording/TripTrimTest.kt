package com.eried.eucplanet.ui.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TripTrimTest {

    /** One sample per second from 12:00:00, with the given count. */
    private fun points(count: Int): List<TripDataPoint> = (0 until count).map { i ->
        TripDataPoint(
            date = "2026-08-09 12:00:%02d.000".format(i),
            speed = 0f, voltage = 0f, temperature = 0f, battery = 0,
            altitude = 0f, latitude = 0.0, longitude = 0.0, totalMileage = 0f,
        )
    }

    @Test fun elapsedOffsets_areMillisFromTheFirstSample() {
        val e = TripTrim.elapsedOffsets(points(4))
        assertArrayEquals(longArrayOf(0L, 1_000L, 2_000L, 3_000L), e)
    }

    @Test fun elapsedOffsets_onEmptyInput_isEmpty() {
        assertEquals(0, TripTrim.elapsedOffsets(emptyList()).size)
    }

    @Test fun elapsedOffsets_unparseableRow_fallsBackToZeroOffset() {
        val pts = points(3).toMutableList()
        pts[1] = pts[1].copy(date = "not a date")
        val e = TripTrim.elapsedOffsets(pts)
        assertArrayEquals(longArrayOf(0L, 0L, 2_000L), e)
    }

    @Test fun apply_withNullRange_returnsTheSameListInstance() {
        val pts = points(5)
        val e = TripTrim.elapsedOffsets(pts)
        assertSame(pts, TripTrim.apply(pts, e, null))
    }

    @Test fun apply_selectsOnlySamplesInsideTheRange() {
        val pts = points(10)
        val e = TripTrim.elapsedOffsets(pts)
        val out = TripTrim.apply(pts, e, 3_000L..5_000L)
        assertEquals(3, out.size)
        assertEquals(pts[3], out.first())
        assertEquals(pts[5], out.last())
    }

    @Test fun apply_rangeInsideARecordingGap_keepsOnlyWhatActuallyExists() {
        // Samples at 0 s and 60 s, nothing between: a dropped connection.
        val pts = listOf(
            points(1).first(),
            points(1).first().copy(date = "2026-08-09 12:01:00.000"),
        )
        val e = TripTrim.elapsedOffsets(pts)
        assertEquals(0, TripTrim.apply(pts, e, 10_000L..20_000L).size)
        assertEquals(1, TripTrim.apply(pts, e, 50_000L..70_000L).size)
    }

    @Test fun apply_withMismatchedElapsedArray_returnsTheInputUntouched() {
        val pts = points(5)
        assertSame(pts, TripTrim.apply(pts, LongArray(2), 0L..1_000L))
    }

    @Test fun countInRange_matchesWhatApplyWouldSelect() {
        val pts = points(10)
        val e = TripTrim.elapsedOffsets(pts)
        assertEquals(3, TripTrim.countInRange(e, 3_000L..5_000L))
        assertEquals(0, TripTrim.countInRange(e, 20_000L..30_000L))
    }

    @Test fun minPoints_isTwo_becauseAChartNeedsALine() {
        assertEquals(2, TripTrim.MIN_POINTS)
    }

    @Test fun elapsedOffsets_handleTheEuropeanDottedFormatToo() {
        // The bound-parser fast path must resolve whichever format the file
        // uses, not just the ISO one the other tests exercise.
        val pts = listOf(
            points(1).first().copy(date = "09.08.2026 12:00:00.000"),
            points(1).first().copy(date = "09.08.2026 12:00:02.000"),
        )
        assertArrayEquals(longArrayOf(0L, 2_000L), TripTrim.elapsedOffsets(pts))
    }

    @Test fun elapsedOffsets_whenNoRowParses_areAllZero() {
        val pts = points(3).map { it.copy(date = "nonsense") }
        assertArrayEquals(longArrayOf(0L, 0L, 0L), TripTrim.elapsedOffsets(pts))
    }

    @Test fun elapsedOffsets_secondsOnlyFormat() {
        val pts = listOf(
            points(1).first().copy(date = "2026-08-09 12:00:00"),
            points(1).first().copy(date = "2026-08-09 12:00:05"),
        )
        assertArrayEquals(longArrayOf(0L, 5_000L), TripTrim.elapsedOffsets(pts))
    }
}
