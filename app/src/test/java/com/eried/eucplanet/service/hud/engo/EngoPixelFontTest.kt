package com.eried.eucplanet.service.hud.engo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngoPixelFontTest {

    private val all = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ .:-+/%°~?"

    /** The cells a string's rectangles fill, at scale 1. */
    private fun filled(s: String): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        for (r in EngoPixelFont.rects(s, 0, 0, 1)) for (y in r[1]..r[3]) for (x in r[0]..r[2]) {
            assertTrue("rectangles of '$s' overlap at ($x,$y)", out.add(x to y))
        }
        return out
    }

    @Test
    fun everyCharacterHasItsOwnGlyph() {
        for (c in all) assertTrue("'$c'", EngoPixelFont.supports(c))
        assertTrue("lower case draws as upper", EngoPixelFont.supports('k'))
        assertFalse(EngoPixelFont.supports('€'))
    }

    @Test
    fun rectanglesStayInsideTheTextBox() {
        for (c in all) {
            val w = EngoPixelFont.width(c.toString(), 1)
            for ((x, y) in filled(c.toString())) {
                assertTrue("'$c' at ($x,$y)", x in 0 until w && y in 0 until EngoPixelFont.ROWS)
            }
        }
    }

    @Test
    fun scaleMultipliesEverything() {
        val s = "PWM 58%"
        assertEquals(EngoPixelFont.width(s, 1) * 4, EngoPixelFont.width(s, 4))
        val px1 = filled(s).size
        val px4 = EngoPixelFont.rects(s, 0, 0, 4).sumOf { (it[2] - it[0] + 1) * (it[3] - it[1] + 1) }
        assertEquals(px1 * 16, px4)
    }

    @Test
    fun digitsAreAllTheSameWidth_soNumbersDoNotJitter() {
        val w = EngoPixelFont.width("0", 1)
        for (d in '0'..'9') assertEquals(w, EngoPixelFont.width(d.toString(), 1))
    }

    @Test
    fun fitAndClip() {
        assertEquals(10, EngoPixelFont.fitScale("42", 116, 10))
        assertTrue(EngoPixelFont.width("188", EngoPixelFont.fitScale("188", 116, 10)) <= 116)
        assertEquals("PWM", EngoPixelFont.clip("PWM %", EngoPixelFont.width("PWM %", 2) - 1, 2))
    }

    @Test
    fun digitsCostFewRectangles() {
        for (d in '0'..'9') assertTrue("'$d'", EngoPixelFont.rects(d.toString(), 0, 0, 1).size <= 9)
    }
}
