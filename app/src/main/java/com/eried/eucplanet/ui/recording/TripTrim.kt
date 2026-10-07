package com.eried.eucplanet.ui.recording

import com.eried.eucplanet.util.TripCsv

/**
 * Trimming Trip Details to a section of a ride.
 *
 * The trim is expressed in elapsed milliseconds from the ride's first sample,
 * not as row indices, so it survives a re-read of the CSV and matches what the
 * rider typed into the trim dialog.
 *
 * Kept free of Compose and Android so it can be unit-tested directly.
 */
object TripTrim {

    /** A selection below this many samples has no line to draw, so it is rejected. */
    const val MIN_POINTS = 2

    /**
     * Elapsed millis from the first parseable timestamp, one entry per point.
     *
     * Parsed once per trip and reused: [TripCsv.parseDate] goes through
     * SimpleDateFormat, and a long ride is tens of thousands of rows, so doing
     * this inside the filter would make every trim change visibly slow.
     *
     * A row whose timestamp does not parse falls back to offset 0, matching how
     * [TripCsv.metricsFrom] skips unparseable dates rather than failing.
     */
    fun elapsedOffsets(points: List<TripDataPoint>): LongArray {
        if (points.isEmpty()) return LongArray(0)
        // Resolve the file's timestamp format once. TripCsv.parseDate probes
        // every known format and swallows a thrown exception per miss, which
        // across tens of thousands of rows costs far more than the parsing.
        val parse = points.firstNotNullOfOrNull { TripCsv.parserFor(it.date) }
            ?: return LongArray(points.size)
        val t0 = points.firstNotNullOfOrNull { parse(it.date) } ?: 0L
        return LongArray(points.size) { i -> parse(points[i].date)?.minus(t0) ?: 0L }
    }

    /**
     * The points inside [range], or [points] itself when [range] is null.
     *
     * Returns the original instance for the untrimmed case so callers that key
     * a `remember` on the result do no extra work on a full trip.
     */
    fun apply(
        points: List<TripDataPoint>,
        elapsed: LongArray,
        range: LongRange?,
    ): List<TripDataPoint> {
        if (range == null || elapsed.size != points.size) return points
        return points.filterIndexed { i, _ -> elapsed[i] in range }
    }

    /**
     * Index into the full point list of the first sample [apply] keeps.
     *
     * Trimming re-bases the chart's sample indices, so anything holding an
     * index across a trim change - the scrub cursor above all - has to be
     * re-expressed against the new list, or it quietly starts pointing at a
     * different moment of the ride.
     */
    fun startIndex(elapsed: LongArray, range: LongRange?): Int {
        if (range == null) return 0
        return elapsed.indexOfFirst { it in range }.coerceAtLeast(0)
    }

    /** How many samples fall inside [range]. Drives the dialog's Apply gate. */
    fun countInRange(elapsed: LongArray, range: LongRange): Int =
        elapsed.count { it in range }
}
