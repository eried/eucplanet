package com.eried.eucplanet.util

import com.eried.eucplanet.data.AutoLightsEngine
import com.eried.eucplanet.location.GpsFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SunCalculatorTest {
    // Fixed June (northern summer) date; base millis is arbitrary (sunrise/sunset
    // share it, so the ordering check is base-independent).
    private val june = DateInfo(year = 2026, month = 6, day = 21, tzOffsetHours = 0.0, localMidnightMillis = 1_750_464_000_000L)

    @Test fun midnightSunAndPolarNight() {
        assertEquals(SunCalculator.SunResult.MidnightSun, SunCalculator.calculateState(78.0, 15.0, june))   // high north, summer
        assertEquals(SunCalculator.SunResult.PolarNight, SunCalculator.calculateState(-78.0, 15.0, june))   // high south, winter
    }

    @Test fun normalDaySunriseBeforeSunset() {
        val r = SunCalculator.calculateState(40.0, -74.0, june) // New York-ish
        assertTrue(r is SunCalculator.SunResult.Normal, "expected Normal, got $r")
        r as SunCalculator.SunResult.Normal
        assertTrue(r.sunriseMillis < r.sunsetMillis, "sunrise should precede sunset")
        // Both fall within the 24h window from local midnight.
        assertTrue(r.sunriseMillis in june.localMidnightMillis..(june.localMidnightMillis + 86_400_000L))
        assertTrue(r.sunsetMillis in june.localMidnightMillis..(june.localMidnightMillis + 86_400_000L))
    }

    @Test fun engineThrottles() {
        val e = AutoLightsEngine()
        val fix = GpsFix(40.0, -74.0, 0f, -1f, Float.NaN, 0L)
        val now = 2_000_000_000_000L
        assertNotNull(e.desiredState(fix, 30, 30, now))        // first real-time tick decides
        assertNull(e.desiredState(fix, 30, 30, now + 1_000L))  // 1s later → throttled (60s window)
    }
}
