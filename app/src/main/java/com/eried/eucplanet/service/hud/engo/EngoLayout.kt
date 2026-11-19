package com.eried.eucplanet.service.hud.engo

/**
 * Pure layout for the ENGO ride page: an [EngoPage] becomes a list of
 * [EngoWidget]s, each a box on the panel and the ActiveLook commands that draw
 * it. No Android, no state - unit-tested, and rendered to pictures by
 * [EngoRaster] for the settings preview.
 *
 *     +---------------------------------------+
 *     | turn       SPEED            TOP       |
 *     | 120 M       42              87        |
 *     |            KM/H                       |
 *     |                                       |
 *     | PWM %     | BAT %      | MOT C        |
 *     | ######### | #######    | ####         |
 *     |    58     |    76      |    58        |
 *     +---------------------------------------+
 *
 * Everything is laid out as the rider sees it, (0,0) top-left. The optics
 * mirror the panel, so the device origin is the rider's bottom-right: every
 * point is flipped once, in [dx]/[dy]. Text is drawn by [EngoPixelFont] as
 * rectangles, so its size is exact and numbers centre under their titles.
 *
 * The first frame draws every widget; after that [delta] erases and redraws
 * only the widgets whose commands changed, so a new speed costs a few hundred
 * bytes instead of the whole page.
 */
object EngoLayout {
    const val W = 304
    const val H = 256

    // --- rider-view layout, all boxes inclusive ---
    private const val SPEED_X0 = 84
    private const val SPEED_X1 = 220
    private const val SPEED_Y0 = 24
    private const val SPEED_Y1 = 94
    private const val SPEED_MAX_SCALE = 10
    private const val UNIT_Y = 98
    // Top right: the title runs above the speed's row so a "MAX PWM" fits; the
    // pair is right-aligned to the panel edge, mirroring the turn on the left.
    private const val TOP_TITLE_X0 = 196
    private const val TOP_X0 = 224
    private const val TOP_X1 = 288
    private const val TOP_TITLE_Y = 8
    private const val TOP_VALUE_Y = 28
    private const val TOP_MAX_SCALE = 5
    private const val NAV_X0 = 16
    private const val NAV_Y0 = 14
    private const val NAV_X1 = 80
    private const val NAV_ARROW_Y1 = 60
    private const val NAV_Y1 = 92
    private const val NAV_CX = 40
    private const val NAV_CY = 36
    private const val NAV_R = 18
    private const val NAV_TEXT_Y = 64
    private const val COL_X0 = 14
    private const val COL_W = 86
    private const val COL_STEP = 95
    private const val TITLE_Y = 145
    private const val BAR_Y0 = 170
    private const val BAR_Y1 = 183
    private const val BAR_SEGMENTS = 8
    private const val BAR_SEG_W = 8
    private const val BAR_GAP = 3
    private const val GRAPH_Y0 = 166
    private const val GRAPH_Y1 = 188
    private const val VALUE_Y0 = 196
    private const val VALUE_H = 28
    private const val VALUE_MAX_SCALE = 4
    private const val LABEL_SCALE = 2

    // RG colours (ENGO 3): the ActiveLook 81-colour palette code, red levels in
    // bits 5-2 and green levels in bits 7-6 and 1-0. No white on a red-green
    // panel; its brightest is the warm yellow 0xFF. Green shows strong on the
    // glasses (half red with full green read yellow-green to a rider), so the
    // warning band is full red with a third green: orange.
    const val RG_TEXT = 0xFF
    const val RG_LABEL = 0x55
    const val RG_DIM = 0x05
    const val RG_OK = 0xC3
    const val RG_WARN = 0x7D
    const val RG_DANGER = 0x3C

    // Grey levels (ENGO 2), 15 brightest.
    private const val G_TEXT = 15
    private const val G_LABEL = 8
    private const val G_DIM = 3

    /** One part of the page: where it may draw, and the commands that draw it.
     *  No [boxes] means decoration that never changes and is never erased. */
    class EngoWidget(val id: String, val boxes: List<IntArray>, val cmds: List<ByteArray>)

