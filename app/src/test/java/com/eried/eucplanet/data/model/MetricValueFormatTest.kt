package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-metric formatter, now shared between the tile a rider reads and the
 * answer a rider hears.
 *
 * It was private to the dashboard, so the spoken answer said "eighty point six"
 * where the tile said "80.6V". Two copies would drift, and the one that drifted
 * would be the spoken one, because nobody is looking at it to notice.
 */
class MetricValueFormatTest {

    private fun metric(
        key: String,
        raw: Float,
        speedUnit: String = "kmh",
        speedLabel: String = "km/h",
        tempUnit: String = "C",
        tempLabel: String = "°C",
        distanceUnit: String = "km",
        pressureUnit: String = "bar",
    ) = MetricValueFormat.format(
        key, raw, speedUnit, speedLabel, tempUnit, tempLabel, distanceUnit, pressureUnit,
    )

    @Test
    fun `a value carries its unit`() {
        assertEquals("80.6V", metric("VOLTAGE", 80.6f))
        assertEquals("3.5A", metric("CURRENT", 3.5f))
        assertEquals("120W", metric("MOTOR_POWER", 120f))
        assertEquals("1.5Nm", metric("TORQUE", 1.5f))
        assertEquals("51%", metric("BATTERY", 51f))
    }

    @Test
    fun `the rider's own units decide`() {
        // Same stored value, two riders, two answers. Storage is km/h and
        // Celsius; what gets spoken is whatever they set.
        assertEquals("30 km/h", metric("SPEED", 30f))
        assertTrue(metric("SPEED", 30f, speedUnit = "mph", speedLabel = "mph").endsWith("mph"))
        assertEquals("25°C", metric("MOTOR_TEMP", 25f))
        assertEquals("77°F", metric("MOTOR_TEMP", 25f, tempUnit = "F", tempLabel = "°F"))
    }

    @Test
    fun `altitude follows the distance unit`() {
        assertEquals("100m", metric("GPS_ALTITUDE", 100f))
        assertEquals("328ft", metric("GPS_ALTITUDE", 100f, distanceUnit = "mi"))
    }

    @Test
    fun `the temperature unit key is case sensitive`() {
        // Units.temperature matches "F" exactly, so a lower case tag silently
        // returns Celsius with a Fahrenheit label on it: 25 with an F after it.
        // Worth pinning, because the failure looks like a plausible reading.
        assertEquals("25°F", metric("MOTOR_TEMP", 25f, tempUnit = "f", tempLabel = "°F"))
        assertEquals("77°F", metric("MOTOR_TEMP", 25f, tempUnit = "F", tempLabel = "°F"))
    }

    @Test
    fun `an unknown key still says a number rather than crashing`() {
        // Metrics get added; this must degrade rather than throw at the moment
        // a rider asks for one.
        assertEquals("1.5", metric("SOMETHING_NEW", 1.5f))
    }

    @Test
    fun `consumption with nothing measured says so`() {
        // NaN is the rolling window admitting it has no rate yet, and a tile
        // that shows a dash must not become an answer that invents one.
        assertEquals("-", metric("WH_PER_KM", Float.NaN))
    }
}
