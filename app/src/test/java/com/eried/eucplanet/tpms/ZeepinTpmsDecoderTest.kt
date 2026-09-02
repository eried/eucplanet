package com.eried.eucplanet.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The published packet, decoded four ways at once.
 *
 * This is what evidence for a decode looks like, and it is the standard the
 * rider's own sensor has never met. One packet yields a pressure, a
 * temperature, a battery level and a wheel number, and every one of the four
 * lands somewhere a real tyre could be. A layout that was wrong would have to
 * be wrong four times in a row and still look sensible.
 */
class ZeepinTpmsDecoderTest {

    private fun bytes(hex: String) = hex.replace(" ", "").chunked(2)
        .map { it.toInt(16).toByte() }.toByteArray()

    /** From the open source projects that decode this family. */
    private val published = bytes("83 EA CA 40 61 81 C8 1C 04 00 0B 0B 00 00 4B 00")

    @Test fun `the published packet reads as a real tyre`() {
        // 0x00041CC8 is 269512 thousandths, so 269.512 kPa.
        assertEquals(269.512f, ZeepinTpmsDecoder.pressureKpa(0x0001, published)!!, 0.001f)
        assertEquals(28.27f, ZeepinTpmsDecoder.temperatureC(0x0001, published)!!, 0.001f)
        assertEquals(75, ZeepinTpmsDecoder.batteryPercent(0x0001, published))
        // 0x83 is the fourth sensor.
        assertEquals(4, ZeepinTpmsDecoder.wheelNumber(0x0001, published))
    }

    @Test fun `269 kPa is 39 psi, which is a tyre a person would pump`() {
        val kpa = ZeepinTpmsDecoder.pressureKpa(0x0001, published)!!
        assertEquals(39.1f, kpa * 0.145038f, 0.2f)
    }

    @Test fun `another company id is another device`() {
        assertNull(ZeepinTpmsDecoder.pressureKpa(0x00AC, published))
        assertNull(ZeepinTpmsDecoder.pressureKpa(0x004C, published))
    }

    @Test fun `the wrong length is the wrong sensor`() {
        assertNull(ZeepinTpmsDecoder.pressureKpa(0x0001, bytes("83EACA4061")))
    }

    @Test fun `a first byte outside the four wheels is a different device on the same id`() {
        // Company 0x0001 is Nokia's, so plenty of unrelated things advertise
        // under it. The sensor number is what separates them.
        val impostor = published.copyOf().also { it[0] = 0x11 }
        assertNull(ZeepinTpmsDecoder.pressureKpa(0x0001, impostor))
    }

    @Test fun `a sensor off the valve reports nothing rather than zero`() {
        val deflated = published.copyOf().also {
            it[6] = 0; it[7] = 0; it[8] = 0; it[9] = 0
        }
        assertNull(ZeepinTpmsDecoder.pressureKpa(0x0001, deflated))
    }

    @Test fun `the rider's own sensor is not mistaken for this family`() {
        assertNull(
            ZeepinTpmsDecoder.pressureKpa(0x0001, bytes("B4AC52020A9C008E281111111B615B"))
        )
    }
}
