package com.eried.eucplanet.app

import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.widget.WeatherWidgetContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file the weather widgets read (iosApp/Widgets/WeatherWidgets.swift
 * decodes these names): a field renamed here and not there is a widget that
 * says there is no forecast.
 */
class IosWeatherWidgetsTest {

    private val content = IosWeatherWidgets.Content(
        fetchedAtMs = 1_700_000_123_456,
        points = listOf(
            WeatherWidgetContent.Point(1_700_000_000_000, 3.96f, "+4", "CLEAR", "18°C", "12 km/h"),
            WeatherWidgetContent.Point(1_700_003_600_000, -2.44f, "-2", "RAIN", "16°C", "20 km/h"),
        ),
        faces = mapOf(
            "CLEAR" to IosWeatherWidgets.Face("😄", "Go ride"),
            "RAIN" to IosWeatherWidgets.Face("😬", "Wet roads"),
        ),
        windowHours = 8,
        windowLabel = "next 8 h",
        place = "Oslo",
        now = "Now",
        hourAgo = "%1\$d h ago",
        lat = 59.91,
        lon = 10.75,
    )

    @Test fun theFileCarriesWhatTheSwiftSideDecodes() {
        val o = JSONObject(IosWeatherWidgets.encode(content))
        // Seconds, as Swift's dates count.
        assertEquals(1_700_000_123L, o.getLong("fetchedAt"))
        assertEquals(8 * 3600, o.getInt("window"))
        assertEquals(3 * 3600, o.getInt("stale"))
        assertEquals("next 8 h", o.getString("windowLabel"))
        assertEquals("Oslo", o.getString("place"))
        assertEquals("Now", o.getString("now"))
        assertEquals("%1\$d h ago", o.getString("hourAgo"))
        val rain = o.getJSONObject("faces").getJSONObject("RAIN")
        assertEquals("😬", rain.getString("emoji"))
        assertEquals("Wet roads", rain.getString("text"))
    }

    @Test fun eachPointSaysItsOwnHour() {
        val points = JSONObject(IosWeatherWidgets.encode(content)).getJSONArray("points")
        assertEquals(2, points.length())
        val second = points.getJSONObject(1)
        assertEquals(1_700_003_600L, second.getLong("t"))
        assertEquals("-2", second.getString("label"))
        assertEquals("RAIN", second.getString("face"))
        assertEquals("16°C", second.getString("temp"))
        assertEquals("20 km/h", second.getString("wind"))
    }

    @Test fun theScoreKeepsOneDecimalForTheCurve() {
        val points = JSONObject(IosWeatherWidgets.encode(content)).getJSONArray("points")
        assertEquals(4.0, points.getJSONObject(0).getDouble("score"), 0.001)
        assertEquals(-2.4, points.getJSONObject(1).getDouble("score"), 0.001)
    }

    @Test fun aRefreshWithNoFixFindsThePlaceAgain() {
        // IosWeatherWidgets.lastWritten reads these back.
        val o = JSONObject(IosWeatherWidgets.encode(content))
        assertEquals(59.91, o.getDouble("lat"), 0.0001)
        assertEquals(10.75, o.getDouble("lon"), 0.0001)
        assertTrue(o.has("place"))
    }
}
