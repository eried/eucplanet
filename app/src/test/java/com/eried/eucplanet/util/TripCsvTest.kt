package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the trip-metrics fix: duration + distance must come out right for every
 * trip source we ingest (EUC Planet / DarknessBot, EUC World ISO, older
 * variants), and a huge lifetime-odometer column must never be mistaken for the
 * trip's length -- the bug that showed "0 length" / absurd length in the trip
 * explorer.
 */
class TripCsvTest {

    @Test
    fun parsesDarknessBotMillisFormat() {
        // The exact stamp from the reported broken trip (trip_20260625_153002.csv).
        val t = TripCsv.parseDate("25.06.2026 15:30:02.741")
        assertTrue("dd.MM.yyyy HH:mm:ss.SSS must parse", t != null && t > 0)
    }

    @Test
    fun parsesEucWorldIsoMicrosFormat() {
        // EUC World writes 6 fractional digits; we truncate to ms before parsing.
        val t = TripCsv.parseDate("2024-06-29T19:55:32.123456")
        assertTrue("ISO micros must parse (truncated to ms)", t != null && t > 0)
    }

    @Test
    fun parsesPlainIsoAndSpaceFormats() {
        assertTrue(TripCsv.parseDate("2024-06-29T19:55:32") != null)
        assertTrue(TripCsv.parseDate("2024-06-29 19:55:32") != null)
        assertTrue(TripCsv.parseDate("25.06.2026 15:30:02") != null)
    }

    @Test
    fun rejectsGarbageDate() {
        assertEquals(null, TripCsv.parseDate("not a date"))
        assertEquals(null, TripCsv.parseDate(""))
        assertEquals(null, TripCsv.parseDate(null))
    }

    @Test
    fun durationIsLastMinusFirstTimestamp() {
        val rows = listOf(
            TripCsv.Quad("25.06.2026 15:30:00.000", 0.0, 0.0, 0f),
            TripCsv.Quad("25.06.2026 15:30:30.000", 0.0, 0.0, 0f),
            TripCsv.Quad("25.06.2026 15:32:00.000", 0.0, 0.0, 0f),
        )
        val m = TripCsv.metricsFrom(rows)
        assertTrue(m.valid)
        assertEquals(120_000L, m.durationMs) // 2 minutes
    }

    @Test
    fun durationIgnoresRowOrderAndUsesMinMax() {
        // Out-of-order rows still yield first..last span.
        val rows = listOf(
            TripCsv.Quad("25.06.2026 15:32:00.000", 0.0, 0.0, 0f),
            TripCsv.Quad("25.06.2026 15:30:00.000", 0.0, 0.0, 0f),
        )
        val m = TripCsv.metricsFrom(rows)
        assertEquals(120_000L, m.durationMs)
    }

    @Test
    fun distanceUsesGpsGreatCircleWhenNoWheelOdometer() {
        // GPS moves but the odometer column is a flat lifetime value (delta 0),
        // e.g. a wheel that doesn't expose trip distance -> fall back to GPS.
        val rows = listOf(
            TripCsv.Quad("25.06.2026 15:30:00.000", 39.0000, -74.0, 5033.0f),
            TripCsv.Quad("25.06.2026 15:30:01.000", 39.0010, -74.0, 5033.0f),
            TripCsv.Quad("25.06.2026 15:30:02.000", 39.0020, -74.0, 5033.0f),
        )
        val m = TripCsv.metricsFrom(rows)
        // ~0.222 km from GPS, NOT the 5033 odometer reading.
        assertTrue("expected ~0.22 km, got ${m.distanceKm}", m.distanceKm in 0.18f..0.27f)
    }

    @Test
    fun distancePrefersWheelOdometerDeltaOverGps() {
        // Both signals present: GPS moves ~0.11 km but the wheel odometer climbs
        // 5033.0 -> 5034.0 (1.0 km). The wheel odometer wins.
        val rows = listOf(
            TripCsv.Quad("25.06.2026 15:30:00.000", 39.0000, -74.0, 5033.0f),
            TripCsv.Quad("25.06.2026 15:30:01.000", 39.0010, -74.0, 5034.0f),
        )
        val m = TripCsv.metricsFrom(rows)
        assertEquals(1.0f, m.distanceKm, 0.001f)
    }

    @Test
    fun distanceFallsBackToMileageDeltaNotRawOdometer() {
        // No usable GPS, lifetime odometer climbs 5033.0 -> 5033.4 over the trip.
        // The trip length is the DELTA (0.4 km), never the 5033 raw reading.
        val rows = listOf(
            TripCsv.Quad("25.06.2026 15:30:00.000", 0.0, 0.0, 5033.0f),
            TripCsv.Quad("25.06.2026 15:30:30.000", 0.0, 0.0, 5033.2f),
            TripCsv.Quad("25.06.2026 15:31:00.000", 0.0, 0.0, 5033.4f),
        )
        val m = TripCsv.metricsFrom(rows)
        assertEquals(0.4f, m.distanceKm, 0.001f)
    }

    @Test
    fun invalidWhenNoTimestampsParse() {
        val rows = listOf(
            TripCsv.Quad("garbage", 0.0, 0.0, 0f),
            TripCsv.Quad("also bad", 0.0, 0.0, 0f),
        )
        val m = TripCsv.metricsFrom(rows)
        assertFalse(m.valid)
        assertEquals(0L, m.durationMs)
    }

    @Test
    fun jumpedGpsFixDoesNotInflateDistance() {
        // A single wild fix > 200 m away is rejected per-step; only the sane
        // ~111 m step counts.
        val rows = listOf(
            TripCsv.Quad("25.06.2026 15:30:00.000", 39.0000, -74.0, 0f),
            TripCsv.Quad("25.06.2026 15:30:01.000", 39.0010, -74.0, 0f),
            TripCsv.Quad("25.06.2026 15:30:02.000", 45.0000, -74.0, 0f), // teleport, ignored
        )
        val m = TripCsv.metricsFrom(rows)
        assertTrue("only the ~0.11 km step should count, got ${m.distanceKm}", m.distanceKm in 0.08f..0.15f)
    }
}
