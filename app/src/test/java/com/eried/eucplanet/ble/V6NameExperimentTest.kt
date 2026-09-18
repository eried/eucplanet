package com.eried.eucplanet.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The InMotion V6 (2025) connects over Nordic UART and answered none of the
 * V14 queries for 15 s (tester diagnostics, 2026-09-18). Until someone
 * captures the official app talking to one, the experiment is to speak the
 * P6's extended-only command set to it, since that wheel gave the legacy
 * queries the same silence. This holds the two decisions that make the
 * experiment happen: the name reaches the V2 adapter, and the V2 adapter
 * takes the P6 branch for it without pretending it is a P6.
 */
class V6NameExperimentTest {

    @Test fun `a V6 name routes to the V2 family, not V1`() {
        assertEquals(WheelFamily.INMOTION_V2, wheelFamilyForName("V6-800679D7"))
        assertEquals(WheelFamily.INMOTION_V2, wheelFamilyForName("inmotion-v6"))
        // The wheels that really speak V1 still do.
        assertEquals(WheelFamily.INMOTION_V1, wheelFamilyForName("V8S-1234"))
        assertEquals(WheelFamily.INMOTION_V1, wheelFamilyForName("V10F-1"))
        assertEquals(WheelFamily.INMOTION_V1, wheelFamilyForName("V5F-1"))
    }

    @Test fun `the V6 name rule is the token, not a substring`() {
        assertTrue(InMotionV2Adapter.isV6NameForTest("V6-800679D7"))
        assertTrue(InMotionV2Adapter.isV6NameForTest("inmotion-v6"))
        assertFalse("V60 is not a V6", InMotionV2Adapter.isV6NameForTest("V60-1"))
        assertFalse(InMotionV2Adapter.isV6NameForTest("KS-16X"))
        assertFalse(InMotionV2Adapter.isV6NameForTest(null))
    }

    @Test fun `a V6 takes the P6 command set but keeps its own name and no typed model`() {
        val a = InMotionV2Adapter()
        val model = a.notifyConnectingTo("V6-800679D7")
        assertEquals("InMotion V6", model?.name)
        assertNull("no typed model until the wheel says what it is", model?.model)
        assertArrayEquals(InMotionV2Commands.getP6RealTimeData(), a.pollRealtime())
        assertArrayEquals(InMotionV2Commands.getP6Settings(), a.pollSettings())
        assertTrue("the P6 connect handshake runs too", a.requiresConnectAuth())

        // A V14 after it goes back to the V14 dialect.
        a.onDisconnect()
        a.notifyConnectingTo("V14-1")
        assertArrayEquals(InMotionV2Commands.getRealTimeData(), a.pollRealtime())
    }
}
