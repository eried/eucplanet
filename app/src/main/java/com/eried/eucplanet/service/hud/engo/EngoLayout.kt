package com.eried.eucplanet.service.hud.engo

import kotlin.math.roundToInt

/**
 * Pure layout for the ENGO ride page: an [EngoPage] becomes a list of
 * [EngoWidget]s, each a box on the panel and the ActiveLook commands that draw
 * it. No Android, no state - unit-tested, and rendered to pictures by
 * [EngoRaster] for the settings preview.
 *
 * Where each element goes comes from [EngoLayouts]: the rider's layout, or the
 * Turn layout while a route is guided. Everything is laid out as the rider
 * sees it, (0,0) top-left. The optics mirror the panel, so the device origin
 * is the rider's bottom-right: every point is flipped once, in [dx]/[dy].
 * Text is drawn as rectangles ([EngoPixelFont] for titles, the rider's
 * [EngoFonts] choice for numbers), so its size is exact and numbers centre
 * under their titles.
 *
 * The first frame draws every widget; after that [delta] erases and redraws
 * only the widgets whose commands changed, so a new speed costs a few hundred
 * bytes instead of the whole page.
 */
object EngoLayout {
    const val W = 304
    const val H = 256

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
    /** The Digital font's dark segments: the palette's faintest yellow, as dim as an unlit bar block. */
    const val RG_GHOST = 0x05

