package com.eried.eucplanet.util

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Shared, format-tolerant helpers for reading trip CSVs.
 *
 * Trip files reach us from several sources with different conventions:
 *  - EUC Planet / DarknessBot: `25.06.2026 15:30:02.741` (dd.MM.yyyy, ms)
 *  - EUC World export:         `2024-06-29T19:55:32.000000` (ISO, micros)
 *  - older variants:          `yyyy-MM-dd HH:mm:ss`
 *
 * Duration and distance used to be derived three different ways (live-record
 * finalize, import, sync), so a fix in one path never reached the others.
 * Centralise the two fiddly bits here -- timestamp parsing and great-circle
 * distance -- so every surface computes trip metrics identically and a foreign
 * file never shows 0:00 / 0.0 just because its date format wasn't recognised.
 */
object TripCsv {

    // Most-specific first. We normalise the fractional second to 3 digits
    // before parsing (see [parseDate]), so the millisecond variants cover
    // both DarknessBot (.SSS) and EUC World (.SSSSSS, truncated).
    private val FORMATS = listOf(
        "dd.MM.yyyy HH:mm:ss.SSS",
        "dd.MM.yyyy HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss",
    ).map { SimpleDateFormat(it, Locale.US).apply { isLenient = false } }

    /**
     * Parse a trip timestamp to epoch-millis, or null if no known format
     * matches. Sub-second precision beyond milliseconds (EUC World writes 6
     * digits) is truncated to 3 so `SSS` parses it instead of reading
     * "000000" as 0 ms past a wrong second.
     */
    fun parseDate(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val normalised = normaliseFraction(s)
        for (fmt in FORMATS) {
            try {
                return fmt.parse(normalised)?.time
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    /** Clamp the fractional-seconds part to at most 3 digits. Handles the
     *  ISO microsecond form and the European ms form; leaves dotted dates
     *  (dd.MM.yyyy) untouched because their dots are before the time. */
    private fun normaliseFraction(s: String): String {
        val dot = s.lastIndexOf('.')
        if (dot < 0) return s
        // Only treat it as a fractional second if everything after the dot is
        // digits (a dd.MM.yyyy date has no trailing all-digit run after the
        // final dot once a time is present, but guard anyway).
        val frac = s.substring(dot + 1)
        if (frac.isEmpty() || !frac.all { it.isDigit() }) return s
        // A European date like "25.06.2026 15:30:02" has its last dot inside
        // the date only when there's no ms; in that case `frac` would start
        // with "2026 15:30:02" which fails the all-digit test above, so we're
        // safe to truncate here.
        return if (frac.length <= 3) s else s.substring(0, dot + 4)
    }

    /** Great-circle distance in metres between two lat/lon points. */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).let { it * it }
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Metrics derived from a trip's samples. [valid] is false when we had no
     *  usable timestamps, so callers can fall back to whatever was stored. */
    data class Metrics(
        val startMs: Long,
        val endMs: Long,
        val distanceKm: Float,
        val valid: Boolean,
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    /**
     * Derive duration + distance from per-sample (dateString, lat, lon,
     * totalMileage) tuples. Distance prefers the wheel odometer DELTA
     * (max - min of "Total mileage", never the raw lifetime reading), falling
     * back to the GPS great-circle (bounded per-step so a jumped fix can't
     * inflate it) only when the wheel never reported a moving odometer, then 0.
     * This is the single definition every surface should use, and it matches
     * live-recording finalize, which also prefers the wheel counter.
     */
    fun metricsFrom(
        rows: List<Quad>,
    ): Metrics {
        var startMs = Long.MAX_VALUE
        var endMs = Long.MIN_VALUE
        var gpsMeters = 0.0
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        var minMileage = Float.MAX_VALUE
        var maxMileage = -Float.MAX_VALUE

        for (row in rows) {
            parseDate(row.date)?.let { t ->
                if (t < startMs) startMs = t
                if (t > endMs) endMs = t
            }
            val lat = row.lat
            val lon = row.lon
            if (lat != 0.0 && lon != 0.0 && !lat.isNaN() && !lon.isNaN()) {
                if (!lastLat.isNaN() && !lastLon.isNaN()) {
                    val d = haversineMeters(lastLat, lastLon, lat, lon)
                    if (d in 0.5..200.0) gpsMeters += d
                }
                lastLat = lat
                lastLon = lon
            }
            val m = row.mileage
            if (m > 0f) {
                if (m < minMileage) minMileage = m
                if (m > maxMileage) maxMileage = m
            }
        }

        val hasTime = startMs != Long.MAX_VALUE && endMs != Long.MIN_VALUE
        val distanceKm = when {
            maxMileage > minMileage && minMileage != Float.MAX_VALUE -> maxMileage - minMileage
            gpsMeters > 0.0 -> (gpsMeters / 1000.0).toFloat()
            else -> 0f
        }
        return Metrics(
            startMs = if (hasTime) startMs else 0L,
            endMs = if (hasTime) endMs else 0L,
            distanceKm = distanceKm,
            valid = hasTime,
        )
    }

    /** One sample's fields relevant to trip metrics. */
    data class Quad(val date: String, val lat: Double, val lon: Double, val mileage: Float)
}
