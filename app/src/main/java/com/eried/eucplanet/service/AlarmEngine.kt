package com.eried.eucplanet.service

import android.content.Context
import android.util.Log
import com.eried.eucplanet.data.db.AlarmDao
import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.VibratorHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

@Singleton
class AlarmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmDao: AlarmDao,
    private val tonePlayer: TonePlayer,
    private val voiceService: VoiceService
) {
    companion object {
        private const val TAG = "AlarmEngine"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vibratorHelper = VibratorHelper(context)

    // Track per-rule state: whether condition was true last check, and last fire time
    private val ruleState = mutableMapOf<Long, RuleState>()

    private data class RuleState(
        var wasActive: Boolean = false,
        var lastFireTimeMs: Long = 0
    )

    /**
     * Called on each telemetry update. Evaluates alarm rules against current data.
     * First-match-per-metric: for each metric type, only the first matching rule fires.
     */
    fun evaluate(data: WheelData) {
        scope.launch {
            val rules = alarmDao.getEnabled()
            val firedMetrics = mutableSetOf<String>()

            for (rule in rules) {
                // Skip if we already fired a rule for this metric
                if (rule.metric in firedMetrics) continue

                val value = getMetricValue(rule.metric, data) ?: continue
                val matches = checkCondition(value, rule.comparator, rule.threshold)
                val state = ruleState.getOrPut(rule.id) { RuleState() }
                val now = System.currentTimeMillis()
                val cooldownMs = rule.cooldownSeconds * 1000L

                if (matches) {
                    firedMetrics.add(rule.metric)

                    val isNewTrigger = !state.wasActive
                    val cooldownExpired = (now - state.lastFireTimeMs) >= cooldownMs

                    // Cooldown is strict: re-crossing the threshold does not bypass it.
                    val shouldFire = cooldownExpired && (isNewTrigger || rule.repeatWhileActive)

                    if (shouldFire) {
                        Log.i(TAG, "Alarm fired: '${rule.name}' ${rule.metric} ${rule.comparator} ${rule.threshold} (value=$value)")
                        state.lastFireTimeMs = now
                        executeActions(rule, data, value)
                    }
                }

                state.wasActive = matches
            }

            // Clean up state for deleted rules
            val activeIds = rules.map { it.id }.toSet()
            ruleState.keys.removeAll { it !in activeIds }
        }
    }

    private fun getMetricValue(metric: String, data: WheelData): Float? {
        return try {
            when (AlarmMetric.valueOf(metric)) {
                AlarmMetric.SPEED -> data.speed.absoluteValue
                AlarmMetric.BATTERY -> data.batteryPercent.toFloat()
                AlarmMetric.TEMPERATURE -> data.maxTemperature
                AlarmMetric.PWM -> data.pwm.absoluteValue
                AlarmMetric.VOLTAGE -> data.voltage
                AlarmMetric.CURRENT -> data.current.absoluteValue
            }
        } catch (_: Exception) { null }
    }

    private fun checkCondition(value: Float, comparator: String, threshold: Float): Boolean =
        when (AlarmComparator.parse(comparator)) {
            AlarmComparator.GREATER_EQUAL -> value >= threshold
            AlarmComparator.LESS_THAN -> value < threshold
        }

    private fun executeActions(rule: AlarmRule, data: WheelData, triggerValue: Float) {
        // Vibrate fires immediately; beep and voice are sequenced so the beep
        // finishes before the voice speaks.
        if (rule.vibrateEnabled) {
            vibratorHelper.oneShot(rule.vibrateDurationMs.toLong())
        }

        scope.launch {
            if (rule.beepEnabled) {
                tonePlayer.playBeep(rule.beepFrequency, rule.beepDurationMs, rule.beepCount)
                if (rule.voiceEnabled && rule.voiceText.isNotBlank()) {
                    delay(200)
                }
            }
            if (rule.voiceEnabled && rule.voiceText.isNotBlank()) {
                val text = expandTemplate(rule.voiceText, rule, data, triggerValue)
                voiceService.speak(text)
            }
        }
    }

    private fun expandTemplate(
        template: String,
        rule: AlarmRule,
        data: WheelData,
        triggerValue: Float
    ): String {
        val metricLabel = try { context.getString(AlarmMetric.valueOf(rule.metric).labelRes) } catch (_: Exception) { rule.metric }
        return template
            .replace("{speed}", "%.0f".format(data.speed.absoluteValue))
            .replace("{battery}", "${data.batteryPercent}")
            .replace("{temp}", "%.0f".format(data.maxTemperature))
            .replace("{pwm}", "%.0f".format(data.pwm.absoluteValue))
            .replace("{voltage}", "%.1f".format(data.voltage))
            .replace("{current}", "%.1f".format(data.current.absoluteValue))
            .replace("{trip}", "%.1f".format(data.tripDistance))
            .replace("{value}", "%.0f".format(triggerValue))
            .replace("{metric}", metricLabel)
            .replace("{threshold}", "%.0f".format(rule.threshold))
    }
}
