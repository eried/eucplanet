package com.eried.eucplanet.service

/**
 * Pure, Android-free acceleration / deceleration split tracker (RaceBox style).
 *
 * Fed a stream of (timeMs, speed) samples in the rider's display speed unit, it
 * detects crossings of the speed grid lines (minSpeed, minSpeed+inc,
 * minSpeed+2*inc, ...) and emits a [Split] each time a full step is crossed. The
 * crossing time is linearly interpolated between the two straddling samples so
 * the reported duration does not quantize to the sample rate (~4 Hz on wheel
 * telemetry, ~25 Hz on an external RaceBox).
 *
 * A "run" is one continuous acceleration OR deceleration. An up run arms when
 * speed rises through a grid line at/above minSpeed and emits "20 to 30"-style
 * splits; a down run (only when [trackDecel] is on) arms when speed falls through
 * a grid line and emits "40 to 30"-style splits down to minSpeed. A run ends when
 * speed reverses past the last crossed line or stalls between lines for
 * [plateauMs]; the caller voices each [Split] live (queued, so a step crossed
 * while the previous line is still being spoken is announced right after rather
 * than dropped). Direction is implicit in the split: toSpeed > fromSpeed is
 * acceleration, toSpeed < fromSpeed is deceleration.
 *
 * Comparison data ([Split.deltaVsPrevious], [Split.deltaVsBest], [Split.isNewBest])
 * is measured against the same step's time in the previous run and the fastest
 * time seen this session, kept separately per direction so a braking split is
 * only compared with earlier braking. Deltas are seconds: positive = slower than
 * the reference.
 *
 * Stateful but deterministic and Android-free, so it is unit-tested directly in
 * AccelSplitTrackerTest.
 */
