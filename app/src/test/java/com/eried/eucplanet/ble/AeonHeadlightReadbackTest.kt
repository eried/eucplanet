package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.HeadlightReadback
import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

class AeonHeadlightReadbackTest {
    private fun telemetry(adapter: VeteranAdapter, frame: ByteArray): WheelData =
        adapter.onRawNotification(frame).filterIsInstance<DecodeResult.Telemetry>().single().data

    // Synthetic variations of a captured packet, with recomputed CRC. The captured cycle below
    // uses the original bytes and CRCs instead. See docs/protocols/aeon-headlight-readback.md.
    private fun frame(level: Int = 0, page: Int = 1, version: Int = 44250): ByteArray {
        val bytes = capturedAeonLightCycle().first().copyOf().apply {
            this[28] = (version shr 8).toByte()
            this[29] = version.toByte()
            this[46] = page.toByte()
            this[49] = level.toByte()
        }
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        for (i in 0..3) bytes[bytes.size - 4 + i] = (crc shr (24 - 8 * i)).toByte()
        return bytes
    }

    @Test fun `captured panel cycle decodes through fragmented BLE notifications`() {
        val adapter = VeteranAdapter().apply { notifyConnectingTo("NF7445") }
        val levels = listOf(HeadlightReadback.Level.OFF, HeadlightReadback.Level.LOW,
            HeadlightReadback.Level.MEDIUM, HeadlightReadback.Level.HIGH, HeadlightReadback.Level.OFF)
        capturedAeonLightCycle().zip(levels).forEach { (frame, expected) ->
            val results = frame.toList().chunked(20).flatMap { adapter.onRawNotification(it.toByteArray()) }
            val data = results.filterIsInstance<DecodeResult.Telemetry>().single().data
            assertEquals(expected, data.headlightReadback!!.level)
            assertEquals(expected != HeadlightReadback.Level.OFF, data.lightOn)
        }
    }

    @Test fun `other pages retain the measured level and its original timestamp`() {
        val adapter = VeteranAdapter()
        val first = telemetry(adapter, frame(3)).headlightReadback
        for (page in listOf(0, 2, 3, 4, 5, 6, 7, 8)) {
            val next = telemetry(adapter, frame(0, page))
            assertEquals(first, next.headlightReadback)
            assertTrue(next.lightOn)
        }
    }

    @Test fun `unknown raw values invalidate the level without inventing an off transition`() {
        val adapter = VeteranAdapter()
        telemetry(adapter, frame(3))
        for (raw in listOf(4, 127, 128, 255)) {
            val data = telemetry(adapter, frame(raw))
            assertNotNull(data.headlightReadback)
            assertNull(data.headlightReadback!!.level)
            assertTrue(data.lightOn)
        }
        assertEquals(HeadlightReadback.Level.OFF, telemetry(adapter, frame(0)).headlightReadback!!.level)
    }

    @Test fun `corrupt and incomplete packets cannot update the readback`() {
        val adapter = VeteranAdapter()
        val first = telemetry(adapter, frame(3)).headlightReadback
        assertTrue(adapter.onRawNotification(frame(0).apply { this[49] = 1 }).isEmpty())
        assertEquals(first, telemetry(adapter, frame(page = 2)).headlightReadback)
        val valid = frame(0)
        assertTrue(adapter.onRawNotification(valid.copyOf(50)).isEmpty())
        assertEquals(HeadlightReadback.Level.OFF,
            telemetry(adapter, valid.copyOfRange(50, valid.size)).headlightReadback!!.level)
    }

    @Test fun `page one with an unproven length is not a level sample`() {
        val bytes = frame(3).copyOf(75)
        bytes[3] = 71
        val crc = CRC32().apply { update(bytes, 0, 71) }.value
        for (i in 0..3) bytes[71 + i] = (crc shr (24 - 8 * i)).toByte()
        val data = telemetry(VeteranAdapter(), bytes)
        assertNotNull(data.headlightReadback)
        assertNull(data.headlightReadback!!.level)
    }

    @Test fun `other models and unidentified wheels retain command tracked state`() {
        for (version in listOf(5000, 8000, 9000, 0)) {
            val adapter = VeteranAdapter()
            adapter.setLight(true)
            val data = telemetry(adapter, frame(0, version = version))
            assertNull(data.headlightReadback)
            assertTrue(data.lightOn)
            adapter.setLight(false)
            val off = telemetry(adapter, frame(3, version = version))
            assertNull(off.headlightReadback)
            assertFalse(off.lightOn)
        }
    }

    @Test fun `all nosfet models accept headlight readback while veteran models reject it`() {
        val state = VeteranHeadlightState()
        val frame = frame(2, page = 1)
        for (model in VeteranModel.entries.filter { it.brandOverride == "NOSFET" }) {
            state.acceptFrame(frame, model)
            assertEquals(HeadlightReadback.Level.MEDIUM, state.snapshot.readback?.level)
            assertTrue(state.snapshot.lightOn)
        }
        for (model in VeteranModel.entries.filter { it.brandOverride != "NOSFET" } + null) {
            state.acceptFrame(frame, model)
            assertNull(state.snapshot.readback)
        }
    }

    @Test fun `model change and disconnect clear the previous wheel level`() {
        val adapter = VeteranAdapter().apply { notifyConnectingTo("NOSFET Aeon") }
        telemetry(adapter, frame(3))
        assertNull(telemetry(adapter, frame(version = 9000)).headlightReadback)
        assertNull(telemetry(adapter, frame(page = 2)).headlightReadback!!.level)
        telemetry(adapter, frame(2))
        adapter.onDisconnect()
        adapter.notifyConnectingTo("NF7445")
        val next = telemetry(adapter, frame(page = 2))
        assertNotNull(next.headlightReadback)
        assertNull(next.headlightReadback!!.level)
        assertFalse(next.lightOn)
    }

    @Test fun `a generic name command does not become an Aeon measurement after identification`() {
        val adapter = VeteranAdapter().apply { notifyConnectingTo("NF7445") }
        adapter.setLight(true)
        val data = telemetry(adapter, frame(page = 2))
        assertNull(data.headlightReadback!!.level)
        assertFalse(data.lightOn)
    }

    @Test fun `telemetry model overrides a mistaken Aeon name for command tracking`() {
        val adapter = VeteranAdapter().apply { notifyConnectingTo("NOSFET Aeon") }
        telemetry(adapter, frame(version = 9000))
        adapter.setLight(true)
        val data = telemetry(adapter, frame(version = 9000))
        assertNull(data.headlightReadback)
        assertTrue(data.lightOn)
    }

    @Test fun `Aeon commands retain the ASCII mapping without fabricating measured state`() {
        val adapter = VeteranAdapter().apply { notifyConnectingTo("NOSFET Aeon") }
        assertEquals("SetLightON", adapter.setLight(true).toString(Charsets.US_ASCII))
        assertNull(adapter.setLightFollowup(true))
        assertFalse(telemetry(adapter, frame(page = 2)).lightOn)
        val first = telemetry(adapter, frame(3))
        assertEquals("SetLightOFF", adapter.setLight(false).toString(Charsets.US_ASCII))
        assertNull(adapter.setLightFollowup(false))
        val waiting = telemetry(adapter, frame(page = 2))
        assertEquals(first.headlightReadback, waiting.headlightReadback)
        assertTrue(waiting.lightOn)
        assertFalse(telemetry(adapter, frame(0)).lightOn)
    }
}
