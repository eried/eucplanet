package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * InMotion V1 headlight, read from the wheel instead of assumed.
 *
 * The wheel reports its light in the slow-info reply, byte 80 of the extended
 * payload, 1 for on (WheelLog InMotionAdapter.parseSlowInfoMessage). The app
 * used to parse that byte and then drop it, keeping only what it had last
 * commanded, so a light switched on the wheel itself or a command the wheel
 * ignored left the button and auto-lights working from a guess.
 *
 * Frames here are built the way the wheel sends them: the 16-byte extended CAN
 * prefix, the payload, then the AA AA / escape / checksum / 55 55 envelope.
 */
class InMotionV1LightTest {

    private var clock = 10_000L

    private fun adapter() = InMotionV1Adapter().also {
        it.nowMs = { clock }
        it.notifyConnectingTo("V8S-81E30005")
    }

    private fun extendedFrame(canId: Int, payload: ByteArray): ByteArray {
        val can = ByteArray(16 + payload.size)
        for (i in 0..3) can[i] = (canId ushr (8 * i)).toByte()
        can[4] = payload.size.toByte()
        can[12] = 0xFE.toByte()   // extended
        can[13] = 0x05            // channel
        payload.copyInto(can, 16)
        return InMotionV1Protocol.wrap(can)
    }

    private fun slowInfo(lightByte: Int, size: Int = 140): ByteArray {
        val p = ByteArray(size)
        if (size > 80) p[80] = lightByte.toByte()
        if (size > 104) p[104] = 87  // V8S car type
        return extendedFrame(InMotionV1Protocol.CanId.SLOW_INFO, p)
    }

    private fun fastInfo(): ByteArray {
        val p = ByteArray(149)
        p[60] = 0x23  // work mode as captured on a V8S
        return extendedFrame(InMotionV1Protocol.CanId.FAST_INFO, p)
    }

    private fun InMotionV1Adapter.telemetry(): WheelData =
        onRawNotification(fastInfo())
            .filterIsInstance<DecodeResult.Telemetry>().single().data

    @Test fun `slow-info byte 80 is the headlight`() {
        assertEquals(true, InMotionV1Parser.parseSlowInfo(ByteArray(140).also { it[80] = 1 })?.lightOn)
        assertEquals(false, InMotionV1Parser.parseSlowInfo(ByteArray(140))?.lightOn)
    }

    @Test fun `a reply too short to carry byte 80 says nothing about the light`() {
        assertNull(InMotionV1Parser.parseSlowInfo(ByteArray(80))?.lightOn)
    }

    @Test fun `before the wheel has reported, telemetry does not claim a light state`() {
        val a = adapter()
        assertNull(a.telemetry().lightReported)
    }

    @Test fun `the wheel's report reaches telemetry`() {
        val a = adapter()
        a.onRawNotification(slowInfo(1))
        a.telemetry().let {
            assertEquals(true, it.lightOn)
            assertEquals(true, it.lightReported)
        }
        // Switched off on the wheel itself: the next report says so.
        a.onRawNotification(slowInfo(0))
        a.telemetry().let {
            assertEquals(false, it.lightOn)
            assertEquals(false, it.lightReported)
        }
    }

    @Test fun `a report from before the command does not undo it`() {
        val a = adapter()
        a.onRawNotification(slowInfo(1))
        a.setLight(false)
        // A slow-info already on its way still says on.
        clock += 500
        a.onRawNotification(slowInfo(1))
        assertEquals(false, a.telemetry().lightOn)
        // The wheel obeyed: a later report agrees.
        clock += InMotionV1Adapter.LIGHT_REPORT_SETTLE_MS
        a.onRawNotification(slowInfo(0))
        assertEquals(false, a.telemetry().lightOn)
    }

    @Test fun `a command the wheel ignored is corrected by the next real report`() {
        val a = adapter()
        a.onRawNotification(slowInfo(0))
        a.setLight(true)
        assertEquals(true, a.telemetry().lightOn)
        clock += InMotionV1Adapter.LIGHT_REPORT_SETTLE_MS
        a.onRawNotification(slowInfo(0))
        a.telemetry().let {
            assertEquals(false, it.lightOn)
            assertEquals(false, it.lightReported)
        }
    }

    @Test fun `a new connection starts without a light state`() {
        val a = adapter()
        a.onRawNotification(slowInfo(1))
        a.onDisconnect()
        a.notifyConnectingTo("V8S-81E30005")
        assertNull(a.telemetry().lightReported)
    }
}
