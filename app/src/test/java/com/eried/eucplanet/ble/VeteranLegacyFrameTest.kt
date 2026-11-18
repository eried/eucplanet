package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/** Real LK13709 Service Mode notifications captured from the wheel. */
class VeteranLegacyFrameTest {
    private fun bytes(hex: String): ByteArray =
        hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val firstHead = bytes("dc5a5c2025590000907200139072001300000e91")
    private val firstTail = bytes("0b8e000001f8024604520002ffdd0009")
    private val firstFrame = firstHead + firstTail

    private fun telemetry(adapter: VeteranAdapter, bytes: ByteArray) =
        adapter.onRawNotification(bytes).filterIsInstance<DecodeResult.Telemetry>().map { it.data }

    private fun crcFrame(len: Int): ByteArray =
        ByteArray(len + 4).also { frame ->
            frame[0] = 0xDC.toByte()
            frame[1] = 0x5A
            frame[2] = 0x5C
            frame[3] = len.toByte()
            val crc = CRC32().apply { update(frame, 0, len) }.value
            for (i in 0..3) frame[len + i] = (crc ushr (24 - i * 8)).toByte()
        }

    @Test fun `LK13709 split frame decodes actual measurements`() {
        val adapter = VeteranAdapter()
        adapter.notifyConnectingTo("LK13709")
        assertTrue(telemetry(adapter, firstHead).isEmpty())
        val data = telemetry(adapter, firstTail).single()
        assertEquals(95.61f, data.voltage, 0.01f)
        assertEquals(0f, data.speed, 0.01f)
        assertEquals(0f, data.phaseCurrent, 0.01f)
        assertEquals(37.29f, data.maxTemperature, 0.01f)
        assertEquals(1282.162f, data.tripDistance, 0.001f)
        assertEquals(1282.162f, data.totalDistance, 0.001f)
        assertEquals(50.4f, data.wheelAlarmSpeedKmh, 0.01f)
        assertEquals(58.2f, data.wheelMaxSpeedKmh, 0.01f)
        assertTrue(data.batteryPercent > 0)
        assertEquals(0, firstFrame[22].toInt())
        assertEquals(0, firstFrame[23].toInt())
        assertEquals(0, firstFrame[30].toInt())
    }

    @Test fun `later rider diagnostics decode captured LK13709 frames`() {
        val captures = listOf(
            bytes("dc5a5c20256000009175001391750013fffd0cfb" +
                "0dee000001f8024604520002ffe3000c") to 95.68f,
            bytes("dc5a5c20256b000091fc001391fc0013fffd09a4" +
                "0e06000001f8024604520002ffe50029") to 95.79f,
        )
        val adapter = VeteranAdapter().apply { notifyConnectingTo("LK13709") }

        captures.forEach { (frame, voltage) ->
            assertEquals(voltage, telemetry(adapter, frame).single().voltage, 0.01f)
        }
    }

    @Test fun `CRC mode latches until disconnect`() {
        val adapter = VeteranAdapter()
        adapter.onRawNotification(crcFrame(39))

        assertTrue(telemetry(adapter, firstFrame).isEmpty())
        assertEquals(1, telemetry(adapter, crcFrame(32)).size)

        adapter.onDisconnect()
        assertEquals(1, telemetry(adapter, firstFrame).size)
    }

    @Test fun `moving LK13709 frames update speed then distance`() {
        val adapter = VeteranAdapter()
        adapter.notifyConnectingTo("LK13709")
        val moving = bytes("dc5a5c2025540018907200139072001300500e96" +
            "0e0f000001f8024604520002ffc70161")
        val later = bytes("dc5a5c2025530019907d0013907d0013003f0ea4" +
            "0e0f000001f8024604520002ffe700b4")
        val atStart = telemetry(adapter, firstFrame).single()
        val inMotion = telemetry(adapter, moving).single()
        val afterTravel = telemetry(adapter, later).single()
        assertEquals(2.4f, inMotion.speed, 0.01f)
        assertEquals(atStart.tripDistance, inMotion.tripDistance, 0.001f)
        assertEquals(1282.173f, afterTravel.tripDistance, 0.001f)
        assertEquals(1282.173f, afterTravel.totalDistance, 0.001f)
    }

