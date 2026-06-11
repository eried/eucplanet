package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.WheelData
import kotlin.math.roundToInt

/** A currently-tripped ride alarm — one per active rule. */
data class RideAlarm(val ruleId: Long, val label: String, val voiceEnabled: Boolean, val vibrateEnabled: Boolean, val spoken: String)

/**
 * Pure alarm evaluation: which enabled [rules] the current telemetry [d] trips
 * right now. The dashboard surfaces these as a banner; the platform actuators
 * (TTS, haptics) fire off the same result. Cooldown / repeat timing is applied by
 * the caller when it decides whether to *re-fire* an already-active rule.
 */
/**
 * Stateful alarm firing — tracks per-rule last-fired time so each rule's
 * [AlarmRule.cooldownSeconds] / [AlarmRule.repeatWhileActive] are honoured.
 * Call [step] once per telemetry frame with the currently-tripped rules; it
 * returns the subset whose actions (voice / vibrate) should fire right now.
 */
class AlarmEngine {
    private val lastFiredMs = HashMap<Long, Long>()
    private val wasActive = HashSet<Long>()

    /** Clear all firing state — call when a ride ends/starts so a rule fires on its
     *  first trip of the new ride instead of being suppressed by last ride's cooldown. */
    fun reset() {
        lastFiredMs.clear()
        wasActive.clear()
    }

    fun step(active: List<RideAlarm>, rules: List<AlarmRule>, nowMs: Long): List<RideAlarm> {
        val activeIds = HashSet<Long>(active.size)
        val fire = ArrayList<RideAlarm>()
        for (ra in active) {
            activeIds.add(ra.ruleId)
            val rule = rules.firstOrNull { it.id == ra.ruleId } ?: continue
            // Floor at 1s so cooldown=0 + repeat can't fire every telemetry frame.
            val cooldownMs = rule.cooldownSeconds.coerceAtLeast(1) * 1000L
            val last = lastFiredMs[ra.ruleId]
            val cooldownElapsed = last == null || nowMs - last >= cooldownMs
            val justActivated = ra.ruleId !in wasActive
            // Fire on activation, then again every cooldown while active if repeat is on.
            if (cooldownElapsed && (justActivated || rule.repeatWhileActive)) {
                fire += ra
                lastFiredMs[ra.ruleId] = nowMs
            }
        }
        // Forget rules that are no longer active so they fire again on re-activation
        // instead of being suppressed by a stale cooldown.
        wasActive.clear(); wasActive.addAll(activeIds)
        lastFiredMs.keys.retainAll(activeIds)
        return fire
    }
}

fun activeAlarms(d: WheelData, rules: List<AlarmRule>): List<RideAlarm> {
    val out = ArrayList<RideAlarm>()
    for (r in rules) {
        if (!r.enabled) continue
        val metric = AlarmMetric.parse(r.metric)
        val cmp = AlarmComparator.parse(r.comparator)
        val value = metric.valueOf(d)
        if (cmp.test(value, r.threshold)) {
            val name = r.name.ifBlank { metric.label }
            out += RideAlarm(
                ruleId = r.id,
                label = "$name ${value.roundToInt()}${metric.unit}",
                voiceEnabled = r.voiceEnabled,
                vibrateEnabled = r.vibrateEnabled,
                spoken = r.voiceText
                    .replace("{metric}", metric.label)
                    .replace("{value}", "${value.roundToInt()} ${metric.unit}"),
            )
        }
    }
    return out
}
