package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.MetricRegistry
import com.eried.eucplanet.data.model.MetricValueFormat
import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Begode's auto power-off countdown: Live B (tag 0x04) bytes 8..9, u16 BE in
 * seconds. A rider's EUC Dash screenshot showed 92:40, which is 5560 s; EUC
 * Dash (MIT) reads the same bytes the same way, taken as reference only.
 */
class BegodeAutoOffTest {

    private fun frame(tag: Int, fill: (ByteArray) -> Unit): ByteArray {
        val f = ByteArray(24)
        f[0] = 0x55; f[1] = 0xAA.toByte()
        fill(f)
        f[18] = tag.toByte()
        for (i in 20..23) f[i] = 0x5A
        return f
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = ((value shr 8) and 0xFF).toByte()
        this[offset + 1] = (value and 0xFF).toByte()
    }

    private fun liveA() = frame(0x00) { f -> f.putU16(2, 6500) }
    private fun liveB(seconds: Int) = frame(0x04) { f -> f.putU16(8, seconds); f.putU16(10, 70) }

    private fun telemetry(results: List<DecodeResult>) =
        results.filterIsInstance<DecodeResult.Telemetry>().last().data

    @Test fun `no Live B yet means no countdown`() {
        val p = BegodeParser()
        assertEquals(-1, telemetry(p.feed(liveA(), BegodeModel.MASTER)).autoOffSeconds)
    }

    @Test fun `Live B bytes 8 and 9 are the countdown in seconds`() {
        val p = BegodeParser()
        p.feed(liveB(5560), BegodeModel.MASTER)
        assertEquals(5560, telemetry(p.feed(liveA(), BegodeModel.MASTER)).autoOffSeconds)
        p.feed(liveB(5559), BegodeModel.MASTER)
        assertEquals(5559, telemetry(p.feed(liveA(), BegodeModel.MASTER)).autoOffSeconds)
    }

    @Test fun `reset forgets it`() {
        val p = BegodeParser()
        p.feed(liveB(300), BegodeModel.MASTER)
        p.reset()
        assertEquals(-1, telemetry(p.feed(liveA(), BegodeModel.MASTER)).autoOffSeconds)
    }

    @Test fun `the tile reads m colon ss, and other wheels read nothing`() {
        assertEquals("92:40", MetricValueFormat.autoOffClock(5560))
        assertEquals("0:05", MetricValueFormat.autoOffClock(5))
        assertEquals(5560f, MetricRegistry.read("AUTO_OFF", WheelData(autoOffSeconds = 5560)))
        assertNull(MetricRegistry.read("AUTO_OFF", WheelData()))
    }
}
