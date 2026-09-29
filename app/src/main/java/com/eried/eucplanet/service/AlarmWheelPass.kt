package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.MetricSanity
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
            when (AlarmMetric.valueOf(metric)) {
                AlarmMetric.SPEED -> data.speed.absoluteValue
                AlarmMetric.BATTERY -> data.batteryPercent.toFloat()
                // NaN for the first half minute of a ride, which the evaluator
                // skips: a rule must not fire on a number that does not exist
                // yet.
                AlarmMetric.BATTERY_ENVELOPE -> data.batteryEnvelope.takeIf { !it.isNaN() }
                AlarmMetric.TEMPERATURE -> data.maxTemperature
                AlarmMetric.PWM -> data.pwm.absoluteValue
                AlarmMetric.VOLTAGE -> data.voltage
                AlarmMetric.CURRENT -> data.current.absoluteValue
                AlarmMetric.TORQUE -> data.torque.absoluteValue
                AlarmMetric.PHASE_CURRENT -> data.phaseCurrent.absoluteValue
                AlarmMetric.WH_CONSUMED -> data.whConsumed
                // NaN until the window has enough distance. Null skips the rule
                // rather than comparing against a number that isn't one, which
                // would either never fire or fire constantly.
                AlarmMetric.WH_PER_KM -> data.whPerKmRecent.takeIf { !it.isNaN() }
                // Null while nothing measures the tyre (skips the rule),
                // 0 kPa when a cap says the tyre is flat. See AlarmLogic.
                AlarmMetric.TIRE_PRESSURE -> AlarmLogic.tirePressureForAlarm(data)
                // The same plausibility filter the tiles and the history use,
                // so an alarm never fires on a sensor a wheel does not have or
                // on the placeholder a family sends when it has nothing. Null
                // skips the rule.
                AlarmMetric.MOTOR_TEMP -> data.temperatures.getOrNull(0)
                    ?.takeIf { MetricSanity.isPlausibleTempC(it) }
                AlarmMetric.CONTROLLER_TEMP -> data.temperatures.getOrNull(1)
                    ?.takeIf { MetricSanity.isPlausibleTempC(it) }
                AlarmMetric.BATTERY_TEMP -> data.temperatures.getOrNull(2)
                    ?.takeIf { MetricSanity.isPlausibleTempC(it) }
                AlarmMetric.G_FORCE -> data.gForce
                AlarmMetric.LATERAL_G -> data.accelX.absoluteValue
                // 0 dBm is "no read yet", not a perfect link.
                AlarmMetric.BT_RSSI -> data.rssiDbm.takeIf { it != 0 }?.toFloat()
                AlarmMetric.RANGE_ESTIMATE -> data.rangeKmEstimate.takeIf { !it.isNaN() }
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
}
