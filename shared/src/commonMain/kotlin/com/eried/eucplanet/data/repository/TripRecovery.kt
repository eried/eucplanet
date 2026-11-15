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

/**
 * The iPhone's Bluetooth relaunch: iOS ends an app in the background to free
 * memory, then starts it again, with no screen, when the wheel next talks.
 * When that happens mid-ride, the trip the app was killed in carries on in
 * the same file and row instead of being finalized and a new one started.
 * Android's process is not ended that way (its foreground service is kept),
 * and a rider who reopens the app later still gets the sweep above.
 */
object KilledTripResume {
    /** The longest the app may have been gone for its trip to carry on. */
    const val MAX_GAP_MS = 10 * 60_000L

    /** The shortest window, so a short idle stop does not rule out every relaunch. */
    const val MIN_GAP_MS = 60_000L

    /**
     * How long the app may have been gone: the auto-record idle stop when it
     * is on (a ride that would have closed by itself is not carried on),
     * kept within [MIN_GAP_MS] and [MAX_GAP_MS], else [MAX_GAP_MS].
     */
    fun maxGapMs(settings: com.eried.eucplanet.data.model.AppSettings): Long =
        if (settings.autoRecord && settings.autoRecordStartInMotion && settings.autoRecordStopIdleSeconds > 0) {
            (settings.autoRecordStopIdleSeconds * 1000L).coerceIn(MIN_GAP_MS, MAX_GAP_MS)
        } else {
            MAX_GAP_MS
        }

    /**
     * The trip to carry on: the newest of [unfinished] (rows with no end
     * time), when its file's last row, [lastRowMs], is at most [maxGapMs]
     * before [nowMs]. Only the newest is a candidate: an older open row is a
     * ride from before, left for the sweep. Null when none qualifies.
     */
    fun pick(
        unfinished: List<TripRecord>,
        lastRowMs: (TripRecord) -> Long?,
        nowMs: Long,
        maxGapMs: Long,
    ): TripRecord? {
        val newest = unfinished.maxByOrNull { it.startTime } ?: return null
        val last = lastRowMs(newest) ?: return null
        return newest.takeIf { nowMs - last in 0..maxGapMs }
    }
}
