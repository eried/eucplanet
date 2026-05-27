package com.eried.eucplanet.service

import android.content.Context
import android.util.Log
import com.eried.eucplanet.data.db.AlarmDao
import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.RadarFrame
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.VibratorHelper
import com.eried.eucplanet.wear.WatchVibrator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

@Singleton
class AlarmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmDao: AlarmDao,
    private val tonePlayer: TonePlayer,
    private val voiceService: VoiceService,
    private val watchVibrator: WatchVibrator,
    private val cheatState: com.eried.eucplanet.cheats.CheatState,
    private val settingsRepository: com.eried.eucplanet.data.repository.SettingsRepository
) {
    companion object {
        private const val TAG = "AlarmEngine"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vibratorHelper = VibratorHelper(context)

    // Serializes evaluate() bodies so the read-check-write on RuleState.lastFireTimeMs
    // is atomic. Without this, telemetry updates ~250ms apart can both pass the cooldown
    // check before either writes the timestamp, producing duplicate alarm fires.
    private val evalMutex = Mutex()

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
        // Quake-console godmode: silences all alarms for the session. The user types
        // `godmode` in the Settings search bar to flip it; lost on app restart.
        if (cheatState.godmode.value) return
        scope.launch {
            evalMutex.withLock {
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
                        val s = settingsRepository.get()
                        executeActions(rule, data, value,
                            com.eried.eucplanet.util.Units.effectiveSpeedUnit(s),
                            com.eried.eucplanet.util.Units.effectiveDistanceUnit(s),
                            com.eried.eucplanet.util.Units.effectiveTempUnit(s))
                    }
                }

                state.wasActive = matches
            }

            // Clean up state for deleted rules
            val activeIds = rules.map { it.id }.toSet()
            ruleState.keys.removeAll { it !in activeIds }
            }
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
                // Radar metrics are evaluated via the dedicated [evaluateRadar]
                // entry point, fed by [RadarRepository.currentFrame], not by
                // the wheel telemetry loop. Returning null here ensures the
                // wheel evaluator never fires a radar rule with stale data.
                AlarmMetric.RADAR_DISTANCE,
                AlarmMetric.RADAR_APPROACH_SPEED -> null
            }
        } catch (_: Exception) { null }
    }

    /**
     * Radar-side evaluator. Called from [com.eried.eucplanet.data.repository.RadarRepository]
     * on each new frame. Walks the same per-rule cooldown / wasActive state
     * as the wheel evaluator so a rider can mix wheel and radar rules without
     * either side double-firing the other's metric.
     *
     * No frame = no eval (e.g. radar disconnected). An empty-but-fresh frame
     * (lane clear) clears wasActive on RADAR_DISTANCE rules so the rule fires
     * again the next time a car appears, even if the rule isn't repeat-while-
     * active.
     */
    fun evaluateRadar(frame: RadarFrame) {
        if (cheatState.godmode.value) return
        scope.launch {
            evalMutex.withLock {
                val rules = alarmDao.getEnabled().filter {
                    it.metric == AlarmMetric.RADAR_DISTANCE.name ||
                        it.metric == AlarmMetric.RADAR_APPROACH_SPEED.name
                }
                val firedMetrics = mutableSetOf<String>()

                for (rule in rules) {
                    if (rule.metric in firedMetrics) continue
                    val value = getRadarMetricValue(rule.metric, frame)
                    val state = ruleState.getOrPut(rule.id) { RuleState() }
                    val now = System.currentTimeMillis()
                    val cooldownMs = rule.cooldownSeconds * 1000L

                    if (value == null) {
                        // Frame is empty for this metric (lane clear).
                        // Reset wasActive so the rule re-fires next time
                        // a target shows up.
                        state.wasActive = false
                        continue
                    }
                    val matches = checkCondition(value, rule.comparator, rule.threshold)
                    if (matches) {
                        firedMetrics.add(rule.metric)
                        val isNewTrigger = !state.wasActive
                        val cooldownExpired = (now - state.lastFireTimeMs) >= cooldownMs
                        val shouldFire = cooldownExpired && (isNewTrigger || rule.repeatWhileActive)
                        if (shouldFire) {
                            Log.i(TAG, "Radar alarm fired: '${rule.name}' ${rule.metric} ${rule.comparator} ${rule.threshold} (value=$value)")
                            state.lastFireTimeMs = now
                            executeRadarActions(rule, frame, value)
                        }
                    }
                    state.wasActive = matches
                }
            }
        }
    }

    private fun getRadarMetricValue(metric: String, frame: RadarFrame): Float? {
        return try {
            when (AlarmMetric.valueOf(metric)) {
                // Distance: the closest car. Empty list → null (handled above).
                AlarmMetric.RADAR_DISTANCE ->
                    frame.threats.minOfOrNull { it.distanceM }?.toFloat()
                // Approach speed: the fastest closer. Empty list → null.
                AlarmMetric.RADAR_APPROACH_SPEED ->
                    frame.threats.maxOfOrNull { it.approachSpeedKmh }?.toFloat()
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun executeRadarActions(rule: AlarmRule, frame: RadarFrame, triggerValue: Float) {
        if (rule.vibrateEnabled) {
            val onPhone = rule.vibrateTarget != "WATCH"
            val onWatch = rule.vibrateTarget == "WATCH" || rule.vibrateTarget == "BOTH"
            if (onPhone) vibratorHelper.oneShot(rule.vibrateDurationMs.toLong())
            if (onWatch) watchVibrator.vibrate(rule.vibrateDurationMs)
        }
        scope.launch {
            if (rule.beepEnabled) {
                tonePlayer.playBeep(rule.beepFrequency, rule.beepDurationMs, rule.beepCount)
                if (rule.voiceEnabled && rule.voiceText.isNotBlank()) {
                    delay(200)
                }
            }
            if (rule.voiceEnabled && rule.voiceText.isNotBlank()) {
                voiceService.speak(expandRadarTemplate(rule.voiceText, frame, triggerValue))
            }
        }
    }

    private fun expandRadarTemplate(template: String, frame: RadarFrame, triggerValue: Float): String {
        val closest = frame.threats.minByOrNull { it.distanceM }
        val approach = frame.threats.maxByOrNull { it.approachSpeedKmh }?.approachSpeedKmh ?: 0
        return template
            .replace("{value}", "%.0f".format(triggerValue))
            .replace("{distance}", "${closest?.distanceM ?: 0}")
            .replace("{approachSpeed}", "$approach")
            .replace("{threatCount}", "${frame.threats.size}")
    }

    private fun checkCondition(value: Float, comparator: String, threshold: Float): Boolean =
        when (AlarmComparator.parse(comparator)) {
            AlarmComparator.GREATER_EQUAL -> value >= threshold
            AlarmComparator.LESS_THAN -> value < threshold
        }

    private fun executeActions(rule: AlarmRule, data: WheelData, triggerValue: Float, speedUnit: String, distanceUnit: String, tempUnit: String) {
        // Vibrate fires immediately; beep and voice are sequenced so the beep
        // finishes before the voice speaks.
        if (rule.vibrateEnabled) {
            val onPhone = rule.vibrateTarget != "WATCH"
            val onWatch = rule.vibrateTarget == "WATCH" || rule.vibrateTarget == "BOTH"
            if (onPhone) vibratorHelper.oneShot(rule.vibrateDurationMs.toLong())
            if (onWatch) watchVibrator.vibrate(rule.vibrateDurationMs)
        }

        scope.launch {
            if (rule.beepEnabled) {
                tonePlayer.playBeep(rule.beepFrequency, rule.beepDurationMs, rule.beepCount)
                if (rule.voiceEnabled && rule.voiceText.isNotBlank()) {
                    delay(200)
                }
            }
            if (rule.voiceEnabled && rule.voiceText.isNotBlank()) {
                val text = expandTemplate(rule.voiceText, rule, data, triggerValue, speedUnit, distanceUnit, tempUnit)
                voiceService.speak(text)
            }
        }
    }

    private fun expandTemplate(
        template: String,
        rule: AlarmRule,
        data: WheelData,
        triggerValue: Float,
        speedUnit: String,
        distanceUnit: String,
        tempUnit: String
    ): String {
        val metricLabel = try { context.getString(AlarmMetric.valueOf(rule.metric).voiceLabelRes) } catch (_: Exception) { rule.metric }
        // {value} and {threshold} are converted using the rule's own metric, speed
        // alarm in mph reads mph, temperature alarm in °F reads °F, everything else
        // stays in its native unit (battery %, PWM %, voltage V, current A).
        val metricForThreshold = try { AlarmMetric.valueOf(rule.metric) } catch (_: Exception) { null }
        val convertedValue = convertForMetric(metricForThreshold, triggerValue, speedUnit, tempUnit)
        val convertedThreshold = convertForMetric(metricForThreshold, rule.threshold, speedUnit, tempUnit)
        val speedConverted = com.eried.eucplanet.util.Units.speed(data.speed.absoluteValue, speedUnit)
        val tempConverted = com.eried.eucplanet.util.Units.temperature(data.maxTemperature, tempUnit)
        val tripConverted = com.eried.eucplanet.util.Units.distance(data.tripDistance, distanceUnit)
        return template
            .replace("{speed}", "%.0f".format(speedConverted))
            .replace("{battery}", "${data.batteryPercent}")
            .replace("{temp}", "%.0f".format(tempConverted))
            .replace("{pwm}", "%.0f".format(data.pwm.absoluteValue))
            .replace("{voltage}", "%.1f".format(data.voltage))
            .replace("{current}", "%.1f".format(data.current.absoluteValue))
            .replace("{trip}", "%.1f".format(tripConverted))
            .replace("{value}", "%.0f".format(convertedValue))
            .replace("{metric}", metricLabel)
            .replace("{threshold}", "%.0f".format(convertedThreshold))
    }

    private fun convertForMetric(metric: AlarmMetric?, valueInternal: Float, speedUnit: String, tempUnit: String): Float =
        when (metric) {
            AlarmMetric.SPEED -> com.eried.eucplanet.util.Units.speed(valueInternal, speedUnit)
            AlarmMetric.TEMPERATURE -> com.eried.eucplanet.util.Units.temperature(valueInternal, tempUnit)
            else -> valueInternal
        }
}
