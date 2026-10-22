package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wheel alarm decision end to end, minus the sounds: which rules run, what
 * each metric reads off a frame, and what fires, through the real evaluator.
 * Each test is a short ride, one frame per tick.
 */
class AlarmWheelPassTest {

    private var t = 1_000_000L
    private val evaluator = AlarmEvaluator()

    private fun rule(
        id: Long,
        metric: AlarmMetric,
        threshold: Float,
        cmp: AlarmComparator = AlarmComparator.GREATER_EQUAL,
        order: Int = id.toInt(),
        cooldown: Int = 5,
        many: Boolean = true,
        leadMs: Int = 0,
        wheel: String? = null,
    ) = AlarmRule(
        id = id, name = "r$id", sortOrder = order, metric = metric.name,
        comparator = cmp.name, threshold = threshold, cooldownSeconds = cooldown,
        repeatWhileActive = many, leadTimeMs = leadMs, wheelAddress = wheel,
    )

    /** One tick, [stepMs] after the last. Returns the ids that fired. */
    private fun tick(rules: List<AlarmRule>, data: WheelData, stepMs: Long = 250, wheel: String? = "V14") =
        AlarmWheelPass.evaluate(evaluator, rules, data, wheel, t.also { t += stepMs }).fired.map { it.ruleId }

    // --- What a frame reads ------------------------------------------------

    @Test fun `signed values alarm on their size, so braking and regen count`() {
        val braking = WheelData(speed = -32f, pwm = -85f, current = -40f, torque = -60f, phaseCurrent = -120f)
        assertEquals(32f, AlarmWheelPass.metricValue("SPEED", braking))
        assertEquals(85f, AlarmWheelPass.metricValue("PWM", braking))
        assertEquals(40f, AlarmWheelPass.metricValue("CURRENT", braking))
        assertEquals(60f, AlarmWheelPass.metricValue("TORQUE", braking))
        assertEquals(120f, AlarmWheelPass.metricValue("PHASE_CURRENT", braking))
        assertEquals(20f, AlarmWheelPass.metricValue("VOLTAGE", WheelData(voltage = 20f)))
    }

    @Test fun `numbers that do not exist yet skip the rule instead of reading as zero`() {
        val early = WheelData() // envelope, Wh/km and range start as NaN, RSSI as 0
        assertNull(AlarmWheelPass.metricValue("BATTERY_ENVELOPE", early))
        assertNull(AlarmWheelPass.metricValue("WH_PER_KM", early))
        assertNull(AlarmWheelPass.metricValue("RANGE_ESTIMATE", early))
        assertNull("0 dBm means no reading yet", AlarmWheelPass.metricValue("BT_RSSI", early))
        assertNull("no motor sensor", AlarmWheelPass.metricValue("MOTOR_TEMP", early))
        assertEquals(-70f, AlarmWheelPass.metricValue("BT_RSSI", WheelData(rssiDbm = -70)))
    }

    @Test fun `radar, GPS and unknown metrics never read from the wheel frame`() {
        val d = WheelData(speed = 50f)
        for (m in listOf("RADAR_DISTANCE", "RADAR_APPROACH_SPEED", "GPS_SPEED", "GPS_ALTITUDE",
                "EXTERNAL_GPS_SPEED", "EXTERNAL_GPS_BATTERY", "NOT_A_METRIC")) {
            assertNull(m, AlarmWheelPass.metricValue(m, d))
        }
    }

    @Test fun `a placeholder temperature from a sensor the wheel does not have is ignored`() {
        assertNull(AlarmWheelPass.metricValue("CONTROLLER_TEMP", WheelData(temperatures = listOf(40f, -273f))))
        assertEquals(40f, AlarmWheelPass.metricValue("MOTOR_TEMP", WheelData(temperatures = listOf(40f, -273f))))
    }

    // --- Rides -------------------------------------------------------------

    @Test fun `speed alarm fires on crossing, then waits out its cooldown`() {
        val rules = listOf(rule(1, AlarmMetric.SPEED, 40f, cooldown = 5))
        assertTrue(tick(rules, WheelData(speed = 35f)).isEmpty())
        assertEquals(listOf(1L), tick(rules, WheelData(speed = 41f)))
        repeat(10) { assertTrue("still in cooldown", tick(rules, WheelData(speed = 42f)).isEmpty()) }
        // 11 ticks x 250 ms = 2.75 s so far; well past 5 s it repeats ("Many").
        var repeated = false
        repeat(20) { if (tick(rules, WheelData(speed = 42f)).isNotEmpty()) repeated = true }
        assertTrue("Many re-alerts after the cooldown", repeated)
    }

    @Test fun `a Once alarm fires one time per crossing and again after dropping below`() {
        val rules = listOf(rule(1, AlarmMetric.PWM, 80f, many = false, cooldown = 0))
        assertEquals(listOf(1L), tick(rules, WheelData(pwm = 82f)))
        repeat(20) { assertTrue(tick(rules, WheelData(pwm = 90f)).isEmpty()) }
        tick(rules, WheelData(pwm = 50f))
        assertEquals("a fresh crossing fires again", listOf(1L), tick(rules, WheelData(pwm = 81f)))
    }

