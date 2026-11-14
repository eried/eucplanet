package com.eried.eucplanet.tpms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the iPhone's tyre radio makes of an advert, without a CoreBluetooth
 * central: the company id split off as Android hands it over, the cap's MAC
 * from its payload as the address, and the address filter CoreBluetooth
 * cannot apply.
 */
class IosTpmsRadioTest {

    private fun bytes(hex: String) = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val uuid = "6F1C2E9A-4B1D-4E0C-9A57-2B3C4D5E6F70"

    /** CoreBluetooth's blob: company 0x00AC little endian, then the rider's LY cap at 3.6 bar. */
    private val lyBlob = bytes("AC00" + "B37352010AA20004281111111B615B")

    /** Company 0x0001, then the published ZEEPIN packet. */
    private val zeepinBlob = bytes("0100" + "83 EA CA 40 61 81 C8 1C 04 00 0B 0B 00 00 4B 00")

    private class Heard : TpmsRadio.Listener {
        val adverts = ArrayList<TpmsAdvert>()
        override fun onAdvert(advert: TpmsAdvert) { adverts += advert }
        override fun onFailed(errorCode: Int) = Unit
    }

    @Test fun anLyCapIsKnownByItsOwnMac() {
        val advert = tpmsAdvertOf(uuid, null, -60, lyBlob)
        assertEquals("5B:61:1B:11:11:11", advert.address)
        val (id, payload) = advert.manufacturer.single()
        assertEquals(LyTpmsDecoder.COMPANY_ID, id)
        assertEquals(15, payload.size)
        // What TpmsScanner.record then checks: a sensor, with a pressure.
        assertTrue(TpmsSignature.isSensor(id, payload, advert.address))
        assertEquals(361.6f, LyTpmsDecoder.pressureKpa(id, payload, advert.address)!!, absoluteTolerance = 0.1f)
    }

    @Test fun aZeepinCapIsKnownByItsOwnMac() {
        val advert = tpmsAdvertOf(uuid, "BR", -70, zeepinBlob)
        assertEquals("83:EA:CA:40:61:81", advert.address)
        assertEquals(ZeepinTpmsDecoder.COMPANY_ID, advert.manufacturer.single().first)
        assertEquals("BR", advert.name)
    }

    @Test fun anythingElseKeepsThePeripheralId() {
        assertEquals(uuid, tpmsAdvertOf(uuid, "Phone", -50, bytes("4C000215AABB")).address)
        assertEquals(uuid, tpmsAdvertOf(uuid, null, -50, null).address)
        assertTrue(tpmsAdvertOf(uuid, null, -50, bytes("4C")).manufacturer.isEmpty())
    }

    @Test fun aMonitorHearsOnlyItsOwnCaps() {
        val listeners = TpmsListeners()
        val monitor = Heard()
        val search = Heard()
        listeners.add(monitor, listOf("5B:61:1B:11:11:11"))
        listeners.add(search, null)
        listeners.hear(tpmsAdvertOf(uuid, null, -60, lyBlob))
        listeners.hear(tpmsAdvertOf(uuid, null, -60, zeepinBlob))
        listeners.hear(tpmsAdvertOf(uuid, "Phone", -50, null))
        assertEquals(listOf("5B:61:1B:11:11:11"), monitor.adverts.map { it.address })
        assertEquals(3, search.adverts.size)
    }

    @Test fun aClosedScanHearsNothing() {
        val listeners = TpmsListeners()
        val heard = Heard()
        listeners.add(heard, null)
        assertTrue(listeners.remove(heard))
        assertTrue(listeners.isEmpty())
        assertEquals(false, listeners.remove(heard))
        listeners.hear(tpmsAdvertOf(uuid, null, -60, lyBlob))
        assertTrue(heard.adverts.isEmpty())
    }
}
