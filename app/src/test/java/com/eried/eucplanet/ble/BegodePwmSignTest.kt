package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Begode sends PWM signed, and the sign is the motor's direction, not a load:
 * a T4 at 42 km/h read -67 % on the dashboard (issue #24). Speed from the same
 * wheel already loses its sign at the apply site; the duty cycle has to lose
 * it too, or a PWM alarm at 80 % can never fire on that wheel.
 */
class BegodePwmSignTest {

    /** A 24-byte Begode frame: 55 AA, payload, tag at 18, 5A 5A 5A 5A. */
    private fun frame(tag: Int, fill: (ByteArray) -> Unit): ByteArray {
        val f = ByteArray(24)
        f[0] = 0x55; f[1] = 0xAA.toByte()
        fill(f)
        f[18] = tag.toByte()
        for (i in 20..23) f[i] = 0x5A
        return f
    }

    private fun ByteArray.putI16(offset: Int, value: Int) {
        val v = value and 0xFFFF
        this[offset] = ((v shr 8) and 0xFF).toByte()
        this[offset + 1] = (v and 0xFF).toByte()
    }

    /** Live A: 134 V class pack at 130.0 V (ratio 2.0), 42 km/h, 12 A. */
    private fun liveA(hwPwmTenths: Int = 0) = frame(0x00) { f ->
        f.putI16(2, 6500)
        f.putI16(4, (42f * 100f / 3.6f).toInt())
        f.putI16(10, 1200)
        f.putI16(14, hwPwmTenths)
    }

    /** 0x07 extras: battery current, motor temperature, true PWM in percent. */
    private fun extras(truePwmPct: Int) = frame(0x07) { f ->
        f.putI16(2, -1200)
        f.putI16(6, 40)
        f.putI16(8, truePwmPct)
    }

    private fun pwmOf(results: List<DecodeResult>): Float =
        results.filterIsInstance<DecodeResult.Telemetry>().last().data.pwm

    @Test fun `the 0x07 true PWM is a magnitude, whichever way the motor turns`() {
        val p = BegodeParser()
        p.feed(liveA(), BegodeModel.T4)
        assertEquals(67f, pwmOf(p.feed(extras(-67), BegodeModel.T4)), 0.001f)
        assertEquals(67f, pwmOf(p.feed(extras(67), BegodeModel.T4)), 0.001f)
    }

    @Test fun `the live frame's hardware PWM is a magnitude too`() {
        val p = BegodeParser()
        p.hwPwmFirmware = true
        assertEquals(67f, pwmOf(p.feed(liveA(hwPwmTenths = -670), BegodeModel.T4)), 0.001f)
        assertEquals(67f, pwmOf(p.feed(liveA(hwPwmTenths = 670), BegodeModel.T4)), 0.001f)
    }
}
