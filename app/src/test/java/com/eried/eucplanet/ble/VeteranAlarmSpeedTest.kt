package com.eried.eucplanet.ble

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

class VeteranAlarmSpeedTest {
    private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `Aeon alarm matches original captured packets while tiltback stays unchanged`() {
        val adapter = VeteranAdapter()
        adapter.notifyConnectingTo("NOSFET Aeon")
        // PC capture 2026-09-11 21:40:19 / 21:40:36, original CRCs.
        for ((speed, hex) in mapOf(
            34 to "4c6441701301028080808080808022e9294692",
            35 to "4c64417013010280808080808080239e2e7604",
        )) {
            assertArrayEquals(hex.bytes(), adapter.setAlarmSpeedCommit(speed.toFloat()))
            assertArrayEquals(VeteranCommands.setTiltbackSpeed(speed), adapter.setMaxSpeedCommit(speed.toFloat()))
        }
        adapter.onDisconnect()
        assertArrayEquals(VeteranCommands.setAlarmSpeed(35), adapter.setAlarmSpeedCommit(35f))
    }

    @Test fun `other models and unknown model retain the generic alarm mapping`() {
        for (model in VeteranModel.entries.filter { it.brandOverride != "NOSFET" } + null) {
            assertArrayEquals(VeteranCommands.setAlarmSpeed(35), VeteranCommands.setAlarmSpeed(35, model))
        }
    }

    @Test fun `all nosfet models use the vendor bank 2 alarm mapping`() {
        for (model in VeteranModel.entries.filter { it.brandOverride == "NOSFET" }) {
            val frame = VeteranCommands.setAlarmSpeed(35, model)
            assertArrayEquals("4c64417013010280808080808080239e2e7604".bytes(), frame)
        }
    }

    @Test fun `Aeon alarm preserves bounds padding CRC and one ATT write`() {
        for (speed in listOf(Int.MIN_VALUE, -1, 0, 1, 34, 35, 99, 100, 128, 200, Int.MAX_VALUE)) {
            val frame = VeteranCommands.setAlarmSpeed(speed, VeteranModel.NOSFET_AEON)
            assertEquals(19, frame.size)
            assertEquals(speed.coerceIn(1, 99), frame[14].toInt() and 255)
            assertTrue(frame.slice(7..13).all { it == 0x80.toByte() })
            assertEquals(CRC32().apply { update(frame, 0, 15) }.value,
                frame.takeLast(4).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 255) })
        }
    }

    @Test fun `generic BLE name resolves alarm mapping from captured Aeon telemetry`() {
        val adapter = VeteranAdapter()
        adapter.notifyConnectingTo("NF7445")
        assertArrayEquals(VeteranCommands.setAlarmSpeed(35), adapter.setAlarmSpeedCommit(35f))
        // Owner capture 2026-09-07, Aeon 503002; original CRC.
        val frame = ("dc5a5c4733f00000d4b20001d69e0001" +
            "00000d22046b0000015e0190acda07b41f140000" +
            "80c800008080808080800800008050002841231e" +
            "00000000808028003e91328000808049e9cee1").bytes()
        frame.toList().chunked(20).forEach { adapter.onRawNotification(it.toByteArray()) }
        assertArrayEquals("4c64417013010280808080808080239e2e7604".bytes(), adapter.setAlarmSpeedCommit(35f))
        // On this branch the same identification also selects the Aeon light
        // path (ASCII single frame, PR #20), so the alarm fix rides alongside it.
        assertEquals("SetLightON", adapter.setLight(true).toString(Charsets.US_ASCII))
        assertNull(adapter.setLightFollowup(true))
    }
}
