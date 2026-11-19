package com.eried.eucplanet.service.hud.engo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        left: EngoCell = EngoCell("BATT", "78%"),
        right: EngoCell = EngoCell("TEMP", "38C"),
    ) = EngoSnapshot(
        connected = connected, speed = speed, speedUnit = "km/h",
        pwmPct = pwm, left = left, right = right,
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
        assertTrue("battery tag", containsText(cmds, "BATT"))
        assertTrue("battery value", containsText(cmds, "78%"))
        assertTrue("temp tag", containsText(cmds, "TEMP"))
        assertTrue("temp value", containsText(cmds, "38C"))
    }

    @Test
    fun telemetryPage_showsTheCornersTheRiderPicked() {
        val cmds = EngoLayout.render(
            snap(left = EngoCell("VOLT", "83.5V"), right = EngoCell.EMPTY), engo2,
        )
        assertTrue("picked corner", containsText(cmds, "VOLT") && containsText(cmds, "83.5V"))
        assertFalse("an empty corner draws nothing", containsText(cmds, "TEMP"))
        assertFalse(containsText(cmds, "BATT"))
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

    /** A decoded text frame, back in rider view (top-left origin). */
    private data class Txt(val x: Int, val y: Int, val rot: Int, val font: Int, val color: Int, val s: String) {
        val w get() = EngoLayout.textWidth(font, s)
        val h get() = EngoLayout.fontHeight(font)
    }

    private fun s16(c: ByteArray, i: Int) = ((c[i].toInt() shl 8) or (c[i + 1].toInt() and 0xFF)).toShort().toInt()

    private fun texts(cmds: List<ByteArray>): List<Txt> = cmds.filter {
        val op = it[1].toInt() and 0xFF
        op == 0x37 || op == 0x3E
    }.map { c ->
        val end = c.indexOfLast { it == 0.toByte() }
        Txt(
            x = EngoLayout.W - 1 - s16(c, 4), y = EngoLayout.H - 1 - s16(c, 6),
            rot = c[8].toInt(), font = c[9].toInt() and 0xFF, color = c[10].toInt() and 0xFF,
            s = String(c, 11, end - 11, Charsets.ISO_8859_1),
        )
    }

    private val worst = listOf(
        snap(speed = 188, pwm = 100, left = EngoCell("VOLT", "134.4V"), right = EngoCell("TRIP", "123.4km")),
        snap(speed = 8, pwm = 5, left = EngoCell("AMPS", "-45A"), right = EngoCell("TEMP", "38C")),
        snap(connected = false),
        snap(navActive = true, distText = "12.3 km", man = EngoManeuver.RIGHT,
            street = "Avenida Libertador Bernardo O'Higgins"),
    )

    @Test
    fun everyTextReadsUprightForTheRider() {
        // Rotation 0 showed upside down on a rider's ENGO 3: the optics mirror the panel.
        for (s in worst) for (caps in listOf(engo2, engo3)) {
            texts(EngoLayout.render(s, caps)).forEach {
                assertEquals("'${it.s}' rotation", EngoLayout.ROT_READ, it.rot)
            }
        }
    }

    @Test
    fun speedSitsTopLeftForTheRider() {
        val t = texts(EngoLayout.render(snap(speed = 42), engo3)).first { it.s == "42" }
        assertTrue("left half", t.x < EngoLayout.W / 2)
        assertTrue("top half", t.y < EngoLayout.H / 2)
    }

    @Test
    fun noTextRunsOffThePanelOrOverlaps() {
        for (s in worst) {
            val ts = texts(EngoLayout.render(s, engo3))
            for (t in ts) {
                assertTrue("'${t.s}' inside", t.x >= 0 && t.y >= 0 &&
                    t.x + t.w <= EngoLayout.W && t.y + t.h <= EngoLayout.H)
            }
            for (i in ts.indices) for (j in i + 1 until ts.size) {
                val a = ts[i]; val b = ts[j]
                val apart = a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y
                assertTrue("'${a.s}' and '${b.s}' overlap", apart)
            }
        }
    }

    @Test
    fun engo3_pwmColourFollowsTheBand() {
        fun fill(pwm: Int): Int {
            val cmds = EngoLayout.render(snap(pwm = pwm), engo3)
            val k = cmds.indexOfFirst { (it[1].toInt() and 0xFF) == 0x34 }
            return cmds[k - 1][4].toInt() and 0xFF // the colour set just before the fill
        }
        assertEquals(EngoLayout.RG_OK, fill(30))
        assertEquals(EngoLayout.RG_WARN, fill(75))
        assertEquals(EngoLayout.RG_DANGER, fill(95))
        val pwmText = texts(EngoLayout.render(snap(pwm = 95), engo3)).first { it.s.startsWith("PWM") }
        assertEquals(EngoLayout.RG_DANGER, pwmText.color)
    }

    @Test
    fun engo3_usesOnlyRealPaletteColours() {
        // The ActiveLook 81-colour palette: red code | green code.
        val reds = listOf(0x00, 0x04, 0x20, 0x14, 0x18, 0x28, 0x1C, 0x38, 0x3C)
        val greens = listOf(0x00, 0x01, 0x02, 0x41, 0x81, 0x82, 0x43, 0x83, 0xC3)
        val palette = reds.flatMap { r -> greens.map { g -> r or g } }.toSet()
        for (s in worst) {
            val cmds = EngoLayout.render(s, engo3)
            texts(cmds).forEach { assertTrue("text colour ${it.color}", it.color in palette) }
            cmds.filter { (it[1].toInt() and 0xFF) == 0x3D }
                .forEach { assertTrue("draw colour", (it[4].toInt() and 0xFF) in palette) }
        }
    }
}
