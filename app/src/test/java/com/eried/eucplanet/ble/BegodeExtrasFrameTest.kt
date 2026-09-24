package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Master v3 on stock firmware showed PWM "flicker from the used percentage
 * back to zero every second" (issue #26). Stock Begode sends the 0x07 extras
 * frame about once a second with its true-PWM and battery-current fields
 * left at zero; the parser knew not to latch onto those zeros, but it still
 * emitted them as telemetry, so every 0x07 frame put a 0 % on the tile until
 * the next Live A frame brought the derived value back.
 *
 * The rule: a zero in an extras field that has never been populated is "not
 * wired", and the frame reports the value the wheel gave us elsewhere. Once
 * the field has been non-zero, a zero is a real zero (the wheel stands).
 */
class BegodeExtrasFrameTest {

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

    /** Live A: 134 V class pack at 130.0 V, 42 km/h, 12 A phase current. */
    private fun liveA() = frame(0x00) { f ->
        f.putI16(2, 6500)
        f.putI16(4, (42f * 100f / 3.6f).toInt())
        f.putI16(10, 1200)
    }

    /** 0x07 extras as stock firmware sends it: motor temp only, the rest zero. */
    private fun extras(truePwmPct: Int = 0, battCurrentCa: Int = 0) = frame(0x07) { f ->
        f.putI16(2, battCurrentCa)
        f.putI16(6, 40)
        f.putI16(8, truePwmPct)
    }

    private fun last(results: List<DecodeResult>) =
        results.filterIsInstance<DecodeResult.Telemetry>().last().data

    @Test fun `an unpopulated 0x07 frame keeps the derived PWM instead of flashing zero`() {
        val p = BegodeParser()
        val derived = last(p.feed(liveA(), BegodeModel.MASTER)).pwm
        assert(derived > 10f) { "the Master at 42 km/h must derive a real PWM, got $derived" }

        val onExtras = last(p.feed(extras(), BegodeModel.MASTER))
        assertEquals("0x07 with PWM=0 on stock firmware is not a reading", derived, onExtras.pwm, 0.001f)
        assertEquals("nor is its zero battery current", 12f, onExtras.current, 0.001f)
        assertEquals(derived, last(p.feed(liveA(), BegodeModel.MASTER)).pwm, 0.001f)
    }

    @Test fun `once 0x07 PWM has been seen, a zero there is the wheel standing still`() {
        val p = BegodeParser()
        p.feed(liveA(), BegodeModel.MASTER)
        assertEquals(55f, last(p.feed(extras(truePwmPct = 55, battCurrentCa = -800), BegodeModel.MASTER)).pwm, 0.001f)
        val stopped = last(p.feed(extras(truePwmPct = 0, battCurrentCa = 0), BegodeModel.MASTER))
        assertEquals(0f, stopped.pwm, 0.001f)
        assertEquals(0f, stopped.current, 0.001f)
        // And Live A frames carry that zero, not a stale 55.
        assertEquals(0f, last(p.feed(liveA(), BegodeModel.MASTER)).pwm, 0.001f)
    }
}
