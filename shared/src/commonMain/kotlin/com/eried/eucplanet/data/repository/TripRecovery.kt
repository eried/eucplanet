package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.util.TripCsv

// A recording the app was killed in the middle of (force-close, crash, the
// iPhone's app switcher) leaves its row with no end time and a CSV holding
// every row flushed before the kill. Both phones run the same sweep at start:
// finalize the row from its file, or drop it, and the file, when nothing
// usable was captured, so it never sits in the trip list as a 0 s trip.

/**
 * Header-driven extraction of (date, lat, lon, mileage) rows from a trip CSV.
 * Top-level so the orphan-adopt and killed-recording finalize share it and it is
 * unit-testable without Room / files.
 */
fun parseTripQuads(text: String): List<TripCsv.Quad> {
    val lines = text.split('\n')
    if (lines.size < 2) return emptyList()
    // lowercase() is locale-invariant in Kotlin, as Locale.US was here.
    val h = lines[0].split(',').map { it.trim().lowercase() }
    val iDate = TripCsv.Columns.date(h); if (iDate < 0) return emptyList()
    val iLat = TripCsv.Columns.latitude(h); val iLon = TripCsv.Columns.longitude(h)
    val iMile = TripCsv.Columns.mileage(h)
    return lines.asSequence().drop(1).mapNotNull { ln ->
        val c = ln.split(',')
        if (iDate >= c.size || c[iDate].isBlank()) return@mapNotNull null
        fun d(i: Int) = if (i in 0 until c.size) c[i].trim().toDoubleOrNull() ?: 0.0 else 0.0
        fun fl(i: Int) = if (i in 0 until c.size) c[i].trim().toFloatOrNull() ?: 0f else 0f
        TripCsv.Quad(c[iDate].trim(), d(iLat), d(iLon), fl(iMile))
    }.toList()
}

/**
 * The finalized copy of a killed recording, reconstructed from its CSV metrics,
 * or null when nothing usable was captured (empty / header-only / no valid rows)
 * so the caller drops the zombie row. Always a copy of the SAME row (id /
 * fileName / uuid preserved): the finalize path UPDATEs, never inserts, so
 * recovery can never produce a duplicate. Pure, for unit tests.
 */
fun finalizedTripOrNull(trip: TripRecord, metrics: TripCsv.Metrics?, rows: Int): TripRecord? {
    if (metrics == null || !metrics.valid || rows <= 0) return null
    return trip.copy(
        startTime = metrics.startMs,
        endTime = metrics.endMs,
        distanceKm = metrics.distanceKm,
        sampleCount = rows,
    )
}

/** Data rows in a trip CSV: every line after the header. */
fun tripCsvRowCount(text: String): Int = (text.count { it == '\n' } - 1).coerceAtLeast(0)

/**
 * The start-up sweep over [unfinished] (the rows with no end time): each one
 * is finalized from its CSV through [keep], or handed to [drop] (which removes
 * the row and its file) when the file holds nothing usable. [liveTripId], the
 * recording running now, is never touched. Idempotent: a finalized row has an
 * end time, so the next sweep does not see it.
 */
suspend fun recoverKilledRecordings(
    unfinished: List<TripRecord>,
    liveTripId: Long?,
    readCsv: (TripRecord) -> String?,
    keep: suspend (finalized: TripRecord, rows: Int) -> Unit,
    drop: suspend (TripRecord) -> Unit,
) {
    for (trip in unfinished) {
        if (trip.id == liveTripId) continue
        val text = readCsv(trip)
        val rows = text?.let(::tripCsvRowCount) ?: 0
        val metrics = text?.let { TripCsv.metricsFrom(parseTripQuads(it)) }
        val finalized = finalizedTripOrNull(trip, metrics, rows)
        if (finalized != null) keep(finalized, rows) else drop(trip)
    }
}
