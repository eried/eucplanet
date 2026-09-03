package com.eried.eucplanet.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decode, checked against the vendor's own arithmetic and the rider's
 * gauge at the same time.
 *
 * The numbers here are no longer a fit to a handful of captures. They are what
 * `com.wicarlink.zl.data.bean.TireBean.parse` does in the LY app, read out of
 * the APK, so the odd 3.144 scale and the 55 degree offset are the
 * manufacturer's. Two earlier guesses looked right for the wrong reason and
 * are pinned here so they cannot come back.
 */
class LyTpmsDecoderTest {

    private val address = "5B:61:1B:11:11:11"

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun kpa(hex: String) = LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, bytes(hex), address)
    private fun celsius(hex: String) = LyTpmsDecoder.temperatureC(LyTpmsDecoder.COMPANY_ID, bytes(hex), address)
    private fun volts(hex: String) = LyTpmsDecoder.batteryVolts(LyTpmsDecoder.COMPANY_ID, bytes(hex), address)

    /** Rider's gauge 0 bar, 27 C. */
    private val flat = "B30052000A8200BB281111111B615B"

    /** Rider's gauge 3.2 bar during a deflation, 27 C. */
    private val midway = "B35B52010AA20004281111111B615B"

    @Test fun `a flat tyre reads zero`() {
        assertEquals(0f, kpa(flat))
    }

    @Test fun `the raw count is scaled, which is what made 3_6 bar read as 1_15`() {
        // Byte 1 is a count, not kPa. At 115 counts the rider's gauge read
        // 3.6 bar: 115 x 3.144 is 361.6 kPa, and unscaled it showed 1.15.
        val at36bar = "B37352010AA20004281111111B615B"
        assertEquals(361.6f, kpa(at36bar)!!, 0.1f)
        assertEquals(3.6f, kpa(at36bar)!! / 100f, 0.02f)
    }

    @Test fun `the pressure high byte is 6, not 3`() {
        // Byte 6 was zero in every capture ever taken, so a wrong guess and
        // the right answer agreed on everything below 803 kPa. Byte 3 moves
        // here and must not touch the pressure; byte 6 does.
        val lowOnly = "B30A52010AA20004281111111B615B"   // 10 counts
        val byte3Set = "B30A52070AA20004281111111B615B"   // byte 3 moved
        val byte6Set = "B30A52010AA20104281111111B615B"   // byte 6 moved
        assertEquals(10 * 3.144f, kpa(lowOnly)!!, 0.1f)
        assertEquals("byte 3 must not touch the pressure", kpa(lowOnly), kpa(byte3Set))
        assertEquals((10 + 256) * 3.144f, kpa(byte6Set)!!, 0.1f)
    }

    @Test fun `temperature is Celsius with a 55 degree offset`() {
        // 0x52 is 82, and 82 - 55 is 27, which is what the rider read.
        // "Fahrenheit" also lands on 27 from 82, which is why it survived: the
        // two only diverge away from room temperature.
        assertEquals(27f, celsius(flat))
        assertEquals(27f, celsius(midway))
        // 0x64 is 100: 45 C by the app's rule, 37.8 C if it were Fahrenheit.
        assertEquals(45f, celsius("B35B64010AA20004281111111B615B"))
    }

    @Test fun `battery is hundredths of a volt above 1_22`() {
        // 0xB3 is 179: 179 x 0.01 + 1.22 = 3.01 V, a healthy CR2032, and the
        // rider's app read about 85 percent at that moment.
        assertEquals(3.01f, volts(flat)!!, 0.001f)
    }

    @Test fun `the sensor id is its own three bytes, printed backwards`() {
        // The same characters the rider sees in the app they bought it with.
        assertEquals("5B611B", LyTpmsDecoder.sensorId(LyTpmsDecoder.COMPANY_ID, bytes(flat), address))
    }

    @Test fun `the cap reports BLE 4_0`() {
        assertEquals(4.0f, LyTpmsDecoder.bleVersion(LyTpmsDecoder.COMPANY_ID, bytes(flat), address)!!, 0.01f)
    }

    @Test fun `one deflation still falls the whole way down`() {
        val deflating = listOf(
            "B39952020AE2008C281111111B615B",
            "B36F52010A0C0004281111111B615B",
            "B36752010AAE008A281111111B615B",
            "B35B52010AA20004281111111B615B",
            "B35352010A00008A281111111B615B",
            "B41B52010AEE009A281111111B615B",
        )
        val values = deflating.map { kpa(it)!! }
        for (i in 1 until values.size) {
            assertTrue("pressure rose while the tyre emptied: $values", values[i] < values[i - 1])
        }
    }

    @Test fun `a device that is not this sensor is not read as a tyre`() {
        assertNull(LyTpmsDecoder.pressureKpa(0x0969, bytes("C2CA702B4EBF248000640000"), "C2:CA:70:2B:4E:BF"))
        assertNull(LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, bytes(midway), "AA:BB:CC:DD:EE:FF"))
        assertNull(LyTpmsDecoder.pressureKpa(0x004C, bytes(midway), address))
    }
}
