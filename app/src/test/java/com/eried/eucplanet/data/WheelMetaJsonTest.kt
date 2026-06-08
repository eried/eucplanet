package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.buildWheelMetaJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WheelMetaJsonTest {

    @Test
    fun allNull_returnsNull() {
        assertNull(buildWheelMetaJson(null, null, null, null, null, null))
    }

    @Test
    fun allBlank_returnsNull() {
        assertNull(buildWheelMetaJson("", "  ", "", "  ", "", "  "))
    }

    @Test
    fun mixedNullAndBlank_returnsNull() {
        assertNull(buildWheelMetaJson(null, "", null, "  ", null, ""))
    }

    @Test
    fun partialFields_jsonContainsOnlyProvidedFields() {
        val result = buildWheelMetaJson(
            brand = "Begode",
            model = "Master",
            serial = null,
            bleMac = "AA:BB:CC:DD:EE:FF",
            bleName = null,
            firmware = "1.07",
        )
        assertNotNull(result)
        val obj = JSONObject(result!!)
        assertEquals("Begode", obj.getString("brand"))
        assertEquals("Master", obj.getString("model"))
        assertEquals("AA:BB:CC:DD:EE:FF", obj.getString("ble_mac"))
        assertEquals("1.07", obj.getString("firmware"))
        // null fields must not be present
        assertEquals(false, obj.has("serial"))
        assertEquals(false, obj.has("ble_name"))
        assertEquals(4, obj.length())
    }

    @Test
    fun allFields_jsonContainsAllKeys() {
        val result = buildWheelMetaJson(
            brand = "InMotion",
            model = "V14",
            serial = "SN12345",
            bleMac = "11:22:33:44:55:66",
            bleName = "InMotionV14",
            firmware = "2.5.1",
        )
        assertNotNull(result)
        val obj = JSONObject(result!!)
        assertEquals(6, obj.length())
        assertEquals("InMotion", obj.getString("brand"))
        assertEquals("V14", obj.getString("model"))
        assertEquals("SN12345", obj.getString("serial"))
        assertEquals("11:22:33:44:55:66", obj.getString("ble_mac"))
        assertEquals("InMotionV14", obj.getString("ble_name"))
        assertEquals("2.5.1", obj.getString("firmware"))
    }

    @Test
    fun blankStrings_treatedAsAbsent() {
        // A blank model should not be included; a non-blank brand should be
        val result = buildWheelMetaJson(
            brand = "KingSong",
            model = "   ",
            serial = null,
            bleMac = "",
            bleName = "KS-S22",
            firmware = null,
        )
        assertNotNull(result)
        val obj = JSONObject(result!!)
        assertEquals("KingSong", obj.getString("brand"))
        assertEquals("KS-S22", obj.getString("ble_name"))
        assertEquals(false, obj.has("model"))
        assertEquals(false, obj.has("ble_mac"))
        assertEquals(false, obj.has("serial"))
        assertEquals(false, obj.has("firmware"))
        assertEquals(2, obj.length())
    }

    @Test
    fun onlyBleMac_returnsSingleKeyJson() {
        val result = buildWheelMetaJson(null, null, null, "DE:AD:BE:EF:00:01", null, null)
        assertNotNull(result)
        val obj = JSONObject(result!!)
        assertEquals(1, obj.length())
        assertEquals("DE:AD:BE:EF:00:01", obj.getString("ble_mac"))
    }
}
