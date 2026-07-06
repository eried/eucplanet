package com.eried.eucplanet.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricSanityTest {

    @Test fun rejectsEmptySensorSlotValue() {
        // (0x00 & 0xFF) - 176 = -176, the InMotion empty-slot artefact.
        assertFalse(MetricSanity.isPlausibleTempC(-176f))
    }

    @Test fun keepsGenuineColdReadings() {
        // 0 C in winter is real, not an error -> must be kept.
        assertTrue(MetricSanity.isPlausibleTempC(0f))
        assertTrue(MetricSanity.isPlausibleTempC(-20f))
    }

    @Test fun keepsNormalAndHotReadings() {
        assertTrue(MetricSanity.isPlausibleTempC(25f))
        assertTrue(MetricSanity.isPlausibleTempC(95f))
    }

    @Test fun rejectsImpossiblyHotAndNaN() {
        assertFalse(MetricSanity.isPlausibleTempC(200f))
        assertFalse(MetricSanity.isPlausibleTempC(Float.NaN))
    }
}
