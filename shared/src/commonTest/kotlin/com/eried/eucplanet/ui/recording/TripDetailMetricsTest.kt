package com.eried.eucplanet.ui.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The trip viewer's tile numbers and customizer order, the same on both phones. */
class TripDetailMetricsTest {

    private fun point(battery: Int, voltage: Float = 84f, current: Float = Float.NaN, pwm: Float = Float.NaN) =
        TripDataPoint(
            date = "2026-11-08 10:00:00", speed = 20f, voltage = voltage, temperature = 30f,
            battery = battery, altitude = 0f, latitude = 0.0, longitude = 0.0, totalMileage = 0f,
            current = current, pwm = pwm,
        )

    @Test
    fun anEmptySavedOrderIsTheDefault() {
        assertEquals(listOf("a", "b", "c"), applyOrder(listOf("a", "b", "c"), emptyList()))
    }

    @Test
    fun theRidersOrderLeadsAndNewKeysFollow() {
        // "x" was removed in a later version; "d" is new since the order was saved.
        assertEquals(listOf("c", "a", "b", "d"), applyOrder(listOf("a", "b", "c", "d"), listOf("c", "x", "a", "b")))
    }

    @Test
    fun chartKeysAreUnique() {
        assertEquals(CHART_KEYS_DEFAULT.size, CHART_KEYS_DEFAULT.toSet().size)
    }

    @Test
    fun batteryStartEndAndDrainedFollowTheRide() {
        // 90 down to 80, a charge back to 85, then down to 70.
        val pts = listOf(90, 85, 80, 85, 78, 70).map { point(it) }
        val s = computeBatteryStats(pts)
        assertEquals(90, s.batteryMax)
        assertEquals(70, s.batteryMin)
        assertEquals(20, s.batteryConsumption)
        assertEquals(90, s.batteryStart)
        assertEquals(70, s.batteryEnd)
        assertEquals(25, s.batteryDrained)
    }

    @Test
    fun peaksSkipMissingColumns() {
        val pts = listOf(
            point(80, voltage = 80f, current = 10f, pwm = 40f),
            point(79, voltage = 80f, current = 25f, pwm = Float.NaN),
            point(78, voltage = 80f, current = Float.NaN, pwm = 55f),
        )
        val s = computeBatteryStats(pts)
        assertEquals(55f, s.maxPwm)
        assertEquals(25f, s.maxCurrent)
        assertEquals(2000f, s.maxPower)
        assertTrue(s.maxTorque.isNaN())
    }

    @Test
    fun aTripWithNoValidReadingsStillAnswers() {
        val s = computeBatteryStats(listOf(point(0, voltage = 0f), point(0, voltage = 0f)))
        assertEquals(0, s.batteryMax)
        assertEquals(0, s.batteryDrained)
        assertTrue(computeBatteryStats(emptyList()).maxPwm.isNaN())
    }
}
