package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards P6 realtime power decoding. The P6 RealTimeInfo body shares the V14
 * layout for battery/motor power: battery power (W, signed int16) at offset 16,
 * motor power (W, signed int16) at offset 18. parseP6Telemetry used to leave
 * both at the WheelData default of 0, so the dashboard Power pill read blank on
 * P6 wheels.
 *
 * The primary case uses a real riding frame from a labelled P6 btsnoop capture
 * (voltage 208.0 V, current 5.00 A -> V*I = 1040 W, which the wheel reports as
 * batteryPower[16] = 1039 W and motorPower[18] = 844 W).
 */
class InMotionV2P6TelemetryTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Real 97-byte P6 0x87 realtime body (bytes after `21 02 87 01 00`). */
    private val ridingFrame = hex(
        "3e51f401000000009911000091063e0b0f044c038e12eb11983a773a6f35" +
        "c7cbcb02bb13c001f401060056000000070000000600000005010000816d" +
        "0400d657d000169d10004848010061c00300f9004900000000000000000000009b05b90be0"
    )

    @Test fun `P6 decodes battery and motor power from offsets 16 and 18`() {
        val wd = InMotionV2Parser.parseP6Telemetry(ridingFrame)!!
        assertEquals(207.98f, wd.voltage, 0.01f)
        assertEquals(5.00f, wd.current, 0.01f)
        // battery power = wheel field at offset 16, matches V*I (1040) to 1 W
        assertEquals(1039, wd.batteryPower)
        // motor power = wheel field at offset 18 (mechanical, ~80% of battery)
        assertEquals(844, wd.motorPower)
    }

    @Test fun `P6 power is signed - regen reads negative`() {
        // Clone a full frame but write a negative battery/motor power (-288 / -214,
        // as seen in a regen frame of the same capture) at offsets 16/18.
        val f = ridingFrame.copyOf()
        (-288).let { f[16] = (it and 0xFF).toByte(); f[17] = ((it shr 8) and 0xFF).toByte() }
        (-214).let { f[18] = (it and 0xFF).toByte(); f[19] = ((it shr 8) and 0xFF).toByte() }
        val wd = InMotionV2Parser.parseP6Telemetry(f)!!
        assertEquals(-288, wd.batteryPower)
        assertEquals(-214, wd.motorPower)
    }

    @Test fun `P6 truncated frame keeps power at zero default`() {
        // A short body (before offsets 16/18 arrive) must not crash and leaves
        // power at the WheelData default so the dashboard reads blank, not garbage.
        val wd = InMotionV2Parser.parseP6Telemetry(hex("3e51f4010000"))!!
        assertEquals(0, wd.batteryPower)
        assertEquals(0, wd.motorPower)
    }

    // Real 0x84 detailed bodies (bytes after `21 02 84`) from a labelled ride
    // capture. Motor temp is at body[64], Fahrenheit encoded as  byte − 126
    // (same as MOS body[58]) - confirmed against the InMotion app's OWN BLE
    // capture (btsnoop), where a cold wheel read body[64]=0xC7 (199) = 73 F,
    // not 199 F. Reading body[64] raw is what showed a cold wheel ~200 F.
    private val detailedHot = hex(
        "af5492ff000000007202000045f6920112ff53ff1bff7bfef4ff96ff9709dc" +
        "00b2f700199018983a204e2e34581b581be02ee02e74bd00000000d52e00e8" +
        "b0e8d8dcb03a0004000000004900020000000000000000006f"
    )
    private val detailedCool = hex(
        "865aeaff0000000000000000d9ff4a0000000000ffffc000000000000000d2" +
        "00deff0c274126983a204e262f581b581be02ee02e50c300000000cace00cc" +
        "b0cbcacab01800030000000049000200000000000000000037"
    )

    @Test fun `P6 detailed frame decodes motor temp as Fahrenheit byte minus 126 at offset 64`() {
        // body[64] = 0xD8 (216) -> 216 − 126 = 90 F -> 32.2 C.
        assertEquals(32.2f, InMotionV2Parser.parseP6DetailedData(detailedHot)!!.motorC!!, 0.2f)
        // Cooler frame: body[64] = 0xCA (202) -> 202 − 126 = 76 F -> 24.4 C.
        assertEquals(24.4f, InMotionV2Parser.parseP6DetailedData(detailedCool)!!.motorC!!, 0.2f)
        // MOS / driver-board are not read from this frame (realtime owns MOS).
        assertEquals(null, InMotionV2Parser.parseP6DetailedData(detailedHot)!!.mosC)
    }

    // Real 96-byte P6 realtime (0x87) bodies from the tester's 2026-07-27 ride,
    // cross-checked frame-by-frame against the on-screen Motor Temp in that same
    // ride's screen recording (analysis in tools/p6-temp-analysis, rmse 0.4 C
    // over 18 frames). Motor temp is body[31] as an 8-bit value that overflows:
    // motor C = (body[31] + 80) & 0xFF.
    private val realtimeCold = hex(
        "3b59c6ff00000000660500008a00a2037cff15001c250c25983a204e2e34d5eb" +
        "df02b21fce3e222f8b0145ac0000d8220000ac010000790200004ea206009a4b" +
        "af03c8308800604c06000ed71200dc0049000000000000000000000076002715"
    )
    private val realtimeHot = hex(
        "c350881a000000008d2000008f2b2e16da367428e21d991d983a204e2e34dc32" +
        "f0022e27ce3e1a331707aeb404002dc000006e0500003c060000daa706000354" +
        "b3031dce880022500600d1da1200e2004900000000000000000000002c250819"
    )

    @Test fun `P6 realtime motor temp is body 31 with 8-bit overflow`() {
        // body[31] = 235 -> (235 + 80) & 0xFF = 59 C (cold end of the ride).
        assertEquals(59f, InMotionV2Parser.parseP6Telemetry(realtimeCold)!!.temperatures[0], 0.5f)
        // body[31] = 50, already wrapped past 255 -> (50 + 80) & 0xFF = 130 C.
        // The old `body[31] - 176` would read -126 C here and blank the tile;
        // this is the overflow the previous code mistook for a dead constant.
        assertEquals(130f, InMotionV2Parser.parseP6Telemetry(realtimeHot)!!.temperatures[0], 0.5f)
    }
}
