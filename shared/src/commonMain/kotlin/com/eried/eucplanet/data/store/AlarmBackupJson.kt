package com.eried.eucplanet.data.store

import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.platform.JSONArray
import com.eried.eucplanet.platform.JSONObject

/**
 * Alarm rules as they travel inside the settings backup. Kept outside
 * SyncManager so a JVM test can round-trip a rule without building the
 * whole manager. Every [AlarmRule] field except the row id must appear in
 * BOTH halves; the drift-guard test fails on the first one that does not.
 */
object AlarmBackupJson {
    fun alarmsToJson(rules: List<AlarmRule>): JSONArray = JSONArray().apply {
        rules.forEach { r ->
            put(JSONObject().apply {
                put("name", r.name)
                put("enabled", r.enabled)
                put("sortOrder", r.sortOrder)
                put("metric", r.metric)
                put("comparator", r.comparator)
                put("threshold", r.threshold.toDouble())
                put("beepEnabled", r.beepEnabled)
                put("beepFrequency", r.beepFrequency)
                put("beepDurationMs", r.beepDurationMs)
                put("beepCount", r.beepCount)
                put("beepModulation", r.beepModulation)
                put("beepGapMs", r.beepGapMs)
                put("beepTransitionPct", r.beepTransitionPct)
                put("beepWaveform", r.beepWaveform)
                put("beepEffect", r.beepEffect)
                put("beepVolume", r.beepVolume)
                put("beepVolumeModulation", r.beepVolumeModulation)
                put("beepModulationReachPct", r.beepModulationReachPct)
                put("beepVolumeReachPct", r.beepVolumeReachPct)
                put("voiceEnabled", r.voiceEnabled)
                put("voiceText", r.voiceText)
                put("vibrateEnabled", r.vibrateEnabled)
                put("vibrateDurationMs", r.vibrateDurationMs)
                put("vibrateTarget", r.vibrateTarget)
                put("cooldownSeconds", r.cooldownSeconds)
                put("repeatWhileActive", r.repeatWhileActive)
                put("leadTimeMs", r.leadTimeMs)
                r.wheelAddress?.let { put("wheelAddress", it) }
                r.wheelName?.let { put("wheelName", it) }
            })
        }
    }

    fun jsonToAlarms(arr: JSONArray?): List<AlarmRule> {
        if (arr == null) return emptyList()
        val out = mutableListOf<AlarmRule>()
        val default = AlarmRule()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += AlarmRule(
                name = o.optString("name", default.name),
                enabled = o.optBoolean("enabled", default.enabled),
                sortOrder = o.optInt("sortOrder", default.sortOrder),
                metric = o.optString("metric", default.metric),
                comparator = o.optString("comparator", default.comparator),
                threshold = o.optDouble("threshold", default.threshold.toDouble()).toFloat(),
                beepEnabled = o.optBoolean("beepEnabled", default.beepEnabled),
                beepFrequency = o.optInt("beepFrequency", default.beepFrequency),
                beepDurationMs = o.optInt("beepDurationMs", default.beepDurationMs),
                beepCount = o.optInt("beepCount", default.beepCount),
                beepModulation = o.optInt("beepModulation", default.beepModulation),
                beepGapMs = o.optInt("beepGapMs", default.beepGapMs),
                // Absent in backups written before the timbre controls: entity defaults.
                beepTransitionPct = o.optInt("beepTransitionPct", default.beepTransitionPct),
                beepWaveform = o.optInt("beepWaveform", default.beepWaveform),
                beepEffect = o.optInt("beepEffect", default.beepEffect),
                beepVolume = o.optInt("beepVolume", default.beepVolume),
                beepVolumeModulation = o.optInt("beepVolumeModulation", default.beepVolumeModulation),
                beepModulationReachPct = o.optInt("beepModulationReachPct", default.beepModulationReachPct),
                beepVolumeReachPct = o.optInt("beepVolumeReachPct", default.beepVolumeReachPct),
                voiceEnabled = o.optBoolean("voiceEnabled", default.voiceEnabled),
                voiceText = o.optString("voiceText", default.voiceText),
                vibrateEnabled = o.optBoolean("vibrateEnabled", default.vibrateEnabled),
                vibrateDurationMs = o.optInt("vibrateDurationMs", default.vibrateDurationMs),
                vibrateTarget = o.optString("vibrateTarget", default.vibrateTarget),
                cooldownSeconds = o.optInt("cooldownSeconds", default.cooldownSeconds),
                repeatWhileActive = o.optBoolean("repeatWhileActive", default.repeatWhileActive),
                leadTimeMs = o.optInt("leadTimeMs", default.leadTimeMs),
                // Absent in older backups -> null -> the classic any-wheel rule.
                wheelAddress = o.optString("wheelAddress").takeIf { it.isNotEmpty() },
                wheelName = o.optString("wheelName").takeIf { it.isNotEmpty() }
            )
        }
        return out
    }
}
