package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.data.repository.MetricSample

/**
 * The glasses' own short history, for graphs that look back less than a
 * minute. The app's shared buffers sample once a second by default, so a 5 s
 * graph from them would be five points; this keeps every wheel update, at
 * most [minStepMs] apart, for the last [keepMs].
 *
 * Only the metrics a graph on the page draws are kept. Thread-safe: the wheel
 * feed records while the page tick reads.
 */
class EngoFastHistory(
    private val keepMs: Long = 60_000L,
    private val minStepMs: Long = 100L,
) {
    private val buffers = HashMap<String, ArrayDeque<MetricSample>>()

    /** Graphs shorter than this read from here; longer ones from the shared history. */
    val coversMs: Long get() = keepMs

    @Synchronized
    fun record(key: String, timestampMs: Long, value: Float) {
        if (value.isNaN()) return
        val q = buffers.getOrPut(key) { ArrayDeque() }
        val last = q.lastOrNull()
        if (last != null && timestampMs - last.timestampMs < minStepMs) return
        q.addLast(MetricSample(timestampMs, value))
        while (q.isNotEmpty() && timestampMs - q.first().timestampMs > keepMs) q.removeFirst()
    }

    /** The samples of [key] from [fromMs] on, oldest first. */
    @Synchronized
    fun since(key: String, fromMs: Long): List<MetricSample> =
        buffers[key]?.filter { it.timestampMs >= fromMs }.orEmpty()

    /** Forget every metric not in [keys], so a graph the rider removed stops costing memory. */
    @Synchronized
    fun retain(keys: Set<String>) {
        buffers.keys.retainAll(keys)
    }

    @Synchronized
    fun clear() = buffers.clear()
}
