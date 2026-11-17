package com.eried.eucplanet.widget

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.util.Units
import com.eried.eucplanet.weather.HourForecast
import com.eried.eucplanet.weather.WeatherFace
import com.eried.eucplanet.weather.WeatherForecast
import com.eried.eucplanet.weather.WeatherScoring
import com.eried.eucplanet.weather.WeatherSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Android's weather widgets and the iPhone's both say about an hour. */
class WeatherWidgetContentTest {

    private val hour = WeatherScoring.ONE_HOUR_MS
    private val t0 = 1_700_000_000_000L

    private fun step(n: Int, tempC: Float = 20f, windMs: Float = 5f, rain: Float = 0f) =
        HourForecast(timeMs = t0 + n * hour, tempC = tempC, precipMmH = rain, snowCmH = 0f, windMs = windMs, isDay = true)

    private fun forecast(fetchedAtMs: Long, vararg hours: HourForecast) =
        WeatherForecast(hours.toList(), fetchedAtMs, 59.91, 10.75, WeatherSource.entries.first())

    @Test fun theScoreIsSigned() {
        assertEquals("+4", WeatherWidgetContent.signedScore(3.7f))
        assertEquals("0", WeatherWidgetContent.signedScore(0.2f))
        assertEquals("-3", WeatherWidgetContent.signedScore(-2.6f))
    }

    @Test fun theTemperatureIsInTheRidersUnit() {
        val metric = AppSettings(unitTemp = "C")
        val imperial = AppSettings(unitTemp = "F")
        assertEquals("20${Units.tempUnit("C")}", WeatherWidgetContent.tempLabel(step(0), metric))
        assertEquals("68${Units.tempUnit("F")}", WeatherWidgetContent.tempLabel(step(0), imperial))
    }

    @Test fun theWindIsInTheRidersSpeedUnit() {
        // 5 m/s is 18 km/h, a little over 11 mph.
        assertEquals("18 km/h", WeatherWidgetContent.windLabel(step(0), AppSettings(unitSpeed = "kmh"), "km/h"))
        assertEquals("11 mph", WeatherWidgetContent.windLabel(step(0), AppSettings(unitSpeed = "mph"), "mph"))
    }

    @Test fun shortWindowsAskForFineSteps() {
        assertTrue(WeatherWidgetContent.fine(12))
        assertFalse(WeatherWidgetContent.fine(13))
    }

    @Test fun thePointsStartAtTheStepInProgressWhenFetched() {
        // Fetched 20 minutes into the second step: the first is history.
        val f = forecast(t0 + hour + 20 * 60_000L, step(0), step(1), step(2), step(3))
        val points = WeatherWidgetContent.points(f, AppSettings(), "km/h")
        assertEquals(listOf(t0 + hour, t0 + 2 * hour, t0 + 3 * hour), points.map { it.timeMs })
    }

    @Test fun aForecastThatStartsLaterKeepsEveryStep() {
        val f = forecast(t0 - hour, step(0), step(1))
        assertEquals(2, WeatherWidgetContent.points(f, AppSettings(), "km/h").size)
    }

    @Test fun aPointSaysWhatThePanelSaysForItsHour() {
        val settings = AppSettings()
        val wet = step(1, tempC = 8f, windMs = 9f, rain = 3f)
        val p = WeatherWidgetContent.points(forecast(t0, step(0), wet), settings, "km/h")[1]
        val b = WeatherScoring.scoreOf(wet, settings)
        assertEquals(b.score, p.score)
        assertEquals(WeatherWidgetContent.signedScore(b.score), p.scoreLabel)
        assertEquals(WeatherFace.of(b).key, p.faceKey)
        assertEquals(WeatherWidgetContent.tempLabel(wet, settings), p.tempLabel)
        assertEquals(WeatherWidgetContent.windLabel(wet, settings, "km/h"), p.windLabel)
    }
}
