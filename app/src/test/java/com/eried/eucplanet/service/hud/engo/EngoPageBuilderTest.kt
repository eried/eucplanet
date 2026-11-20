package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.data.model.DEFAULT_ENGO_ELEMENTS
import com.eried.eucplanet.data.model.EngoElement
import com.eried.eucplanet.data.model.EngoHudSettings
import com.eried.eucplanet.data.repository.MetricSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngoPageBuilderTest {

    private val now = 10_000_000L

    private fun hist(vararg v: Float) = v.mapIndexed { i, x -> MetricSample(now - (v.size - 1 - i) * 1000L, x) }

    private val histories = mapOf(
        "SPEED" to hist(20f, 35f, 52f, 41f),
        "LOAD" to hist(30f, 66f, 88f, 72f),
        "BATTERY" to hist(80f, 79f, 78f),
        "MOTOR_TEMP" to hist(50f, 54f, 58f),
    )
    private val live = mapOf("SPEED" to 41f, "LOAD" to 72f, "BATTERY" to 78f, "MOTOR_TEMP" to 58f)

    private fun elements(vararg e: EngoElement) =
        DEFAULT_ENGO_ELEMENTS.indices.map { e.getOrNull(it) ?: EngoElement("") }

    private fun build(
        engo: EngoHudSettings = EngoHudSettings(),
        units: EngoMetrics.Units = EngoMetrics.Units(),
        connected: Boolean = true,
        nav: EngoNav? = null,
        recent: (String, Long) -> List<MetricSample> = { k, _ -> histories[k].orEmpty() },
    ) = EngoPageBuilder.build(engo, units, 65, 85, connected, { live[it] }, { histories[it].orEmpty() },
        recent, nav, now)

    @Test
    fun defaults_speedThenTopSpeed_thenBatteryPwmTemp() {
        val p = build()
        assertEquals(EngoLayoutKind.COCKPIT, p.layout)
        assertEquals(listOf("KM/H", "TOP", "BAT %", "PWM %", "TMP°C"), p.elements.map { it?.title })
        assertEquals("41", p.elements[0]!!.value)
        assertEquals("52", p.elements[1]!!.value)
        assertEquals(EngoStyle.NUMBER, p.elements[0]!!.style)
        assertEquals(EngoStyle.BLOCKS, p.elements[2]!!.style)
    }

    @Test
    fun statisticsComeFromTheHistory() {
        val p = build(EngoHudSettings(elements = elements(
            EngoElement("LOAD", "MAX"), EngoElement("LOAD", "MIN"), EngoElement("LOAD", "AVG"))))
        assertEquals(listOf("88", "30", "64"), p.elements.take(3).map { it!!.value })
        assertEquals("MAX PWM", p.elements[0]!!.title)
    }

    @Test
    fun theRidersUnits() {
        val p = build(units = EngoMetrics.Units(speed = "mph", temp = "F"),
            engo = EngoHudSettings(elements = elements(EngoElement("SPEED"), EngoElement("MOTOR_TEMP"))))
        assertEquals("25", p.elements[0]!!.value) // 41 km/h
        assertEquals("MPH", p.elements[0]!!.title)
        assertEquals("MOT°F", p.elements[1]!!.title)
        assertEquals("136", p.elements[1]!!.value) // 58 °C
        assertNull("an empty element draws nothing", p.elements[2])
    }

    @Test
    fun rollingBackReadsAsSpeed() {
        val p = EngoPageBuilder.build(EngoHudSettings(), EngoMetrics.Units(), 65, 85, true,
            { if (it == "SPEED") -12f else null }, { emptyList() }, { _, _ -> emptyList() }, null, now)
        assertEquals("12", p.elements[0]!!.value)
    }

    @Test
    fun pwmFollowsTheRidersGaugeBands() {
        assertEquals(EngoBand.OK, EngoPageBuilder.pwmBand(40f, 65, 85))
        assertEquals(EngoBand.WARN, EngoPageBuilder.pwmBand(79f, 65, 85))
        assertEquals(EngoBand.DANGER, EngoPageBuilder.pwmBand(85f, 65, 85))
        assertEquals(EngoBand.WARN, build().elements[3]!!.band) // 72%
        assertEquals(EngoBand.NORMAL, build().elements[2]!!.band)
    }

    @Test
    fun barsLevel_andOnlyAGraphGetsASeries() {
        val p = build()
        assertEquals(0.72f, p.elements[3]!!.level!!, 0.001f) // PWM out of 100
        assertEquals(0.78f, p.elements[2]!!.level!!, 0.001f)
        assertTrue("blocks need no series", p.elements[3]!!.series.isEmpty())

        val g = build(EngoHudSettings(elements = elements(EngoElement("LOAD", style = "GRAPH", windowSec = 5))))
        val s = g.elements[0]!!.series
        assertTrue(s.size >= 2)
        assertTrue(s.all { it in 0f..1f })
        assertEquals(0.72f, s.last(), 0.001f)
    }

    @Test
    fun theGraphAsksForItsOwnWindow_orTheDashboards() {
        val asked = mutableListOf<Long>()
        val rec: (String, Long) -> List<MetricSample> = { k, w -> asked += w; histories[k].orEmpty() }
        val engo = EngoHudSettings(elements = elements(
            EngoElement("LOAD", style = "GRAPH", windowSec = 5),
            EngoElement("LOAD", style = "GRAPH", windowSec = 0),
        ))
        EngoPageBuilder.build(engo, EngoMetrics.Units(), 65, 85, true, { live[it] }, { histories[it].orEmpty() },
            rec, null, now, dashboardWindowSec = 120)
        assertEquals(listOf(5_000L, 120_000L), asked)
    }

    @Test
    fun aShortGraphMovesInHalfSecondSteps() {
        // Inside one step the line is the same, so the glasses are sent nothing new.
        val samples = (0 until 100).map { MetricSample(now - 10_000 + it * 100L, (it % 7).toFloat()) }
        val a = EngoPageBuilder.series("LOAD", samples, now + 10, 5_000)
        val b = EngoPageBuilder.series("LOAD", samples, now + 400, 5_000)
        assertEquals(a, b)
    }

    @Test
    fun disconnected_dashesEverywhere() {
        val p = build(connected = false)
        assertTrue(p.elements.filterNotNull().all { it.value == "--" })
    }

    @Test
    fun theTurnAndTheSwitchReachTheLayout() {
        val n = EngoNav(EngoManeuver.LEFT, "120 M")
        assertEquals(n, build(nav = n).nav)
        assertEquals(false, build(engo = EngoHudSettings(navTakeover = false), nav = n).turnsOn)
    }

    @Test
    fun aMetricWithoutStatisticsShowsItsValue() {
        val p = EngoPageBuilder.build(EngoHudSettings(elements = elements(EngoElement("TRIP", "MAX"))),
            EngoMetrics.Units(), 65, 85, true, { if (it == "TRIP") 23.4f else null }, { emptyList() },
            { _, _ -> emptyList() }, null, now)
        assertEquals("TRIP KM", p.elements[0]!!.title)
        assertEquals("23.4", p.elements[0]!!.value)
    }

    @Test
    fun unknownNamesFallBack() {
        val p = build(EngoHudSettings(layout = "NOPE", font = "COMIC"))
        assertEquals(EngoLayoutKind.COCKPIT, p.layout)
        assertEquals(EngoFontStyle.PIXEL, p.font)
    }
}
