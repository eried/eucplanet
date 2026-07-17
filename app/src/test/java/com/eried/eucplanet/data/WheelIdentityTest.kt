package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.WheelIdentity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WheelIdentityTest {

    @Test
    fun empty_returnsNull() {
        assertNull(WheelIdentity().toJson())
    }

    @Test
    fun mergedFields_areReported() {
        val id = WheelIdentity()
        id.merge(brand = "Begode", model = "Master", serial = "SN-1",
                 bleMac = "AA:BB:CC:DD:EE:FF", bleName = "Gotway_1234", firmware = "1.07")
        val obj = JSONObject(id.toJson()!!)
        assertEquals("Begode", obj.getString("brand"))
        assertEquals("SN-1", obj.getString("serial"))
        assertEquals("AA:BB:CC:DD:EE:FF", obj.getString("ble_mac"))
        assertEquals(6, obj.length())
    }

    /** The bug this class exists for: the wheel powers off, WheelRepository nulls
     *  model/serial/firmware, and the ride ends. The identity must still be known. */
    @Test
    fun disconnectAfterRide_doesNotEraseIdentity() {
        val id = WheelIdentity()
        id.merge(brand = "LeaperKim", model = "NOSFET Apex", serial = "SN-9",
                 bleMac = "88:25:83:F5:8D:BB", bleName = "Veteran", firmware = "2.0")
        // wheel disconnects -> every live field reads back null
        id.merge(brand = null, model = null, serial = null,
                 bleMac = null, bleName = null, firmware = null)
        val obj = JSONObject(id.toJson()!!)
        assertEquals("NOSFET Apex", obj.getString("model"))
        assertEquals("SN-9", obj.getString("serial"))
        assertEquals("88:25:83:F5:8D:BB", obj.getString("ble_mac"))
    }

    @Test
    fun blankNeverOverwritesAKnownValue() {
        val id = WheelIdentity()
        id.merge(serial = "SN-1")
        id.merge(serial = "   ")
        assertEquals("SN-1", JSONObject(id.toJson()!!).getString("serial"))
    }

    /** Identity trickles in: the MAC is known at connect, the serial only after the
     *  wheel answers. A later real value must land. */
    @Test
    fun lateArrivingFields_areAdded() {
        val id = WheelIdentity()
        id.merge(bleMac = "AA:01")
        assertEquals(false, JSONObject(id.toJson()!!).has("serial"))
        id.merge(serial = "SN-7", model = "Master")
        val obj = JSONObject(id.toJson()!!)
        assertEquals("SN-7", obj.getString("serial"))
        assertEquals("Master", obj.getString("model"))
        assertEquals("AA:01", obj.getString("ble_mac"))
    }

    @Test
    fun aRealValueReplacesAnEarlierRealValue() {
        val id = WheelIdentity()
        id.merge(model = "Unknown")
        id.merge(model = "Master")
        assertEquals("Master", JSONObject(id.toJson()!!).getString("model"))
    }

    /** Each ride starts fresh — one trip must never inherit the previous wheel. */
    @Test
    fun clear_resetsForTheNextTrip() {
        val id = WheelIdentity()
        id.merge(serial = "SN-1", bleMac = "AA:01")
        id.clear()
        assertNull(id.toJson())
        id.merge(bleMac = "BB:02")
        val obj = JSONObject(id.toJson()!!)
        assertEquals("BB:02", obj.getString("ble_mac"))
        assertEquals(false, obj.has("serial"))
    }

    @Test
    fun onlyBlanksEverMerged_staysNull() {
        val id = WheelIdentity()
        id.merge(brand = "", model = "  ", serial = null)
        assertNull(id.toJson())
    }

    @Test
    fun matchesBuildWheelMetaJsonShape() {
        val id = WheelIdentity()
        id.merge(brand = "Begode", model = "Master", bleMac = "AA:BB", firmware = "1.07")
        assertNotNull(id.toJson())
        val obj = JSONObject(id.toJson()!!)
        assertEquals(4, obj.length())       // blanks omitted, exactly like the builder
    }
}
