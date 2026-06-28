package com.eried.eucplanet.service

/**
 * The stateful firing core of the alarm system, with **no Android dependencies**
 * so the safety-critical "does an alarm trigger, when, and which one" behaviour
 * is fully unit-testable (see AlarmEvaluatorTest).
 *
 * [AlarmEngine] owns everything Android: reading rules from Room, resolving
 * metric values from telemetry, and the side effects (beep / voice / vibrate).
 * It feeds rules + readings in here and gets back the list of rules to fire.
 *
 * Behaviour, per evaluation tick:
 *  - Rules are grouped by metric; per metric only the **most severe matched**
 *    rule can fire (highest threshold for `>=`, lowest for `<`). Order in the
 *    list is irrelevant, so a lower alarm can't mask or "eat" a higher one.
 *  - Each rule keeps its own cooldown + armed state, so escalating tiers each
 *    fire as they're reached, and one rule's cooldown never blocks another.
 *  - A rule matches instantly (value past threshold) or predictively (the recent
 *    trend projects it across the threshold within the rule's lead time).
 *
 * All decision math lives in [AlarmLogic]; this class only carries the state
 * (per-rule edge + last-fire time, per-metric sample history) across ticks.
 */
class AlarmEvaluator {

    /** The minimal rule view the evaluator needs, decoupled from the Room entity. */
    data class Rule(
        val id: Long,
        val metric: String,
        val comparator: String,
        val threshold: Float,
        val cooldownSeconds: Int,
        val repeatWhileActive: Boolean,
        val leadTimeMs: Int,
    )

    /** A fire event: the rule that should alert, and the reading that triggered it. */
    data class Fired(val ruleId: Long, val value: Float)

    /** What a null reading for a metric means this tick. */
    enum class NoReading {
        /** Leave the metric's rules untouched (e.g. a wheel metric absent this frame). */
        SKIP,

        /** Disarm the metric's rules and drop its trend (e.g. radar lane-clear), so the
         *  next time a value appears it counts as a fresh crossing. */
        RESET,
    }

    private data class RuleState(var wasActive: Boolean = false, var lastFireTimeMs: Long = 0L)
    private data class Sample(val t: Long, val v: Float)

    private val ruleState = HashMap<Long, RuleState>()
    private val sampleBuffers = HashMap<String, ArrayDeque<Sample>>()

    // Predictive-trend tuning, overridable from settings (AlarmEngine pushes the
    // rider's values in). Defaults equal the AlarmLogic constants so the unit
    // tests and out-of-box behaviour are unchanged.
    @Volatile var slopeWindowMs: Long = AlarmLogic.SLOPE_WINDOW_MS
    @Volatile var bufferMaxMs: Long = AlarmLogic.BUFFER_MAX_MS
    @Volatile var slopeMinSamples: Int = AlarmLogic.SLOPE_MIN_SAMPLES
    @Volatile var slopeMinSpanMs: Long = AlarmLogic.SLOPE_MIN_SPAN_MS

    /**
     * Evaluate [rules] against the readings from [value] at [nowMs]. Returns the
     * rules that should fire this tick (at most one per metric). [onNoReading]
     * controls what a null reading does (see [NoReading]).
     *
     * Does NOT prune stale state -- call [prune] from the caller that holds the
     * full enabled-rule set (so a radar-only tick can't drop wheel-rule state).
     */
    fun evaluate(
        rules: List<Rule>,
        nowMs: Long,
        onNoReading: NoReading = NoReading.SKIP,
        value: (metric: String) -> Float?,
    ): List<Fired> {
        val fired = ArrayList<Fired>()
        for ((metric, metricRules) in rules.groupBy { it.metric }) {
            val v = value(metric)
            if (v == null) {
                if (onNoReading == NoReading.RESET) {
                    metricRules.forEach { ruleState.getOrPut(it.id) { RuleState() }.wasActive = false }
                    sampleBuffers.remove(metric)
                }
                continue
            }
            evaluateMetricGroup(metric, v, nowMs, metricRules)?.let { fired.add(it) }
        }
        return fired
    }

    /**
     * Evaluate every rule on one metric against [value]; return the single most
     * severe rule that should fire (or null), and refresh every rule's edge state.
     */
    private fun evaluateMetricGroup(
        metric: String,
        value: Float,
        now: Long,
        rules: List<Rule>,
    ): Fired? {
        recordSample(metric, value, now)
        val slope = AlarmLogic.slopePerSec(
            sampleBuffers[metric]?.map { it.t to it.v } ?: emptyList(), now,
            slopeWindowMs, slopeMinSamples, slopeMinSpanMs
        )

        // First pass: who matches, and which matched rule is most severe.
        val matched = HashMap<Long, Boolean>(rules.size)
        var best: Rule? = null
        var bestRank = Float.NEGATIVE_INFINITY
        for (rule in rules) {
            ruleState.getOrPut(rule.id) { RuleState() }
            val isMatch = AlarmLogic.matchesNow(value, rule.comparator, rule.threshold) ||
                AlarmLogic.predictiveMatch(rule.comparator, rule.threshold, rule.leadTimeMs, value, slope)
            matched[rule.id] = isMatch
            if (isMatch) {
                val rank = AlarmLogic.severityRank(rule.comparator, rule.threshold)
                if (rank > bestRank) { bestRank = rank; best = rule }
            }
        }

        // Decide a fire for the most-severe matched rule, honouring its own cooldown / edge.
        var result: Fired? = null
        best?.let { rule ->
            val state = ruleState.getValue(rule.id)
            if (AlarmLogic.shouldFire(
                    matched = true,
                    wasActive = state.wasActive,
                    msSinceLastFire = now - state.lastFireTimeMs,
                    cooldownMs = rule.cooldownSeconds * 1000L,
                    repeatWhileActive = rule.repeatWhileActive,
                )
            ) {
                state.lastFireTimeMs = now
                result = Fired(rule.id, value)
            }
        }

        // Refresh edge state for ALL rules, so each tier tracks its own crossing
        // (a tier not selected this tick must still record whether it's currently
        // active, or it would re-fire spuriously next tick).
        for (rule in rules) {
            ruleState.getValue(rule.id).wasActive = matched[rule.id] == true
        }
        return result
    }

    /** Append a sample to a metric's history and drop anything older than the window. */
    private fun recordSample(metric: String, value: Float, now: Long) {
        val buf = sampleBuffers.getOrPut(metric) { ArrayDeque() }
        buf.addLast(Sample(now, value))
        val cutoff = now - bufferMaxMs
        while (buf.isNotEmpty() && buf.first().t < cutoff) buf.removeFirst()
    }

    /** Drop state + trend history for rules / metrics that no longer exist. Pass
     *  the FULL set of currently-enabled rules so a partial (e.g. radar-only)
     *  tick can't wipe other rules' state. */
    fun prune(activeRuleIds: Set<Long>, activeMetrics: Set<String>) {
        ruleState.keys.removeAll { it !in activeRuleIds }
        sampleBuffers.keys.removeAll { it !in activeMetrics }
    }

    /** Clear all state (e.g. on disconnect). Test hook too. */
    fun reset() {
        ruleState.clear()
        sampleBuffers.clear()
    }
}