    // Grey levels (ENGO 2), 15 brightest.
    private const val G_TEXT = 15
    private const val G_LABEL = 8
    private const val G_DIM = 3
    private const val G_GHOST = 2

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
        val navigating = p.nav != null
        val spec = EngoLayouts.resolve(p.layout, navigating, p.turnsOn)
        val nav = if (EngoLayouts.showsTurn(p.layout, navigating, p.turnsOn)) p.nav else null
        val ws = ArrayList<EngoWidget>()
        ws += frame(caps)
        if (spec.dividers.isNotEmpty()) ws += dividers(spec.dividers, caps)
        spec.turn?.let { ws += turnWidgets(it, nav, caps) }
        val big = bigNumbers(spec, p)
        val heights = numberHeights(spec, p, big)
        for (spot in spec.spots) {
            ws += spotWidgets(spot, p.elements.getOrNull(spot.element), p.font, p.ghost,
                heights[spot.element] ?: 0, spot.element in big, caps)
        }
        return ws
    }

    // --- sizing ---

    /** The style a place draws: the rider's, when the place has room for it. */
    private fun styleIn(spot: EngoLayouts.Spot, r: EngoReading?): EngoStyle =
        r?.style?.takeIf { it in spot.styles } ?: EngoStyle.NUMBER

    /**
     * The elements whose number takes the room a bar or graph would have: a
     * number-only place, unless it sits in a row with bars or graphs, where it
     * keeps the row's line so titles, bands and numbers stay aligned.
     */
    private fun bigNumbers(spec: EngoLayouts.Spec, p: EngoPage): Set<Int> {
        fun numberOnly(s: EngoLayouts.Spot) = styleIn(s, p.elements.getOrNull(s.element)) == EngoStyle.NUMBER
        return spec.spots.filter { spot ->
            numberOnly(spot) && (spot.group == 0 || spec.spots.filter { it.group == spot.group }.all(::numberOnly))
        }.mapTo(HashSet()) { it.element }
    }

    /** A place's number area (x0, y0, x1, y1): the whole place when [big], else above or below its band. */
    private fun numberZone(g: EngoLayouts.Geom, big: Boolean): IntArray = when (g) {
        is EngoLayouts.Geom.Hero -> intArrayOf(g.x0, g.numTop, g.x1, if (big) g.numBottom else g.numBottomWithVisual)
        is EngoLayouts.Geom.Corner -> intArrayOf(g.x0, g.valueY, g.x1, g.valueY + g.valueMaxH - 1)
        is EngoLayouts.Geom.Cell -> intArrayOf(g.x0, if (big) g.y0 + 21 else g.y0 + 51, g.x1, g.y1 - 2)
    }

    /**
     * Each element's number height: the tallest that fits its place, and
     * within a group of same-sized places the smallest of those, so a row of
     * numbers reads at one size.
     */
    private fun numberHeights(spec: EngoLayouts.Spec, p: EngoPage, big: Set<Int>): Map<Int, Int> {
        val fitted = HashMap<Int, Int>()
        val zoneH = HashMap<Int, Int>()
        for (spot in spec.spots) {
            val r = p.elements.getOrNull(spot.element) ?: continue
            val z = numberZone(spot.geom, spot.element in big)
            val font = EngoFonts.forText(p.font, r.value)
            fitted[spot.element] = EngoFonts.fit(font, r.value, z[2] - z[0] + 1, z[3] - z[1] + 1)
            zoneH[spot.element] = z[3] - z[1] + 1
        }
        val out = HashMap(fitted)
        spec.spots.filter { it.group != 0 && it.element in fitted }
            .groupBy { it.group to zoneH.getValue(it.element) }
            .values.forEach { members ->
                val h = members.minOf { fitted.getValue(it.element) }
                members.forEach { out[it.element] = h }
            }
        return out
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

    /** Thin lines between places. */
    private fun dividers(lines: List<IntArray>, caps: EngoCaps): EngoWidget {
        val c = ArrayList<ByteArray>()
        c += pen(RG_DIM, G_DIM, caps)
        lines.forEach { c += line(it[0], it[1], it[2], it[3]) }
        return EngoWidget("div", emptyList(), c)
    }

    private fun spotWidgets(
        spot: EngoLayouts.Spot, r: EngoReading?, font: EngoFontStyle, ghost: Boolean, numberH: Int, big: Boolean,
        caps: EngoCaps,
    ): List<EngoWidget> {
        val id = "e${spot.element + 1}"
        val style = styleIn(spot, r)
        val title = ArrayList<ByteArray>().apply { add(pen(RG_LABEL, G_LABEL, caps)) }
        val value = ArrayList<ByteArray>()
        val visual = ArrayList<ByteArray>()
        val zone = numberZone(spot.geom, big)
        if (r != null) {
            value += number(r.value, font, zone, numberH, centred = !(spot.geom is EngoLayouts.Geom.Corner && !spot.geom.centred),
                colour = pen(colour(r.band), G_TEXT, caps), ghost = ghost, caps = caps)
        } else value += pen(RG_TEXT, G_TEXT, caps)

        return when (val g = spot.geom) {
            is EngoLayouts.Geom.Hero -> {
                if (r != null) title += textCentered(r.title, (g.x0 + g.x1 + 1) / 2, g.titleY, g.titleScale, g.x1 - g.x0 + 1)
                val ws = mutableListOf(
                    EngoWidget("${id}v", listOf(zone.copyOf()), value),
                    EngoWidget("${id}t", listOf(intArrayOf(g.x0, g.titleY - 2, g.x1, g.titleY + EngoPixelFont.height(g.titleScale) + 1)), title),
                )
                if (style != EngoStyle.NUMBER && r != null) {
                    visual += visual(r, style, g.x0, g.x1, g.visTop, g.visBottom, caps)
                    ws += EngoWidget("${id}g", listOf(intArrayOf(g.x0, g.visTop, g.x1, g.visBottom)), visual)
                }
                ws
            }
            is EngoLayouts.Geom.Corner -> {
                if (r != null) {
                    val t = EngoPixelFont.clip(r.title, g.x1 - g.titleX0 + 1, LABEL_SCALE)
                    val tw = EngoPixelFont.width(t, LABEL_SCALE)
                    val tx = if (g.centred) (g.titleX0 + g.x1 + 1) / 2 - tw / 2 else g.x1 + 1 - tw
                    title += text(t, tx, g.titleY, LABEL_SCALE)
                }
                listOf(
                    EngoWidget("${id}t", listOf(intArrayOf(g.titleX0, g.titleY - 2, g.x1, g.titleY + 15)), title),
                    EngoWidget("${id}v", listOf(intArrayOf(g.x0, g.valueY - 2, g.x1, g.valueY + g.valueMaxH + 2)), value),
                )
            }
            is EngoLayouts.Geom.Cell -> {
                if (r != null) title += text(EngoPixelFont.clip(r.title, g.x1 - g.x0 + 1, LABEL_SCALE), g.x0, g.y0, LABEL_SCALE)
                val ws = mutableListOf(EngoWidget("${id}t", listOf(intArrayOf(g.x0, g.y0 - 1, g.x1, g.y0 + 15)), title))
                if (style != EngoStyle.NUMBER && r != null) {
                    visual += visual(r, style, g.x0, g.x1, g.y0 + 19, g.y0 + 45, caps)
                    ws += EngoWidget("${id}g", listOf(intArrayOf(g.x0, g.y0 + 19, g.x1, g.y0 + 45)), visual)
                }
                val top = if (big) g.y0 + 19 else g.y0 + 49
                ws += EngoWidget("${id}v", listOf(intArrayOf(g.x0, top, g.x1, g.y1)), value)
                ws
            }
        }
    }

    /**
     * [s] in the rider's number font at height [h], in the middle of [zone]
     * vertically, centred or right-aligned by what is drawn (a seven-segment
     * "7" has no left stroke, so its advance would sit it off centre). Cut
     * from the end if even the smallest size is too wide.
     */
    private fun number(
        s: String, style: EngoFontStyle, zone: IntArray, h: Int, centred: Boolean, colour: ByteArray, ghost: Boolean,
        caps: EngoCaps,
    ): List<ByteArray> {
        val font = EngoFonts.forText(style, s)
        val drawnH = font.heights(h).first()
        val room = zone[2] - zone[0] + 1
        var t = s
        while (t.length > 1 && font.width(t, drawnH) > room) t = t.dropLast(1)
        val y = zone[1] + (zone[3] - zone[1] + 1 - drawnH) / 2
        val lit = font.rects(t, 0, y, drawnH)
        // A segment font's dark segments, drawn faintly first when the rider
        // wants them. Either way the digits centre as whole cells, so turning
        // them off never moves a number.
        val cells = font.ghost(t, 0, y, drawnH)
        val dark = if (ghost) cells else emptyList()
        val ink = lit + cells
        if (ink.isEmpty()) return listOf(colour)
        val inkX0 = ink.minOf { it[0] }
        val inkX1 = ink.maxOf { it[2] }
        val shift = if (centred) (zone[0] + zone[2] + 1) / 2 - (inkX1 - inkX0 + 1) / 2 - inkX0 else zone[2] - inkX1
        val out = ArrayList<ByteArray>()
        if (dark.isNotEmpty()) {
            out += pen(RG_GHOST, G_GHOST, caps)
            dark.forEach { out += rect(it[0] + shift, it[1], it[2] + shift, it[3], filled = true) }
        }
        out += colour
        lit.forEach { out += rect(it[0] + shift, it[1], it[2] + shift, it[3], filled = true) }
        return out
    }

    /** The bar or graph band of a reading, in (x0..x1, top..bottom). */
    private fun visual(r: EngoReading, style: EngoStyle, x0: Int, x1: Int, top: Int, bottom: Int, caps: EngoCaps): List<ByteArray> =
        when (style) {
            EngoStyle.BLOCKS -> blocks(r, x0, x1, top, bottom, caps)
            EngoStyle.SMOOTH -> smooth(r, x0, x1, top, bottom, caps)
            EngoStyle.GRAPH -> graph(r, x0, x1, top, bottom, caps)
            EngoStyle.NUMBER -> emptyList()
        }

    private fun barHeight(top: Int, bottom: Int) = (bottom - top + 1 - 8).coerceIn(8, 14)

    /** Segments: lit up to the level, the rest dim. */
    private fun blocks(r: EngoReading, x0: Int, x1: Int, top: Int, bottom: Int, caps: EngoCaps): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val w = x1 - x0 + 1
        val count = when { w < 100 -> 8; w < 200 -> 10; else -> 16 }
        val gap = 3
        val segW = (w - (count - 1) * gap) / count
        val off = (w - (count * segW + (count - 1) * gap)) / 2
        val bh = barHeight(top, bottom)
        val y0 = top + (bottom - top + 1 - bh) / 2
        val lit = r.level?.let { (it.coerceIn(0f, 1f) * count + 0.5f).toInt() } ?: 0
        fun seg(k: Int) = (x0 + off + k * (segW + gap)).let { rect(it, y0, it + segW - 1, y0 + bh - 1, filled = true) }
        if (lit > 0) {
            out += pen(colour(r.band), G_TEXT, caps)
            for (k in 0 until lit) out += seg(k)
        }
        if (lit < count) {
            out += pen(RG_DIM, G_DIM, caps)
            for (k in lit until count) out += seg(k)
        }
        return out
    }

    /** One continuous bar: lit up to the level, the rest of the track dim. */
    private fun smooth(r: EngoReading, x0: Int, x1: Int, top: Int, bottom: Int, caps: EngoCaps): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val sh = maxOf(4, barHeight(top, bottom) * 2 / 3)
        val y0 = top + (bottom - top + 1 - sh) / 2
        val y1 = y0 + sh - 1
        val litW = ((r.level ?: 0f).coerceIn(0f, 1f) * (x1 - x0 + 1)).roundToInt()
        if (litW > 0) {
            out += pen(colour(r.band), G_TEXT, caps)
            out += rect(x0, y0, x0 + litW - 1, y1, filled = true)
        }
        if (litW < x1 - x0 + 1) {
            out += pen(RG_DIM, G_DIM, caps)
            out += rect(x0 + litW, y0, x1, y1, filled = true)
        }
        return out
    }

    /** The reading's window as a line over a dim floor, two pixels thick. */
    private fun graph(r: EngoReading, x0: Int, x1: Int, top: Int, bottom: Int, caps: EngoCaps): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val gy0 = top + 2
        val gy1 = bottom - 2
        out += pen(RG_DIM, G_DIM, caps)
        out += line(x0, gy1, x1, gy1)
        val pts = resample(r.series, ((x1 - x0 + 1) / 2).coerceAtLeast(2))
        if (pts.size >= 2) {
            val n = pts.size
            val h = gy1 - 2 - gy0
            val xy = IntArray(n * 2)
            for (k in 0 until n) {
                xy[k * 2] = dx(x0 + (x1 - 1 - x0) * k / (n - 1))
                xy[k * 2 + 1] = dy(gy1 - 2 - (pts[k].coerceIn(0f, 1f) * h + 0.5f).toInt())
            }
            out += pen(colour(r.band), G_TEXT, caps)
            out += ActiveLookProtocol.polyline(xy)
            // The second pass one pixel to the rider's right and up makes it bolder.
            out += ActiveLookProtocol.polyline(IntArray(xy.size) { if (it % 2 == 0) xy[it] - 1 else xy[it] + 1 })
        }
        return out
    }

    /** [s] in at most [max] points, linearly between its own: a graph needs no more than one every two pixels. */
    internal fun resample(s: List<Float>, max: Int): List<Float> {
        if (s.size <= max || s.size < 2) return s
        return List(max) { k ->
            val pos = k * (s.size - 1).toFloat() / (max - 1)
            val i = pos.toInt().coerceAtMost(s.size - 2)
            val f = pos - i
            s[i] + (s[i + 1] - s[i]) * f
        }
    }

    /** The turn and its distance apart, so a distance counting down does not resend the arrow. */
    private fun turnWidgets(t: EngoLayouts.Turn, n: EngoNav?, caps: EngoCaps): List<EngoWidget> {
        val arrow = ArrayList<ByteArray>()
        arrow += pen(RG_TEXT, G_TEXT, caps)
        val dist = ArrayList<ByteArray>()
        dist += if (t.distBright) pen(RG_TEXT, G_TEXT, caps) else pen(RG_LABEL, G_LABEL, caps)
        if (n != null) {
            arrow += arrow(n.maneuver, t)
            val room = t.distBox[2] - t.distBox[0] + 1
            val s = EngoPixelFont.clip(n.distance.uppercase(), room, t.distScale)
            val x = if (t.distCentred) (t.distBox[0] + t.distBox[2] + 1) / 2 - EngoPixelFont.width(s, t.distScale) / 2 else t.distBox[0]
            dist += text(s, x, t.distY, t.distScale)
        }
        return listOf(
            EngoWidget("navArrow", listOf(t.arrowBox.copyOf()), arrow),
            EngoWidget("navDist", listOf(t.distBox.copyOf()), dist),
        )
    }

    /** A turn arrow around the turn place's centre, as thick polylines. */
    private fun arrow(m: EngoManeuver, t: EngoLayouts.Turn): List<ByteArray> {
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
            out += ActiveLookProtocol.circf(dx(t.cx), dy(t.cy), t.r / 2 + 1)
            return out
        }
        for (s in shapes) {
            val xy = IntArray(s.size) { k ->
                if (k % 2 == 0) dx(t.cx + (s[k] * t.r).toInt()) else dy(t.cy + (s[k] * t.r).toInt())
            }
            // [thick] pixels wide: the line drawn thick x thick times, shifted.
            for (ox in -(t.thick - 1)..0) for (oy in -(t.thick - 1)..0) {
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

    /** Pixel-font text centred on [cx], cut to [room] if it would not fit. */
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
