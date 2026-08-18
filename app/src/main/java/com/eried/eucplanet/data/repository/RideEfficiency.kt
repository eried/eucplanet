package com.eried.eucplanet.data.repository

/**
 * Ride consumption and the range that follows from it.
 *
 * Consumption is a rate, so it is measured over a window rather than since
 * connect: energy and distance are both read as cumulative series and
 * differenced across the same two endpoints. That is the whole point of the
 * shape. The version this replaced divided energy-since-connect by the wheel's
 * own trip meter, two counters that reset on different events, so a rider who
 * rode before opening the app was told their wheel sipped power.
 *
 * Range then divides what is left by that rate. No wheel reports pack capacity
 * and no model table carries it, so energy per percent is learned from the ride
 * itself, only ever as good as the wheel's own battery percentage. A rider who
 * enters their pack size seeds that rate ([seedWhPerPercent]) so the estimate
 * answers from the first km instead of waiting for the pack to drop.
 *
 * Kept free of Android and of coroutines so it can be unit-tested directly.
 */
object RideEfficiency {

    /** Distance the window must cover before a rate means anything. */
    const val MIN_WINDOW_KM = 0.2f

    /** Percent the pack must drop before energy-per-percent is worth quoting. */
    const val MIN_PCT_DROP = 3

    /** Nothing on a wheel goes this far on one charge. */
    const val MAX_RANGE_KM = 400f

    /**
     * Net Wh per km across a window, or NaN when the window cannot answer.
     *
     * NaN rather than zero for both of the cases that occur in normal riding:
     * too little distance to divide by, and a stretch where regen matched or
     * beat consumption. Neither is "0 Wh/km", and a rider reading a tile has no
     * way to tell an honest zero from a missing one.
     */
    fun whPerKm(spanWh: Float, spanKm: Float): Float =
        if (spanKm >= MIN_WINDOW_KM && spanWh > 0f) spanWh / spanKm else Float.NaN

    /**
     * Energy per battery percent, learned from this ride, or NaN before the
     * pack has dropped enough for the answer to mean anything.
     *
     * [baselineWh] and [baselinePct] are the oldest readings of the session;
     * the pack dropping [MIN_PCT_DROP] is the floor because a percentage
     * rounded to whole numbers makes a one-percent drop a coin flip between
     * half and one and a half.
     */
    fun whPerPercent(netWh: Float, baselineWh: Float, pct: Int, baselinePct: Int): Float {
        if (baselinePct <= 0 || baselineWh.isNaN()) return Float.NaN
        val dropped = baselinePct - pct
        if (dropped < MIN_PCT_DROP) return Float.NaN
        val spent = netWh - baselineWh
        return if (spent > 0f) spent / dropped else Float.NaN
    }

    /**
     * Wh per percent implied by a rider-entered pack size, or NaN when unset
     * (0). A full pack is 100 percent, so each percent is [capacityWh] / 100.
     * Stands in for the ride-learned rate only until that rate exists, since a
     * real pack sags below its nameplate and the ride measures what it did.
     */
    fun seedWhPerPercent(capacityWh: Int): Float =
        if (capacityWh > 0) capacityWh / 100f else Float.NaN

    /**
     * Range left in km at [whPerKm], or NaN while either input is unknown.
     *
     * Capped, because a long descent can drive consumption low enough to
     * promise a range no wheel has: better a suspiciously round ceiling than a
     * four-figure number a rider might plan a route on.
     */
    fun rangeKm(whPerKm: Float, whPerPercent: Float, pct: Int): Float {
        if (whPerKm.isNaN() || whPerKm <= 0f) return Float.NaN
        if (whPerPercent.isNaN() || whPerPercent <= 0f) return Float.NaN
        if (pct !in 1..100) return Float.NaN
        return (whPerPercent * pct / whPerKm).coerceIn(0f, MAX_RANGE_KM)
    }
}
