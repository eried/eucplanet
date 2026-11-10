package com.eried.eucplanet.util

import com.eried.eucplanet.platform.localTimeParts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The sun schedule the auto-lights follow, now in common code so the iPhone
 * runs the same numbers. It used java.util.Calendar for the day and its
 * midnight; these pin the platform clock giving the same answers.
 */
class SunCalculatorTest {

    /** 2026-06-21 12:00 UTC, midsummer. */
    private val midsummer = 1_782_043_200_000L

    @Test fun midnightIsTheStartOfTheLocalDay() {
        val m = SunCalculator.localMidnight(midsummer)
        val t = localTimeParts(m)
        assertEquals(0, t.hour)
        assertEquals(0, t.minute)
        assertEquals(0, t.second)
        assertEquals(0, t.millis)
        val day = localTimeParts(midsummer)
        assertEquals(day.day, t.day)
        assertTrue(midsummer - m in 0 until 25 * 3_600_000L)
    }

    @Test fun theArcticHasMidnightSunInJune() {
        assertIs<SunCalculator.SunResult.MidnightSun>(SunCalculator.calculateState(78.2, 15.6, midsummer))
    }

    @Test fun theAntarcticHasPolarNightInJune() {
        assertIs<SunCalculator.SunResult.PolarNight>(SunCalculator.calculateState(-78.0, 166.0, midsummer))
    }

    @Test fun theEquatorHasTwelveHours() {
        val r = SunCalculator.calculateState(0.0, 0.0, midsummer)
        assertIs<SunCalculator.SunResult.Normal>(r)
        val hours = (r.sunsetMillis - r.sunriseMillis) / 3_600_000.0
        assertTrue(hours in 11.8..12.4, "day length $hours h")
        assertTrue(r.sunriseMillis < r.sunsetMillis)
    }
}
