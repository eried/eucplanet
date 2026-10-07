package com.eried.eucplanet.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window a widget draws: from now to the hours the rider configured.
 *
 * A widget can sit unrefreshed for a long time, so the rules about what counts
 * as "now" matter more here than they do in the panel, which is only ever open
 * seconds after a fetch.
 */
class WeatherScoringTest {

    private val h = WeatherScoring.ONE_HOUR_MS

    private fun hours(startMs: Long, n: Int, stepMs: Long = h) = (0 until n).map {
        HourForecast(
            timeMs = startMs + it * stepMs,
            tempC = 15f,
            precipMmH = 0f,
            snowCmH = 0f,
            windMs = 2f,
            isDay = true,
        )
    }

    @Test fun `the window starts at now and runs the configured hours`() {
        val now = 1_000_000_000_000L
        val w = WeatherScoring.window(hours(now, 48), windowHours = 8, nowMs = now)
        assertEquals(9, w.size)                       // now plus eight ahead
        assertEquals(now, w.first().timeMs)
        assertEquals(now + 8 * h, w.last().timeMs)
    }

    @Test fun `hours already ridden are dropped`() {
        val now = 1_000_000_000_000L
        // A forecast fetched this morning, read this evening.
        val w = WeatherScoring.window(hours(now - 10 * h, 24), windowHours = 6, nowMs = now)
        assertTrue("nothing older than an hour ago", w.all { it.timeMs >= now - h })
    }

    @Test fun `the hour in progress is kept, not skipped`() {
        val now = 1_000_000_000_000L
        // The provider stamps the top of the hour; "now" is twenty past.
        val w = WeatherScoring.window(hours(now - 20 * 60_000L, 6), windowHours = 4, nowMs = now)
        assertEquals(now - 20 * 60_000L, w.first().timeMs)
    }

    @Test fun `an empty forecast gives an empty window`() {
        assertTrue(WeatherScoring.window(emptyList(), 8, 1L).isEmpty())
        assertNull(WeatherScoring.currentOf(emptyList(), 1L))
    }

    @Test fun `current is the nearest entry either side of now`() {
        val now = 1_000_000_000_000L
        val list = hours(now - h, 4)
        // now sits between the second and third; the second is 0 away.
        assertEquals(now, WeatherScoring.currentOf(list, now)?.timeMs)
        // Ten minutes before the top of an hour still reads that hour.
        assertEquals(now, WeatherScoring.currentOf(list, now - 10 * 60_000L)?.timeMs)
    }

    @Test fun `a fine grained forecast keeps its finer steps`() {
        val now = 1_000_000_000_000L
        val quarter = h / 4
        val w = WeatherScoring.window(hours(now, 96, quarter), windowHours = 8, nowMs = now)
        assertEquals(33, w.size)                       // 8 h at 15 min, plus now
        assertEquals(quarter, w[1].timeMs - w[0].timeMs)
    }
}
