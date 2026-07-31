package com.eried.eucplanet.data.model

/**
 * One 10 km / 10 mi split record in the trip meter's log. Distances are stored
 * in km (the app's canonical distance unit); the detail view converts to the
 * rider's chosen unit at render time. All fields are cheap running accumulators,
 * captured the moment a boundary is crossed.
 */
data class TripMeterSplit(
    /** 1, 2, 3 ... in the order the boundaries were crossed. */
    val index: Int,
    /** Boundary distance in km (10, 20, 30 ... for a km rider; 16.09, 32.19 ...
     *  for a mi rider whose boundary is every 10 mi). */
    val markDistanceKm: Float,
    /** Active (moving) time elapsed when this mark was reached, in ms. */
    val cumulativeMs: Long,
    /** Time for this segment alone (cumulativeMs minus the previous mark's), in ms. */
    val segmentMs: Long,
    /** Average speed over this segment in km/h (one step / segmentMs). */
    val segmentAvgKmh: Float,
    /** Peak speed seen during this segment in km/h (running max). */
    val segmentMaxKmh: Float,
    /** Battery percent sampled at the mark. -1 when unknown. */
    val batteryPctAtMark: Int,
)

/**
 * Persisted running state of the car-odometer-style trip meter. Counts distance
 * while a wheel is connected (independent of recording) and is cleared only by a
 * manual reset or by Stop All. Distances are km; the UI converts to the rider's
 * unit.
 */
data class TripMeterState(
    /** Total distance since the last reset, in km. */
    val distanceKm: Float = 0f,
    /** Active (moving) time since the last reset, in ms. */
    val activeMs: Long = 0L,
    /** Wall-clock (epoch ms) the meter first started counting after a reset. 0 = never. */
    val startedAtMs: Long = 0L,
    /** Full split log, oldest first. Never pruned by the dashboard stats window. */
    val splits: List<TripMeterSplit> = emptyList(),
) {
    /** Overall average speed in km/h over the active time. 0 when no active time yet. */
    val overallAvgKmh: Float
        get() = if (activeMs > 0L) (distanceKm / (activeMs / 3_600_000.0)).toFloat() else 0f
}

/**
 * Pure split-accumulation core for the trip meter, free of Android types so it is
 * unit-testable on the JVM. Feed it per-tick deltas; it maintains the running
 * total and appends a [TripMeterSplit] each time distance crosses the next
 * multiple of [intervalKm], resetting the segment accumulators at every boundary.
 *
 * The in-progress (partial) segment is not a split yet; it lives in the running
 * [distanceKm] / [activeMs] the [snapshot] exposes.
 */
class TripMeterAccumulator(
    /** The split step in km (10 for a km rider, 10 mi -> ~16.09 for a mi rider). */
    var intervalKm: Float = 10f,
) {
    var distanceKm: Double = 0.0
        private set
    var activeMs: Long = 0L
        private set
    var startedAtMs: Long = 0L
        private set

    private val splits = mutableListOf<TripMeterSplit>()
    // Peak speed within the current (in-progress) segment; reset at each boundary.
    private var segmentMaxKmh: Float = 0f

    /**
     * Advance the meter by one telemetry tick.
     *
     * @param distanceDeltaKm distance covered since the previous tick, in km (>= 0).
     * @param dtActiveMs active (moving) time since the previous tick, in ms (>= 0).
     * @param speedKmh current speed in km/h, folded into the segment running max.
     * @param batteryPct battery percent sampled this tick (used when a mark lands here).
     * @param nowMs wall clock, used only to stamp [startedAtMs] on the first motion.
     */
    fun onTick(
        distanceDeltaKm: Float,
        dtActiveMs: Long,
        speedKmh: Float,
        batteryPct: Int,
        nowMs: Long,
    ) {
        if (startedAtMs == 0L && (distanceDeltaKm > 0f || dtActiveMs > 0L)) startedAtMs = nowMs
        if (dtActiveMs > 0L) activeMs += dtActiveMs
        if (speedKmh > segmentMaxKmh) segmentMaxKmh = speedKmh
        if (distanceDeltaKm > 0f) distanceKm += distanceDeltaKm.toDouble()

        val step = intervalKm.toDouble().coerceAtLeast(0.0001)
        // Usually crosses 0 or 1 boundary per tick; the loop also handles a rare
        // multi-boundary jump (a big GPS delta) without losing a split.
        while (distanceKm + 1e-6 >= (splits.size + 1) * step) {
            val index = splits.size + 1
            val markKm = index * step
            val prevCumMs = splits.lastOrNull()?.cumulativeMs ?: 0L
            val cumulativeMs = activeMs
            val segMs = (cumulativeMs - prevCumMs).coerceAtLeast(0L)
            val segAvg = if (segMs > 0L) (intervalKm / (segMs / 3_600_000.0)).toFloat() else 0f
            splits.add(
                TripMeterSplit(
                    index = index,
                    markDistanceKm = markKm.toFloat(),
                    cumulativeMs = cumulativeMs,
                    segmentMs = segMs,
                    segmentAvgKmh = segAvg,
                    segmentMaxKmh = segmentMaxKmh,
                    batteryPctAtMark = batteryPct,
                )
            )
            segmentMaxKmh = 0f
        }
    }

    /** Current running state (total + full split log). */
    fun snapshot(): TripMeterState =
        TripMeterState(
            distanceKm = distanceKm.toFloat(),
            activeMs = activeMs,
            startedAtMs = startedAtMs,
            splits = splits.toList(),
        )

    /** Zero the total and clear the split log. */
    fun reset() {
        distanceKm = 0.0
        activeMs = 0L
        startedAtMs = 0L
        splits.clear()
        segmentMaxKmh = 0f
    }

    /**
     * Reload from a persisted state (app restart). The in-progress segment's
     * running max isn't persisted, so it restarts at 0; the completed splits and
     * the totals carry over exactly.
     */
    fun restore(state: TripMeterState) {
        distanceKm = state.distanceKm.toDouble()
        activeMs = state.activeMs
        startedAtMs = state.startedAtMs
        splits.clear()
        splits.addAll(state.splits)
        segmentMaxKmh = 0f
    }
}