class AccelSplitTracker(
    private var increment: Int,
    private var minSpeed: Int,
    // A run that neither advances a step nor reverses for this long is
    // considered finished (the rider settled into a cruise between two lines).
    private val plateauMs: Long = 3000L,
    // When false, only acceleration (up) runs are tracked, byte-for-byte the
    // original behaviour. When true, deceleration (down) runs are tracked too.
    private var trackDecel: Boolean = false,
) {
    /** One completed step, e.g. 20 -> 30 (accel) or 40 -> 30 (decel) in 1.21 s. */
    data class Split(
        val fromSpeed: Int,
        val toSpeed: Int,
        val seconds: Double,
        val deltaVsPrevious: Double?,
        val deltaVsBest: Double?,
        val isNewBest: Boolean,
    )

    private var running = false
    private var direction = 0       // +1 accelerating, -1 decelerating
    private var lastLine = 0        // last grid line crossed this run
    private var lastCrossMs = 0.0   // interpolated time we crossed lastLine

    private var havePrev = false
    private var prevT = 0L
    private var prevV = 0.0

    // Per-step split time (seconds) keyed by the step's "from" grid line, for the
    // run in progress. Comparison baselines are kept separately per direction so
    // an up split never compares against a down split at the same line.
    private val currentRun = HashMap<Int, Double>()
    private val previousUp = HashMap<Int, Double>()
    private val previousDown = HashMap<Int, Double>()
    private val bestUp = HashMap<Int, Double>()
    private val bestDown = HashMap<Int, Double>()

    /** Re-reads config; a change wipes in-flight timing so old and new step
     *  grids (or a direction-mode switch) never mix within one run. Session
     *  history is preserved. */
    fun configure(increment: Int, minSpeed: Int, trackDecel: Boolean) {
        if (increment == this.increment && minSpeed == this.minSpeed &&
            trackDecel == this.trackDecel) return
        this.increment = increment
        this.minSpeed = minSpeed
        this.trackDecel = trackDecel
        running = false
        direction = 0
        currentRun.clear()
        havePrev = false
    }

    /** Full reset including session history. Call on disconnect / new session. */
    fun hardReset() {
        running = false
        direction = 0
        currentRun.clear()
        previousUp.clear()
        previousDown.clear()
        bestUp.clear()
        bestDown.clear()
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

        // A reversal past the last crossed line, or a stall between lines, ends
        // the run (rolling its per-step times forward as the comparison baseline)
        // before we look for fresh crossings.
        if (running) {
            val reversed = if (direction > 0) v1 < lastLine else v1 > lastLine
            val notProgressed = if (direction > 0) v1 < lastLine + increment
                                else v1 > lastLine - increment
            if (reversed || (notProgressed && (t1 - lastCrossMs) > plateauMs)) {
                endRun()
                running = false
                direction = 0
            }
        }

        // Arm a run the moment speed crosses a grid line at/above minSpeed.
        if (!running) {
            val up = firstGridLineCrossedUp(v0, v1)
            val down = if (trackDecel) firstGridLineCrossedDown(v0, v1) else null
            when {
                up != null -> startRun(+1, up, interp(t0.toDouble(), v0, t1.toDouble(), v1, up.toDouble()))
                down != null -> startRun(-1, down, interp(t0.toDouble(), v0, t1.toDouble(), v1, down.toDouble()))
            }
        }

        // Emit a Split for every full step crossed within this sample segment.
        if (running && direction > 0) {
            while (v1 >= lastLine + increment) {
                emit(out, lastLine, lastLine + increment,
                    interp(t0.toDouble(), v0, t1.toDouble(), v1, (lastLine + increment).toDouble()))
            }
        } else if (running && direction < 0) {
            while (v1 <= lastLine - increment && lastLine - increment >= minSpeed) {
                emit(out, lastLine, lastLine - increment,
                    interp(t0.toDouble(), v0, t1.toDouble(), v1, (lastLine - increment).toDouble()))
            }
        }
        return out
    }

    private fun startRun(dir: Int, line: Int, cross: Double) {
        running = true
        direction = dir
        lastLine = line
        lastCrossMs = cross
        currentRun.clear()
    }

    private fun emit(out: MutableList<Split>, fromLine: Int, toLine: Int, cross: Double) {
        val sec = (cross - lastCrossMs) / 1000.0
        val prevMap = if (direction > 0) previousUp else previousDown
        val bestMap = if (direction > 0) bestUp else bestDown
        val prevSplit = prevMap[fromLine]
        val oldBest = bestMap[fromLine]
        val isNewBest = oldBest == null || sec < oldBest
        out.add(
            Split(
                fromSpeed = fromLine,
                toSpeed = toLine,
                seconds = sec,
                deltaVsPrevious = prevSplit?.let { sec - it },
                deltaVsBest = oldBest?.let { sec - it },
                isNewBest = isNewBest,
            )
        )
        currentRun[fromLine] = sec
        if (isNewBest) bestMap[fromLine] = sec
        lastLine = toLine
        lastCrossMs = cross
    }

    /** Carry the just-finished run forward as the previous-run comparison
     *  baseline for its direction. */
    private fun endRun() {
        if (currentRun.isNotEmpty()) {
            val prevMap = if (direction > 0) previousUp else previousDown
            prevMap.clear()
            prevMap.putAll(currentRun)
        }
        currentRun.clear()
    }

    /** Smallest grid line G (>= minSpeed) with v0 < G <= v1 (crossed going up),
     *  or null if none. */
    private fun firstGridLineCrossedUp(v0: Double, v1: Double): Int? {
        var line = minSpeed
        while (line <= v1) {
            if (v0 < line) return line
            line += increment
        }
        return null
    }

    /** Largest grid line G (>= minSpeed) with v1 <= G < v0 (crossed going down),
     *  or null if none. The largest is the first one crossed while slowing. */
    private fun firstGridLineCrossedDown(v0: Double, v1: Double): Int? {
        if (v0 <= minSpeed) return null
        var line = minSpeed
        var candidate: Int? = null
        while (line < v0) {
            if (line >= v1) candidate = line
            line += increment
        }
        return candidate
    }

    private fun interp(t0: Double, v0: Double, t1: Double, v1: Double, target: Double): Double {
        if (v1 == v0) return t1
        val frac = ((target - v0) / (v1 - v0)).coerceIn(0.0, 1.0)
        return t0 + (t1 - t0) * frac
    }
}
