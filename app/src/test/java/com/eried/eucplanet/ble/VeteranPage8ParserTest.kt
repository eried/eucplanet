package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class VeteranPage8ParserTest {

    private fun createPage8Frame(
        payload24: ByteArray = ByteArray(24) { 0x80.toByte() }
    ): ByteArray {
        val frame = ByteArray(75)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A.toByte()
        frame[2] = 0x5C.toByte()
        frame[3] = 71.toByte() // LEN = 71
        frame[46] = 8.toByte()  // pageId = 8
        System.arraycopy(payload24, 0, frame, 47, payload24.size.coerceAtMost(24))

        val crc = CRC32().apply { update(frame, 0, 71) }.value
        frame[71] = ((crc shr 24) and 0xFF).toByte()
        frame[72] = ((crc shr 16) and 0xFF).toByte()
        frame[73] = ((crc shr 8) and 0xFF).toByte()
        frame[74] = (crc and 0xFF).toByte()
        return frame
    }

    @Test
    fun `real world Aeon capture parses expected values`() {
        // Captured from a NOSFET Aeon (firmware 503.0.02):
        // Bytes 47..70: 00 00 80 50 00 2a 41 23 1e 00 00 00 00 80 80 28 00 3e 91 32 80 00 80 80
        val capturedPayload = byteArrayOf(
            0x00.toByte(), 0x00.toByte(), 0x80.toByte(), 0x50.toByte(),
            0x00.toByte(), 0x2A.toByte(), 0x41.toByte(), 0x23.toByte(),
            0x1E.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x80.toByte(), 0x80.toByte(), 0x28.toByte(),
            0x00.toByte(), 0x3E.toByte(), 0x91.toByte(), 0x32.toByte(),
            0x80.toByte(), 0x00.toByte(), 0x80.toByte(), 0x80.toByte()
        )
        val frame = createPage8Frame(capturedPayload)

        val settings = VeteranParser.parsePage8(frame)
        assertNotNull(settings)
        assertEquals(0, settings!!.headlightMode)
        assertEquals(80, settings.pedalHardnessPercent)
        assertEquals(42, settings.tiltbackSpeedKmh)
        assertEquals(65, settings.pwmTiltbackPercent)
        assertEquals(35, settings.alarmSpeedKmh)
        assertEquals(30, settings.displayBrightnessPercent)
        assertEquals(0, settings.gyroCalibrationState)
        assertEquals(false, settings.transportMode)
        assertEquals(0, settings.displayUnits)
        assertEquals(0, settings.voltageAdjustmentTenths)
        assertNull(settings.lowBatteryMode)  // sentinel 0x80
        assertNull(settings.highSpeedMode)  // sentinel 0x80
        assertEquals(0, settings.keyToneVolumePercent)
        assertEquals(62, settings.maxChargeVoltageRaw)
        assertEquals(145, settings.voltageBase)
        assertEquals(50, settings.dynamicAssistPercent)
        assertEquals(0, settings.pedalDipCompensationPercent)

        // 145V base + 62/10V = 151.2V
        assertEquals(151.2f, settings.maxChargeVoltageVolts!!, 0.01f)
    }

    @Test
    fun `sentinel 0x80 yields null for all fields`() {
        val allSentinels = ByteArray(24) { 0x80.toByte() }
        val frame = createPage8Frame(allSentinels)

        val settings = VeteranParser.parsePage8(frame)
        assertNotNull(settings)
        assertNull(settings!!.headlightMode)
        assertNull(settings.pedalHardnessPercent)
        assertNull(settings.tiltbackSpeedKmh)
        assertNull(settings.pwmTiltbackPercent)
        assertNull(settings.alarmSpeedKmh)
        assertNull(settings.displayBrightnessPercent)
        assertNull(settings.gyroCalibrationState)
        assertNull(settings.transportMode)
        assertNull(settings.displayUnits)
        assertNull(settings.voltageAdjustmentTenths)
        assertNull(settings.lowBatteryMode)
        assertNull(settings.highSpeedMode)
        assertNull(settings.keyToneVolumePercent)
        assertNull(settings.maxChargeVoltageRaw)
        assertNull(settings.voltageBase)
        assertNull(settings.dynamicAssistPercent)
        assertNull(settings.pedalDipCompensationPercent)
        assertNull(settings.maxChargeVoltageVolts)
    }

    @Test
    fun `signed voltage adjustment decodes positive and negative offsets`() {
        val payloadPos = ByteArray(24) { 0x80.toByte() }
        payloadPos[12] = 5.toByte() // byte 59 -> offset 59 - 47 = 12
        val settingsPos = VeteranParser.parsePage8(createPage8Frame(payloadPos))
        assertEquals(5, settingsPos?.voltageAdjustmentTenths)

        val payloadNeg = ByteArray(24) { 0x80.toByte() }
        payloadNeg[12] = (-10).toByte()
        val settingsNeg = VeteranParser.parsePage8(createPage8Frame(payloadNeg))
        assertEquals(-10, settingsNeg?.voltageAdjustmentTenths)
    }

    @Test
    fun `max charge voltage calculates correctly for 30S and 36S packs`() {
        val payload36S = ByteArray(24) { 0x80.toByte() }
        payload36S[17] = 62.toByte()  // byte 64: raw 62
        payload36S[18] = 145.toByte() // byte 65: base 145
        val settings36S = VeteranParser.parsePage8(createPage8Frame(payload36S))
        assertEquals(151.2f, settings36S?.maxChargeVoltageVolts!!, 0.01f)

        val payload30S = ByteArray(24) { 0x80.toByte() }
        payload30S[17] = 50.toByte()  // byte 64: raw 50
        payload30S[18] = 121.toByte() // byte 65: base 121
        val settings30S = VeteranParser.parsePage8(createPage8Frame(payload30S))
        assertEquals(126.0f, settings30S?.maxChargeVoltageVolts!!, 0.01f)
    }

    @Test
    fun `transport mode and toggles decode properly`() {
        val payload = ByteArray(24) { 0x80.toByte() }
        payload[10] = 1.toByte() // byte 57: transport mode enabled
        payload[11] = 1.toByte() // byte 58: displayUnits = imperial
        payload[13] = 1.toByte() // byte 60: lowBatteryMode = 1
        payload[14] = 1.toByte() // byte 61: highSpeedMode = 1

        val settings = VeteranParser.parsePage8(createPage8Frame(payload))
        assertEquals(true, settings?.transportMode)
        assertEquals(1, settings?.displayUnits)
        assertEquals(1, settings?.lowBatteryMode)
        assertEquals(1, settings?.highSpeedMode)
    }

    @Test
    fun `frame validation rejects truncated or non-page-8 frames`() {
        val valid = createPage8Frame()
        assertNotNull(VeteranParser.parsePage8(valid))

        // Truncated frame (< 71 bytes)
        val short = valid.copyOf(70)
        assertNull(VeteranParser.parsePage8(short))

        // Wrong pageId (e.g. pageId 1)
        val wrongPage = valid.clone()
        wrongPage[46] = 1.toByte()
        assertNull(VeteranParser.parsePage8(wrongPage))
    }

    @Test
    fun `raw payload is preserved`() {
        val payload = ByteArray(24) { it.toByte() }
        val frame = createPage8Frame(payload)
        val settings = VeteranParser.parsePage8(frame)
        assertNotNull(settings)
        assertTrue(payload.contentEquals(settings!!.rawPayload))
    }

    @Test
    fun `freshness check returns expected boolean`() {
        val now = 100_000_000_000L
        val freshSettings = VeteranPage8Settings(receivedAtNanos = now - 2_000_000_000L) // 2s ago
        assertTrue(freshSettings.isFresh(nowNanos = now, maxAgeMs = 5_000))

        val staleSettings = VeteranPage8Settings(receivedAtNanos = now - 12_000_000_000L) // 12s ago
        assertFalse(staleSettings.isFresh(nowNanos = now, maxAgeMs = 5_000))
    }

    @Test
    fun `feed reassembles full frame and parses via parsePage8`() {
        val frame = createPage8Frame()
        val parser = VeteranParser()
        val frames = parser.feed(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0].isLong)
        val settings = VeteranParser.parsePage8(frames[0].bytes)
        assertNotNull(settings)
    }
}
