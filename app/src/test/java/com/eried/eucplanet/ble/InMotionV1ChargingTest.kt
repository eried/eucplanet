package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InMotion V1 charging, read from the work mode.
 *
 * V8F / V8S / V10 encode their state in the high nibble of fast-info byte 60:
 * 1 shutdown, 2 drive, 3 charging (WheelLog InMotionAdapter.getWorkModeString,
 * applied to exactly these models in parseFastInfoMessage). A V8S capture read
 * 0x23 for a whole ride, which is "drive". The current cannot stand in: on the
 * charger the wheel keeps reporting its own idle draw.
 */
class InMotionV1ChargingTest {

    private fun extendedFrame(canId: Int, payload: ByteArray): ByteArray {
        val can = ByteArray(16 + payload.size)
        for (i in 0..3) can[i] = (canId ushr (8 * i)).toByte()
        can[4] = payload.size.toByte()
        can[12] = 0xFE.toByte()   // extended
        can[13] = 0x05            // channel
        payload.copyInto(can, 16)
        return InMotionV1Protocol.wrap(can)
    }

    private fun fastInfo(workMode: Int): ByteArray =
        ByteArray(149).also {
            it[60] = workMode.toByte()
            // 84.00 V and +0.02 A, a V8S's idle draw sitting on the charger.
            it[24] = 0xD0.toByte(); it[25] = 0x20
            it[20] = 2
        }

    private fun charging(workMode: Int, model: InMotionV1Model?): Boolean =
        InMotionV1Parser.parseFastInfo(fastInfo(workMode), model)!!.charging

    @Test fun `work mode 3x is charging on a V8S`() {
        assertTrue(charging(0x30, InMotionV1Model.V8S))
        assertTrue(charging(0x31, InMotionV1Model.V8S))  // low nibble 1 is "engine off", still charging
    }

    @Test fun `drive and shutdown are not charging`() {
        assertFalse(charging(0x23, InMotionV1Model.V8S))  // as captured while riding
        assertFalse(charging(0x10, InMotionV1Model.V8S))
    }

    @Test fun `the V8F and the V10 family read it too`() {
        val models = listOf(InMotionV1Model.V8F) + InMotionV1Model.values().filter { it.isV10Family }
        for (m in models) assertTrue(m.name, charging(0x30, m))
    }

    @Test fun `models on the legacy work-mode table never read as charging`() {
        // Low nibble 3 is "large angle" on the legacy table, and 0x30 there is
        // idle; neither is a charge. Glide 3 is on that table in WheelLog too.
        for (m in listOf(InMotionV1Model.V8, InMotionV1Model.GLIDE3, InMotionV1Model.V5F, InMotionV1Model.L6)) {
            assertFalse(m.name, charging(0x30, m))
            assertFalse(m.name, charging(0x33, m))
        }
        assertFalse(charging(0x30, null))
    }

    @Test fun `the adapter passes the charge flag through for a V8S`() {
        val a = InMotionV1Adapter()
        a.notifyConnectingTo("V8S-81E30005")
        val data = a.onRawNotification(extendedFrame(InMotionV1Protocol.CanId.FAST_INFO, fastInfo(0x30)))
            .filterIsInstance<DecodeResult.Telemetry>().single().data
        assertTrue(data.charging)
        assertEquals(84f, data.voltage, 0.001f)
    }
}
