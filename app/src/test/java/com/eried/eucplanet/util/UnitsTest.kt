package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Conversion + unit-selection tests. These guard the "miles and km must be
 * accurate" invariant: known reference values, and round-trips (display ->
 * internal -> display) that the alarm thresholds and sliders rely on.
 */
class UnitsTest {

    private val eps = 0.01f

    // --- speed ---

    @Test fun speedKnownValues() {
        assertEquals(100f, Units.speed(100f, "kmh"), eps)
        assertEquals(62.1371f, Units.speed(100f, "mph"), eps)
        assertEquals(10f, Units.speed(36f, "ms"), eps)        // 36 km/h = 10 m/s
        assertEquals(53.996f, Units.speed(100f, "kn"), eps)   // 100 km/h in knots
        assertEquals(42f, Units.speed(42f, "garbage"), eps)   // unknown -> passthrough km/h
    }

    @Test fun speedRoundTripsThroughDisplayUnits() {
        for (unit in listOf("kmh", "mph", "ms", "kn")) {
            val display = Units.speed(73.4f, unit)
            assertEquals("speed round-trip $unit", 73.4f, Units.speedToKmh(display, unit), eps)
        }
    }

    @Test fun speedToKmhKnownValues() {
        assertEquals(100f, Units.speedToKmh(62.1371f, "mph"), eps)
        assertEquals(36f, Units.speedToKmh(10f, "ms"), eps)
    }

    // --- distance ---

    @Test fun distanceKnownValues() {
        assertEquals(1f, Units.distance(1f, "km"), eps)
        assertEquals(0.621371f, Units.distance(1f, "mi"), eps)
        assertEquals(1000f, Units.distance(1f, "m"), eps)
        assertEquals(3280.84f, Units.distance(1f, "ft"), 0.1f)
        assertEquals(0.5f, Units.distance(5f, "mil"), eps)    // Scandinavian mil = 10 km
    }

    @Test fun distanceRoundTripsThroughDisplayUnits() {
        for (unit in listOf("km", "mi", "m", "ft", "mil")) {
            val display = Units.distance(12.34f, unit)
            assertEquals("distance round-trip $unit", 12.34f, Units.distanceToKm(display, unit), eps)
        }
    }

    // --- temperature ---

    @Test fun temperatureKnownValues() {
        assertEquals(32f, Units.temperature(0f, "F"), eps)
        assertEquals(212f, Units.temperature(100f, "F"), eps)
        assertEquals(273.15f, Units.temperature(0f, "K"), eps)
        assertEquals(25f, Units.temperature(25f, "C"), eps)
    }

    @Test fun temperatureRoundTrips() {
        for (unit in listOf("C", "F", "K")) {
            val display = Units.temperature(41.5f, unit)
            assertEquals("temp round-trip $unit", 41.5f, Units.temperatureToCelsius(display, unit), eps)
        }
    }

    // --- effective unit selection (legacy fallback) ---

    @Test fun blankUnitsFallBackToImperialFlag() {
        val metric = AppSettings(imperialUnits = false)   // unitSpeed/etc default ""
        assertEquals("kmh", Units.effectiveSpeedUnit(metric))
        assertEquals("km", Units.effectiveDistanceUnit(metric))
        assertEquals("C", Units.effectiveTempUnit(metric))

        val imperial = AppSettings(imperialUnits = true)
        assertEquals("mph", Units.effectiveSpeedUnit(imperial))
        assertEquals("mi", Units.effectiveDistanceUnit(imperial))
        assertEquals("F", Units.effectiveTempUnit(imperial))
    }

    @Test fun explicitUnitWinsOverLegacyFlag() {
        // Even with the legacy imperial flag set, an explicit per-unit choice wins.
        val s = AppSettings(imperialUnits = true, unitSpeed = "ms", unitDistance = "km", unitTemp = "K")
        assertEquals("ms", Units.effectiveSpeedUnit(s))
        assertEquals("km", Units.effectiveDistanceUnit(s))
        assertEquals("K", Units.effectiveTempUnit(s))
    }

    // --- system classification ---

    @Test fun unitSystemClassification() {
        assertEquals(Units.UnitSystem.METRIC, Units.unitSystemOf(AppSettings(imperialUnits = false)))
        assertEquals(Units.UnitSystem.IMPERIAL, Units.unitSystemOf(AppSettings(imperialUnits = true)))
        // A mix (m/s speed) is CUSTOM, not metric.
        assertEquals(
            Units.UnitSystem.CUSTOM,
            Units.unitSystemOf(AppSettings(unitSpeed = "ms", unitDistance = "km", unitTemp = "C"))
        )
    }

    @Test fun distanceAndTempUnitSymbols() {
        assertEquals("km", Units.distanceUnit("km"))
        assertEquals("mi", Units.distanceUnit("mi"))
        assertEquals("°F", Units.tempUnit("F"))
        assertEquals("°C", Units.tempUnit("C"))
        assertEquals("K", Units.tempUnit("K"))
    }
}
