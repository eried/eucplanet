package com.eried.eucplanet.amazfit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazfitProtocolTest {

    @Test
    fun `poll interval follows the watchUpdateRate tiers`() {
        assertEquals(1500, amazfitPollIntervalMsFor("CONSERVATIVE"))
        assertEquals(1000, amazfitPollIntervalMsFor("NORMAL"))
        assertEquals(500, amazfitPollIntervalMsFor("FAST"))
        assertEquals(1000, amazfitPollIntervalMsFor("garbage"))
    }

    @Test
    fun `encode serialises every scalar type and nested event lists`() {
        val text = AmazfitJson.encode(
            mapOf(
                "c" to true,
                "b" to 78,
                "ts" to 1_700_000_000_000L,
                "s" to 28.5f,
                "n" to "Demo V14",
                "ev" to listOf(mapOf("k" to "vibe", "ms" to 300), mapOf("k" to "quit"))
            )
        )
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals(true, obj["c"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(78, obj["b"]!!.jsonPrimitive.content.toInt())
        assertEquals(1_700_000_000_000L, obj["ts"]!!.jsonPrimitive.content.toLong())
        assertEquals(28.5f, obj["s"]!!.jsonPrimitive.content.toFloat(), 0.0001f)
        assertEquals("Demo V14", obj["n"]!!.jsonPrimitive.content)
        val events = obj["ev"]!!.jsonArray
        assertEquals(2, events.size)
        assertEquals("vibe", events[0].jsonObject["k"]!!.jsonPrimitive.content)
        assertEquals(300, events[0].jsonObject["ms"]!!.jsonPrimitive.content.toInt())
        assertEquals("quit", events[1].jsonObject["k"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encode turns NaN into the -1 sentinel the watch expects`() {
        val text = AmazfitJson.encode(mapOf("gs" to Float.NaN))
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals(-1f, obj["gs"]!!.jsonPrimitive.content.toFloat(), 0f)
        assertTrue("must be valid JSON, no NaN literal", !text.contains("NaN"))
    }

    @Test
    fun `cmdOf reads the control intent and rejects anything else`() {
        assertEquals("horn", AmazfitJson.cmdOf("""{"cmd":"horn"}"""))
        assertEquals("action:LOCK_TOGGLE", AmazfitJson.cmdOf("""{"cmd":"action:LOCK_TOGGLE","x":1}"""))
        assertNull(AmazfitJson.cmdOf("""{"cmd":5}"""))
        assertNull(AmazfitJson.cmdOf("""{"nope":"horn"}"""))
        assertNull(AmazfitJson.cmdOf("not json"))
        assertNull(AmazfitJson.cmdOf(""))
    }
}
