package com.eried.eucplanet.service

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * next-experimental's case from ChargingEstimatorTest. [chargingSampleCap] is
 * internal to shared, so this case lives here and the rest of that test stays
 * in app/src/test.
 */
class ChargingSampleCapTest {

    @Test
    fun theSampleCapLeavesRoomForEveryWindowInTheAdvancedRange() {
        // 30 s to 20 min, at up to 50 readings a second.
        for (windowMs in listOf(30_000L, 300_000L, 1_200_000L)) {
            assertTrue(chargingSampleCap(windowMs) >= windowMs / 20L)
        }
    }
}