    @Test fun `two speed tiers, the higher one fires when crossed even while the lower is cooling down`() {
        val rules = listOf(rule(1, AlarmMetric.SPEED, 30f, cooldown = 30), rule(2, AlarmMetric.SPEED, 35f, cooldown = 30))
        assertEquals(listOf(1L), tick(rules, WheelData(speed = 31f)))
        assertEquals("35 is its own alarm, not eaten by 30", listOf(2L), tick(rules, WheelData(speed = 36f)))
    }

    @Test fun `speed and PWM together, only the group higher in the list sounds`() {
        // PWM dragged above speed in the list: PWM has priority.
        val rules = listOf(
            rule(1, AlarmMetric.PWM, 80f, order = 0, cooldown = 3),
            rule(2, AlarmMetric.SPEED, 40f, order = 1, cooldown = 3),
        )
        val hard = WheelData(speed = 45f, pwm = 85f)
        assertEquals("one alarm per tick, the top group", listOf(1L), tick(rules, hard))
        assertEquals("PWM cooling down, speed fills the gap", listOf(2L), tick(rules, hard))
        repeat(4) { tick(rules, hard) }
        // Past PWM's 3 s cooldown it takes the slot back.
        var pwmAgain = false
        repeat(12) { if (1L in tick(rules, hard)) pwmAgain = true }
        assertTrue(pwmAgain)
    }

    @Test fun `a lower Once alarm is not lost while a higher one is sounding`() {
        val rules = listOf(
            rule(1, AlarmMetric.PWM, 80f, order = 0, cooldown = 1),
            rule(2, AlarmMetric.BATTERY, 20f, cmp = AlarmComparator.LESS_THAN, order = 1, many = false),
        )
        // Both cross on the same tick: PWM wins, battery must still come later.
        assertEquals(listOf(1L), tick(rules, WheelData(pwm = 85f, batteryPercent = 18)))
        var batteryFired = false
        repeat(8) { if (2L in tick(rules, WheelData(pwm = 85f, batteryPercent = 18))) batteryFired = true }
        assertTrue("the battery alarm fires once PWM is in cooldown", batteryFired)
    }

    @Test fun `low battery and high temperature use their own comparators`() {
        val rules = listOf(
            rule(1, AlarmMetric.BATTERY, 15f, cmp = AlarmComparator.LESS_THAN),
            rule(2, AlarmMetric.TEMPERATURE, 70f),
        )
        assertTrue(tick(rules, WheelData(batteryPercent = 40, maxTemperature = 50f)).isEmpty())
        assertEquals(listOf(1L), tick(rules, WheelData(batteryPercent = 14, maxTemperature = 50f)))
    }

    @Test fun `a rule bound to another wheel stays silent, an unbound one still works`() {
        val rules = listOf(
            rule(1, AlarmMetric.SPEED, 30f, wheel = "OTHER"),
            rule(2, AlarmMetric.PWM, 80f),
        )
        val result = AlarmWheelPass.evaluate(evaluator, rules, WheelData(speed = 50f, pwm = 90f), "V14", t)
        assertEquals(listOf(2L), result.rules.map { it.id })
        assertEquals(listOf(2L), result.fired.map { it.ruleId })
        // Same rules on the bound wheel: its speed rule runs.
        val onOther = AlarmWheelPass.evaluate(AlarmEvaluator(), rules, WheelData(speed = 50f, pwm = 10f), "OTHER", t)
        assertEquals(listOf(1L), onOther.fired.map { it.ruleId })
    }

    @Test fun `with no wheel connected only unbound rules run`() {
        val rules = listOf(rule(1, AlarmMetric.SPEED, 30f, wheel = "V14"), rule(2, AlarmMetric.SPEED, 30f))
        val r = AlarmWheelPass.evaluate(evaluator, rules, WheelData(speed = 40f), null, t)
        assertEquals(listOf(2L), r.rules.map { it.id })
    }

    @Test fun `a predictive PWM alarm fires before the threshold when PWM climbs fast`() {
        val rules = listOf(rule(1, AlarmMetric.PWM, 90f, leadMs = 2000))
        var firedAt: Float? = null
        var pwm = 40f
        while (pwm < 90f && firedAt == null) {
            if (tick(rules, WheelData(pwm = pwm)).isNotEmpty()) firedAt = pwm
            pwm += 5f // 20 %/s at 250 ms ticks
        }
        val at = firedAt
        assertTrue("fired early, at $at", at != null && at < 90f)
    }

    @Test fun `the same PWM held flat never trips a predictive alarm`() {
        val rules = listOf(rule(1, AlarmMetric.PWM, 90f, leadMs = 2000))
        repeat(40) { assertTrue(tick(rules, WheelData(pwm = 70f)).isEmpty()) }
    }

    @Test fun `early in a ride the battery estimate is skipped, then alarms normally`() {
        val rules = listOf(rule(1, AlarmMetric.BATTERY_ENVELOPE, 30f, cmp = AlarmComparator.LESS_THAN))
        repeat(5) { assertTrue(tick(rules, WheelData(batteryPercent = 10)).isEmpty()) }
        assertEquals(listOf(1L), tick(rules, WheelData(batteryEnvelope = 25f)))
    }
}
