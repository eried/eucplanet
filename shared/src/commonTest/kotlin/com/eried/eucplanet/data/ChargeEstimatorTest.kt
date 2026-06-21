package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.WheelData
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChargeEstimatorTest {
    @Test fun estimatesRateAndEta() {
        val est = ChargeEstimator(targetPercent = 80f)
        var s = est.step(WheelData(batteryPercent = 50, current = -2f, voltage = 84f, timestamp = 0L))
        s = est.step(WheelData(batteryPercent = 51, current = -2f, voltage = 84f, timestamp = 60_000L))
        s = est.step(WheelData(batteryPercent = 52, current = -2f, voltage = 84f, timestamp = 120_000L))
        s = est.step(WheelData(batteryPercent = 53, current = -2f, voltage = 84f, timestamp = 180_000L))
        assertTrue(s.charging, "should detect charging")
        assertTrue(s.ratePctPerMin in 0.8f..1.2f, "rate ~1%/min, got ${s.ratePctPerMin}")
        assertNotNull(s.minutesToTarget)
        assertTrue(s.minutesToTarget!! in 24f..30f, "to 80% ≈ 27 min, got ${s.minutesToTarget}")
        assertTrue(s.energyWh > 0f, "energy integrated")
    }

    @Test fun notChargingWhenIdle() {
        val est = ChargeEstimator()
        var s = est.step(WheelData(batteryPercent = 70, current = 0f, timestamp = 0L))
        s = est.step(WheelData(batteryPercent = 70, current = 0f, timestamp = 120_000L))
        assertTrue(!s.charging, "flat battery + no current = not charging")
    }
}
