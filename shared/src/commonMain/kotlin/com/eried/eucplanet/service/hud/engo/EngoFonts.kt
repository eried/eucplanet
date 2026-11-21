package com.eried.eucplanet.service.hud.engo

import kotlin.math.roundToInt

/** The fonts the rider can pick for the page's numbers. Titles always use [EngoPixelFont]. */
enum class EngoFontStyle { PIXEL, DIGITAL, BOLD }

/**
 * A font for the page's numbers, drawn as filled rectangles like
 * [EngoPixelFont], so every size is exact and a number centres to the pixel.
 * Sized by its height in pixels: [heights] lists the ones it can draw at.
 */
interface EngoNumberFont {
    fun supports(c: Char): Boolean

    /** The heights this font draws at, at most [max] (or its smallest), tallest first. */
    fun heights(max: Int): List<Int>

    fun width(s: String, h: Int): Int

    /** The filled rectangles of [s] with its top-left at (x, y): each (x0, y0, x1, y1), inclusive. */
    fun rects(s: String, x: Int, y: Int, h: Int): List<IntArray>

    /** What the font draws faintly behind [s] (a segment display's dark segments); most draw nothing. */
    fun ghost(s: String, x: Int, y: Int, h: Int): List<IntArray> = emptyList()
}

object EngoFonts {

    fun of(style: EngoFontStyle): EngoNumberFont = when (style) {
        EngoFontStyle.PIXEL -> Pixel
        EngoFontStyle.DIGITAL -> Digital
        EngoFontStyle.BOLD -> Bold
    }

    /** The rider's font when it has every character of [s], else the pixel font, which has them all. */
    fun forText(style: EngoFontStyle, s: String): EngoNumberFont {
        val f = of(style)
        return if (s.all { f.supports(it) }) f else Pixel
    }

    /** The tallest height up to [maxH] at which [s] fits [maxW]; the font's smallest when none does. */
    fun fit(font: EngoNumberFont, s: String, maxW: Int, maxH: Int): Int {
        val hs = font.heights(maxH)
        return hs.firstOrNull { font.width(s, it) <= maxW } ?: hs.last()
    }

    /** Today's 5x7 font, scaled by whole cells. */
    object Pixel : EngoNumberFont {
        override fun supports(c: Char) = EngoPixelFont.supports(c)
        override fun heights(max: Int) = (maxOf(1, max / EngoPixelFont.ROWS) downTo 1).map { it * EngoPixelFont.ROWS }
        override fun width(s: String, h: Int) = EngoPixelFont.width(s, maxOf(1, h / EngoPixelFont.ROWS))
        override fun rects(s: String, x: Int, y: Int, h: Int) =
            EngoPixelFont.rects(s, x, y, maxOf(1, h / EngoPixelFont.ROWS))
    }

    /**
     * Thicker digits on a 7x11 grid, two cells per stroke with the corners
     * cut, so a big number reads rounder than the pixel font at the same size.
     */
    object Bold : EngoNumberFont {
        private const val ROWS = 11

        private val SRC: Map<Char, List<String>> = mapOf(
            '0' to listOf(".#####.", "#######", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", "#######", ".#####."),
            '1' to listOf("...##..", "..###..", ".####..", "...##..", "...##..", "...##..", "...##..", "...##..", "...##..", ".######", ".######"),
            '2' to listOf(".#####.", "#######", "##...##", ".....##", "....###", "..####.", ".###...", "###....", "##.....", "#######", "#######"),
            '3' to listOf(".#####.", "#######", "##...##", ".....##", "..####.", "..####.", ".....##", ".....##", "##...##", "#######", ".#####."),
            '4' to listOf("....##.", "...###.", "..####.", ".##.##.", "##..##.", "#######", "#######", "....##.", "....##.", "....##.", "....##."),
            '5' to listOf("#######", "#######", "##.....", "##.....", "######.", "#######", ".....##", ".....##", "##...##", "#######", ".#####."),
            '6' to listOf(".#####.", "#######", "##...##", "##.....", "######.", "#######", "##...##", "##...##", "##...##", "#######", ".#####."),
            '7' to listOf("#######", "#######", ".....##", "....##.", "...##..", "..##...", "..##...", "..##...", "..##...", "..##...", "..##..."),
            '8' to listOf(".#####.", "#######", "##...##", "##...##", ".#####.", "#######", "##...##", "##...##", "##...##", "#######", ".#####."),
            '9' to listOf(".#####.", "#######", "##...##", "##...##", "##...##", "#######", ".######", ".....##", "##...##", "#######", ".#####."),
            '-' to listOf(".....", ".....", ".....", ".....", "#####", "#####", ".....", ".....", ".....", ".....", "....."),
            '.' to listOf("..", "..", "..", "..", "..", "..", "..", "..", "..", "##", "##"),
            ':' to listOf("..", "..", "##", "##", "..", "..", "..", "##", "##", "..", ".."),
            ' ' to listOf("...", "...", "...", "...", "...", "...", "...", "...", "...", "...", "..."),
        )

        private class Glyph(val width: Int, val rects: List<IntArray>)

        private val GLYPHS: Map<Char, Glyph> = SRC.mapValues { (c, rows) ->
            require(rows.size == ROWS) { "bold glyph '$c' needs $ROWS rows" }
            require(rows.all { it.length == rows[0].length }) { "bold glyph '$c' rows differ in width" }
            Glyph(rows[0].length, EngoPixelFont.merge(rows))
        }

        override fun supports(c: Char) = GLYPHS.containsKey(c)
        override fun heights(max: Int) = (maxOf(1, max / ROWS) downTo 1).map { it * ROWS }

        override fun width(s: String, h: Int): Int {
            if (s.isEmpty()) return 0
            val u = maxOf(1, h / ROWS)
            return (s.sumOf { GLYPHS[it]?.width ?: 0 } + (s.length - 1)) * u
        }

        override fun rects(s: String, x: Int, y: Int, h: Int): List<IntArray> {
            val u = maxOf(1, h / ROWS)
            val out = ArrayList<IntArray>()
            var cx = x
            for (c in s) {
                val g = GLYPHS[c] ?: continue
                for (r in g.rects) {
                    out += intArrayOf(cx + r[0] * u, y + r[1] * u, cx + (r[0] + r[2]) * u - 1, y + (r[1] + r[3]) * u - 1)
                }
                cx += (g.width + 1) * u
            }
            return out
        }
    }

