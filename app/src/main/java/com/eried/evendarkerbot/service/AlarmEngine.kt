package com.eried.evendarkerbot.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.eried.evendarkerbot.data.db.AlarmDao
import com.eried.evendarkerbot.data.model.AlarmComparator
import com.eried.evendarkerbot.data.model.AlarmMetric
import com.eried.evendarkerbot.data.model.AlarmRule
import com.eried.evendarkerbot.data.model.WheelData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

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

                    val shouldFire = when {
                        isNewTrigger -> true
                        rule.repeatWhileActive && cooldownExpired -> true
                        else -> false
                    }

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

    private fun checkCondition(value: Float, comparator: String, threshold: Float): Boolean {
        return try {
            when (AlarmComparator.valueOf(comparator)) {
                AlarmComparator.GREATER_THAN -> value > threshold
                AlarmComparator.LESS_THAN -> value < threshold
                AlarmComparator.GREATER_EQUAL -> value >= threshold
                AlarmComparator.LESS_EQUAL -> value <= threshold
            }
        } catch (_: Exception) { false }
    }

    private fun executeActions(rule: AlarmRule, data: WheelData, triggerValue: Float) {
        if (rule.beepEnabled) {
            scope.launch {
                tonePlayer.playBeep(rule.beepFrequency, rule.beepDurationMs, rule.beepCount)
            }
        }

        if (rule.voiceEnabled && rule.voiceText.isNotBlank()) {
            val text = expandTemplate(rule.voiceText, rule, data, triggerValue)
            voiceService.speak(text)
        }

        if (rule.vibrateEnabled) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(rule.vibrateDurationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    private fun expandTemplate(
        template: String,
        rule: AlarmRule,
        data: WheelData,
        triggerValue: Float
    ): String {
        val metricLabel = try { AlarmMetric.valueOf(rule.metric).label } catch (_: Exception) { rule.metric }
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
