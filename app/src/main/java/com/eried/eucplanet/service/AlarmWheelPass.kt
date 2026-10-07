package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.MetricRegistry
import com.eried.eucplanet.data.model.WheelData
import kotlin.math.absoluteValue

/**
 * The wheel-telemetry half of [AlarmEngine] with no Android in it: which rules
 * run, what each metric reads from a frame, and what the evaluator decides.
 * The engine adds the sounds, voice, vibration and logging around the result.
 * Kept separate so the whole decision (rule filter, metric reading, priority,
 * cooldown, prediction) is testable with plain JUnit in AlarmWheelPassTest.
 */
internal object AlarmWheelPass {

    /** The rules that ran this tick and the ones that fire. */
    data class Result(val rules: List<AlarmRule>, val fired: List<AlarmEvaluator.Fired>)

    /** Unbound rules always apply; a rule bound to a wheel only while that
     *  wheel is the connected one. Every evaluate path uses this. */
    fun applies(rule: AlarmRule, connectedAddress: String?): Boolean =
        rule.wheelAddress == null || rule.wheelAddress == connectedAddress

    fun toEvaluatorRule(rule: AlarmRule) = AlarmEvaluator.Rule(
        id = rule.id,
        metric = rule.metric,
        comparator = rule.comparator,
        threshold = rule.threshold,
        cooldownSeconds = rule.cooldownSeconds,
        repeatWhileActive = rule.repeatWhileActive,
        leadTimeMs = rule.leadTimeMs,
    )

    /**
     * One wheel tick. Group priority is the order metrics first appear when
     * the rules are sorted by sortOrder (the list order the rider drags): only
     * the highest-priority ready group sounds, lower ones fill its cooldown.
     */
    fun evaluate(
        evaluator: AlarmEvaluator,
        enabledRules: List<AlarmRule>,
        data: WheelData,
        connectedAddress: String?,
        nowMs: Long,
    ): Result {
        val rules = enabledRules.filter { applies(it, connectedAddress) }
        val metricPriority = rules.sortedBy { it.sortOrder }.map { it.metric }.distinct()
        val fired = evaluator.evaluate(
            rules.map { toEvaluatorRule(it) },
            nowMs,
            AlarmEvaluator.NoReading.SKIP,
            metricPriority,
        ) { metric -> metricValue(metric, data) }
        return Result(rules, fired)
    }

    /**
     * What an alarm on [metric] reads from a wheel frame, or null to skip its
     * rules this tick. Signed values (speed, PWM, currents, torque) compare by
     * magnitude, so braking and regen count as much as driving.
     */
    fun metricValue(metric: String, data: WheelData): Float? {
        return try {
            val m = AlarmMetric.valueOf(metric)
            when (m) {
                AlarmMetric.SPEED,
                // PWM is the registry's LOAD, by alias.
                AlarmMetric.PWM,
                AlarmMetric.CURRENT,
                AlarmMetric.TORQUE,
                AlarmMetric.PHASE_CURRENT,
                AlarmMetric.LATERAL_G -> wheelRead(m, data)?.absoluteValue
                AlarmMetric.BATTERY,
                AlarmMetric.TEMPERATURE,
                AlarmMetric.VOLTAGE,
                AlarmMetric.WH_CONSUMED,
                AlarmMetric.G_FORCE -> wheelRead(m, data)
                // NaN for the first half minute of a ride, which the evaluator
                // skips: a rule must not fire on a number that does not exist
                // yet. The registry reads NaN as null.
                AlarmMetric.BATTERY_ENVELOPE -> wheelRead(m, data)
                // NaN until the window has enough distance. Null skips the rule
                // rather than comparing against a number that isn't one, which
                // would either never fire or fire constantly.
                AlarmMetric.WH_PER_KM,
                AlarmMetric.RANGE_ESTIMATE -> wheelRead(m, data)
                // Null while nothing measures the tyre (skips the rule),
                // 0 kPa when a cap says the tyre is flat. See AlarmLogic.
                AlarmMetric.TIRE_PRESSURE -> AlarmLogic.tirePressureForAlarm(data)
                // The same plausibility filter the tiles and the history use,
                // so an alarm never fires on a sensor a wheel does not have or
                // on the placeholder a family sends when it has nothing. Null
                // skips the rule.
                AlarmMetric.MOTOR_TEMP,
                AlarmMetric.CONTROLLER_TEMP,
                AlarmMetric.BATTERY_TEMP -> wheelRead(m, data)
                // 0 dBm is "no read yet", not a perfect link.
                AlarmMetric.BT_RSSI -> wheelRead(m, data)
                // Radar and GPS metrics have their own entry points in the
                // engine (radar frames, location fixes). Null here keeps the
                // wheel loop from firing one of them on stale or absent data.
                AlarmMetric.RADAR_DISTANCE,
                AlarmMetric.RADAR_APPROACH_SPEED,
                AlarmMetric.GPS_SPEED,
                AlarmMetric.GPS_ALTITUDE,
                AlarmMetric.EXTERNAL_GPS_SPEED,
                AlarmMetric.EXTERNAL_GPS_BATTERY -> null
            }
        } catch (_: Exception) { null }
    }

    /**
     * [metric] read through [MetricRegistry.read]: the value as the frame
     * holds it, or null on that metric's "no value" sentinel. Alarm names are
     * registry keys or aliases.
     */
    private fun wheelRead(metric: AlarmMetric, data: WheelData): Float? =
        MetricRegistry.def(metric.name).read(data)
}
