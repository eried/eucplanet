package com.eried.eucplanet.data.repository

/**
 * The two things done to every chart buffer on every sample: drop what is
 * older than the buffer keeps, and hand out the part the rider's window shows.
 *
 * Both used to test every sample in every buffer, on each sample, and the
 * second built its list by growing it. The samples are in time order, so the
 * boundary is found by position and the copy is made at its final size. A
 * buffer that is not in order (the phone's clock stepped back) is handled the
 * old way, sample by sample, so the result is the same in every case.
 */
object HistoryWindow {

    private fun inOrder(list: List<MetricSample>): Boolean {
        for (i in 1 until list.size) {
            if (list[i].timestampMs < list[i - 1].timestampMs) return false
        }
        return true
    }

    /** Index of the first sample at or after [fromMs]. [list] must be in order. */
    private fun firstIndexFrom(list: List<MetricSample>, fromMs: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].timestampMs < fromMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Removes the samples older than [cutoffMs]. */
    fun dropOlderThan(list: MutableList<MetricSample>, cutoffMs: Long) {
        if (list.isEmpty() || list[0].timestampMs >= cutoffMs && inOrder(list)) return
        if (!inOrder(list)) {
            list.removeAll { it.timestampMs < cutoffMs }
            return
        }
        val keepFrom = firstIndexFrom(list, cutoffMs)
        if (keepFrom > 0) list.subList(0, keepFrom).clear()
    }

    /** A copy of the samples at or after [fromMs], for publishing. */
    fun shownFrom(list: List<MetricSample>, fromMs: Long): List<MetricSample> {
        if (list.isEmpty()) return emptyList()
        if (!inOrder(list)) return list.filter { it.timestampMs >= fromMs }
        val from = firstIndexFrom(list, fromMs)
        if (from >= list.size) return emptyList()
        return ArrayList(list.subList(from, list.size))
    }
}
