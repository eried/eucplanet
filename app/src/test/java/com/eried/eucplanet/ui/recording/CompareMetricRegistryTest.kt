package com.eried.eucplanet.ui.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every metric a comparison can draw names a real Trip Details chart. */
class CompareMetricRegistryTest {

    private fun point(speed: Float = 0f, current: Float = Float.NaN) = TripDataPoint(
        date = "2026-10-20 18:22:07", speed = speed, voltage = 120f, temperature = 30f,
        battery = 80, altitude = 0f, latitude = 0.0, longitude = 0.0, totalMileage = 0f,
        current = current,
    )

    @Test fun `compare keys are chart keys`() {
        val missing = CompareMetric.entries.map { it.key }.filter { it !in CHART_KEYS_DEFAULT }
        assertTrue("Not a chart: $missing", missing.isEmpty())
    }

    @Test fun `keys are unique`() {
        val keys = CompareMetric.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun `an empty or all-zero column is not shared`() {
        val a = listOf(point(speed = 10f, current = 5f), point(speed = 12f, current = 6f))
        val b = listOf(point(speed = 8f), point(speed = 9f))
        val shared = CompareMetric.shared(a, b)
        assertTrue(CompareMetric.SPEED in shared)
        assertTrue(CompareMetric.CURRENT !in shared)
        assertTrue(CompareMetric.ALTITUDE !in shared)
    }
}
