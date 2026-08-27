package com.eried.eucplanet.weather

import com.eried.eucplanet.data.model.WeatherSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The weather / ridability module: the score behaves like the spec reads,
 * the providers parse, the defaults are the rider's stated comfort, and the
 * module ships disabled with its strings in every locale.
 */
class WeatherModuleTest {

    private fun hour(
        tempC: Float = 20f,
        rain: Float = 0f,
        snow: Float = 0f,
        wind: Float = 0f,
        day: Boolean = true,
    ) = HourForecast(0L, tempC, rain, snow, wind, day)

    private fun s(h: HourForecast) = RidabilityScore.score(h, 14f, 31f, 2f, 4.5f)

    // --- The score ----------------------------------------------------------

    @Test fun `a mild dry calm daylight hour is a 10`() {
        assertEquals(10f, s(hour()).score, 0.001f)
    }

    @Test fun `cold and heat pull the score down past their thresholds`() {
        assertTrue(s(hour(tempC = 13f)).score < 10f)
        assertTrue(s(hour(tempC = 4f)).score < s(hour(tempC = 10f)).score)
        assertTrue(s(hour(tempC = 35f)).score < 10f)
        // Inside the band there is no temperature penalty at all.
        assertEquals(10f, s(hour(tempC = 14f)).score, 0.001f)
        assertEquals(10f, s(hour(tempC = 31f)).score, 0.001f)
    }

    @Test fun `rain bites, snow bites much harder, and snow supersedes rain`() {
        val rain = s(hour(rain = 1f))
        val snow = s(hour(rain = 1f, snow = 1f))
        assertTrue(rain.rain && !rain.snow)
        assertTrue(snow.snow && !snow.rain)
        assertTrue(snow.score < rain.score - 2f)
    }

    @Test fun `night is a nudge, not a verdict`() {
        val d = s(hour())
        val n = s(hour(day = false))
        assertEquals(1f, d.score - n.score, 0.001f)
        assertTrue(n.night)
    }

    @Test fun `wind ramps between breezy and windy, then keeps climbing`() {
        assertEquals(10f, s(hour(wind = 1.9f)).score, 0.001f)
        val breeze = s(hour(wind = 3f))
        val windy = s(hour(wind = 4.5f))
        val gale = s(hour(wind = 8f))
        assertTrue(breeze.score < 10f)
        assertTrue(windy.score < breeze.score)
        assertTrue(gale.score < windy.score)
        assertEquals(7.5f, windy.score, 0.05f)  // full 2.5 ramp at the windy line
    }

    @Test fun `the floor is zero, never negative`() {
        val awful = s(hour(tempC = -20f, snow = 4f, wind = 12f, day = false))
        assertEquals(0f, awful.score, 0.001f)
    }

    // --- Providers ----------------------------------------------------------

    @Test fun `open-meteo parses into forecast hours`() {
        val body = """
            {"hourly":{
              "time":[1756200000,1756203600],
              "temperature_2m":[21.5,12.0],
              "precipitation":[0.0,1.2],
              "snowfall":[0.0,0.0],
              "wind_speed_10m":[1.2,5.0],
              "is_day":[1,0]}}
        """.trimIndent()
        val hours = WeatherRepository().parseOpenMeteo(body)
        assertEquals(2, hours.size)
        assertEquals(1756200000_000L, hours[0].timeMs)
        assertEquals(21.5f, hours[0].tempC, 0.001f)
        assertTrue(hours[0].isDay)
        assertEquals(1.2f, hours[1].precipMmH, 0.001f)
        assertTrue(!hours[1].isDay)
    }

