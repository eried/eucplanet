package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.abs

/**
 * Pins Veteran Oryx (mVer 8) telemetry decoding.
 *
 * Built from real Oryx realtime frames. Each frame carries always-valid base
 * fields plus one rotating "page" (selector at byte 46). This test guards:
 *   - battery is the wheel's own BMS state-of-charge, read verbatim from byte 50
 *     but only in the page-2 sub-frame, and held steady across the other pages;
 *   - the speed magnitude decodes from the base field at offset 6 (the
 *     repository drops the sign downstream).
 *
 * Fixture values: voltage 171.63 V, speed i16 -132 (forward) / +25 (manual),
 * current 0.9 A, temp 41.17 C, version 8005 -> mVer 8. Pure-JVM test.
 */
class VeteranOryxTest {

    private fun bytes(hex: String) =
        hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // Real frame captured while riding forward: speed i16 is NEGATIVE (-132).
    private val ORYX_FORWARD = bytes(
        "dc 5a 5c 53 43 0b ff 7c 88 a3 00 1b 88 a3 00 1b 00 09 10 15 " +
        "03 84 00 00 07 d0 07 d0 1f 45 00 c8 ff fd 05 bc 80 c8 00 00 " +
        "80 80 80 80 80 80 02 32 01 01 5f 80 80 0f f6 0f f6 0f ef 0f f7 " +
        "0f f9 0f f3 0f f5 0f f6 0f f6 0f f6 0f f6 0f ee 0f f3 0f f5 0f f5 " +
        "45 9f 69 0f"
    )

    // Real frame captured rolling the wheel slowly by hand: speed i16 is
    // POSITIVE (+25) - the opposite sign to the forward-ride frame above.
    private val ORYX_MANUAL = bytes(
        "dc 5a 5c 53 42 ee 00 19 ae 97 00 1b ae 97 00 1b 00 2b 0e 33 " +
        "03 84 00 00 07 d0 07 d0 1f 45 00 c8 00 08 01 81 80 c8 00 00 " +
        "80 80 80 80 80 80 05 00 00 00 00 00 2a 0f ef 0f ef 0f f1 0f f1 " +
        "0f f1 0f ea 0f ee 0f ef 0f ef 0f ef 0f ef 0f e9 0f ed 0f ee 0f ee " +
        "3a e8 bc 2c"
    )

    // model = null so the test proves the model resolves from the in-frame
    // mVer (offset 28), exactly as it does for a wheel advertising a generic name.
    private fun decode(frame: ByteArray) = VeteranParser.parseTelemetry(frame, model = null)

    @Test
    fun `oryx battery is read verbatim from the page-2 BMS byte`() {
        val d = decode(ORYX_FORWARD)
        assertNotNull("Oryx frame must decode to telemetry", d)
        // ORYX_FORWARD is a page-2 sub-frame (byte 46 == 2); byte 50 = 0x5F = 95
        // is the wheel's own BMS state-of-charge, read verbatim. The old voltage
        // curve read this near-full pack as 0 %.
        assertEquals(95, d!!.batteryPercent)
    }

    @Test
    fun `oryx battery from a page-2 frame persists across the other pages`() {
        val adapter = VeteranAdapter()
        adapter.notifyConnectingTo(null)
        // Page-2 frame establishes the BMS SoC (95)...
        val onPage2 = adapter.onRawNotification(ORYX_FORWARD)
            .filterIsInstance<DecodeResult.Telemetry>().last()
        assertEquals(95, onPage2.data.batteryPercent)
        // ...and a later page-5 frame must keep showing it (cached), not flicker
        // to the voltage-curve estimate.
        val onPage5 = adapter.onRawNotification(ORYX_MANUAL)
            .filterIsInstance<DecodeResult.Telemetry>().last()
        assertEquals(95, onPage5.data.batteryPercent)
    }

    @Test
    fun `oryx speed magnitude decodes from the base field`() {
        // Speed is the base field at offset 6; the repository takes its
        // magnitude, so only the size matters. Forward-ride frame raw i16 -132
        // -> 13.2; manual-roll frame raw i16 +25 (opposite sign) -> 2.5.
        assertEquals(13.2f, abs(decode(ORYX_FORWARD)!!.speed), 0.05f)
        assertEquals(2.5f, abs(decode(ORYX_MANUAL)!!.speed), 0.05f)
    }

    @Test
    fun `oryx voltage, current and temperature parse correctly`() {
        val d = decode(ORYX_FORWARD)!!
        assertEquals(171.63f, d.voltage, 0.01f)
        assertEquals(0.9f, d.current, 0.05f)
        assertEquals(41.17f, d.maxTemperature, 0.05f)
    }
}
