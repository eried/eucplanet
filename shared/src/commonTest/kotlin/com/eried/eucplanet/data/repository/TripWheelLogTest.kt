package com.eried.eucplanet.data.repository

import com.eried.eucplanet.platform.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Both phones' recorders stream the wheel through [TripWheelLog], so an
 * iPhone trip's Extra column reads the way an Android one does (the trip
 * tools' wheel picker and eucviewer read exactly these rows).
 */
class TripWheelLogTest {

    private fun TripWheelLog.drain(): List<String> = generateSequence { nextExtra() }.toList()

    @Test fun theBlockGoesOutOnePairPerRowInAndroidsOrder() {
        val log = TripWheelLog()
        log.identity.merge(
            brand = "Begode", model = "Master", serial = "SN-1",
            bleMac = "aa:bb:cc:dd:ee:ff", bleName = "GotWay_1234", firmware = "1.07",
        )
        log.queueIdentity()
        assertEquals(
            listOf(
                "wheel.name=GotWay_1234", "wheel.mac=AABBCCDDEEFF", "wheel.brand=Begode",
                "wheel.model=Master", "wheel.serial=SN-1", "wheel.firmware=1.07",
            ),
            log.drain(),
        )
    }

    @Test fun aLateSerialAddsOneRowAndAReconnectRepeatsTheBlock() {
        val log = TripWheelLog()
        log.identity.merge(bleName = "V14", brand = "InMotion")
        log.queueIdentity()
        log.drain()
        log.identity.merge(serial = "SN-7")
        log.queueIdentity()
        assertEquals(listOf("wheel.serial=SN-7"), log.drain())
        log.onDisconnect()
        assertEquals(listOf(TripWheelLog.DISCONNECTED), log.drain())
        log.onReconnect()
        log.queueIdentity()
        assertEquals(listOf("wheel.name=V14", "wheel.brand=InMotion", "wheel.serial=SN-7"), log.drain())
    }

    @Test fun theRowIsWrittenOnlyWhenTheIdentityChanges() {
        val log = TripWheelLog()
        assertNull(log.jsonIfChanged())
        log.identity.merge(model = "Master")
        assertEquals("Master", JSONObject(log.jsonIfChanged()!!).getString("model"))
        assertNull(log.jsonIfChanged())
        log.identity.merge(serial = "SN-1")
        assertEquals("SN-1", JSONObject(log.jsonIfChanged()!!).getString("serial"))
    }

    @Test fun resetForgetsThePreviousRide() {
        val log = TripWheelLog()
        log.identity.merge(serial = "SN-1")
        log.queueIdentity()
        log.jsonIfChanged()
        log.reset()
        assertNull(log.nextExtra())
        assertNull(log.identity.toJson())
        log.identity.merge(serial = "SN-1")
        log.queueIdentity()
        assertEquals(listOf("wheel.serial=SN-1"), log.drain())
        assertEquals("SN-1", JSONObject(log.jsonIfChanged()!!).getString("serial"))
    }

    @Test fun aCoreBluetoothIdIsNotAMac() {
        assertNull(TripWheelLog.macOrNull("1A2B3C4D-5E6F-7081-92A3-B4C5D6E7F809"))
        assertNull(TripWheelLog.macOrNull(null))
        assertEquals("88:25:83:F5:8D:BB", TripWheelLog.macOrNull("88:25:83:F5:8D:BB"))
        assertEquals("VIRTUAL:begode_master", TripWheelLog.macOrNull("VIRTUAL:begode_master"))
    }

    @Test fun blanksNeverEraseAKnownField() {
        val id = WheelIdentity()
        id.merge(serial = "SN-1", model = "Master")
        id.merge(serial = "  ", model = null)
        val obj = JSONObject(id.toJson()!!)
        assertEquals("SN-1", obj.getString("serial"))
        assertEquals("Master", obj.getString("model"))
    }
}
