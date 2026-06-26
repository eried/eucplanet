package com.eried.eucplanet.service

import android.content.Context
import android.util.Log
import com.eried.eucplanet.data.db.AlarmDao
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
import java.util.Locale
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

    // Serializes evaluate() bodies so the read-check-write on the evaluator's
    // per-rule state is atomic. Without this, telemetry updates ~250ms apart
    // could both pass the cooldown check before either records the fire,
    // producing duplicate alarm fires.
    private val evalMutex = Mutex()

    // The pure, Android-free firing core. Holds all per-rule edge / cooldown
    // state and per-metric trend history; unit-tested in AlarmEvaluatorTest.
    private val evaluator = AlarmEvaluator()

    private fun AlarmRule.toEvaluatorRule() = AlarmEvaluator.Rule(
        id = id,
        metric = metric,
        comparator = comparator,
        threshold = threshold,
        cooldownSeconds = cooldownSeconds,
        repeatWhileActive = repeatWhileActive,
        leadTimeMs = leadTimeMs,
    )

    /**
     * Called on each telemetry update. Evaluates alarm rules against current data.
     *
     * Per metric, the *most severe* matching rule fires -- not the first one in
     * the list. So two speed alarms at 30 and 35 both work: crossing 35 fires
     * the 35 rule even though the 30 rule is also (still) matched and may be
     * mid-cooldown. Order in the list no longer changes which alarm you get,
     * which kills the old "the lower alarm ate the higher one" bug. Each rule
     * keeps its own cooldown / edge state, so escalating through tiers fires
     * each tier once as you reach it.
     */
    fun evaluate(data: WheelData) {
        // Quake-console godmode: silences all alarms for the session. The user types
        // `godmode` in the Settings search bar to flip it; lost on app restart.
        if (cheatState.godmode.value) return
        scope.launch {
            evalMutex.withLock {
            // Persisted session mute, set by the dashboard's MUTE_ALARMS action.
            // Read inside the launch so we always see the latest store value.
            if (settingsRepository.get().alarmsMuted) return@withLock
            val rules = alarmDao.getEnabled()
            val now = System.currentTimeMillis()

            val fired = evaluator.evaluate(
                rules.map { it.toEvaluatorRule() },
                now,
                AlarmEvaluator.NoReading.SKIP,
            ) { metric ->
                // Radar metrics report null here -- they're driven by
                // [evaluateRadar] off the radar frame, not wheel telemetry.
                getMetricValue(metric, data)
            }

            if (fired.isNotEmpty()) {
                val s = settingsRepository.get()
                val su = com.eried.eucplanet.util.Units.effectiveSpeedUnit(s)
                val du = com.eried.eucplanet.util.Units.effectiveDistanceUnit(s)
                val tu = com.eried.eucplanet.util.Units.effectiveTempUnit(s)
                val byId = rules.associateBy { it.id }
                for (f in fired) {
                    val rule = byId[f.ruleId] ?: continue
                    Log.i(TAG, "Alarm fired: '${rule.name}' ${rule.metric} ${rule.comparator} ${rule.threshold} (value=${f.value}, lead=${rule.leadTimeMs}ms)")
                    executeActions(rule, data, f.value, su, du, tu)
                }
            }

            // Clean up state + sample history for rules / metrics no longer
            // present. Driven from the wheel path, which sees all enabled rules.
            evaluator.prune(
                rules.mapTo(mutableSetOf()) { it.id },
                rules.mapTo(mutableSetOf()) { it.metric },
            )
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
                if (rules.isEmpty()) return@withLock
                val now = System.currentTimeMillis()

                // RESET: an empty frame (lane clear) disarms the radar rules and
                // drops their trend, so the next car counts as a fresh crossing.
                val fired = evaluator.evaluate(
                    rules.map { it.toEvaluatorRule() },
                    now,
                    AlarmEvaluator.NoReading.RESET,
                ) { metric -> getRadarMetricValue(metric, frame) }

                if (fired.isNotEmpty()) {
                    val byId = rules.associateBy { it.id }
                    for (f in fired) {
                        val rule = byId[f.ruleId] ?: continue
                        Log.i(TAG, "Radar alarm fired: '${rule.name}' ${rule.metric} ${rule.comparator} ${rule.threshold} (value=${f.value})")
                        executeRadarActions(rule, frame, f.value)
                    }
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
                val freq = if (rule.beepModulation == 1)
                    AlarmLogic.modulatedBeepHz(rule.beepFrequency, triggerValue, rule.comparator, rule.threshold)
                else rule.beepFrequency
                tonePlayer.playBeep(freq, rule.beepDurationMs, rule.beepCount)
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
                val freq = if (rule.beepModulation == 1)
                    AlarmLogic.modulatedBeepHz(rule.beepFrequency, triggerValue, rule.comparator, rule.threshold)
                else rule.beepFrequency
                tonePlayer.playBeep(freq, rule.beepDurationMs, rule.beepCount)
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
            // Force a dot decimal for numbers that go to TTS -- some voices in
            // comma-decimal locales read "7,1" as "seven one" rather than
            // "seven point one". Same convention as nav/NavFormat.oneDecimal.
            .replace("{voltage}", String.format(Locale.US, "%.1f", data.voltage))
            .replace("{current}", String.format(Locale.US, "%.1f", data.current.absoluteValue))
            .replace("{trip}", String.format(Locale.US, "%.1f", tripConverted))
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
