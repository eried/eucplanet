package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.WheelData
import kotlin.test.Test
import kotlin.test.assertEquals

/** Validates the speed-sign fix (issue 1): speed/PWM/current alarms watch the
 *  MAGNITUDE, so a rule still trips while rolling backward / regen-braking
 *  (signed telemetry), matching Android's `.absoluteValue`. */
class AlarmMetricTest {
    @Test fun speedPwmCurrentAreMagnitude() {
        val d = WheelData(speed = -25f, pwm = -30f, current = -10f)
        assertEquals(25f, AlarmMetric.SPEED.valueOf(d))
        assertEquals(30f, AlarmMetric.PWM.valueOf(d))
        assertEquals(10f, AlarmMetric.CURRENT.valueOf(d))
    }

    @Test fun overspeedTripsWhileReversing() {
        // A 45 km/h overspeed rule must fire at -48 km/h (reverse), not stay silent.
        val rule = AlarmRule(id = 1, metric = "SPEED", threshold = 45f)
        val reversing = WheelData(speed = -48f)
        val metric = AlarmMetric.parse(rule.metric)
        val cmp = AlarmComparator.parse(rule.comparator)
        assertEquals(true, cmp.test(metric.valueOf(reversing), rule.threshold))
    }
}
