package com.eried.eucplanet.tpms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cap's MAC read out of its own payload, which is the address the iPhone
 * (IosTpmsRadio) keys a sensor by, since iOS never shows a MAC. The samples
 * are the ones LyTpmsDecoderTest and ZeepinTpmsDecoderTest pin.
 */
class TpmsPayloadAddressTest {

    private fun bytes(hex: String) = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The rider's LY cap at 0 bar, 27 C (LyTpmsDecoderTest's flat). */
    private val lyFlat = bytes("B30052000A8200BB281111111B615B")

    /** The same cap at 3.6 bar. */
    private val ly36bar = bytes("B37352010AA20004281111111B615B")

    /** The published ZEEPIN packet: wheel 4, 269 kPa. */
    private val zeepin = bytes("83 EA CA 40 61 81 C8 1C 04 00 0B 0B 00 00 4B 00")

    @Test fun lyMacIsTheLastSixBytesBackwards() {
        // The address LyTpmsDecoderTest uses for this cap, as Android reports it.
        assertEquals("5B:61:1B:11:11:11", TpmsPayloadAddress.fromManufacturer(LyTpmsDecoder.COMPANY_ID, lyFlat))
    }

    @Test fun lyMacIsStableAcrossReadings() {
        assertEquals(
            TpmsPayloadAddress.fromManufacturer(LyTpmsDecoder.COMPANY_ID, lyFlat),
            TpmsPayloadAddress.fromManufacturer(LyTpmsDecoder.COMPANY_ID, ly36bar),
        )
    }

    @Test fun zeepinMacIsTheFirstSixBytes() {
        assertEquals("83:EA:CA:40:61:81", TpmsPayloadAddress.fromManufacturer(ZeepinTpmsDecoder.COMPANY_ID, zeepin))
    }

    @Test fun zeepinMacNeedsAWheelNumber() {
        val other = zeepin.copyOf().also { it[0] = 0x10 }
        assertNull(TpmsPayloadAddress.fromManufacturer(ZeepinTpmsDecoder.COMPANY_ID, other))
    }

    @Test fun anythingElseHasNoPayloadMac() {
        // Right id, wrong length; wrong id, right length; an empty payload.
        assertNull(TpmsPayloadAddress.fromManufacturer(LyTpmsDecoder.COMPANY_ID, lyFlat.copyOf(14)))
        assertNull(TpmsPayloadAddress.fromManufacturer(0x004C, lyFlat))
        assertNull(TpmsPayloadAddress.fromManufacturer(ZeepinTpmsDecoder.COMPANY_ID, ByteArray(0)))
        assertNull(TpmsPayloadAddress.of(listOf(0x004C to bytes("0215"))))
    }

    @Test fun ofPicksTheEntryThatCarriesAMac() {
        val heard = listOf(0x004C to bytes("0215AABB"), LyTpmsDecoder.COMPANY_ID to lyFlat)
        assertEquals("5B:61:1B:11:11:11", TpmsPayloadAddress.of(heard))
    }

    @Test fun aDerivedLyAddressIsASensorAndDecodes() {
        val address = assertNotNull(TpmsPayloadAddress.fromManufacturer(LyTpmsDecoder.COMPANY_ID, ly36bar))
        assertTrue(TpmsSignature.isSensor(LyTpmsDecoder.COMPANY_ID, ly36bar, address))
        assertEquals(361.6f, LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, ly36bar, address)!!, absoluteTolerance = 0.1f)
        assertEquals(27f, LyTpmsDecoder.temperatureC(LyTpmsDecoder.COMPANY_ID, ly36bar, address))
        // The per-phone UUID iOS hands out instead would never match.
        val uuid = "6F1C2E9A-4B1D-4E0C-9A57-2B3C4D5E6F70"
        assertFalse(TpmsSignature.isSensor(LyTpmsDecoder.COMPANY_ID, ly36bar, uuid))
        assertNull(LyTpmsDecoder.pressureKpa(LyTpmsDecoder.COMPANY_ID, ly36bar, uuid))
    }

    @Test fun aDerivedZeepinAddressIsASensor() {
        val address = assertNotNull(TpmsPayloadAddress.fromManufacturer(ZeepinTpmsDecoder.COMPANY_ID, zeepin))
        assertTrue(TpmsSignature.isSensor(ZeepinTpmsDecoder.COMPANY_ID, zeepin, address))
        assertEquals(269f, ZeepinTpmsDecoder.pressureKpa(ZeepinTpmsDecoder.COMPANY_ID, zeepin)!!, absoluteTolerance = 1f)
    }

    @Test fun noFilterLetsEverythingThrough() {
        assertTrue(TpmsPayloadAddress.passes("5B:61:1B:11:11:11", null))
        assertTrue(TpmsPayloadAddress.passes("6F1C2E9A-4B1D-4E0C-9A57-2B3C4D5E6F70", null))
    }

    @Test fun aFilterKeepsOnlyItsCaps() {
        val paired = listOf("5B:61:1B:11:11:11", "83:EA:CA:40:61:81")
        assertTrue(TpmsPayloadAddress.passes("5B:61:1B:11:11:11", paired))
        assertTrue(TpmsPayloadAddress.passes("83:ea:ca:40:61:81", paired), "case does not matter")
        assertFalse(TpmsPayloadAddress.passes("5B:F4:63:22:22:22", paired))
        assertFalse(TpmsPayloadAddress.passes("6F1C2E9A-4B1D-4E0C-9A57-2B3C4D5E6F70", paired))
        // An empty filter is a filter: nothing passes.
        assertFalse(TpmsPayloadAddress.passes("5B:61:1B:11:11:11", emptyList()))
    }
}
