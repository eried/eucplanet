package com.eried.eucplanet.data.repository

/**
 * Finds the places a recording plausibly contains more than one ride.
 *
 * A single trip file often is not one journey: the rider swapped wheels, the
 * recording ran on through a coffee stop, or the phone lost GPS in a tunnel and
 * picked it up somewhere else entirely. This proposes the cuts; the rider
 * decides which are real, because only they know whether a twenty minute stop
 * was a break or a traffic light.
 *
 * Kept free of Compose and Android so it can be unit-tested directly.
 */
object TripSplitDetector {

    /** Why a cut was proposed. Shown to the rider so the choice is informed. */
    enum class Reason {
        /** A different wheel started reporting. */
        WHEEL_CHANGE,

        /** No sample for a long stretch: the recording was paused, the app was
         *  killed, or the link dropped. */
        TIME_GAP,

        /** Samples kept coming but the wheel was not moving. */
        STOPPED,
    }

    /**
     * A proposed cut BEFORE [index]: rows `0 until index` are one ride, rows
     * `index..` the next.
     */
    data class Cut(
        val index: Int,
        /** Elapsed millis from the ride's first sample, for display. */
        val atElapsedMs: Long,
        val reason: Reason,
        /** How long the gap or stop lasted. Zero for a wheel change. */
        val durationMs: Long,
    )

    /** Defaults chosen to be conservative: they should miss a marginal case
     *  rather than propose cuts nobody wants. */
    const val DEFAULT_GAP_MS = 5 * 60_000L
    const val DEFAULT_STOP_MS = 10 * 60_000L
    const val DEFAULT_MOVING_KMH = 1.5f

    /**
     * @param elapsedMs elapsed offsets, one per row, as produced by
     *   `TripTrim.elapsedOffsets`
     * @param speedKmh per-row speed
     * @param wheelChangeIndices rows where a genuinely different wheel took
     *   over, which the Extra-column walker already identifies
     * @param gapMs no sample for at least this long proposes a cut
     * @param stopMs not moving for at least this long proposes a cut
     * @param movingKmh at or below this counts as stopped
     */
    fun detect(
        elapsedMs: LongArray,
        speedKmh: List<Float>,
        wheelChangeIndices: Set<Int> = emptySet(),
        gapMs: Long = DEFAULT_GAP_MS,
        stopMs: Long = DEFAULT_STOP_MS,
        movingKmh: Float = DEFAULT_MOVING_KMH,
    ): List<Cut> {
        val n = minOf(elapsedMs.size, speedKmh.size)
        if (n < 2) return emptyList()
        val cuts = ArrayList<Cut>()

        // A wheel change is a hard boundary: two different machines cannot be
        // one ride, whatever the timing looks like.
        for (i in wheelChangeIndices.sorted()) {
            if (i in 1 until n) {
                cuts.add(Cut(i, elapsedMs[i], Reason.WHEEL_CHANGE, 0L))
            }
        }

        // Time gaps between consecutive samples.
        for (i in 1 until n) {
            val gap = elapsedMs[i] - elapsedMs[i - 1]
            if (gap >= gapMs) {
                cuts.add(Cut(i, elapsedMs[i], Reason.TIME_GAP, gap))
            }
        }

        // Stationary runs. The cut goes at the END of the run, where the rider
        // set off again, so the stop belongs to the ride that preceded it
        // rather than opening the next one with a long pause.
        var runStart = -1
        for (i in 0 until n) {
            val stopped = speedKmh[i].let { !it.isNaN() && it <= movingKmh }
            if (stopped) {
                if (runStart < 0) runStart = i
            } else {
                if (runStart >= 0) {
                    val dur = elapsedMs[i - 1] - elapsedMs[runStart]
                    if (dur >= stopMs) cuts.add(Cut(i, elapsedMs[i], Reason.STOPPED, dur))
                    runStart = -1
                }
            }
        }
        // A run reaching the end is not a cut: there is no ride after it.

        // One cut per row, wheel change winning, then the longest event. Two
        // detectors firing at the same row is the norm, not the exception: a
        // long pause usually shows up as both a gap and a stop.
        return cuts
            .groupBy { it.index }
            .map { (_, atRow) ->
                atRow.minWithOrNull(
                    compareBy({ it.reason != Reason.WHEEL_CHANGE }, { -it.durationMs })
                )!!
            }
            .sortedBy { it.index }
    }
}
