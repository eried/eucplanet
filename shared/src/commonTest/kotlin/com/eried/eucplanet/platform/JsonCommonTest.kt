package com.eried.eucplanet.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every org.json the facade can stand for agrees on: Android's, the
 * JVM test copy and the iOS port. Exact text (key order, escapes) is in
 * JsonAospTest, which only the iOS port is held to.
 */
class JsonCommonTest {

    @Test
    fun roundTripsTheTypesSettingsUse() {
        val o = JSONObject()
            .put("s", "text")
            .put("b", true)
            .put("i", 42)
            .put("l", 1_700_000_000_000L)
            .put("d", 2.5)
            .put("nested", JSONObject().put("x", 1))
            .put("list", JSONArray().put("a").put(2))
        val back = JSONObject(o.toString())
        assertEquals("text", back.optString("s", "?"))
        assertTrue(back.optBoolean("b", false))
        assertEquals(42, back.optInt("i", 0))
        assertEquals(1_700_000_000_000L, back.optLong("l", 0L))
        assertEquals(2.5, back.optDouble("d", 0.0))
        assertEquals(1, back.optJSONObject("nested")?.optInt("x", 0))
        assertEquals(2, back.optJSONArray("list")?.length())
        assertEquals("a", back.getJSONArray("list").getString(0))
        assertEquals(7, back.length())
    }

    @Test
    fun optFallsBackForMissingAndWrongTypes() {
        val o = JSONObject("""{"n":"7","t":"TRUE","x":"junk","f":3.9}""")
        assertEquals("dflt", o.optString("missing", "dflt"))
        assertEquals("", o.optString("missing"))
        assertEquals(7, o.optInt("n", 0))
        assertEquals(3, o.optInt("f", 0))
        assertTrue(o.optBoolean("t", false))
        assertEquals(-1, o.optInt("x", -1))
        assertFalse(o.optBoolean("x", false))
        assertNull(o.optJSONObject("n"))
        assertNull(o.optJSONArray("missing"))
        assertTrue(o.optDouble("missing").isNaN())
    }

    @Test
    fun putNullRemovesTheName() {
        val o = JSONObject().put("a", 1)
        o.put("a", null as Any?)
        assertFalse(o.has("a"))
    }

    @Test
    fun explicitNullIsNull() {
        val o = JSONObject("""{"a":null}""")
        assertTrue(o.has("a"))
        assertTrue(o.isNull("a"))
        assertTrue(o.isNull("missing"))
        assertEquals(JSONNull, o.opt("a"))
    }

    @Test
    fun getThrowsForMissing() {
        assertFailsWith<JSONException> { JSONObject().getString("nope") }
        assertFailsWith<JSONException> { JSONObject("[1]") }
        assertFailsWith<JSONException> { JSONArray().get(0) }
    }

    @Test
    fun keysAndNames() {
        val o = JSONObject().put("one", 1).put("two", 2)
        assertEquals(setOf("one", "two"), o.keys().asSequence().toSet())
        assertEquals(2, o.names()?.length())
        assertNull(JSONObject().names())
    }

    @Test
    fun arraysFromCollections() {
        val a = JSONArray(listOf("x", 1, null))
        assertEquals(3, a.length())
        assertEquals("x", a.optString(0))
        assertEquals(1, a.optInt(1))
        assertTrue(a.isNull(2))
        assertNull(a.opt(5))
        assertEquals("x", a.remove(0))
        assertEquals(2, a.length())
    }

    @Test
    fun objectsFromMaps() {
        val o = JSONObject(mapOf("k" to "v", "n" to 5))
        assertEquals("v", o.getString("k"))
        assertEquals(5, o.getInt("n"))
    }

    @Test
    fun quoteEscapes() {
        assertEquals("\"a\\\"b\"", jsonQuote("a\"b"))
        assertEquals("\"line\\nnext\"", jsonQuote("line\nnext"))
    }

    @Test
    fun nonFiniteNumbersAreRefused() {
        assertFailsWith<JSONException> { JSONObject().put("x", Double.NaN) }
        assertFailsWith<JSONException> { JSONArray().put(Double.POSITIVE_INFINITY) }
    }
}