    /** The whole page as one batched frame: HOLD, clear, every widget, FLUSH. */
    fun render(p: EngoPage, caps: EngoCaps): List<ByteArray> = full(widgets(p, caps))

    fun full(ws: List<EngoWidget>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        out += ActiveLookProtocol.holdFlush(ActiveLookProtocol.HOLD)
        out += ActiveLookProtocol.clear()
        ws.forEach { out += it.cmds }
        out += ActiveLookProtocol.holdFlush(ActiveLookProtocol.FLUSH)
        return out
    }

    /**
     * What to send to go from [prev] (what the glasses show) to [next]: the
     * whole page when there is no previous or its shape changed, nothing when
     * nothing changed, else each changed widget erased and drawn again.
     */
    fun delta(prev: List<EngoWidget>?, next: List<EngoWidget>, caps: EngoCaps): List<ByteArray> {
        if (prev == null || prev.size != next.size || prev.indices.any { prev[it].id != next[it].id }) {
            return full(next)
        }
        val changed = next.indices.filter { !same(prev[it].cmds, next[it].cmds) }
        if (changed.isEmpty()) return emptyList()
        if (changed.any { next[it].boxes.isEmpty() }) return full(next)
        val out = ArrayList<ByteArray>()
        out += ActiveLookProtocol.holdFlush(ActiveLookProtocol.HOLD)
        for (i in changed) {
            out += pen(0x00, 0, caps)
            next[i].boxes.forEach { out += rect(it[0], it[1], it[2], it[3], filled = true) }
            out += next[i].cmds
        }
        out += ActiveLookProtocol.holdFlush(ActiveLookProtocol.FLUSH)
        return out
    }

