package com.eried.eucplanet.service

import kotlin.math.max

/**
 * Immutable snapshot of the current charging estimate. Times are in minutes;
 * absolute clock times are formatted by the caller (so this stays clock-free and
 * unit-testable). `null` ETAs mean "not enough information yet".
 */
data class ChargingEstimate(
    /** Latest battery percentage seen this session. */
    val percent: Float = 0f,
    /** Battery percentage when this charging session started. */
    val startPercent: Float = 0f,
    /** Percentage added since the session started (`percent - startPercent`, ≥ 0). */
    val addedPercent: Float = 0f,
    /** Observed charge rate, %/min (least-squares over a rolling window). */
    val ratePctPerMin: Float = 0f,
    /** True once enough has been observed to make a trustworthy prediction. */
    val warmedUp: Boolean = false,
    /** Minutes to reach the target (e.g. 80 %), or null if past it / not warmed up. */
    val minutesToTarget: Float? = null,
    /** Minutes to reach 100 %, including the pessimistic CV taper above the target. */
    val minutesToFull: Float? = null,
)

/**
 * Within-session charging estimator. It is driven purely by the **battery
 * %-climb rate**, which every wheel reports (even the InMotion V14, whose
 * current sensor reads ~0 A while charging) — so there is no pack-capacity
 * guessing, no per-wheel settings, and no cross-session learning.
 *
 * Feed it `(timestampMs, percent)` samples while a session is active. It:
 *  - waits for a short warm-up ([warmupMinPercentGain] gained AND
 *    [warmupMinDurationMs] elapsed) so plugging in at 79 % still observes a
 *    couple of percent before predicting;
 *  - fits a least-squares slope over a rolling [windowMs] window for the rate;
 *  - extrapolates linearly to [targetPercent] (e.g. 80 %);
 *  - models the constant-voltage taper above the target pessimistically: the
 *    `target → 100 %` segment is charged at `rate / cvTaperFactor`, so the
 *    full-charge ETA errs long rather than over-promising.
 */
class ChargingEstimator(
    private val targetPercent: Float = 80f,
    private val cvTaperFactor: Float = 2.2f,
    private val warmupMinPercentGain: Float = 2f,
    private val warmupMinDurationMs: Long = 30_000L,
    private val windowMs: Long = 120_000L,
) {
    private data class Sample(val t: Long, val p: Float)

    private val samples = ArrayDeque<Sample>()
    private var startPercent: Float? = null
    private var startTimeMs: Long = 0L

    /** Add a battery-percent reading. Call once per telemetry tick while charging. */
    fun addSample(timestampMs: Long, percent: Float) {
        if (startPercent == null) {
            startPercent = percent
            startTimeMs = timestampMs
        }
        samples.addLast(Sample(timestampMs, percent))
        // Drop samples older than the rolling window (keep at least one).
        while (samples.size > 1 && timestampMs - samples.first().t > windowMs) {
            samples.removeFirst()
        }
    }

    /** Clear all session state (call when a charging session ends/starts fresh). */
    fun reset() {
        samples.clear()
        startPercent = null
        startTimeMs = 0L
    }

    fun estimate(): ChargingEstimate {
        val start = startPercent ?: return ChargingEstimate()
        val latest = samples.lastOrNull() ?: return ChargingEstimate(startPercent = start)
        val percent = latest.p
        val added = percent - start
        val rate = slopePctPerMin()
        val durationMs = latest.t - startTimeMs

        val warmedUp = added >= warmupMinPercentGain &&
            durationMs >= warmupMinDurationMs &&
            rate > 0f

        val minutesToTarget: Float? = when {
            !warmedUp -> null
            percent >= targetPercent -> null
            else -> (targetPercent - percent) / rate
        }

        val minutesToFull: Float? = when {
            !warmedUp -> null
            percent >= 100f -> null
            else -> {
                val ccPart = if (percent < targetPercent) (targetPercent - percent) / rate else 0f
                val cvStart = max(percent, targetPercent)
                val cvRange = 100f - cvStart
                val cvPart = cvRange * cvTaperFactor / rate
                ccPart + cvPart
            }
        }

        return ChargingEstimate(
            percent = percent,
            startPercent = start,
            addedPercent = max(0f, added),
            ratePctPerMin = rate,
            warmedUp = warmedUp,
            minutesToTarget = minutesToTarget,
            minutesToFull = minutesToFull,
        )
    }

    /** Least-squares slope of percent-vs-time over the windowed samples, %/min. */
    private fun slopePctPerMin(): Float {
        if (samples.size < 2) return 0f
        val t0 = samples.first().t
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        val n = samples.size
        for (s in samples) {
            val x = (s.t - t0) / 60000.0 // minutes
            val y = s.p.toDouble()
            sumX += x
            sumY += y
            sumXY += x * y
            sumXX += x * x
        }
        val denom = n * sumXX - sumX * sumX
        if (denom == 0.0) return 0f
        return ((n * sumXY - sumX * sumY) / denom).toFloat()
    }
}
