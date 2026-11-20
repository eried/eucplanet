package com.eried.eucplanet.service.hud.engo

/**
 * Draws ActiveLook command frames into pixels, as the rider would see them.
 *
 * It reads the very bytes the glasses are sent (clear, colour, grey, line,
 * rect, filled rect, polyline, circles) and undoes the optical mirroring, so
 * the settings preview and the test renders show exactly what a page's
 * commands draw, not a second idea of the layout.
 *
 * Colours are an approximation of the panel: the red and green levels of the
 * 81-colour palette mixed as RGB, grey levels as grey. Text commands (the
 * glasses' own fonts) are not drawn; the ride page does not use them.
 */
object EngoRaster {
    const val W = EngoLayout.W
    const val H = EngoLayout.H

    private val RED_CODES = intArrayOf(0x00, 0x04, 0x20, 0x14, 0x18, 0x28, 0x1C, 0x38, 0x3C)
    private val GREEN_CODES = intArrayOf(0x00, 0x01, 0x02, 0x41, 0x81, 0x82, 0x43, 0x83, 0xC3)
    private const val RED_MASK = 0x3C
    private const val GREEN_MASK = 0xC3

    /** ARGB for a palette code; codes off the palette are magenta, so a test sees them. */
    fun argbOfRg(code: Int): Int {
        val r = RED_CODES.indexOf(code and RED_MASK)
        val g = GREEN_CODES.indexOf(code and GREEN_MASK)
        if (r < 0 || g < 0) return 0xFFFF00FF.toInt()
        val rr = r * 255 / 8
        val gg = g * 230 / 8
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8)
    }

    fun argbOfGrey(level: Int): Int {
        val v = level.coerceIn(0, 15) * 17
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** True when [code] is one of the 81 palette colours. */
    fun onPalette(code: Int): Boolean =
        RED_CODES.contains(code and RED_MASK) && GREEN_CODES.contains(code and GREEN_MASK) &&
            (code and (RED_MASK or GREEN_MASK)) == code

    /**
     * Run [cmds] over [into] (rider view, row-major, ARGB). Returns [into].
     * [color] says whether the panel is colour (palette codes) or grey.
     */
    fun render(cmds: List<ByteArray>, color: Boolean, into: IntArray = blank()): IntArray {
        var pen = if (color) argbOfRg(0xFF) else argbOfGrey(15)
        for (f in cmds) {
            val op = f[1].toInt() and 0xFF
            val long = (f[2].toInt() and 0x20) != 0
            val dataStart = if (long) 5 else 4
            val data = f.copyOfRange(dataStart, f.size - 1)
            fun s16(i: Int) = ((data[i].toInt() shl 8) or (data[i + 1].toInt() and 0xFF)).toShort().toInt()
            fun u8(i: Int) = data[i].toInt() and 0xFF
            when (op) {
                0x01 -> into.fill(BLACK)
                0x30 -> if (!color) pen = argbOfGrey(u8(0))
                0x3D -> if (color) pen = argbOfRg(u8(0))
                0x31 -> plot(into, s16(0), s16(2), pen)
                0x32 -> line(into, s16(0), s16(2), s16(4), s16(6), pen)
                0x33 -> {
                    val x0 = s16(0); val y0 = s16(2); val x1 = s16(4); val y1 = s16(6)
                    line(into, x0, y0, x1, y0, pen); line(into, x0, y1, x1, y1, pen)
                    line(into, x0, y0, x0, y1, pen); line(into, x1, y0, x1, y1, pen)
                }
                0x34 -> {
                    val x0 = minOf(s16(0), s16(4)); val x1 = maxOf(s16(0), s16(4))
                    val y0 = minOf(s16(2), s16(6)); val y1 = maxOf(s16(2), s16(6))
                    for (y in y0..y1) for (x in x0..x1) plot(into, x, y, pen)
                }
                0x35, 0x36 -> {
                    val cx = s16(0); val cy = s16(2); val r = u8(4)
                    for (y in -r..r) for (x in -r..r) {
                        val d = x * x + y * y
                        val inside = d <= r * r
                        if (inside && (op == 0x36 || d >= (r - 1) * (r - 1))) plot(into, cx + x, cy + y, pen)
                    }
                }
                0x38 -> {
                    var k = 0
                    while (k + 7 < data.size) {
                        line(into, s16(k), s16(k + 2), s16(k + 4), s16(k + 6), pen)
                        k += 4
                    }
                }
                else -> Unit // hold/flush, text, anything else draws nothing here
            }
        }
        return into
    }

    const val BLACK = 0xFF000000.toInt()

    fun blank(): IntArray = IntArray(W * H) { BLACK }

    /** A device-coordinate pixel, stored where the rider sees it. */
    private fun plot(px: IntArray, x: Int, y: Int, c: Int) {
        if (x !in 0 until W || y !in 0 until H) return
        val rx = W - 1 - x
        val ry = H - 1 - y
        px[ry * W + rx] = c
    }

    private fun line(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        var x = x0; var y = y0
        val dx = kotlin.math.abs(x1 - x0); val sx = if (x0 < x1) 1 else -1
        val dy = -kotlin.math.abs(y1 - y0); val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            plot(px, x, y, c)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }
}