    @Test fun `notifications can cross short frame boundaries`() {
        val adapter = VeteranAdapter()
        adapter.notifyConnectingTo("LK13709")
        // Service Mode log lines 129–134: 20 + 20 + 20 + 20 + 20 + 8 bytes.
        val chunks = listOf(
            "dc5a5c2025590000907200139072001300000e94",
            "0b84000001f8024604520002ffe30008dc5a5c20",
            "255900009072001390720013fffd0e940b830000",
            "01f8024604520002ffe60010dc5a5c2025590000",
            "907200139072001300000e940b83000001f80246",
            "04520002ffe30009"
        )
        assertEquals(listOf(0, 1, 0, 1, 0, 1),
            chunks.map { telemetry(adapter, bytes(it)).size })
    }

    @Test fun `LK13709 frames survive other splits and concatenation`() {
        val moving = bytes("dc5a5c2025540018907200139072001300500e96" +
            "0e0f000001f8024604520002ffc70161")
        val adapter = VeteranAdapter().apply { notifyConnectingTo("LK13709") }
        assertTrue(telemetry(adapter, moving.copyOfRange(0, 7)).isEmpty())
        assertTrue(telemetry(adapter, moving.copyOfRange(7, 21)).isEmpty())
        val split = telemetry(adapter, moving.copyOfRange(21, moving.size)).single()
        assertEquals(2.4f, split.speed, 0.01f)

        val concatenated = telemetry(VeteranAdapter(), firstFrame + moving)
        assertEquals(2, concatenated.size)
        assertEquals(0f, concatenated[0].speed, 0.01f)
        assertEquals(2.4f, concatenated[1].speed, 0.01f)
    }

    @Test fun `truncated and malformed short candidates resync without false telemetry`() {
        val adapter = VeteranAdapter()
        assertTrue(telemetry(adapter, firstFrame.copyOfRange(0, 35)).isEmpty())
        assertEquals(1, telemetry(adapter, firstFrame.copyOfRange(35, 36)).size)
        val invalidLen = bytes("dc5a5c0f")
        val invalidReserved = firstFrame.copyOf().also { it[22] = 1 }
        val invalidCharge = firstFrame.copyOf().also { it[23] = 2 }
        val invalidMode = firstFrame.copyOf().also { it[30] = 3 }
        for (invalid in listOf(invalidLen, invalidReserved, invalidCharge, invalidMode)) {
            val fresh = VeteranAdapter()
            assertEquals(1, telemetry(fresh, invalid + firstFrame).size)
        }
    }

    @Test fun `unobserved short lengths and impossible telemetry cannot become peaks`() {
        val invalidLength = firstFrame.copyOfRange(0, 20).also { it[3] = 0x10 }
        val impossibleVoltage = firstFrame.copyOf().also {
            it[4] = 0xFF.toByte()
            it[5] = 0xFF.toByte()
        }
        val falseMagicInsidePayload = firstFrame.copyOf().also {
            it[8] = 0xDC.toByte()
            it[9] = 0x5A
            it[10] = 0x5C
            it[11] = 0x10
            it[22] = 1 // Force the outer damaged frame to resynchronize.
        }
        for (invalid in listOf(invalidLength, impossibleVoltage, falseMagicInsidePayload)) {
            val adapter = VeteranAdapter()
            adapter.notifyConnectingTo("Sherman-Max")
            val accepted = telemetry(adapter, invalid + firstFrame)
            assertEquals(1, accepted.size)
            assertEquals(95.61f, accepted.single().voltage, 0.01f)
        }
    }
}
