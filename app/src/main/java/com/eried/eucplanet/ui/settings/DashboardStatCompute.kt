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
        DashboardStat.MAX, DashboardStat.SUSTAINED_PEAK -> values.max()
        DashboardStat.AVG -> values.average().toFloat()
        DashboardStat.MEDIAN -> percentileOf(values, 0.50)
        DashboardStat.P75 -> percentileOf(values, 0.75)
        DashboardStat.P95 -> percentileOf(values, 0.95)
        DashboardStat.P99 -> percentileOf(values, 0.99)
    }
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