    /**
     * Digits built from the seven segments of a wheel's own display, but
     * joined: the bars and uprights meet with no gaps, so a digit reads as
     * one solid shape at any size. Drawn into a mask and merged into as few
     * rectangles as it takes (a "1" is one), the least Bluetooth of the three.
     */
    object Digital : EngoNumberFont {
        private const val MIN_H = 14

        private val SEGMENTS = mapOf(
            '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
            '5' to "afgcd", '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg", '-' to "g",
        )

        private fun digitW(h: Int) = maxOf(6, (h * 0.56f).roundToInt())
        private fun stroke(h: Int) = maxOf(2, (h * 0.12f).roundToInt())
        private fun spacing(h: Int) = maxOf(2, (h * 0.14f).roundToInt())

        /** One character's merged rectangles at height [h], (x, y, w, h) from its top-left. */
        private val cache = HashMap<Triple<Char, Int, Boolean>, List<IntArray>>()

        /** Which pixels of [c]'s cell at height [h] its segments light. */
        private fun mask(c: Char, h: Int): Array<BooleanArray> {
            val t = stroke(h)
            val w = charW(c, h)
            val mid = (h - t) / 2
            val mask = Array(h) { BooleanArray(w) }
            fun fill(x0: Int, y0: Int, x1: Int, y1: Int) {
                for (yy in y0..y1) for (xx in x0..x1) if (yy in 0 until h && xx in 0 until w) mask[yy][xx] = true
            }
            when (c) {
                '.' -> fill(0, h - t, t - 1, h - 1)
                ':' -> for (cy in listOf(h / 3 - t / 2, 2 * h / 3 - t / 2)) fill(0, cy, t - 1, cy + t - 1)
                else -> SEGMENTS[c]?.forEach { seg ->
                    when (seg) {
                        'a' -> fill(0, 0, w - 1, t - 1)
                        'g' -> fill(0, mid, w - 1, mid + t - 1)
                        'd' -> fill(0, h - t, w - 1, h - 1)
                        'f' -> fill(0, 0, t - 1, mid + t - 1)
                        'b' -> fill(w - t, 0, w - 1, mid + t - 1)
                        'e' -> fill(0, mid, t - 1, h - 1)
                        else -> fill(w - t, mid, w - 1, h - 1) // c
                    }
                }
            }
            return mask
        }

        /** [c]'s lit segments, or with [unlit] the segments of an "8" it leaves dark. */
        private val cacheLock = kotlinx.atomicfu.locks.SynchronizedObject()

        private fun glyph(c: Char, h: Int, unlit: Boolean = false): List<IntArray> =
            kotlinx.atomicfu.locks.synchronized(cacheLock) { glyphLocked(c, h, unlit) }

        private fun glyphLocked(c: Char, h: Int, unlit: Boolean): List<IntArray> = cache.getOrPut(Triple(c, h, unlit)) {
            val m = mask(c, h)
            val shown = if (!unlit) m else {
                if (c !in SEGMENTS) return@getOrPut emptyList()
                val eight = mask('8', h)
                Array(h) { y -> BooleanArray(m[y].size) { x -> eight[y][x] && !m[y][x] } }
            }
            EngoPixelFont.merge(shown.map { row -> CharArray(row.size) { if (row[it]) '#' else '.' }.concatToString() })
        }

        private fun charW(c: Char, h: Int) = when (c) {
            '.', ':' -> stroke(h)
            ' ' -> digitW(h) / 2
            else -> digitW(h)
        }

        override fun supports(c: Char) = c in SEGMENTS || c == '.' || c == ':' || c == ' '
        override fun heights(max: Int) = (maxOf(MIN_H, max) downTo MIN_H).toList()

        override fun width(s: String, h: Int): Int =
            if (s.isEmpty()) 0 else s.sumOf { charW(it, h) } + spacing(h) * (s.length - 1)

        override fun rects(s: String, x: Int, y: Int, h: Int): List<IntArray> = place(s, x, y, h, unlit = false)

        /** The dark segments, drawn faintly so each digit sits in its "8" like on a wheel's display. */
        override fun ghost(s: String, x: Int, y: Int, h: Int): List<IntArray> = place(s, x, y, h, unlit = true)

        private fun place(s: String, x: Int, y: Int, h: Int, unlit: Boolean): List<IntArray> {
            val out = ArrayList<IntArray>()
            var cx = x
            for (c in s) {
                for (r in glyph(c, h, unlit)) out += intArrayOf(cx + r[0], y + r[1], cx + r[0] + r[2] - 1, y + r[1] + r[3] - 1)
                cx += charW(c, h) + spacing(h)
            }
            return out
        }
    }
}
