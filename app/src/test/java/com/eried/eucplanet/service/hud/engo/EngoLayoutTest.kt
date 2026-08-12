package com.eried.eucplanet.service.hud.engo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structure tests for [EngoLayout]: page selection, batching, widget presence,
 * and the colour (ENGO 3) vs grey (ENGO 2) path. Pixel constants are tuned on a
 * real unit, so we assert opcodes + content, not exact positions.
 */
class EngoLayoutTest {

    private val engo2 = EngoCaps(colorRYG = false)
    private val engo3 = EngoCaps(colorRYG = true)

    private fun snap(
        connected: Boolean = true,
        speed: Int = 42,
        pwm: Int = 61,
        navActive: Boolean = false,
        distText: String = "",
        man: EngoManeuver = EngoManeuver.STRAIGHT,
        street: String = "",
    ) = EngoSnapshot(
        connected = connected, speed = speed, speedUnit = "km/h",
        batteryPct = 78, pwmPct = pwm, temp = 38, tempUnit = "C",
        navActive = navActive, navDistanceText = distText, navManeuver = man, navStreet = street,
    )

    private fun ops(cmds: List<ByteArray>) = cmds.map { it[1].toInt() and 0xFF }

    /** Text opcodes (grey 0x37 or colour 0x3E) whose ASCII payload contains [s]. */
    private fun containsText(cmds: List<ByteArray>, s: String) = cmds.any { c ->
        val op = c[1].toInt() and 0xFF
        (op == 0x37 || op == 0x3E) && String(c, Charsets.ISO_8859_1).contains(s)
    }

    @Test
    fun frameIsBatchedHoldClearFlush() {
        val cmds = EngoLayout.render(snap(), engo2)
        assertArrayEquals(ActiveLookProtocol.holdFlush(ActiveLookProtocol.HOLD), cmds.first())
        assertArrayEquals(ActiveLookProtocol.clear(), cmds[1])
        assertArrayEquals(ActiveLookProtocol.holdFlush(ActiveLookProtocol.FLUSH), cmds.last())
    }

    @Test
    fun telemetryPage_hasSpeedAndPwmBar() {
        val cmds = EngoLayout.render(snap(speed = 42, pwm = 61), engo2)
        assertTrue("speed text", containsText(cmds, "42"))
        assertTrue("bar outline (rect)", 0x33 in ops(cmds))
        assertTrue("bar fill (rectf) when pwm>0", 0x34 in ops(cmds))
        assertTrue("battery text", containsText(cmds, "BATT 78%"))
        assertTrue("temp text", containsText(cmds, "TEMP 38C"))
    }

    @Test
    fun pwmZero_drawsOutlineButNoFill() {
        val cmds = EngoLayout.render(snap(pwm = 0), engo2)
        assertTrue("bar outline", 0x33 in ops(cmds))
        assertFalse("no fill at 0%", 0x34 in ops(cmds))
    }

    @Test
    fun engo2_greyText_engo3_colorText() {
        val c2 = EngoLayout.render(snap(), engo2)
        assertTrue("mono uses txt", 0x37 in ops(c2))
        assertFalse("mono never txtColor", 0x3E in ops(c2))

        val c3 = EngoLayout.render(snap(), engo3)
        assertTrue("colour uses txtColor", 0x3E in ops(c3))
        assertFalse("colour never txt", 0x37 in ops(c3))
    }

    @Test
    fun navTakeover_hasArrowDistanceStreet() {
        val cmds = EngoLayout.render(
            snap(navActive = true, distText = "120 m", man = EngoManeuver.LEFT, street = "Main Street"),
            engo2,
        )
        assertTrue("arrow lines", 0x32 in ops(cmds))
        assertTrue("distance", containsText(cmds, "120 m"))
        assertTrue("street", containsText(cmds, "Main Street"))
        // Nav page has no PWM bar.
        assertFalse("no PWM text on nav", containsText(cmds, "PWM"))
    }

    @Test
    fun disconnected_showsDashes() {
        val cmds = EngoLayout.render(snap(connected = false), engo2)
        assertTrue(containsText(cmds, "--"))
    }
}
