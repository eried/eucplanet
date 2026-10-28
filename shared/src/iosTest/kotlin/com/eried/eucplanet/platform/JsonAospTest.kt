package com.eried.eucplanet.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS port prints exactly what Android's org.json prints, so a settings
 * file passed between the two phones hashes the same. Each expected string is
 * what an Android device returns for the same calls.
 */
class JsonAospTest {

    @Test
    fun keysKeepInsertionOrder() {
        val o = JSONObject().put("z", 1).put("a", 2).put("m", 3)
        assertEquals("""{"z":1,"a":2,"m":3}""", o.toString())
        assertEquals("""{"b":1,"a":{"d":true,"c":"x"}}""", JSONObject("""{"b":1,"a":{"d":true,"c":"x"}}""").toString())
    }

    @Test
    fun numbersPrintLikeNumberToString() {
        val o = JSONObject()
            .put("whole", 3.0)
            .put("frac", 0.1)
            .put("neg0", -0.0)
            .put("big", 1.0e20)
            .put("float", 0.1f)
            .put("long", 1_700_000_000_000L)
        assertEquals(
            """{"whole":3,"frac":0.1,"neg0":-0,"big":1.0E20,"float":0.1,"long":1700000000000}""",
            o.toString(),
        )
    }

    @Test
    fun stringsEscapeSlashesAndControls() {
        val o = JSONObject().put("p", "a/b\\c\"d\te\u0001")
        assertEquals("""{"p":"a\/b\\c\"d\te\u0001"}""", o.toString())
    }

    @Test
    fun indentedOutput() {
        val o = JSONObject().put("a", 1).put("b", JSONArray().put(1).put(2)).put("e", JSONObject())
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    1,\n    2\n  ],\n  \"e\": {}\n}", o.toString(2))
    }

    @Test
    fun explicitNullReadsAsTheTextNull() {
        val o = JSONObject("""{"a":null}""")
        assertEquals("null", o.optString("a", "fallback"))
        assertEquals("""{"a":null}""", o.toString())
    }

    @Test
    fun lenientInput() {
        val o = JSONObject("{ /* c */ a = 1; 'b' => \"two\", # line\n c: 0x10, d: 010, e: 5000000000 }")
        assertEquals(1, o.getInt("a"))
        assertEquals("two", o.getString("b"))
        assertEquals(16, o.getInt("c"))
        assertEquals(8, o.getInt("d"))
        assertEquals(5_000_000_000L, o.get("e"))
    }

    @Test
    fun parsedNumberTypes() {
        val o = JSONObject("""{"i":1,"d":1.0,"s":"1.5"}""")
        assertEquals(1, o.get("i"))
        assertEquals(1.0, o.get("d"))
        assertEquals("""{"i":1,"d":1,"s":"1.5"}""", o.toString())
        assertEquals(1, o.optInt("s"))
    }

    @Test
    fun byteOrderMarkIsSkipped() {
        assertEquals(1, JSONObject("﻿{\"a\":1}").getInt("a"))
    }
}
