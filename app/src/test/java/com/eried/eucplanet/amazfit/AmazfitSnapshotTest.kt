package com.eried.eucplanet.amazfit

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazfitSnapshotTest {

    private val nav = AmazfitSnapshot.Nav(show = false, angleDeg = 0f, primary = "", distance = "", arrived = false)

    private fun encode(
        settings: AppSettings = AppSettings(),
        gps: Pair<Float, String>? = null,
        events: List<Map<String, Any>> = emptyList()
    ) = AmazfitSnapshot.encode(
        data = WheelData(speed = 28.5f, batteryPercent = 78, voltage = 95.4f),
        connected = true,
        wheelName = "Demo V14",
        maxSpeedKmh = 70f,
        settings = settings,
        speedMultiplier = 1f,
        phoneBatteryPercent = 64,
        accentArgb = "#FF00BCD4",
        gps = gps,
        nav = nav,
        events = events,
        nowMs = 1_700_000_000_000L
    )

    @Test
    fun `frame carries every key the watch reads`() {
        val frame = encode()
        val missing = AmazfitSnapshot.WATCH_KEYS.filter { it !in frame.keys }
        assertTrue("missing keys: $missing", missing.isEmpty())
        assertEquals("state", frame["k"])
        assertEquals("Demo V14", frame["n"])
        assertEquals(78, frame["b"])
        assertEquals(64, frame["b2"])
    }

    @Test
    fun `gps uses the -1 sentinel when absent and the value when present`() {
        assertEquals(-1f, encode()["gs"])
        assertEquals("", encode()["gsr"])
        val withGps = encode(gps = 31.2f to "PHONE")
        assertEquals(31.2f, withGps["gs"])
        assertEquals("PHONE", withGps["gsr"])
    }

    @Test
    fun `poll interval follows the update-rate tier`() {
        assertEquals(1000, encode(AppSettings().copy(watchUpdateRate = "NORMAL"))["pi"])
        assertEquals(500, encode(AppSettings().copy(watchUpdateRate = "FAST"))["pi"])
        assertEquals(1500, encode(AppSettings().copy(watchUpdateRate = "CONSERVATIVE"))["pi"])
    }

    @Test
    fun `events pass through and the whole frame serialises to JSON`() {
        val text = AmazfitJson.encode(encode(events = listOf(mapOf("k" to "vibe", "ms" to 300))))
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("vibe", obj["ev"]!!.jsonArray[0].jsonObject["k"]!!.jsonPrimitive.content)
        assertEquals(70f, obj["ms"]!!.jsonPrimitive.content.toFloat(), 0f)
        assertEquals(1_700_000_000_000L, obj["ts"]!!.jsonPrimitive.content.toLong())
    }
}
