package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.MetricRegistry
import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Begode motor temperature edges. These PIN today's behaviour, they do not
 * claim it is right: 0 keeps the last reading, -40..150 arms (both ends
 * included), 151 is dropped, and a sub-zero reading like -5 arms. Each is a
 * decision for the owner.
 */
class BegodeMotorTempEdgesTest {

    private fun extras(motorC: Int): ByteArray {
        val f = ByteArray(24)
        f[0] = 0x55; f[1] = 0xAA.toByte()
        val v = motorC and 0xFFFF
        f[6] = ((v shr 8) and 0xFF).toByte()
        f[7] = (v and 0xFF).toByte()
        f[18] = 0x07
        for (i in 20..23) f[i] = 0x5A
        return f
    }

    private fun motor(p: BegodeParser, c: Int): Float? {
        val t: WheelData = p.feed(extras(c), BegodeModel.MASTER)
            .filterIsInstance<DecodeResult.Telemetry>().last().data
        return MetricRegistry.read("MOTOR_TEMP", t)
    }

    @Test fun `a 0 after a real reading keeps the last reading`() {
        val p = BegodeParser()
        assertEquals(41f, motor(p, 41)!!, 0.01f)
        assertEquals(41f, motor(p, 0)!!, 0.01f)
    }

    @Test fun `minus 40 and 150 arm the sensor`() {
        assertEquals(-40f, motor(BegodeParser(), -40)!!, 0.01f)
        assertEquals(150f, motor(BegodeParser(), 150)!!, 0.01f)
    }

    @Test fun `151 is dropped, before and after arming`() {
        assertNull(motor(BegodeParser(), 151))
        val p = BegodeParser()
        motor(p, 60)
        assertEquals(60f, motor(p, 151)!!, 0.01f)
    }

    @Test fun `minus 41 is dropped`() {
        assertNull(motor(BegodeParser(), -41))
    }

    @Test fun `a sub-zero reading like minus 5 arms the sensor`() {
        assertEquals(-5f, motor(BegodeParser(), -5)!!, 0.01f)
    }
}
