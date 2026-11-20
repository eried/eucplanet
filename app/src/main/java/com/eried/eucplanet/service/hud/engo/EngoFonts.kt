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
     * Seven segments, like a wheel's own display: the uprights run the full
     * height of each half, the bars sit between them with a small gap, so a
     * "1" stays one tall stroke. At most seven rectangles a digit, the least
     * Bluetooth of the three.
     */
    object Digital : EngoNumberFont {
        private const val MIN_H = 14

        private val SEGMENTS = mapOf(
            '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
            '5' to "afgcd", '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg", '-' to "g",
        )

        private fun digitW(h: Int) = maxOf(6, (h * 0.56f).roundToInt())
        private fun stroke(h: Int) = maxOf(2, (h * 0.12f).roundToInt())
        private fun gap(h: Int) = maxOf(1, (stroke(h) + 2) / 4)
        private fun spacing(h: Int) = maxOf(2, (h * 0.14f).roundToInt())

        private fun charW(c: Char, h: Int) = when (c) {
            '.', ':' -> stroke(h)
            ' ' -> digitW(h) / 2
            else -> digitW(h)
        }

        override fun supports(c: Char) = c in SEGMENTS || c == '.' || c == ':' || c == ' '
        override fun heights(max: Int) = (maxOf(MIN_H, max) downTo MIN_H).toList()

        override fun width(s: String, h: Int): Int =
            if (s.isEmpty()) 0 else s.sumOf { charW(it, h) } + spacing(h) * (s.length - 1)

        override fun rects(s: String, x: Int, y: Int, h: Int): List<IntArray> {
            val out = ArrayList<IntArray>()
            val t = stroke(h); val g = gap(h); val w = digitW(h)
            val mid = y + (h - t) / 2
            // Where the uprights split when there is no middle bar, [g] apart.
            val split = mid + t / 2
            var cx = x
            for (c in s) {
                val segs = SEGMENTS[c].orEmpty()
                // An upright stops [g] short of a bar beside it, and runs to
                // the edge where there is none, so a "1" is one tall stroke.
                val upperTop = if ('a' in segs) y + t + g else y
                val upperEnd = if ('g' in segs) mid - g - 1 else split - 1 - g / 2
                val lowerTop = if ('g' in segs) mid + t + g else split + (g - g / 2)
                val lowerEnd = if ('d' in segs) y + h - t - g - 1 else y + h - 1
                when (c) {
                    '.' -> out += intArrayOf(cx, y + h - t, cx + t - 1, y + h - 1)
                    ':' -> for (cy in listOf(y + h / 3 - t / 2, y + 2 * h / 3 - t / 2)) out += intArrayOf(cx, cy, cx + t - 1, cy + t - 1)
                    else -> segs.forEach { seg ->
                        out += when (seg) {
                            'a' -> intArrayOf(cx + g, y, cx + w - 1 - g, y + t - 1)
                            'g' -> intArrayOf(cx + g, mid, cx + w - 1 - g, mid + t - 1)
                            'd' -> intArrayOf(cx + g, y + h - t, cx + w - 1 - g, y + h - 1)
                            'f' -> intArrayOf(cx, upperTop, cx + t - 1, upperEnd)
                            'b' -> intArrayOf(cx + w - t, upperTop, cx + w - 1, upperEnd)
                            'e' -> intArrayOf(cx, lowerTop, cx + t - 1, lowerEnd)
                            else -> intArrayOf(cx + w - t, lowerTop, cx + w - 1, lowerEnd) // c
                        }
                    }
                }
                cx += charW(c, h) + spacing(h)
            }
            return out
        }
    }
}
