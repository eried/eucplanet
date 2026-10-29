package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The history half of TpmsReachesTheAppTest (app module): WheelRepository's
 * sampler for TIRE_PRESSURE, which moved to :shared with it.
 */
class TirePressureHistoryTest {

    @Test fun `history records a flat tyre and skips a wheel with no sensor`() {
        val sampler = EXTRA_HISTORY_METRICS.first { it.first == "TIRE_PRESSURE" }.second

        // Nothing measuring: no sample. Recording zero would draw a wheel that
        // has no sensor as a tyre that has been flat since the app started.
        assertNull(sampler(WheelData()))

        // A cap on a flat tyre: recorded, because it is a reading.
        assertEquals(0f, sampler(WheelData(tirePressureKpa = 0f, hasTirePressure = true)))

        assertEquals(360f, sampler(WheelData(tirePressureKpa = 360f, hasTirePressure = true)))
    }
}
