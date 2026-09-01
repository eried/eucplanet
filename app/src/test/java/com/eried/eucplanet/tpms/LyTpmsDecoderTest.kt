package com.eried.eucplanet.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is known about this sensor, including the part that was wrong.
 *
 * The packets are real, captured on a rider's phone from the cap on their own
 * tyre. What was wrong was the reading of them: bytes 4..5 were called pressure
 * on the strength of two captures at 530 and 538 kPa. Those are one and a half
 * percent apart, inside the field's own noise, so they could not tell a
 * pressure from anything else that drifts. Two points that close prove nothing,
 * and calling it verified was the mistake.
 *
 * The rider then read a gauge across the real range:
 *
 *   42.5 psi -> bytes 4..5 = 0x0A84 (2692)
 *   24.5 psi -> bytes 4..5 = 0x0A12 (2578)
 *    0.0 psi -> bytes 4..5 = 0x0A62 (2658)
 *
 * A tyre going from 42 psi to flat did not move it. It is not the pressure. It
 * sits between 2578 and 2692 and barely stirs, which reads like a coin cell in
 * millivolts, around 2.6 V.
 *
 * These tests keep the decoder honest while the real field is still unknown:
 * it must return null rather than publish a guess, and the disproof stays
 * written down so the next person does not spend an evening rediscovering it.
 */
class LyTpmsDecoderTest {

    private val address = "5B:61:1B:11:11:11"

    private fun bytes(hex: String) = hex.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    /**
     * Six real packets, all captured while the gauge read about 78 psi.
     *
     * Worth keeping as a set: everything that varies here varies at a CONSTANT
     * pressure, which is what makes them useful. Bytes 1, 5 and 7 swing wildly
     * and cannot be the reading. Bytes 0 and 2 hold still - 0xB3-B4 and
     * 0x51-52 - and are the only candidates a future capture needs to test.
     */
    private val at78psi = listOf(
        "B30051010AD80057281111111B615B",
        "B4AB52020AB50033281111111B615B",
        "B4AC52020A9C008E281111111B615B",
        "B4AC51020A2D0041281111111B615B",
        "B4AA52030A860059281111111B615B",
        "B4A752000A80009D281111111B615B",
    )

    @Test fun `the decoder publishes nothing while the format is unknown`() {
        // A wrong pressure on a rider's screen is worse than no pressure: they
        // would trust it, and it is about a tyre.
        for (hex in at78psi) {
            assertNull(
                "a guess reached the rider: $hex",
                LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, bytes(hex), address),
            )
        }
    }

    @Test fun `bytes 4 and 5 are not the pressure, which is the whole point`() {
        // The rider's own numbers, as the disproof. If someone revives this
        // field as pressure, these are what they have to explain.
        val readings = mapOf(42.5f to 0x0A84, 24.5f to 0x0A12, 0.0f to 0x0A62)
        val atZero = readings[0.0f]!!
        val atFull = readings[42.5f]!!
        // A tyre emptied from 42.5 psi to nothing moved this by 1.3 percent.
        val movement = kotlin.math.abs(atFull - atZero).toFloat() / atZero
        assertTrue("bytes 4..5 tracked the tyre after all", movement < 0.05f)
    }

    @Test fun `the constant bytes are the ones a new capture must test`() {
        // Everything that moves at a fixed pressure is noise; what holds still
        // is what can carry a reading. This is the shortlist.
        val payloads = at78psi.map { bytes(it) }
        fun spread(i: Int) = payloads.map { it[i].toInt() and 0xFF }.let { it.max() - it.min() }
        assertTrue("byte 0 stopped being stable", spread(0) <= 2)
        assertTrue("byte 2 stopped being stable", spread(2) <= 2)
        // The noisy ones, stated so the contrast is on the record.
        assertTrue("byte 5 stopped being noisy", spread(5) > 100)
        assertTrue("byte 7 stopped being noisy", spread(7) > 100)
    }

    @Test fun `the sensor is still recognised by its shape`() {
        // Identification does not depend on the decode: these repeat their own
        // MAC, reversed, at the end of the payload, and that is what tells one
        // apart from every other advertiser in a garage.
        val tail = bytes(at78psi[0]).takeLast(3).map { it.toInt() and 0xFF }
        assertEquals(listOf(0x1B, 0x61, 0x5B), tail)
        assertEquals(15, bytes(at78psi[0]).size)
        assertEquals(0x00AC, LyTpmsDecoder.COMPANY_ID)
    }

    @Test fun `a candidate is worth logging, which is all the signature decides`() {
        // This marks packets worth keeping in the decode trail. It does NOT
        // decide what gets added: adopting on this shape put a stranger's
        // device in as the rider's tyre sensor, because repeating your own
        // MAC is a habit plenty of hardware has. Three unrelated devices in
        // one room did it during a single sweep.
        for (hex in at78psi) {
            assertTrue("the real sensor stopped being logged: $hex",
                TpmsSignature.looksLikeSensor(hex, address))
        }
    }

    @Test fun `the MAC counts at either end and in either order`() {
        // The first rule was "the payload STARTS with the MAC". This sensor
        // ends with it, backwards, and that guess would have missed it.
        assertTrue(TpmsSignature.looksLikeSensor("B4AC52020A9C008E281111111B615B", address))
        assertTrue(TpmsSignature.looksLikeSensor("5B611B111111DEADBEEF", address))
        // Someone else's phone, laptop or earbuds: no MAC in the payload.
        assertTrue(!TpmsSignature.looksLikeSensor("0102030405060708", address))
        assertTrue(!TpmsSignature.looksLikeSensor("B4AC52020A9C008E281111111B615B", "AA:BB:CC:DD:EE:FF"))
        assertTrue(!TpmsSignature.looksLikeSensor("B4AC52020A9C008E281111111B615B", null))
    }

    @Test fun `a device that merely repeats its MAC is not adopted as a sensor`() {
        // Captured in the room while hunting for the cap: all three repeat
        // their own MAC and none of them is a tyre sensor. Nothing here
        // decodes as a pressure, which is the only thing that may add one.
        val strangers = listOf(
            "C2CA702B4EBF248000640000" to "C2:CA:70:2B:4E:BF",
            "F2DF89C852D9D46400000000" to "F2:DF:89:C8:52:D9",
            "F5CBED753B0100FF6A9751BE7400000000" to "F5:CB:ED:75:3B:01",
        )
        for ((hex, mac) in strangers) {
            assertTrue("$mac stopped looking like a candidate", TpmsSignature.looksLikeSensor(hex, mac))
            assertNull(
                "$mac was adopted as a tyre sensor",
                LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, bytes(hex), mac),
            )
        }
    }

    @Test fun `a foreign company id is never this sensor`() {
        assertNull(LyTpmsDecoder.pressureKpa(0x0969, bytes(at78psi[0]), address))
    }
}
