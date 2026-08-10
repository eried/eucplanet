package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.TripSplitDetector
import com.eried.eucplanet.data.repository.TripSplitDetector.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripSplitDetectorTest {

    /** Elapsed offsets one second apart. */
    private fun secs(n: Int) = LongArray(n) { it * 1_000L }

    @Test fun aSteadyRide_hasNoCuts() {
        val cuts = TripSplitDetector.detect(secs(100), List(100) { 20f })
        assertTrue(cuts.isEmpty())
    }

    @Test fun tooFewSamples_isEmptyNotACrash() {
        assertTrue(TripSplitDetector.detect(LongArray(1), listOf(10f)).isEmpty())
        assertTrue(TripSplitDetector.detect(LongArray(0), emptyList()).isEmpty())
    }

    @Test fun aLongTimeGap_proposesACut() {
        // 10 samples, then a 20 minute hole, then 10 more.
        val e = LongArray(20) { if (it < 10) it * 1_000L else 1_200_000L + it * 1_000L }
        val cuts = TripSplitDetector.detect(e, List(20) { 20f })
        assertEquals(1, cuts.size)
        assertEquals(10, cuts.first().index)
        assertEquals(Reason.TIME_GAP, cuts.first().reason)
        assertTrue(cuts.first().durationMs >= TripSplitDetector.DEFAULT_GAP_MS)
    }

    @Test fun aShortPause_isNotACut() {
        // A one minute hole is well under the five minute threshold.
        val e = LongArray(20) { if (it < 10) it * 1_000L else 60_000L + it * 1_000L }
        assertTrue(TripSplitDetector.detect(e, List(20) { 20f }).isEmpty())
    }

    @Test fun aLongStopWhileStillRecording_proposesACut() {
        // Moving, then 15 minutes stationary at 1 Hz, then moving again.
        val stopped = 900
        val speeds = List(10) { 20f } + List(stopped) { 0f } + List(10) { 20f }
        val cuts = TripSplitDetector.detect(secs(speeds.size), speeds)
        assertEquals(1, cuts.size)
        assertEquals(Reason.STOPPED, cuts.first().reason)
        // Cut lands where the rider set off again, so the stop belongs to the
        // ride before it.
        assertEquals(10 + stopped, cuts.first().index)
    }

    @Test fun aBriefTrafficLight_isNotACut() {
        val speeds = List(10) { 20f } + List(45) { 0f } + List(10) { 20f }
        assertTrue(TripSplitDetector.detect(secs(speeds.size), speeds).isEmpty())
    }

    @Test fun aStopRunningToTheEnd_isNotACut_thereIsNoRideAfterIt() {
        val speeds = List(10) { 20f } + List(900) { 0f }
        assertTrue(TripSplitDetector.detect(secs(speeds.size), speeds).isEmpty())
    }

    @Test fun aWheelChange_alwaysProposesACut() {
        val cuts = TripSplitDetector.detect(
            secs(50), List(50) { 20f }, wheelChangeIndices = setOf(25)
        )
        assertEquals(1, cuts.size)
        assertEquals(25, cuts.first().index)
        assertEquals(Reason.WHEEL_CHANGE, cuts.first().reason)
    }

    @Test fun aWheelChangeAtRowZero_isIgnored_thereIsNothingBeforeIt() {
        val cuts = TripSplitDetector.detect(
            secs(50), List(50) { 20f }, wheelChangeIndices = setOf(0)
        )
        assertTrue(cuts.isEmpty())
    }

    @Test fun whenTwoDetectorsFireOnTheSameRow_theWheelChangeWins() {
        // A long hole AND a wheel swap at the same row: one cut, labelled as the
        // swap, because that is the more meaningful reason to show a rider.
        val e = LongArray(20) { if (it < 10) it * 1_000L else 1_200_000L + it * 1_000L }
        val cuts = TripSplitDetector.detect(e, List(20) { 20f }, wheelChangeIndices = setOf(10))
        assertEquals(1, cuts.size)
        assertEquals(Reason.WHEEL_CHANGE, cuts.first().reason)
    }

    @Test fun cutsComeBackInRowOrder() {
        val e = LongArray(40) {
            when {
                it < 10 -> it * 1_000L
                it < 25 -> 1_200_000L + it * 1_000L
                else -> 2_400_000L + it * 1_000L
            }
        }
        val cuts = TripSplitDetector.detect(e, List(40) { 20f })
        assertEquals(2, cuts.size)
        assertTrue(cuts[0].index < cuts[1].index)
    }

    @Test fun thresholdsAreHonoured() {
        val e = LongArray(20) { if (it < 10) it * 1_000L else 120_000L + it * 1_000L }
        // Two minute hole: no cut at the default, one at a one minute threshold.
        assertTrue(TripSplitDetector.detect(e, List(20) { 20f }).isEmpty())
        assertEquals(1, TripSplitDetector.detect(e, List(20) { 20f }, gapMs = 60_000L).size)
    }

    @Test fun nanSpeedIsNotTreatedAsStopped() {
        val speeds = List(10) { 20f } + List(900) { Float.NaN } + List(10) { 20f }
        assertTrue(TripSplitDetector.detect(secs(speeds.size), speeds).isEmpty())
    }
}
