package com.eried.eucplanet.service

/**
 * Pure, Android-free acceleration-split tracker (RaceBox style).
 *
 * Fed a stream of (timeMs, speed) samples in the rider's display speed unit, it
 * detects upward crossings of the speed grid lines (minSpeed, minSpeed+inc,
 * minSpeed+2*inc, ...) and emits a [Split] each time a full step is crossed. The
 * crossing time is linearly interpolated between the two straddling samples so
 * the reported duration does not quantize to the sample rate (~4 Hz on wheel
 * telemetry, ~25 Hz on an external RaceBox).
 *
 * A "run" is one continuous acceleration. It arms when speed rises through a grid
 * line at or above minSpeed and ends when speed falls back below the last crossed
 * line (decel) or stalls between lines for [plateauMs]; the caller voices each
 * [Split] live (queued, so a step crossed while the previous line is still being
 * spoken is announced right after rather than dropped).
 *
 * Comparison data ([Split.deltaVsPrevious], [Split.deltaVsBest], [Split.isNewBest])
 * is measured against the same step's time in the previous run and the fastest
 * time seen this session, so the caller can voice "0.2 seconds faster than
 * previous". Deltas are seconds: positive = slower than the reference.
 *
 * Stateful but deterministic and Android-free, so it is unit-tested directly in
 * AccelSplitTrackerTest.
 */
class AccelSplitTracker(
    private var increment: Int,
    private var minSpeed: Int,
    // A run that neither advances a step nor decelerates for this long is
    // considered finished (the rider settled into a cruise between two lines).
    private val plateauMs: Long = 3000L,
) {
    /** One completed step, e.g. 20 -> 30 in 1.21 s. */
    data class Split(
        val fromSpeed: Int,
        val toSpeed: Int,
        val seconds: Double,
        val deltaVsPrevious: Double?,
        val deltaVsBest: Double?,
        val isNewBest: Boolean,
    )

    private var running = false
    private var lastLine = 0        // last grid line crossed this run
    private var lastCrossMs = 0.0   // interpolated time we crossed lastLine

    private var havePrev = false
    private var prevT = 0L
    private var prevV = 0.0

    // Per-step split time (seconds) keyed by the step's lower grid line.
    private val currentRun = HashMap<Int, Double>()
    private val previousRun = HashMap<Int, Double>()
    private val bestByLine = HashMap<Int, Double>()

    /** Re-reads config; a change wipes in-flight timing so old and new step
     *  grids never mix within one run. Session history is preserved. */
    fun configure(increment: Int, minSpeed: Int) {
        if (increment == this.increment && minSpeed == this.minSpeed) return
        this.increment = increment
        this.minSpeed = minSpeed
        running = false
        currentRun.clear()
        havePrev = false
    }

    /** Full reset including session history. Call on disconnect / new session. */
    fun hardReset() {
        running = false
        currentRun.clear()
        previousRun.clear()
        bestByLine.clear()
        havePrev = false
    }

    fun onSample(timeMs: Long, speed: Double): List<Split> {
        if (increment <= 0) return emptyList()
        val out = ArrayList<Split>()

        if (!havePrev) {
            havePrev = true
            prevT = timeMs
            prevV = speed
            return out
        }
        val t0 = prevT
        val v0 = prevV
        val t1 = timeMs
        val v1 = speed
        prevT = t1
        prevV = v1
        if (t1 <= t0) return out // out-of-order or duplicate timestamp

        // A drop below the last crossed line, or a stall between lines, ends the
        // run (rolling its per-step times forward as the comparison baseline)
        // before we look for fresh crossings.
        if (running) {
            val fellBelow = v1 < lastLine
            val stalled = v1 < lastLine + increment && (t1 - lastCrossMs) > plateauMs
            if (fellBelow || stalled) {
                endRun()
                running = false
            }
        }

        // Arm a run the moment speed rises through a grid line at/above minSpeed.
        if (!running) {
            val line = firstGridLineCrossed(v0, v1)
            if (line != null) {
                running = true
                lastLine = line
                lastCrossMs = interp(t0.toDouble(), v0, t1.toDouble(), v1, line.toDouble())
                currentRun.clear()
            }
        }

        // Emit a Split for every full step crossed within this sample segment.
        if (running) {
            while (v1 >= lastLine + increment) {
                val nextLine = lastLine + increment
                val cross = interp(t0.toDouble(), v0, t1.toDouble(), v1, nextLine.toDouble())
                val sec = (cross - lastCrossMs) / 1000.0
                val prevSplit = previousRun[lastLine]
                val oldBest = bestByLine[lastLine]
                val isNewBest = oldBest == null || sec < oldBest
                out.add(
                    Split(
                        fromSpeed = lastLine,
                        toSpeed = nextLine,
                        seconds = sec,
                        deltaVsPrevious = prevSplit?.let { sec - it },
                        deltaVsBest = oldBest?.let { sec - it },
                        isNewBest = isNewBest,
                    )
                )
                currentRun[lastLine] = sec
                if (isNewBest) bestByLine[lastLine] = sec
                lastLine = nextLine
                lastCrossMs = cross
            }
        }
        return out
    }

    /** Carry the just-finished run forward as the previous-run comparison baseline. */
    private fun endRun() {
        if (currentRun.isNotEmpty()) {
            previousRun.clear()
            previousRun.putAll(currentRun)
        }
        currentRun.clear()
    }

    /** Smallest grid line G (>= minSpeed) with v0 < G <= v1, or null if none. */
    private fun firstGridLineCrossed(v0: Double, v1: Double): Int? {
        var line = minSpeed
        while (line <= v1) {
            if (v0 < line) return line
            line += increment
        }
        return null
    }

    private fun interp(t0: Double, v0: Double, t1: Double, v1: Double, target: Double): Double {
        if (v1 == v0) return t1
        val frac = ((target - v0) / (v1 - v0)).coerceIn(0.0, 1.0)
        return t0 + (t1 - t0) * frac
    }
}
