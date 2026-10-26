package com.eried.eucplanet.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavEtaTest {
    @Test fun noRideYetUsesSetting() =
        assertEquals(25.0, NavEta.speedKmh(25.0, null, 0.0, 50.0), 1e-9)

    @Test fun blendHalfwayAtFiveMinutes() =
        assertEquals(27.5, NavEta.speedKmh(25.0, 30.0, 300.0, 50.0), 1e-9)

    @Test fun fullyRideAverageAfterTenMinutes() =
        assertEquals(30.0, NavEta.speedKmh(25.0, 30.0, 900.0, 50.0), 1e-9)

    @Test fun cappedAtEightyPercentOfTiltback() =
        assertEquals(28.0, NavEta.speedKmh(25.0, 40.0, 900.0, 35.0), 1e-9)

    @Test fun noTiltbackSkipsCap() =
        assertEquals(40.0, NavEta.speedKmh(25.0, 40.0, 900.0, 0.0), 1e-9)

    @Test fun neverBelowFloor() =
        assertEquals(5.0, NavEta.speedKmh(25.0, 1.0, 900.0, 50.0), 1e-9)

    @Test fun etaIsDistanceOverSpeed() =
        // 10 km at 20 km/h = 30 min
        assertEquals(1_000L + 30 * 60_000L, NavEta.etaEpochMs(1_000L, 10_000.0, 20.0))

    @Test fun movingAverageCountsOnlyMovingTime() {
        val m = MovingAverage()
        m.add(0L, 20.0, 4.0)
        m.add(1_000L, 20.0, 4.0)
        m.add(2_000L, 20.0, 4.0)
        assertEquals(2.0, m.movingSeconds, 1e-9)
        assertEquals(20.0, m.avgKmh!!, 1e-9)
    }

    @Test fun stoppedTimeDoesNotCount() {
        val m = MovingAverage()
        m.add(0L, 30.0, 4.0)
        m.add(1_000L, 30.0, 4.0)
        // two minutes at a light
        var t = 1_000L
        repeat(120) { t += 1_000L; m.add(t, 0.0, 4.0) }
        m.add(t + 1_000L, 30.0, 4.0)
        assertEquals(2.0, m.movingSeconds, 1e-9)
        assertEquals(30.0, m.avgKmh!!, 1e-9)
    }

    @Test fun movingAverageSkipsGaps() {
        val m = MovingAverage()
        m.add(0L, 20.0, 4.0)
        m.add(60_000L, 20.0, 4.0) // a 60 s gap (GPS lost) counts at most 5 s
        assertEquals(5.0, m.movingSeconds, 1e-9)
    }

    @Test fun movingAverageEmptyIsNull() = assertNull(MovingAverage().avgKmh)

    @Test fun closingSpeedNeedsTwentySeconds() {
        val c = ClosingSpeed()
        c.add(0L, 1_000.0)
        c.add(10_000L, 900.0)
        assertNull(c.kmh())
    }

    @Test fun closingSpeedFromShrinkingDistance() {
        val c = ClosingSpeed()
        c.add(0L, 1_000.0)
        c.add(36_000L, 800.0) // 200 m in 36 s = 20 km/h
        assertEquals(20.0, c.kmh()!!, 1e-9)
    }

    @Test fun closingSpeedHiddenWhenMovingAway() {
        val c = ClosingSpeed()
        c.add(0L, 800.0)
        c.add(36_000L, 1_000.0)
        assertNull(c.kmh())
    }

    @Test fun closingSpeedHiddenWhenSlow() {
        val c = ClosingSpeed()
        c.add(0L, 1_000.0)
        c.add(36_000L, 990.0) // 1 km/h
        assertNull(c.kmh())
    }

    @Test fun closingSpeedForgetsOldSamples() {
        val c = ClosingSpeed()
        c.add(0L, 5_000.0)       // dropped: older than 120 s
        c.add(100_000L, 1_000.0)
        c.add(136_000L, 800.0)
        assertEquals(20.0, c.kmh()!!, 1e-9)
    }
}
