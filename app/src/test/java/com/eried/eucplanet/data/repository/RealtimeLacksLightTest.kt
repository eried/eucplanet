package com.eried.eucplanet.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which wheels keep the light state the rider asked for over what a frame reads. */
class RealtimeLacksLightTest {

    @Test fun `a V6 keeps the state the rider asked for`() {
        assertTrue(realtimeLacksLight("InMotion V6", "inmotion_v2", null))
    }

    @Test fun `a P6 keeps it too`() {
        assertTrue(realtimeLacksLight("InMotion P6", "inmotion_v2", null))
    }

    @Test fun `a V14 or a V12 reads its light from the frame`() {
        assertFalse(realtimeLacksLight("InMotion V14 50S", "inmotion_v2", null))
        assertFalse(realtimeLacksLight("InMotion V12 Pro", "inmotion_v2", null))
        assertFalse(realtimeLacksLight(null, "inmotion_v2", null))
    }

    @Test fun `an InMotion V1 counts until it has reported its light`() {
        assertTrue(realtimeLacksLight("InMotion V8S", "inmotion_v1", null))
        assertFalse(realtimeLacksLight("InMotion V8S", "inmotion_v1", false))
    }
}
