package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.data.repository.MetricSample

/**
 * Shared computation for every place the dashboard surfaces a per-cell
 * statistic (composite cell renderer, the slot-sheet preview, and the
 * per-slot stat readouts on the live grid).
 *
 * Returns null when there's not enough buffered data to satisfy the
 * requested stat (callers render a placeholder dash in that case).
 * [DashboardStat.CURRENT] falls back to [fallbackCurrent] when the
 * history buffer is empty, so a freshly-spawned composite cell still
 * shows the live wheel value before any 1Hz samples land.
 */
fun computeDashboardStatValue(
    stat: DashboardStat,
    samples: List<MetricSample>,
    fallbackCurrent: Float
): Float? {
    if (stat == DashboardStat.CURRENT) {
        return samples.lastOrNull()?.value ?: fallbackCurrent
    }
    if (samples.isEmpty()) return null
    // Straight over the samples. Each stat used to start by copying every
    // value into a list of boxed floats, and this runs for every stat chip on
    // the dashboard.
    return when (stat) {
        DashboardStat.NONE, DashboardStat.EMPTY -> null
        DashboardStat.CURRENT -> fallbackCurrent
        DashboardStat.MIN -> {
            var m = samples[0].value
            for (i in 1 until samples.size) m = minOf(m, samples[i].value)
            m
        }
        DashboardStat.MAX -> {
            var m = samples[0].value
            for (i in 1 until samples.size) m = maxOf(m, samples[i].value)
            m
        }
        // Highest level HELD for >= 2s, so a momentary spike doesn't count.
        DashboardStat.SUSTAINED_PEAK -> sustainedPeak(samples, windowMs = 2000L)
        DashboardStat.AVG -> {
            var sum = 0.0
            for (s in samples) sum += s.value
            (sum / samples.size).toFloat()
        }
        DashboardStat.MEDIAN -> percentileOf(samples, 0.50)
        DashboardStat.P75 -> percentileOf(samples, 0.75)
        DashboardStat.P95 -> percentileOf(samples, 0.95)
        DashboardStat.P99 -> percentileOf(samples, 0.99)
    }
}

/**
 * The stat chips of one dashboard, remembered between recompositions.
 *
 * The dashboard recomposes many times a second while a wheel talks, and each
 * pass asked for every chip's stat again: a pass over the whole history, for
 * the percentiles a sort of it. The history only changes when a sample is
 * added, and then it is a new list. So a chip's value is kept for as long as
 * it is asked for with the very same list.
 *
 * Used from composition only, so from one thread.
 */
class DashboardStatCache {
    private class Entry(val samples: List<MetricSample>, val value: Float?)
    private val entries = HashMap<String, Entry>()

    /** [slot] names the chip: one entry per chip. [samples] must not be empty. */
    fun value(slot: String, stat: DashboardStat, samples: List<MetricSample>): Float? {
        val key = slot + '/' + stat.name
        val hit = entries[key]
        if (hit != null && hit.samples === samples) return hit.value
        val value = computeDashboardStatValue(stat, samples, fallbackCurrent = samples.last().value)
        entries[key] = Entry(samples, value)
        return value
    }
}

/**
 * Highest value sustained for at least [windowMs]. For each start sample we
 * take the minimum value over the [windowMs] window beginning there (the level
 * that held for the whole window); the sustained peak is the max of those
 * window minima. A spike shorter than the window can't raise a window's minimum,
 * so it's ignored. Requires real per-sample timestamps (samples are time
 * ordered). Falls back to the plain max when no window spans the full duration
 * (e.g. fewer than [windowMs] of data buffered).
 */
private fun sustainedPeak(samples: List<MetricSample>, windowMs: Long): Float {
    val n = samples.size
    if (n <= 1) return samples.firstOrNull()?.value ?: 0f
    var best = Float.NEGATIVE_INFINITY
    for (i in 0 until n) {
        val end = samples[i].timestampMs + windowMs
        var windowMin = Float.POSITIVE_INFINITY
        var k = i
        while (k < n && samples[k].timestampMs <= end) {
            windowMin = minOf(windowMin, samples[k].value)
            k++
        }
        // Only count windows that actually span the required duration.
        if (samples[k - 1].timestampMs - samples[i].timestampMs >= windowMs) {
            best = maxOf(best, windowMin)
        }
    }
    return if (best == Float.NEGATIVE_INFINITY) samples.maxOf { it.value } else best
}

private fun percentileOf(samples: List<MetricSample>, p: Double): Float {
    // A plain array of floats, sorted in place: same order as sorting the
    // boxed list did (the total order of Float, NaN last).
    val sorted = FloatArray(samples.size) { samples[it].value }
    sorted.sort()
    if (sorted.isEmpty()) return 0f
    val rank = (p * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
    val lo = kotlin.math.floor(rank).toInt()
    val hi = kotlin.math.ceil(rank).toInt()
    if (lo == hi) return sorted[lo]
    val frac = (rank - lo).toFloat()
    return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
}
