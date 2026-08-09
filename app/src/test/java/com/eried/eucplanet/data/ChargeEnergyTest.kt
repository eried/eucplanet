package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.ChargeEnergy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeEnergyTest {

    private val V2 = false   // InMotion V2 (V14) and friends: discharge is negative
    private val V1 = true    // InMotion V1 (V8S): discharge is positive

    @Test fun v2_chargingIsPositivePower_countsAsEnergyIn() {
        // 100 V x 5 A for one hour = 500 Wh into the battery.
        val wh = ChargeEnergy.stepWh(500f, 500f, 60_000L, V2)
        // One minute of it.
        assertEquals(500f / 60f, wh, 0.01f)
        assertTrue(wh > 0f)
    }

    @Test fun v2_ridingIsNegativePower_countsAsEnergyOut() {
        val wh = ChargeEnergy.stepWh(-500f, -500f, 60_000L, V2)
        assertTrue(wh < 0f)
    }

    @Test fun v1_chargingIsNegativePower_stillCountsAsEnergyIn() {
        // The V8S bug: charging reports negative power, which used to land in
        // the bucket the Battery screen labels "Used".
        val wh = ChargeEnergy.stepWh(-500f, -500f, 60_000L, V1)
        assertTrue("V1 charging must integrate as energy IN", wh > 0f)
        assertEquals(500f / 60f, wh, 0.01f)
    }

    @Test fun v1_ridingIsPositivePower_stillCountsAsEnergyOut() {
        // The V8S idle +3 W that used to be reported as "Charged".
        val wh = ChargeEnergy.stepWh(3f, 3f, 60_000L, V1)
        assertTrue("V1 riding must integrate as energy OUT", wh < 0f)
    }

    @Test fun theTwoFamilies_areExactMirrorImages() {
        val a = ChargeEnergy.stepWh(120f, 140f, 10_000L, V2)
        val b = ChargeEnergy.stepWh(120f, 140f, 10_000L, V1)
        assertEquals(a, -b, 0.0001f)
    }

    @Test fun trapezoidUsesTheAverageOfTheTwoSamples() {
        // 0 W rising to 200 W over 60 s averages 100 W, so 100 Wh x 60/3600.
        val wh = ChargeEnergy.stepWh(0f, 200f, 60_000L, V2)
        assertEquals(100f * (60f / 3600f), wh, 0.001f)
    }

    @Test fun nonPositiveElapsed_contributesNothing() {
        assertEquals(0f, ChargeEnergy.stepWh(500f, 500f, 0L, V2), 0f)
        assertEquals(0f, ChargeEnergy.stepWh(500f, 500f, -1_000L, V2), 0f)
    }

    @Test fun aGapLongerThanTheCap_contributesNothing() {
        assertEquals(0f, ChargeEnergy.stepWh(500f, 500f, ChargeEnergy.MAX_GAP_MS + 1, V2), 0f)
    }

    @Test fun aSlowChargingSampleIsNoLongerTruncated() {
        // Wheels throttle their notify rate while charging. A 30 s gap used to
        // be clamped to 5 s, losing five sixths of the energy for that step.
        val wh = ChargeEnergy.stepWh(500f, 500f, 30_000L, V2)
        assertEquals(500f * (30f / 3600f), wh, 0.01f)
    }

    @Test fun capIsSixtySeconds() {
        assertEquals(60_000L, ChargeEnergy.MAX_GAP_MS)
    }
}
