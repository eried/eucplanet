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

    /** A metric group's fire candidate for this tick: its most-severe matched
     *  rule, the value, and whether it's allowed to fire right now (cooldown +
     *  edge), computed WITHOUT committing the fire so priority can pick a winner. */
    private data class Candidate(val rule: Rule, val value: Float, val wouldFire: Boolean)

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
     * Evaluate [rules] against the readings from [value] at [nowMs] and return the
     * rules to fire this tick. [onNoReading] controls what a null reading does.
     *
     * Two firing modes:
     *  - [metricPriority] EMPTY (legacy / radar): every metric fires its own
     *    most-severe fireable rule independently -- multiple metrics can sound at
     *    once. Exactly the previous behaviour.
     *  - [metricPriority] NON-EMPTY (wheel alarms): the metric groups are a
     *    priority list (highest first). At most ONE rule fires per tick -- the
     *    most-severe fireable rule of the highest-priority group that's ready.
     *    A higher group thus suppresses lower ones while it's actively alerting;
     *    during its cooldown the next ready group fills the gap. Crucially, a
     *    lower group's would-fire candidate that loses to a higher group keeps
     *    its fresh-crossing state (it is NOT marked active), so a higher alarm
     *    can never silently eat a lower "Once" alarm -- it fires once the higher
     *    one goes quiet. Metrics absent from [metricPriority] sort last.
     *
     * Does NOT prune stale state -- call [prune] from the caller that holds the
     * full enabled-rule set (so a radar-only tick can't drop wheel-rule state).
     */
    fun evaluate(
        rules: List<Rule>,
        nowMs: Long,
        onNoReading: NoReading = NoReading.SKIP,
        metricPriority: List<String> = emptyList(),
        value: (metric: String) -> Float?,
    ): List<Fired> {
        val priorityMode = metricPriority.isNotEmpty()
        val matchedByRule = HashMap<Long, Boolean>()
        // Insertion order = the order groups were declared in [rules].
        val candidates = LinkedHashMap<String, Candidate>()

        // Pass 1: per metric, record the sample, find matches, and compute the
        // group's fire candidate WITHOUT committing any fire.
        for ((metric, metricRules) in rules.groupBy { it.metric }) {
            val v = value(metric)
            if (v == null) {
                if (onNoReading == NoReading.RESET) {
                    metricRules.forEach { ruleState.getOrPut(it.id) { RuleState() }.wasActive = false }
                    sampleBuffers.remove(metric)
                }
                continue
            }
            recordSample(metric, v, nowMs)
            val slope = AlarmLogic.slopePerSec(
                sampleBuffers[metric]?.map { it.t to it.v } ?: emptyList(), nowMs,
                slopeWindowMs, slopeMinSamples, slopeMinSpanMs
            )
            var best: Rule? = null
            var bestRank = Float.NEGATIVE_INFINITY
            for (rule in metricRules) {
                ruleState.getOrPut(rule.id) { RuleState() }
                val isMatch = AlarmLogic.matchesNow(v, rule.comparator, rule.threshold) ||
                    AlarmLogic.predictiveMatch(rule.comparator, rule.threshold, rule.leadTimeMs, v, slope)
                matchedByRule[rule.id] = isMatch
                if (isMatch) {
                    val rank = AlarmLogic.severityRank(rule.comparator, rule.threshold)
                    if (rank > bestRank) { bestRank = rank; best = rule }
                }
            }
            best?.let { rule ->
                val st = ruleState.getValue(rule.id)
                val wouldFire = AlarmLogic.shouldFire(
                    matched = true,
                    wasActive = st.wasActive,
                    msSinceLastFire = nowMs - st.lastFireTimeMs,
                    cooldownMs = rule.cooldownSeconds * 1000L,
                    repeatWhileActive = rule.repeatWhileActive,
                )
                candidates[metric] = Candidate(rule, v, wouldFire)
            }
        }

        // Pass 2: decide who actually fires.
        val fired = ArrayList<Fired>()
        // Would-fire candidates that were suppressed by priority: their crossing
        // must be preserved (edge not advanced) so they can still fire later.
        val preserved = HashSet<Long>()
        if (priorityMode) {
            var winner: Candidate? = null
            for (metric in orderMetrics(candidates.keys, metricPriority)) {
                val c = candidates[metric] ?: continue
                if (!c.wouldFire) continue
                if (winner == null) winner = c else preserved.add(c.rule.id)
            }
            winner?.let { c ->
                ruleState.getValue(c.rule.id).lastFireTimeMs = nowMs
                fired.add(Fired(c.rule.id, c.value))
            }
        } else {
            for ((_, c) in candidates) {
                if (c.wouldFire) {
                    ruleState.getValue(c.rule.id).lastFireTimeMs = nowMs
                    fired.add(Fired(c.rule.id, c.value))
                }
            }
        }

        // Pass 3: refresh edge state for every rule that had a reading, EXCEPT the
        // suppressed would-fire candidates (so a higher-priority alarm doesn't
        // consume a lower one's fresh crossing).
        for ((ruleId, isMatch) in matchedByRule) {
            if (ruleId in preserved) continue
            ruleState.getValue(ruleId).wasActive = isMatch
        }
        return fired
    }

    /** Group metrics by priority: those listed in [priority] first (in that
     *  order), then any remaining present metrics in their existing order. */
    private fun orderMetrics(present: Set<String>, priority: List<String>): List<String> {
        val out = ArrayList<String>(present.size)
        for (m in priority) if (m in present && m !in out) out.add(m)
        for (m in present) if (m !in out) out.add(m)
        return out
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