    private fun same(a: List<ByteArray>, b: List<ByteArray>): Boolean =
        a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }

    fun widgets(p: EngoPage, caps: EngoCaps): List<EngoWidget> {
        val ws = ArrayList<EngoWidget>()
        ws += frame(caps)
        ws += dividers(caps)
        ws += navWidgets(p.nav, caps)
        ws += speedWidget(p, caps)
        ws += EngoWidget("unit", listOf(intArrayOf(SPEED_X0, UNIT_Y - 2, SPEED_X1, UNIT_Y + 15)),
            listOf(pen(RG_LABEL, G_LABEL, caps)) +
                textCentered(p.speedUnit, (SPEED_X0 + SPEED_X1) / 2, UNIT_Y, LABEL_SCALE, SPEED_X1 - SPEED_X0))
        ws += topWidgets(p.top, p.connected, caps)
        for (i in 0 until 3) ws += column(i, p.bottom.getOrNull(i), p.graphs, caps)
        return ws
    }

    // --- widgets ---

    /** Corner marks, the edge of what the glasses can show. */
    private fun frame(caps: EngoCaps): EngoWidget {
        val c = ArrayList<ByteArray>()
        c += pen(RG_LABEL, G_LABEL, caps)
        val a = 12; val t = 2; val i = 2
        for ((x, y, sx, sy) in listOf(
            listOf(i, i, 1, 1), listOf(W - 1 - i, i, -1, 1),
            listOf(i, H - 1 - i, 1, -1), listOf(W - 1 - i, H - 1 - i, -1, -1),
        )) {
            c += rect(x, y, x + sx * (a - 1), y + sy * (t - 1), filled = true)
            c += rect(x, y, x + sx * (t - 1), y + sy * (a - 1), filled = true)
        }
        return EngoWidget("frame", emptyList(), c)
    }

    /** Thin lines between the three bottom columns. */
    private fun dividers(caps: EngoCaps): EngoWidget {
        val c = ArrayList<ByteArray>()
        c += pen(RG_DIM, G_DIM, caps)
        for (i in 1 until 3) {
            val x = COL_X0 + i * COL_STEP - (COL_STEP - COL_W) / 2 - 1
            c += line(x, TITLE_Y + 1, x, VALUE_Y0 + VALUE_H - 2)
        }
        return EngoWidget("div", emptyList(), c)
    }

    private fun speedWidget(p: EngoPage, caps: EngoCaps): EngoWidget {
        val room = SPEED_X1 - SPEED_X0
        val s = EngoPixelFont.fitScale(p.speed, room, SPEED_MAX_SCALE)
        val y = SPEED_Y0 + (SPEED_Y1 - SPEED_Y0 + 1 - EngoPixelFont.height(s)) / 2
        return EngoWidget("speed", listOf(intArrayOf(SPEED_X0, SPEED_Y0, SPEED_X1, SPEED_Y1)),
            listOf(pen(RG_TEXT, G_TEXT, caps)) + textCentered(p.speed, (SPEED_X0 + SPEED_X1) / 2, y, s, room))
    }

    private fun topWidgets(r: EngoReading?, connected: Boolean, caps: EngoCaps): List<EngoWidget> {
        val title = ArrayList<ByteArray>()
        title += pen(RG_LABEL, G_LABEL, caps)
        val value = ArrayList<ByteArray>()
        if (r != null) {
            val t = EngoPixelFont.clip(r.title, TOP_X1 - TOP_TITLE_X0, LABEL_SCALE)
            title += text(t, TOP_X1 - EngoPixelFont.width(t, LABEL_SCALE), TOP_TITLE_Y, LABEL_SCALE)
            val v = if (connected) r.value else "--"
            val s = EngoPixelFont.fitScale(v, TOP_X1 - TOP_X0, TOP_MAX_SCALE)
            val cut = EngoPixelFont.clip(v, TOP_X1 - TOP_X0, s)
            value += pen(colour(r.band), G_TEXT, caps)
            value += text(cut, TOP_X1 - EngoPixelFont.width(cut, s), TOP_VALUE_Y, s)
        } else value += pen(RG_TEXT, G_TEXT, caps)
        return listOf(
            EngoWidget("topTitle", listOf(intArrayOf(TOP_TITLE_X0, TOP_TITLE_Y - 2, TOP_X1, TOP_TITLE_Y + 15)), title),
            EngoWidget("topValue", listOf(intArrayOf(TOP_X0, TOP_VALUE_Y - 2, TOP_X1, TOP_VALUE_Y + 37)), value),
        )
    }

    private fun column(i: Int, r: EngoReading?, graphs: Boolean, caps: EngoCaps): List<EngoWidget> {
        val x0 = COL_X0 + i * COL_STEP
        val x1 = x0 + COL_W - 1
        val title = ArrayList<ByteArray>()
        title += pen(RG_LABEL, G_LABEL, caps)
        val gauge = ArrayList<ByteArray>()
        val value = ArrayList<ByteArray>()
        if (r != null) {
            title += text(EngoPixelFont.clip(r.title, COL_W, LABEL_SCALE), x0, TITLE_Y, LABEL_SCALE)
            gauge += if (graphs) graph(r, x0, x1, caps) else bar(r, x0, caps)
            val s = EngoPixelFont.fitScale(r.value, COL_W, VALUE_MAX_SCALE)
            val y = VALUE_Y0 + (VALUE_H - EngoPixelFont.height(s)) / 2
            value += pen(colour(r.band), G_TEXT, caps)
            value += textCentered(r.value, (x0 + x1 + 1) / 2, y, s, COL_W)
        } else {
            gauge += pen(RG_DIM, G_DIM, caps)
            value += pen(RG_TEXT, G_TEXT, caps)
        }
        return listOf(
            EngoWidget("t$i", listOf(intArrayOf(x0, TITLE_Y - 1, x1, TITLE_Y + 15)), title),
            EngoWidget("g$i", listOf(intArrayOf(x0, GRAPH_Y0 - 2, x1, GRAPH_Y1 + 2)), gauge),
            EngoWidget("v$i", listOf(intArrayOf(x0, VALUE_Y0 - 2, x1, VALUE_Y0 + VALUE_H + 2)), value),
        )
    }

    /** Segments: lit up to the level, the rest dim. */
    private fun bar(r: EngoReading, x0: Int, caps: EngoCaps): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val lit = r.level?.let { (it.coerceIn(0f, 1f) * BAR_SEGMENTS + 0.5f).toInt() } ?: 0
        fun seg(k: Int) = (x0 + k * (BAR_SEG_W + BAR_GAP)).let { rect(it, BAR_Y0, it + BAR_SEG_W - 1, BAR_Y1, filled = true) }
        if (lit > 0) {
            out += pen(colour(r.band), G_TEXT, caps)
            for (k in 0 until lit) out += seg(k)
        }
        if (lit < BAR_SEGMENTS) {
            out += pen(RG_DIM, G_DIM, caps)
            for (k in lit until BAR_SEGMENTS) out += seg(k)
        }
        return out
    }

    /** The last minute as a line over a dim floor, two pixels thick. */
    private fun graph(r: EngoReading, x0: Int, x1: Int, caps: EngoCaps): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        out += pen(RG_DIM, G_DIM, caps)
        out += line(x0, GRAPH_Y1, x1, GRAPH_Y1)
        val pts = r.series
        if (pts.size >= 2) {
            val n = pts.size
            val h = GRAPH_Y1 - 2 - GRAPH_Y0
            val xy = IntArray(n * 2)
            for (k in 0 until n) {
                xy[k * 2] = dx(x0 + (x1 - 1 - x0) * k / (n - 1))
                xy[k * 2 + 1] = dy(GRAPH_Y1 - 2 - (pts[k].coerceIn(0f, 1f) * h + 0.5f).toInt())
            }
            out += pen(colour(r.band), G_TEXT, caps)
            out += ActiveLookProtocol.polyline(xy)
            // The second pass one pixel to the rider's right and up makes it bolder.
            out += ActiveLookProtocol.polyline(IntArray(xy.size) { if (it % 2 == 0) xy[it] - 1 else xy[it] + 1 })
        }
        return out
    }

    /** The turn and its distance apart, so a distance counting down does not resend the arrow. */
    private fun navWidgets(n: EngoNav?, caps: EngoCaps): List<EngoWidget> {
        val arrow = ArrayList<ByteArray>()
        arrow += pen(RG_TEXT, G_TEXT, caps)
        val dist = ArrayList<ByteArray>()
        dist += pen(RG_LABEL, G_LABEL, caps)
        if (n != null) {
            arrow += arrow(n.maneuver)
            dist += text(EngoPixelFont.clip(n.distance.uppercase(), NAV_X1 - NAV_X0, LABEL_SCALE), NAV_X0, NAV_TEXT_Y, LABEL_SCALE)
        }
        return listOf(
            EngoWidget("navArrow", listOf(intArrayOf(NAV_X0, NAV_Y0, NAV_X1, NAV_ARROW_Y1)), arrow),
            EngoWidget("navDist", listOf(intArrayOf(NAV_X0, NAV_ARROW_Y1 + 2, NAV_X1, NAV_Y1)), dist),
        )
    }

    /** A turn arrow around the nav corner's centre, as thick polylines. */
    private fun arrow(m: EngoManeuver): List<ByteArray> {
        val shapes: List<List<Float>> = when (m) {
            EngoManeuver.STRAIGHT -> listOf(
                listOf(0f, 1f, 0f, -1f), listOf(-0.6f, -0.4f, 0f, -1f, 0.6f, -0.4f))
            EngoManeuver.LEFT -> listOf(
                listOf(0.4f, 1f, 0.4f, -0.2f, -1f, -0.2f), listOf(-0.55f, -0.65f, -1f, -0.2f, -0.55f, 0.25f))
            EngoManeuver.RIGHT -> listOf(
                listOf(-0.4f, 1f, -0.4f, -0.2f, 1f, -0.2f), listOf(0.55f, -0.65f, 1f, -0.2f, 0.55f, 0.25f))
            EngoManeuver.SLIGHT_LEFT -> listOf(
                listOf(0.3f, 1f, 0.3f, 0.2f, -0.6f, -0.7f), listOf(-0.6f, -0.05f, -0.6f, -0.7f, 0.05f, -0.7f))
            EngoManeuver.SLIGHT_RIGHT -> listOf(
                listOf(-0.3f, 1f, -0.3f, 0.2f, 0.6f, -0.7f), listOf(0.6f, -0.05f, 0.6f, -0.7f, -0.05f, -0.7f))
            EngoManeuver.UTURN -> listOf(
                listOf(0.5f, 1f, 0.5f, -0.4f, 0.3f, -0.8f, -0.1f, -1f, -0.5f, -0.8f, -0.7f, -0.4f, -0.7f, 0.4f),
                listOf(-1f, 0.05f, -0.7f, 0.45f, -0.4f, 0.05f))
            EngoManeuver.ARRIVE -> emptyList()
        }
        val out = ArrayList<ByteArray>()
        if (m == EngoManeuver.ARRIVE) {
            out += ActiveLookProtocol.circf(dx(NAV_CX), dy(NAV_CY), NAV_R / 2 + 1)
            return out
        }
        for (s in shapes) {
            val xy = IntArray(s.size) { k ->
                if (k % 2 == 0) dx(NAV_CX + (s[k] * NAV_R).toInt()) else dy(NAV_CY + (s[k] * NAV_R).toInt())
            }
            // Three pixels thick: the line drawn nine times, shifted.
            for (ox in -2..0) for (oy in -2..0) {
                out += ActiveLookProtocol.polyline(IntArray(xy.size) { if (it % 2 == 0) xy[it] + ox else xy[it] + oy })
            }
        }
        return out
    }

    // --- drawing helpers ---

    private fun colour(b: EngoBand): Int = when (b) {
        EngoBand.NORMAL -> RG_TEXT
        EngoBand.OK -> RG_OK
        EngoBand.WARN -> RG_WARN
        EngoBand.DANGER -> RG_DANGER
    }

    /** Pixel-font text with its top-left at rider-view (x, y). */
    private fun text(s: String, x: Int, y: Int, scale: Int): List<ByteArray> =
        EngoPixelFont.rects(s, x, y, scale).map { rect(it[0], it[1], it[2], it[3], filled = true) }

    /** Pixel-font text centred on [cx], cut to [room] if it would not fit at scale 1. */
    private fun textCentered(s: String, cx: Int, y: Int, scale: Int, room: Int): List<ByteArray> {
        val t = EngoPixelFont.clip(s, room, scale)
        return text(t, cx - EngoPixelFont.width(t, scale) / 2, y, scale)
    }

    /** Set the colour for the next commands, colour or grey per model. */
    private fun pen(rg: Int, grey: Int, caps: EngoCaps): ByteArray =
        if (caps.colorRYG) ActiveLookProtocol.color(rg) else ActiveLookProtocol.grayscale(grey)

    // --- rider view -> device coordinates ---
    private fun dx(x: Int) = W - 1 - x
    private fun dy(y: Int) = H - 1 - y

    private fun line(x0: Int, y0: Int, x1: Int, y1: Int) =
        ActiveLookProtocol.line(dx(x0), dy(y0), dx(x1), dy(y1))

    /** Rectangle from rider-view corners (inclusive), sent with device min/max corners. */
    private fun rect(x0: Int, y0: Int, x1: Int, y1: Int, filled: Boolean): ByteArray {
        val a = minOf(dx(x0), dx(x1)); val b = minOf(dy(y0), dy(y1))
        val c = maxOf(dx(x0), dx(x1)); val d = maxOf(dy(y0), dy(y1))
        return if (filled) ActiveLookProtocol.rectf(a, b, c, d) else ActiveLookProtocol.rect(a, b, c, d)
    }
}
