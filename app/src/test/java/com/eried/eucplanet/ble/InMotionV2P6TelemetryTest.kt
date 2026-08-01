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

    @Test fun `P6 decodes TPMS tire pressure from offsets 78-79 (u16le kPa)`() {
        // body[78..79] = f9 00 = 249 kPa (high byte 0). Cross-checked against the
        // InMotion app btsnoop where body[78]=201 kPa showed as 29.1 psi.
        val wd = InMotionV2Parser.parseP6Telemetry(ridingFrame)!!
        assertEquals(249f, wd.tirePressureKpa, 0.01f)
        // 249 kPa -> 36.1 psi / 2.49 bar via Units.
        assertEquals(36.1f, com.eried.eucplanet.util.Units.pressure(wd.tirePressureKpa, "psi"), 0.1f)
        assertEquals(2.49f, com.eried.eucplanet.util.Units.pressure(wd.tirePressureKpa, "bar"), 0.01f)
    }

    @Test fun `P6 truncated frame keeps power at zero default`() {
        // A short body (before offsets 16/18 arrive) must not crash and leaves
        // power at the WheelData default so the dashboard reads blank, not garbage.
        val wd = InMotionV2Parser.parseP6Telemetry(hex("3e51f4010000"))!!
        assertEquals(0, wd.batteryPower)
        assertEquals(0, wd.motorPower)
    }

    // Real 86-byte P6 0x84 detailed body from the 2026-07-27 ride. The temp
    // block is a signed byte + 80 C at bytes 59/61/62/63/64/65 (motor, board,
    // cpu, imu, battery, mosfet) - community-documented, cross-checked here.
    private val detailed = hex(
        "8e5676fa000000006611000070e5f504baf3d1f297fd8b0c0000c6fea605e200" +
        "55e9bc1b621b983a204e2e344c1d4c1de02ee02e8cb900000000df3200f2b0ee" +
        "d5dcb065000400000000490000000000000000000000"
    )

    @Test fun `P6 detailed frame decodes the temp block as signed byte plus 80`() {
        val t = InMotionV2Parser.parseP6DetailedData(detailed)!!
        assertEquals(130f, t.motorC!!, 0.5f)       // body[59] = 0x32 (50)
        assertEquals(66f, t.controllerC!!, 0.5f)   // body[61] = 0xf2 (242 -> -14 +80)
        assertEquals(37f, t.batteryC!!, 0.5f)      // body[64] = 0xd5 (213 -> -43 +80)
        assertEquals(62f, t.imuC!!, 0.5f)          // body[63] = 0xee (238 -> -18 +80)
        assertEquals(44f, t.mosfetC!!, 0.5f)       // body[65] = 0xdc (220 -> -36 +80)
        assertEquals(null, t.cpuC)                 // body[62] = 0xb0 (176 -> 0 C = absent)
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

    // Three real 0x87 realtime bodies from the 2026-07-31 phase/torque ride, each
    // pinned to the InMotion app's Live Information screen at the matching second
    // (video clock == btsnoop UTC). The app shows Motor Torque and Phase Current;
    // the P6 sends torque (body[12], int16/100 Nm) but NOT phase current - the app
    // derives phase from torque, so we do too (phase = torque / 0.586 N.m/A).
    // frame -> (torque Nm, app phase A, app current A, app power W)
    private val phaseFrameLowLoad = hex(  // app 21:59:33.8: trq 1.3, phase 2.2, I 0.42, P 96
        "a159d6ff00000000ce08000083009205a0ff200040267325983a204ef337d1df" +
        "da04832400339328f700796b00000e16000062010000bb010000072107003e13" +
        "0804794f9200cead0600644d1400d20049000200000000000000000070007b1631")
    private val phaseFrameBraking = hex( // app 21:59:43.8: trq 9.7 (regen), phase 16.6, I 1.82, P 418
        "bd594aff000000001809000032fce6015efe04ff40267325983a204ef337d1e1" +
        "db0483247a37932800012a710000211800006c010000c501000010210700ef18" +
        "08048c519200d8ad06006e4d1400d200490002000000000000000000c1fc7b16a7")
    private val phaseFrameHighLoad = hex( // app 21:59:23.8: trq 96.7, phase 165.1, I 15.38, P 3471
        "2e5802060000000098070000c8257c078f0d2f08a626da25983a204ef337d0dc" +
        "d8038324db329328eb0018610000ba12000058010000b1010000fb200700dd08" +
        "0804254c9200c4ad06005a4d1400d2004900020000000000000000003e207b16f6")

    @Test fun `P6 phase current is derived from torque and matches the InMotion app`() {
        // Torque (body[12]) is real; phase current is torque / Kt. Tolerances cover
        // the app's one-decimal rounding and the Kt fit.
        val low = InMotionV2Parser.parseP6Telemetry(phaseFrameLowLoad)!!
        assertEquals(1.31f, low.torque, 0.01f)
        assertEquals(2.2f, low.phaseCurrent, 0.15f)     // app showed 2.2 A

        val braking = InMotionV2Parser.parseP6Telemetry(phaseFrameBraking)!!
        assertEquals(-9.74f, braking.torque, 0.01f)     // regen: torque is negative
        assertEquals(-16.6f, braking.phaseCurrent, 0.2f) // signed; app shows magnitude 16.6

        val high = InMotionV2Parser.parseP6Telemetry(phaseFrameHighLoad)!!
        assertEquals(96.72f, high.torque, 0.01f)
        assertEquals(165.1f, high.phaseCurrent, 1.0f)   // app showed 165.1 A
    }

    @Test fun `P6 phase current holds the exact torque relationship`() {
        // Locks the Kt constant so a change to it is caught. phase = torque / 0.586.
        for (frame in listOf(phaseFrameLowLoad, phaseFrameBraking, phaseFrameHighLoad)) {
            val wd = InMotionV2Parser.parseP6Telemetry(frame)!!
            assertEquals(wd.torque / 0.586f, wd.phaseCurrent, 0.001f)
        }
    }
}
