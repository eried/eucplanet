package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.data.model.EngoHudSettings
import com.eried.eucplanet.data.model.EngoSlot
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

    private fun build(
        engo: EngoHudSettings = EngoHudSettings(),
        units: EngoMetrics.Units = EngoMetrics.Units(),
        connected: Boolean = true,
        nav: EngoNav? = null,
    ) = EngoPageBuilder.build(engo, units, 65, 85, connected, { live[it] }, { histories[it].orEmpty() }, nav, now)

    @Test
    fun defaults_topSpeedTopRight_batteryPwmTempAlongTheBottom() {
        val p = build()
        assertEquals("41", p.speed)
        assertEquals("KM/H", p.speedUnit)
        assertEquals("TOP", p.top!!.title)
        assertEquals("52", p.top!!.value)
        assertEquals(listOf("BAT %", "PWM %", "TMP°C"), p.bottom.map { it?.title })
    }

    @Test
    fun statisticsComeFromTheHistory() {
        val p = build(EngoHudSettings(slots = listOf(
            EngoSlot("LOAD", "MAX"), EngoSlot("LOAD", "MIN"), EngoSlot("LOAD", "AVG"))))
        assertEquals(listOf("88", "30", "64"), p.bottom.map { it!!.value })
        assertEquals("MAX PWM", p.bottom[0]!!.title)
    }

    @Test
    fun theRidersUnits() {
        val p = build(units = EngoMetrics.Units(speed = "mph", temp = "F"),
            engo = EngoHudSettings(slots = listOf(EngoSlot("MOTOR_TEMP"), EngoSlot(""), EngoSlot(""))))
        assertEquals("25", p.speed) // 41 km/h
        assertEquals("MPH", p.speedUnit)
        assertEquals("MOT°F", p.bottom[0]!!.title)
        assertEquals("136", p.bottom[0]!!.value) // 58 °C
        assertNull("an empty slot draws nothing", p.bottom[1])
    }

    @Test
    fun pwmFollowsTheRidersGaugeBands() {
        assertEquals(EngoBand.OK, EngoPageBuilder.pwmBand(40f, 65, 85))
        assertEquals(EngoBand.WARN, EngoPageBuilder.pwmBand(79f, 65, 85))
        assertEquals(EngoBand.DANGER, EngoPageBuilder.pwmBand(85f, 65, 85))
        assertEquals(EngoBand.WARN, build().bottom[1]!!.band) // 72%
        assertEquals(EngoBand.NORMAL, build().bottom[0]!!.band)
    }

    @Test
    fun barsAndGraphs() {
        val p = build()
        assertEquals(0.72f, p.bottom[1]!!.level!!, 0.001f) // PWM out of 100
        assertEquals(0.78f, p.bottom[0]!!.level!!, 0.001f)
        val s = p.bottom[1]!!.series
        assertTrue(s.size >= 2)
        assertTrue(s.all { it in 0f..1f })
        assertEquals(0.72f, s.last(), 0.001f)
    }

    @Test
    fun disconnected_dashesEverywhere() {
        val p = build(connected = false)
        assertEquals("--", p.speed)
        assertEquals("--", p.top!!.value)
        assertTrue(p.bottom.all { it!!.value == "--" })
    }

    @Test
    fun turnsOnlyWhenTheRiderWantsThem() {
        val n = EngoNav(EngoManeuver.LEFT, "120 M")
        assertEquals(n, build(nav = n).nav)
        assertNull(build(engo = EngoHudSettings(navTakeover = false), nav = n).nav)
    }

    @Test
    fun aMetricWithoutStatisticsShowsItsValue() {
        val p = EngoPageBuilder.build(EngoHudSettings(top = EngoSlot("TRIP", "MAX")), EngoMetrics.Units(),
            65, 85, true, { if (it == "TRIP") 23.4f else null }, { emptyList() }, null, now)
        assertEquals("TRIP KM", p.top!!.title)
        assertEquals("23.4", p.top!!.value)
    }
}
