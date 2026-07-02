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
 *  - **median-filters the raw input** over [medianFilterSize] samples so a
 *    single-frame voltage dip can't drag the regression. Veteran captures
 *    of a Lynx charging at 5 A show ~1 % of frames carrying a wildly low
 *    voltage (the wheel's pack-V measurement briefly tracks the charger's
 *    on/off PWM pulses); median filtering knocks those out cleanly without
 *    a per-wheel allow-list;
 *  - waits for a short warm-up ([warmupMinPercentGain] gained AND
 *    [warmupMinDurationMs] elapsed) so a plug-in moment that lands inside
 *    a noisy stretch doesn't ship a wild ETA;
 *  - fits a least-squares slope over a rolling [windowMs] window for the rate;
 *  - extrapolates linearly to [targetPercent] (e.g. 80 %), with an optional
 *    very-small [targetTaperFactor] (default 1.05) so the 80 % ETA errs a
 *    hair long rather than over-promising;
 *  - extrapolates the `target → 100 %` segment with [cvTaperFactor] (default
 *    2.0). A Lynx-class report at 5 A showed our 1.3 factor under-predicting
 *    the 100 % ETA by ~50 % (predicted 2 h, actual ~3.5 h); 2.0 sits roughly
 *    between that and the historical 2.2 — pessimistic enough to cover the
 *    real CV taper without the wild 1 h overshoot that pushed us off 2.2.
 */
class ChargingEstimator(
    // Params are `var` so the app can push the rider's Advanced-settings values
    // in live (WheelRepository mirrors them); estimate() reads them each call so
    // a change applies without losing the running charge session.
    var targetPercent: Float = 80f,
    var targetTaperFactor: Float = 1.05f,
    var cvTaperFactor: Float = 2.0f,
    // Warm-up: 5 % gained over 5 min (was 3 % / 3 min). Bumped after testers
    // reported predictions wobbling for the first ~10 min on Lynx / Oryx; the
    // post-fix Veteran voltage stream is cleaner but the % gate dominates on
    // slow-tapering chargers near full anyway, so the extra 2 % buys real
    // slope-quality at the cost of a longer "warming up" splash. The rider
    // still sees the live 0.001 % battery readout during warm-up — only the
    // "X min to full" line waits for these gates to clear.
    var warmupMinPercentGain: Float = 5f,
    var warmupMinDurationMs: Long = 300_000L,
    // 5 min window: enough integer SoC transitions in the window that the
    // slope estimate stays within a few % of the truth even on a BMS that
    // ticks at 1 % resolution.
    var windowMs: Long = 300_000L,
    // Hard cap on a sensible ETA. Any computed minutesToFull above this
    // means the slope just got transiently tiny (a sample sequence where
    // no integer step has yet landed in the window); return null instead
    // and let the commitEta layer hold the previous value. 8 h covers
    // even worst-case real charge cycles (0 % -> 100 % on a slow brick).
    var sanityCapMinutes: Float = 480f,
    // Median-filter window (must be odd, ≥ 1; 1 disables filtering). 7 covers
    // ~0.8 s of telemetry at the 9 Hz Veteran cadence — small enough that the
    // smoothed series tracks real % transitions with sub-second lag, big
    // enough that a single rogue voltage dip is dropped completely.
    var medianFilterSize: Int = 7,
) {
    private data class Sample(val t: Long, val p: Float)

    private val samples = ArrayDeque<Sample>()
    private val rawWindow = ArrayDeque<Float>()
    private var startPercent: Float? = null
    private var startTimeMs: Long = 0L

    /** Add a battery-percent reading. Call once per telemetry tick while charging. */
    fun addSample(timestampMs: Long, percent: Float) {
        val smoothed = medianFilter(percent)
        if (startPercent == null) {
            startPercent = smoothed
            startTimeMs = timestampMs
        }
        samples.addLast(Sample(timestampMs, smoothed))
        // Drop samples older than the rolling window (keep at least one).
        while (samples.size > 1 && timestampMs - samples.first().t > windowMs) {
            samples.removeFirst()
        }
    }

    /** Clear all session state (call when a charging session ends/starts fresh). */
    fun reset() {
        samples.clear()
        rawWindow.clear()
        startPercent = null
        startTimeMs = 0L
    }

    /** Rolling median of the last [medianFilterSize] raw inputs. Single-frame
     *  outliers (the wheel briefly reporting a sagged voltage during a
     *  charger PWM pulse) get dropped completely; persistent transitions are
     *  preserved with sub-second lag at the typical 9 Hz Veteran rate. */
    private fun medianFilter(percent: Float): Float {
        if (medianFilterSize <= 1) return percent
        rawWindow.addLast(percent)
        while (rawWindow.size > medianFilterSize) rawWindow.removeFirst()
        val sorted = rawWindow.toFloatArray().also { it.sort() }
        return sorted[sorted.size / 2]
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
            else -> ((targetPercent - percent) / rate * targetTaperFactor)
                .takeIf { it.isFinite() && it <= sanityCapMinutes }
        }

        val minutesToFull: Float? = when {
            !warmedUp -> null
            percent >= 100f -> null
            else -> {
                val ccPart = if (percent < targetPercent)
                    (targetPercent - percent) / rate * targetTaperFactor
                else 0f
                val cvStart = max(percent, targetPercent)
                val cvRange = 100f - cvStart
                val cvPart = cvRange * cvTaperFactor / rate
                (ccPart + cvPart).takeIf { it.isFinite() && it <= sanityCapMinutes }
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
