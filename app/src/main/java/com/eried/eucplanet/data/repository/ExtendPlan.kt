package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.TripRecord

/**
 * Which trips an Extend may reach, and what it would actually merge.
 *
 * The rules used to live inside the Compose dialog, where the only way to test
 * them was to write the same arithmetic a second time in the test and hope the
 * two stayed in step. They are here instead: plain Kotlin over a list of trips,
 * so the dialog and the tests are looking at the same code.
 *
 * Two kinds of trip are kept out of an extend, because merging either writes
 * the same samples into the result twice - doubled distance, a duration longer
 * than the ride took:
 *
 *  - one that overlaps the anchor in time. It is a piece already inside it, or,
 *    when the rider is standing on a piece, the combined trip that contains it.
 *  - one that sits entirely inside some other trip. A range reaching past an
 *    unrelated combined trip would otherwise sweep up both it and the pieces it
 *    was made from, and none of those touch the anchor.
 *
 * They are skipped rather than treated as walls: the ride before an
 * already-merged piece is still a fair thing to extend into.
 */
object ExtendPlan {

    /** How far either end reaches, counted in trips the rider can actually pick. */
    const val REACH = 8

    /** What the dialog needs: the two ends' options and the merge they describe. */
    data class Reach(
        /** Indexes into the ordered list, earlier than the anchor. */
        val back: List<Int>,
        /** Indexes into the ordered list, later than the anchor. */
        val forward: List<Int>,
    ) {
        /** Nothing to extend into: every neighbour is already part of this trip. */
        val isDeadEnd: Boolean get() = back.isEmpty() && forward.isEmpty()
    }

    /** The trips a chosen span would merge, and how many it stepped over. */
    data class Merge(
        val trips: List<TripRecord>,
        val skipped: Int,
    )

    private fun endOf(t: TripRecord): Long = t.endTime ?: t.startTime

    /** True when [t] shares any time with [anchor]. Touching ends do not count:
     *  a ride that stops at 9:00:00 and one that starts there are neighbours,
     *  not the same ride. */
    fun overlaps(anchor: TripRecord, t: TripRecord): Boolean =
        t.id != anchor.id && t.startTime < endOf(anchor) && endOf(t) > anchor.startTime

    /** True when some other trip in [all] covers [t] completely and is strictly
     *  bigger. Strictly, so two trips of the same span do not each swallow the
     *  other and both disappear. */
    fun isInsideAnother(t: TripRecord, all: List<TripRecord>): Boolean {
        val tEnd = endOf(t)
        return all.any { o ->
            o.id != t.id && o.startTime <= t.startTime && endOf(o) >= tEnd &&
                (o.startTime < t.startTime || endOf(o) > tEnd)
        }
    }

    /** Trips that must be left out of an extend anchored on [anchor]. */
    fun excluded(anchor: TripRecord, t: TripRecord, all: List<TripRecord>): Boolean =
        overlaps(anchor, t) || isInsideAnother(t, all)

    /**
     * The pickable trips either side of [anchor] in [ordered] (chronological).
     *
     * [reach] counts pickable trips, not positions, so a combined trip's pieces
     * cannot eat the budget and hide the rides beyond them.
     */
    fun reach(ordered: List<TripRecord>, anchor: TripRecord, reach: Int = REACH): Reach {
        val at = ordered.indexOfFirst { it.id == anchor.id }
        if (at < 0) return Reach(emptyList(), emptyList())
        val back = buildList {
            var i = at - 1
            while (i >= 0 && size < reach) {
                if (!excluded(anchor, ordered[i], ordered)) add(i)
                i--
            }
        }.reversed()
        val forward = buildList {
            var i = at + 1
            while (i <= ordered.lastIndex && size < reach) {
                if (!excluded(anchor, ordered[i], ordered)) add(i)
                i++
            }
        }
        return Reach(back, forward)
    }

    /**
     * What merging from [fromIdx] to [toIdx] would produce.
     *
     * The span is continuous - everything between the two ends comes along,
     * because a merge that skipped a ride in the middle would describe a
     * journey nobody made - minus the trips this extend must leave alone.
     */
    fun merge(ordered: List<TripRecord>, anchor: TripRecord, fromIdx: Int, toIdx: Int): Merge {
        val at = ordered.indexOfFirst { it.id == anchor.id }
        if (at < 0) return Merge(emptyList(), 0)
        val lo = minOf(fromIdx, at).coerceIn(0, ordered.lastIndex)
        val hi = maxOf(toIdx, at).coerceIn(0, ordered.lastIndex)
        val span = ordered.subList(lo, hi + 1)
        val kept = span.filter { it.id == anchor.id || !excluded(anchor, it, ordered) }
        return Merge(kept, span.size - kept.size)
    }
}
