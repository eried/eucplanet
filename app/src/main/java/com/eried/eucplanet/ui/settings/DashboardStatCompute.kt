package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.data.repository.MetricSample

/**
 * Shared computation for every place the dashboard surfaces a per-cell
 * statistic (composite cell renderer, the slot-sheet preview, and the
 * future per-corner stat readouts on the live grid).
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
    val values = samples.map { it.value }
    return when (stat) {
        DashboardStat.NONE, DashboardStat.EMPTY -> null
        DashboardStat.CURRENT -> fallbackCurrent
        DashboardStat.MIN -> values.min()
        DashboardStat.MAX -> values.max()
        // Highest level HELD for >= 2s, so a momentary spike doesn't count.
        // (Was plain max(), which contradicted the "ignore <2s spikes" intent.)
        DashboardStat.SUSTAINED_PEAK -> sustainedPeak(samples, windowMs = 2000L)
        DashboardStat.AVG -> values.average().toFloat()
        DashboardStat.MEDIAN -> percentileOf(values, 0.50)
        DashboardStat.P75 -> percentileOf(values, 0.75)
        DashboardStat.P95 -> percentileOf(values, 0.95)
        DashboardStat.P99 -> percentileOf(values, 0.99)
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

private fun percentileOf(values: List<Float>, p: Double): Float {
    val sorted = values.sorted()
    if (sorted.isEmpty()) return 0f
    val rank = (p * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
    val lo = kotlin.math.floor(rank).toInt()
    val hi = kotlin.math.ceil(rank).toInt()
    if (lo == hi) return sorted[lo]
    val frac = (rank - lo).toFloat()
    return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
}
