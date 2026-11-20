package com.eried.eucplanet.service.hud.engo

/**
 * A 7-row pixel font the ENGO page draws itself, as filled rectangles.
 *
 * The glasses' own fonts are proportional and their boxes are not what the
 * documentation says (a rider's ENGO 3 drew the 49 px font ~60 px tall), so a
 * string could not be centred or kept off its neighbour with any certainty.
 * Drawn from rectangles, every string has an exact width and height at any
 * scale, which is what lets the numbers sit centred under their titles.
 *
 * Each glyph is merged into as few rectangles as its shape allows (rows of
 * equal runs become one tall rectangle), so a digit costs 3 to 8 commands.
 */
object EngoPixelFont {
    const val ROWS = 7

    private val SRC: Map<Char, List<String>> = mapOf(
        '0' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf(".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to listOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to listOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to listOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
        'H' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to listOf("###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"),
        'J' to listOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to listOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to listOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to listOf("#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#"),
        'O' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to listOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to listOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to listOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to listOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
        'X' to listOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to listOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        ' ' to listOf("..", "..", "..", "..", "..", "..", ".."),
        '.' to listOf("..", "..", "..", "..", "..", "##", "##"),
        ':' to listOf("..", "##", "##", "..", "##", "##", ".."),
        '-' to listOf("....", "....", "....", "####", "....", "....", "...."),
        '+' to listOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
        '/' to listOf("....#", "....#", "...#.", "..#..", ".#...", "#....", "#...."),
        '%' to listOf("##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"),
        '°' to listOf(".##.", "#..#", "#..#", ".##.", "....", "....", "...."),
        '~' to listOf(".....", ".....", ".#...", "#.#.#", "...#.", ".....", "....."),
        '?' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."),
    )

    /** A glyph: its width in cells and its cells merged into (x, y, w, h) rectangles. */
    private class Glyph(val width: Int, val rects: List<IntArray>)

    private val GLYPHS: Map<Char, Glyph> = SRC.mapValues { (c, rows) ->
        require(rows.size == ROWS) { "glyph '$c' needs $ROWS rows" }
        require(rows.all { it.length == rows[0].length }) { "glyph '$c' rows differ in width" }
        Glyph(rows[0].length, merge(rows))
    }

    /** Whether [c] has a glyph of its own (anything else draws as '?'). */
    fun supports(c: Char): Boolean = GLYPHS.containsKey(c.uppercaseChar())

    private fun glyph(c: Char): Glyph = GLYPHS[c.uppercaseChar()] ?: GLYPHS.getValue('?')

    /** Width of [s] in pixels at [scale]: one empty cell between characters. */
    fun width(s: String, scale: Int): Int {
        if (s.isEmpty()) return 0
        val cells = s.sumOf { glyph(it).width } + (s.length - 1)
        return cells * scale
    }

    fun height(scale: Int): Int = ROWS * scale

    /** The filled rectangles of [s] with its top-left at (x, y): each (x0, y0, x1, y1), inclusive. */
    fun rects(s: String, x: Int, y: Int, scale: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        var cx = x
        for (c in s) {
            val g = glyph(c)
            for (r in g.rects) {
                out += intArrayOf(
                    cx + r[0] * scale, y + r[1] * scale,
                    cx + (r[0] + r[2]) * scale - 1, y + (r[1] + r[3]) * scale - 1,
                )
            }
            cx += (g.width + 1) * scale
        }
        return out
    }

    /** The largest scale from [max] down to 1 at which [s] fits [room] pixels. */
    fun fitScale(s: String, room: Int, max: Int): Int {
        var k = max
        while (k > 1 && width(s, k) > room) k--
        return k
    }

    /** [s] cut from the end until it fits [room] pixels at [scale]. */
    fun clip(s: String, room: Int, scale: Int): String {
        var t = s
        while (t.isNotEmpty() && width(t, scale) > room) t = t.dropLast(1)
        return t.trimEnd()
    }

    /**
     * Horizontal runs per row, then runs repeated on the rows below folded
     * into one taller rectangle: each (x, y, w, h) in cells. Shared with the
     * bold number font, drawn the same way.
     */
    internal fun merge(rows: List<String>): List<IntArray> {
        val out = ArrayList<IntArray>()
        // Open rectangles keyed by their run (start, end): y0 and height so far.
        var open = HashMap<Pair<Int, Int>, IntArray>()
        for ((y, row) in rows.withIndex()) {
            val runs = ArrayList<Pair<Int, Int>>()
            var x = 0
            while (x < row.length) {
                if (row[x] == '#') {
                    val s = x
                    while (x < row.length && row[x] == '#') x++
                    runs += s to x
                } else x++
            }
            val next = HashMap<Pair<Int, Int>, IntArray>()
            for (run in runs) {
                val o = open.remove(run)
                next[run] = if (o != null) intArrayOf(o[0], o[1] + 1) else intArrayOf(y, 1)
            }
            for ((run, o) in open) out += intArrayOf(run.first, o[0], run.second - run.first, o[1])
            open = next
        }
        for ((run, o) in open) out += intArrayOf(run.first, o[0], run.second - run.first, o[1])
        return out
    }
}
