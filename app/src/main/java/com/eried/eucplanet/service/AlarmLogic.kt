package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric

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

    /** Hard pitch ceiling (Hz), below the 44.1 kHz Nyquist limit. */
    const val BEEP_MOD_MAX_HZ = 20000
    /** Pitch floor for big reductions (a low thud, still audible). */
    const val BEEP_MOD_MIN_HZ = 80
    /** Factor stored x100: 100 = 1.0x (unchanged). */
    const val BEEP_FACTOR_UNITY = 100

    /** Internal max reading for a metric (km/h, %, C, V, A, m); min is 0 (no
     *  negatives). Bounds the modulation reach + the studio test slider so a
     *  metric never simulates an impossible value (e.g. PWM > 100). */
    fun metricReadMax(metric: String): Float = when (metric) {
        AlarmMetric.SPEED.name -> 150f
        AlarmMetric.BATTERY.name -> 100f
        AlarmMetric.PWM.name -> 100f
        AlarmMetric.TEMPERATURE.name -> 100f
        AlarmMetric.VOLTAGE.name -> 300f
        AlarmMetric.CURRENT.name -> 50f
        AlarmMetric.RADAR_DISTANCE.name -> 140f
        AlarmMetric.RADAR_APPROACH_SPEED.name -> 150f
        else -> 100f
    }

    /** Overshoot (value units past the threshold) at which modulation reaches its
     *  factor: the distance from the threshold to the metric's far limit in the
     *  severe direction. ">=" reaches at the metric max, "<" reaches at 0. */
    fun metricReachSpan(metric: String, comparator: String, threshold: Float): Float =
        when (AlarmComparator.parse(comparator)) {
            AlarmComparator.GREATER_EQUAL -> (metricReadMax(metric) - threshold).coerceAtLeast(1f)
            AlarmComparator.LESS_THAN -> threshold.coerceAtLeast(1f)
        }

    /**
     * How far [value] has pushed past [threshold], as a 0..1 fraction that hits
     * 1.0 at [reachSpan] value-units past it. Direction-aware (overspeed grows as
     * value rises; low-battery grows as value falls) and 0 on the safe side.
     */
    fun overshootFraction(value: Float, comparator: String, threshold: Float, reachSpan: Float): Float {
        val over = when (AlarmComparator.parse(comparator)) {
            AlarmComparator.GREATER_EQUAL -> value - threshold
            AlarmComparator.LESS_THAN -> threshold - value
        }
        if (over <= 0f) return 0f
        return (over / reachSpan.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    /**
     * Modulated beep pitch in Hz. [factorX100] is a multiplier x100: 100 = 1.0x
     * (unchanged -- always [baseHz]); the pitch ramps from [baseHz] toward
     * baseHz * factor as the value pushes past the threshold, reaching the factor
     * at the metric's far limit (so it stays within the metric's real range), then
     * plateauing. Above 1.0x ramps up; below 1.0x (down to 0.1x) ramps down.
     */
    fun modulatedBeepHz(
        baseHz: Int,
        value: Float,
        comparator: String,
        threshold: Float,
        factorX100: Int,
        metric: String,
    ): Int {
        if (factorX100 == BEEP_FACTOR_UNITY) return baseHz
        val frac = overshootFraction(value, comparator, threshold, metricReachSpan(metric, comparator, threshold))
        val target = baseHz * (factorX100 / 100f)
        val v = baseHz + (target - baseHz) * frac
        return v.toInt().coerceIn(BEEP_MOD_MIN_HZ, BEEP_MOD_MAX_HZ)
    }

    /**
     * Modulated beep volume (0..100 % of system). [factorX100] is a multiplier
     * x100: 100 = 1.0x (constant [baseVolPct]); the volume ramps toward
     * baseVol * factor, reaching the factor at the metric's far limit. Above 1.0x
     * ramps louder (capped at 100); below 1.0x ramps quieter. Clamped to 0..100.
     */
    fun modulatedVolumePct(
        baseVolPct: Int,
        factorX100: Int,
        value: Float,
        comparator: String,
        threshold: Float,
        metric: String,
    ): Int {
        val base = baseVolPct.coerceIn(0, 100)
        if (factorX100 == BEEP_FACTOR_UNITY) return base
        val frac = overshootFraction(value, comparator, threshold, metricReachSpan(metric, comparator, threshold))
        val target = base * (factorX100 / 100f)
        val v = base + (target - base) * frac
        return v.toInt().coerceIn(0, 100)
    }
}
