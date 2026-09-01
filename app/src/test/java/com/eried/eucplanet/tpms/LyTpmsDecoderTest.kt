package com.eried.eucplanet.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real packets from a real sensor, at pressures a rider read off a gauge.
 *
 * The format is not published, so these captures ARE the specification. They
 * were taken on a Pixel with the app logging every advertisement in range: one
 * set with the tyre at 5.3 bar, another after it had been let down and pumped
 * back to just under 78 psi. Nothing else in range moved with the pressure.
 *
 * If someone changes the decoder and these still pass, it still reads the
 * sensor the rider actually owns.
 */
class LyTpmsDecoderTest {

    private val address = "5B:61:1B:11:11:11"

    private fun bytes(hex: String) = hex.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    /** Captured while the gauge read 5.3 bar, which is 530 kPa. */
    private val at530 = listOf(
        "B28151000A6100E2281111111B615B" to 531.4f,
        "B37151000A5000AF281111111B615B" to 528.0f,
    )

    /** Captured while the gauge read just under 78 psi, which is 538 kPa. */
    private val at538 = listOf(
        "B4A752000A80009D281111111B615B" to 537.6f,
        "B43951020A7800D8281111111B615B" to 536.0f,
    )

    @Test fun `the 5point3 bar capture decodes to 5point3 bar`() {
        for ((hex, expected) in at530) {
            val kpa = LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, bytes(hex), address)
            assertEquals(hex, expected, kpa!!, 0.01f)
            // The rider's gauge said 530. Within one percent of it.
            assertTrue("$hex is not near 530 kPa", kotlin.math.abs(kpa - 530f) < 5f)
        }
    }

    @Test fun `the 78 psi capture decodes to 78 psi`() {
        for ((hex, expected) in at538) {
            val kpa = LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, bytes(hex), address)
            assertEquals(hex, expected, kpa!!, 0.01f)
            // 78 psi is 537.8 kPa.
            assertTrue("$hex is not near 538 kPa", kotlin.math.abs(kpa - 537.8f) < 5f)
        }
    }

    @Test fun `the two captures are far enough apart to prove the field`() {
        // The whole argument: a real pressure change moved these bytes and
        // nothing else. If the field were a counter or a checksum this would
        // not hold.
        val low = at530.map {
            LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, bytes(it.first), address)!!
        }.average()
        val high = at538.map {
            LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, bytes(it.first), address)!!
        }.average()
        assertTrue("the field did not move with the tyre", high > low)
    }

    @Test fun `a different company id is not this sensor`() {
        // 0x0969 devices were in the same captures, with their own MAC at the
        // FRONT of the payload, and were mistaken for sensors once already.
        assertNull(LyTpmsDecoder.pressureKpa(0x0969, bytes(at530[0].first), address))
    }

    @Test fun `a payload that does not end in this sensor's MAC is not adopted`() {
        // What stops another 0x00AC device being read as a tyre.
        assertNull(
            LyTpmsDecoder.pressureKpa(
                LyTpmsDecoder.COMPANY_ID, bytes(at530[0].first), "AA:BB:CC:DD:EE:FF",
            )
        )
    }

    @Test fun `a short or empty payload is not a pressure`() {
        assertNull(LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, ByteArray(0), address))
        assertNull(LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, bytes("B28151000A61"), address))
    }

    @Test fun `zero is a sensor with nothing to say, not a flat tyre`() {
        assertNull(
            LyTpmsDecoder.pressureKpa(
                LyTpmsDecoder.COMPANY_ID, bytes("B281510000000000281111111B615B"), address,
            )
        )
    }

    @Test fun `an implausible reading is refused rather than shown`() {
        // FFFF over five is 13107 kPa, which is not a tyre.
        assertNull(
            LyTpmsDecoder.pressureKpa(
                LyTpmsDecoder.COMPANY_ID, bytes("B2815100FFFF00E2281111111B615B"), address,
            )
        )
    }
}
