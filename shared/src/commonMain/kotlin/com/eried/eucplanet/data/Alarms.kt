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
