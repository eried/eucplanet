package com.eried.eucplanet.service.hud.engo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EngoFontsTest {

    private val numbers = listOf("0123456789", "-12.5", "92:40", "--", "188", "8")

    @Test
    fun everyFontDrawsWhatTheNumbersUse() {
        for (style in EngoFontStyle.entries) {
            val f = EngoFonts.of(style)
            for (s in numbers) assertTrue("$style '$s'", s.all { f.supports(it) })
        }
    }

    @Test
    fun widthMatchesWhatIsDrawn_atEverySize() {
        for (style in EngoFontStyle.entries) {
            val f = EngoFonts.of(style)
            for (h in f.heights(120)) for (s in numbers) {
                val r = f.rects(s, 10, 20, h)
                val x0 = r.minOf { it[0] }; val x1 = r.maxOf { it[2] }
                val y0 = r.minOf { it[1] }; val y1 = r.maxOf { it[3] }
                assertTrue("$style h$h '$s' starts at x", x0 >= 10)
                assertTrue("$style h$h '$s' inside its width", x1 - 10 + 1 <= f.width(s, h))
                assertTrue("$style h$h '$s' inside its height", y0 >= 20 && y1 - 20 + 1 <= h)
                for (it in r) assertTrue("$style h$h '$s' rect inverted", it[2] >= it[0] && it[3] >= it[1])
            }
        }
    }

    @Test
    fun fitPicksTheTallestThatFits() {
        for (style in EngoFontStyle.entries) {
            val f = EngoFonts.of(style)
            val h = EngoFonts.fit(f, "188", 136, 70)
            assertTrue("$style ${f.width("188", h)} wide", f.width("188", h) <= 136)
            assertTrue(h <= 70)
            val taller = f.heights(70).firstOrNull { it > h }
            if (taller != null) assertTrue("$style could be taller", f.width("188", taller) > 136)
        }
    }

    @Test
    fun digitalDrawsTheDarkSegmentsOfAnEight_andOnlyThose() {
        val h = 40
        fun px(rs: List<IntArray>) = rs.flatMap { r -> (r[1]..r[3]).flatMap { y -> (r[0]..r[2]).map { x -> x to y } } }.toSet()
        val eight = px(EngoFonts.Digital.rects("8", 0, 0, h))
        assertTrue(EngoFonts.Digital.ghost("8", 0, 0, h).isEmpty())
        for (c in "0123456789-") {
            val lit = px(EngoFonts.Digital.rects("$c", 0, 0, h))
            val dark = px(EngoFonts.Digital.ghost("$c", 0, 0, h))
            assertTrue("'$c' lit and dark never overlap", lit.intersect(dark).isEmpty())
            assertEquals("'$c' lit and dark make an 8", eight, lit + dark)
        }
        assertTrue(EngoFonts.Pixel.ghost("42", 0, 0, 70).isEmpty())
        assertTrue(EngoFonts.Bold.ghost("42", 0, 0, 66).isEmpty())
    }

    @Test
    fun aLetterFallsBackToThePixelFont() {
        assertSame(EngoFonts.Pixel, EngoFonts.forText(EngoFontStyle.DIGITAL, "TOP"))
        assertSame(EngoFonts.Digital, EngoFonts.forText(EngoFontStyle.DIGITAL, "42"))
    }

    @Test
    fun digitalIsTheCheapest() {
        val digital = EngoFonts.Digital.rects("188", 0, 0, 70).size
        val pixel = EngoFonts.Pixel.rects("188", 0, 0, 70).size
        assertTrue("digital $digital vs pixel $pixel", digital <= pixel)
        // Joined segments merge: a "1" is one rectangle, an "8" seven.
        assertEquals(1 + 7, EngoFonts.Digital.rects("18", 0, 0, 40).size)
    }
}