    @Test fun `met norway parses its hourly entries and flags snow and night`() {
        val body = """
            {"properties":{"timeseries":[
              {"time":"2026-08-27T10:00:00Z","data":{
                "instant":{"details":{"air_temperature":18.0,"wind_speed":3.0}},
                "next_1_hours":{"summary":{"symbol_code":"lightrain"},
                                "details":{"precipitation_amount":0.4}}}},
              {"time":"2026-08-27T22:00:00Z","data":{
                "instant":{"details":{"air_temperature":-2.0,"wind_speed":1.0}},
                "next_1_hours":{"summary":{"symbol_code":"snow_night"},
                                "details":{"precipitation_amount":1.5}}}},
              {"time":"2026-08-30T10:00:00Z","data":{
                "instant":{"details":{"air_temperature":20.0,"wind_speed":2.0}}}}
            ]}}
        """.trimIndent()
        val hours = WeatherRepository().parseMetNo(body)
        // The entry without an hourly block is skipped.
        assertEquals(2, hours.size)
        assertEquals(0.4f, hours[0].precipMmH, 0.001f)
        assertEquals(0f, hours[0].snowCmH, 0.001f)
        assertEquals(1.5f, hours[1].snowCmH, 0.001f)
        assertEquals(0f, hours[1].precipMmH, 0.001f)
        assertTrue(!hours[1].isDay)
    }

    // --- Settings & registry ------------------------------------------------

    @Test fun `ships disabled, with the rider's stated comfort defaults`() {
        val w = WeatherSettings()
        assertTrue(!w.enabled)
        assertEquals(6, w.windowHours)
        assertEquals("OPEN_METEO", w.source)
        assertEquals(14, w.coldC)
        assertEquals(31, w.hotC)
        assertEquals(20, w.breezyTenthsMs)   // 2.0 m/s
        assertEquals(45, w.windyTenthsMs)    // 4.5 m/s
    }

    @Test fun `unknown source ids fall back to open-meteo`() {
        assertEquals(WeatherSource.OPEN_METEO, WeatherSource.byId("bogus"))
        assertEquals(WeatherSource.MET_NO, WeatherSource.byId("MET_NO"))
    }

    // --- Surfaces -----------------------------------------------------------

    @Test fun `the source picker is a combo and the faces have their lingo`() {
        val nav = File("src/main/java/com/eried/eucplanet/ui/settings/NavigatorSettingsContent.kt").readText()
        assertTrue(nav.contains("ExposedDropdownMenuBox"))
        val flyout = File("src/main/java/com/eried/eucplanet/ui/dashboard/WeatherFlyout.kt").readText()
        assertTrue(flyout.contains("weather_face_snow"))
        assertTrue(flyout.contains("Brush.horizontalGradient"))
    }

    @Test fun `the icon and flyout only exist when the module is enabled`() {
        val dash = File("src/main/java/com/eried/eucplanet/ui/dashboard/DashboardScreen.kt").readText()
        assertTrue(dash.contains("if (weatherSettings.enabled)"))
        assertTrue(dash.contains("showWeatherFlyout && weatherSettings.enabled"))
    }

    @Test fun `the section is Navigation & weather and every locale has the strings`() {
        val keys = listOf(
            "weather_section", "weather_icon_desc", "weather_now", "weather_fetching",
            "weather_updated_ago", "weather_refresh", "weather_error",
            "weather_window_6h", "weather_window_24h", "weather_window_3d", "weather_window_1w",
            "weather_win_6", "weather_win_24", "weather_win_3d", "weather_win_1w",
            "weather_settings_entry", "weather_enable", "weather_enable_desc",
            "weather_window_label", "weather_source_label", "weather_comfort_desc",
            "weather_cold", "weather_hot", "weather_breezy", "weather_windy",
            "weather_source_credit",
            "weather_face_clear", "weather_face_meh", "weather_face_rain",
            "weather_face_snow", "weather_face_wind", "weather_face_night",
            "weather_face_cold", "weather_face_hot",
        )
        val missing = File("src/main/res").listFiles()!!
            .filter { it.isDirectory && it.name.startsWith("values") && File(it, "strings.xml").exists() }
            .flatMap { dir ->
                val t = File(dir, "strings.xml").readText()
                keys.filter { !t.contains("name=\"$it\"") }.map { "${dir.name}/$it" }
            }
        assertTrue("missing: $missing", missing.isEmpty())
        // The renamed section title, in English at least, names both halves.
        val en = File("src/main/res/values/strings.xml").readText()
        assertTrue(en.contains("<string name=\"nav_setting_params\">Navigation &amp; weather</string>"))
    }
}
