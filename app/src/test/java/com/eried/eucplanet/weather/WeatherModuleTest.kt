package com.eried.eucplanet.weather

import com.eried.eucplanet.data.model.WeatherSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The weather / ridability module: the signed score behaves like the rider's
 * own calibration reads, the providers parse, the defaults are the stated
 * comfort, and the module ships disabled with its strings in every locale.
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

    // --- The score, centred on zero -----------------------------------------

    @Test fun `mid-band calm dry daylight is the full +5`() {
        assertEquals(5f, s(hour(tempC = 22.5f)).score, 0.001f)
    }

    @Test fun `band-edge temperature with low wind is a mild plus, about +1`() {
        val v = s(hour(tempC = 15f, wind = 1f)).score
        assertTrue("was $v", v in 0.5f..2f)
    }

    @Test fun `slightly cold and gusty lands around -1`() {
        val v = s(hour(tempC = 12f, wind = 3f)).score
        assertTrue("was $v", v in -1.6f..-0.4f)
    }

    @Test fun `strong wind plus rain is the -5 floor`() {
        assertEquals(-5f, s(hour(wind = 8f, rain = 2f)).score, 0.001f)
    }

    @Test fun `snow with cold but no wind is only about -1, not a disaster`() {
        val v = s(hour(tempC = 5f, snow = 0.5f)).score
        assertTrue("was $v", v in -2f..-1f)
    }

    @Test fun `very cold alone stays near neutral, dressing is the fix`() {
        val v = s(hour(tempC = -10f)).score
        assertTrue("was $v", v in -1f..0f)
    }

    @Test fun `precipitation cancels the comfort bonuses`() {
        // A warm calm hour with rain must not read positive.
        assertTrue(s(hour(tempC = 22.5f, rain = 0.5f)).score < 0f)
    }

    @Test fun `night is a nudge, not a verdict`() {
        val d = s(hour(tempC = 22.5f))
        val n = s(hour(tempC = 22.5f, day = false))
        assertEquals(0.5f, d.score - n.score, 0.001f)
        assertTrue(n.night)
    }

    @Test fun `wind ramps between breezy and windy, then keeps climbing`() {
        val calm = s(hour(tempC = 22.5f, wind = 0f))
        val breeze = s(hour(tempC = 22.5f, wind = 3f))
        val windy = s(hour(tempC = 22.5f, wind = 4.5f))
        val gale = s(hour(tempC = 22.5f, wind = 8f))
        assertTrue(breeze.score < calm.score)
        assertTrue(windy.score < breeze.score)
        assertTrue(gale.score < windy.score)
        // Full -2 ramp at the windy line: +3 temp bonus, no calm bonus.
        assertEquals(1f, windy.score, 0.05f)
    }

    @Test fun `snow supersedes rain in the flags and bites no harder`() {
        val rain = s(hour(rain = 1f))
        val snow = s(hour(rain = 1f, snow = 1f))
        assertTrue(rain.rain && !rain.snow)
        assertTrue(snow.snow && !snow.rain)
        assertEquals(rain.score, snow.score, 0.5f)
    }

    @Test fun `the scale clamps at -5 and +5`() {
        val awful = s(hour(tempC = -20f, snow = 4f, wind = 12f, day = false))
        assertEquals(-5f, awful.score, 0.001f)
        assertTrue(s(hour(tempC = 22.5f)).score <= 5f)
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

    @Test fun `the source picker is a combo and the flyout wears the nav popup style`() {
        val nav = File("src/main/java/com/eried/eucplanet/ui/settings/NavigatorSettingsContent.kt").readText()
        assertTrue(nav.contains("ExposedDropdownMenuBox"))
        val flyout = File("src/main/java/com/eried/eucplanet/ui/dashboard/WeatherFlyout.kt").readText()
        // Nav-popup family: inverse panel, rounded, shadowed.
        assertTrue(flyout.contains("navPopupPanel"))
        assertTrue(flyout.contains("RoundedCornerShape(12.dp)"))
        // The signed blue-to-magenta scale, no tap tooltips.
        assertTrue(flyout.contains("weatherGood"))
        assertTrue(flyout.contains("weatherBad"))
        assertTrue(flyout.contains("Brush.horizontalGradient"))
        assertTrue(!flyout.contains("weather_face_"))
        assertTrue(!flyout.contains("clickable"))
    }

    @Test fun `the icon and flyout only exist when the module is enabled`() {
        val dash = File("src/main/java/com/eried/eucplanet/ui/dashboard/DashboardScreen.kt").readText()
        assertTrue(dash.contains("if (weatherSettings.enabled)"))
        assertTrue(dash.contains("showWeatherFlyout && weatherSettings.enabled"))
    }

    @Test fun `the score scale tokens are registered for the theme editor`() {
        val theme = File("src/main/java/com/eried/eucplanet/ui/theme/AppThemeColors.kt").readText()
        assertTrue(theme.contains("ThemeTokenSpec(\"weatherGood\""))
        assertTrue(theme.contains("ThemeTokenSpec(\"weatherBad\""))
        assertTrue(theme.contains("weatherGood = weatherGood.takeOrElse"))
        assertTrue(theme.contains("weatherBad = weatherBad.takeOrElse"))
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
        )
        val missing = File("src/main/res").listFiles()!!
            .filter { it.isDirectory && it.name.startsWith("values") && File(it, "strings.xml").exists() }
            .flatMap { dir ->
                val t = File(dir, "strings.xml").readText()
                keys.filter { !t.contains("name=\"$it\"") }.map { "${dir.name}/$it" }
            }
        assertTrue("missing: $missing", missing.isEmpty())
        // The face tooltip strings are gone from every locale, not just used
        // nowhere.
        val leftovers = File("src/main/res").listFiles()!!
            .filter { it.isDirectory && it.name.startsWith("values") && File(it, "strings.xml").exists() }
            .filter { File(it, "strings.xml").readText().contains("weather_face_") }
            .map { it.name }
        assertTrue("face strings left in: $leftovers", leftovers.isEmpty())
        // The renamed section title, in English at least, names both halves.
        val en = File("src/main/res/values/strings.xml").readText()
        assertTrue(en.contains("<string name=\"nav_setting_params\">Navigation &amp; weather</string>"))
    }
}
