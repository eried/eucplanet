package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AlarmComparator

/**
 * Pure, Android-free decision math for the alarm engine, split out so the
 * tricky parts -- predictive projection, trend slope, and "which of several
 * matching rules on one metric is the most severe" -- can be unit-tested
 * without a Context, tone player, or database. [AlarmEngine] owns the stateful
 * side (cooldown, edge detection, side effects) and calls into here.
 */
object AlarmLogic {

    // Predictive-alarm slope window. We fit a line to the samples from the last
    // [SLOPE_WINDOW_MS] and project it leadTimeMs into the future. A ~1.5 s
    // look-back is long enough to shrug off single-sample jitter (V14 streams
    // ~4 Hz => ~6 points) yet short enough to track a genuine ramp (PWM
    // climbing into a cutout). Below [SLOPE_MIN_SAMPLES] points, or a span under
    // [SLOPE_MIN_SPAN_MS], we don't trust the slope and fall back to plain
    // threshold crossing.
    const val SLOPE_WINDOW_MS = 1500L
    const val SLOPE_MIN_SAMPLES = 3
    const val SLOPE_MIN_SPAN_MS = 300L
    const val BUFFER_MAX_MS = 2500L

    /** Does [value] satisfy the rule's threshold right now? */
    fun matchesNow(value: Float, comparator: String, threshold: Float): Boolean =
        when (AlarmComparator.parse(comparator)) {
            AlarmComparator.GREATER_EQUAL -> value >= threshold
            AlarmComparator.LESS_THAN -> value < threshold
        }

    /**
     * The single fire decision, factored out so the safety-critical timing is
     * unit-tested. Given the most-severe matched rule for a metric:
     *
     *  - [matched]              is it satisfied right now (instant or predictive)
     *  - [wasActive]            was it satisfied on the previous evaluation
     *  - [msSinceLastFire]      wall-clock ms since this rule last alerted
     *  - [cooldownMs]           the rule's cooldown
     *  - [repeatWhileActive]    "Many" mode (re-alert while held) vs "Once"
     *
     * Behaviour, exactly:
     *  - Once (repeatWhileActive = false): fires only on a fresh crossing
     *    (wasActive false -> matched true) and only once the cooldown since the
     *    last alert has elapsed. Staying past the threshold does NOT re-alert.
     *  - Many (repeatWhileActive = true): additionally re-alerts every time the
     *    cooldown elapses while the value stays past the threshold.
     *  - Cooldown is the minimum gap between alerts in BOTH modes (so flicker
     *    around the threshold can't machine-gun alerts).
     */
    fun shouldFire(
        matched: Boolean,
        wasActive: Boolean,
        msSinceLastFire: Long,
        cooldownMs: Long,
        repeatWhileActive: Boolean,
    ): Boolean {
        if (!matched) return false
        val cooldownElapsed = msSinceLastFire >= cooldownMs
        val isNewTrigger = !wasActive
        return cooldownElapsed && (isNewTrigger || repeatWhileActive)
    }

    /**
     * Higher = fires first when several rules on a metric match at once. For
     * GREATER_EQUAL the bigger threshold is the graver warning; for LESS_THAN
     * the smaller threshold is (battery < 10 beats battery < 20).
     */
    fun severityRank(comparator: String, threshold: Float): Float =
        when (AlarmComparator.parse(comparator)) {
            AlarmComparator.GREATER_EQUAL -> threshold
            AlarmComparator.LESS_THAN -> -threshold
        }

    /**
     * True when a rule with [leadTimeMs] > 0 should fire early: the recent trend
     * ([slope], value-units per second) projects the value across [threshold]
     * within the lead window, while still moving *toward* it from the safe side.
     * A flat or retreating value, or one already past the threshold (plain
     * [matchesNow] covers that), never triggers here. [slope] null = not enough
     * history to trust a trend.
     */
    fun predictiveMatch(
        comparator: String,
        threshold: Float,
        leadTimeMs: Int,
        value: Float,
        slope: Float?,
    ): Boolean {
        if (leadTimeMs <= 0 || slope == null) return false
        val projected = value + slope * (leadTimeMs / 1000f)
        return when (AlarmComparator.parse(comparator)) {
            AlarmComparator.GREATER_EQUAL -> slope > 0f && value < threshold && projected >= threshold
            AlarmComparator.LESS_THAN -> slope < 0f && value >= threshold && projected <= threshold
        }
    }

    /**
     * Least-squares slope (value units per second) over [samples] (timestamp ms
     * to value) within [windowMs] of [now], or null when there aren't enough
     * well-spread points to trust a trend.
     */
    fun slopePerSec(
        samples: List<Pair<Long, Float>>,
        now: Long,
        windowMs: Long = SLOPE_WINDOW_MS,
        minSamples: Int = SLOPE_MIN_SAMPLES,
        minSpanMs: Long = SLOPE_MIN_SPAN_MS,
    ): Float? {
        val cutoff = now - windowMs
        val pts = samples.filter { it.first >= cutoff }
        if (pts.size < minSamples) return null
        if (pts.last().first - pts.first().first < minSpanMs) return null
        val t0 = pts.first().first
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        val n = pts.size.toDouble()
        for (p in pts) {
            val x = (p.first - t0) / 1000.0
            val y = p.second.toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = n * sxx - sx * sx
        if (denom == 0.0) return null
        return ((n * sxy - sx * sy) / denom).toFloat()
    }
}
