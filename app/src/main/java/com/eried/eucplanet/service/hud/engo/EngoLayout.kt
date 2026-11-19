package com.eried.eucplanet.service.hud.engo

/**
 * Pure layout for the ENGO HUD: turns an [EngoSnapshot] into the batched list of
 * ActiveLook command frames for one screen. No Android, no state - unit-tested.
 *
 * Two screens: a telemetry page (speed + PWM bar + the rider's two corners) and a nav
 * takeover (turn arrow + distance + street) chosen by [EngoSnapshot.navActive].
 * The whole screen is wrapped in holdFlush HOLD...FLUSH so it presents at once.
 *
 * Coordinates: everything below is laid out as the rider sees it, (0,0) top-left.
 * The glasses' optics mirror the panel, so the device origin (0,0) is the rider's
 * BOTTOM-RIGHT and x/y grow left/up. [dx]/[dy] flip every point once, and text uses
 * rotation [ROT_READ] (ActiveLook "R4"), whose anchor is the text's top-left as the
 * rider sees it. A rider on an ENGO 3 saw the first build upside down (rotation 0).
 *
 * Text never overlaps or runs off the panel: each string gets a box from
 * [textWidth] (a generous per-font glyph width) and drops to a smaller font, or is
 * clipped, when it would not fit its slot. The tests check every box.
 */
object EngoLayout {
    const val W = 304
    const val H = 256

    /** ActiveLook text rotation that reads left to right, upright, for the rider. */
    const val ROT_READ = 4

    // Built-in ActiveLook fonts: id -> pixel height (fonts 0 and 1 are both 24 px).
    const val FONT_S = 1
    const val FONT_M = 2
    const val FONT_L = 3
    private val FONT_H = mapOf(0 to 24, 1 to 24, 2 to 35, 3 to 49)
    /** Widest likely glyph per font (proportional font, so this over-estimates). */
    private val FONT_W = mapOf(0 to 15, 1 to 15, 2 to 21, 3 to 30)

    fun fontHeight(font: Int): Int = FONT_H[font] ?: 24
    fun textWidth(font: Int, s: String): Int = (FONT_W[font] ?: 15) * s.length

    // --- rider-view layout ---
    private const val M = 16 // side margin; the panel edges are soft in the optics
    private const val MID = 152
    private const val SPEED_Y = 18
    private const val UNIT_Y = 72
    private const val BAR_X0 = 150
    private const val BAR_X1 = 288
    private const val BAR_Y0 = 22
    private const val BAR_Y1 = 54
    private const val PWM_Y = 62
    private const val TAG_Y = 150
    private const val VALUE_Y = 178

    // Grey levels (ENGO 2). 15 = brightest.
    private const val GREY_BRIGHT = 15
    private const val GREY_DIM = 8

    // RG colours (ENGO 3). The byte is the ActiveLook 81-colour palette code:
    // red levels live in bits 5-2, green levels in bits 7-6 and 1-0. There is no
    // white on a red-green panel; its brightest is the warm yellow 0xFF.
    const val RG_TEXT = 0xFF // readings: brightest
    const val RG_LABEL = 0x55 // tags, units, bar outline: half bright, same hue
    const val RG_OK = 0xC3 // full green, no red
    const val RG_WARN = 0xDB // yellow
    const val RG_DANGER = 0x3C // full red, no green

    /** PWM thresholds for the warning colour (mirrors the app's gauge bands). */
    private const val PWM_WARN = 70
    private const val PWM_DANGER = 90

    fun render(s: EngoSnapshot, caps: EngoCaps): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        out += ActiveLookProtocol.holdFlush(ActiveLookProtocol.HOLD)
        out += ActiveLookProtocol.clear()
        if (s.navActive) renderNav(s, caps, out) else renderTelemetry(s, caps, out)
        out += ActiveLookProtocol.holdFlush(ActiveLookProtocol.FLUSH)
        return out
    }

    private fun renderTelemetry(s: EngoSnapshot, caps: EngoCaps, out: MutableList<ByteArray>) {
        // Speed (primary) + unit, top-left.
        val speedStr = if (s.connected) s.speed.toString() else "--"
        out += text(M, SPEED_Y, pick(speedStr, MID - M, caps.speedFont), GREY_BRIGHT, RG_TEXT, speedStr, caps)
        out += text(M, UNIT_Y, FONT_S, GREY_DIM, RG_LABEL, s.speedUnit, caps)

        // PWM bar, top-right: dim outline, a fill as long as the PWM, and the
        // number under it. Green, yellow, red by band on ENGO 3; grey on ENGO 2.
        out += fillColor(GREY_DIM, RG_LABEL, caps)
        out += rect(BAR_X0, BAR_Y0, BAR_X1, BAR_Y1, filled = false)
        val pct = s.pwmPct.coerceIn(0, 100)
        val band = pwmColorRg(pct)
        if (s.connected && pct > 0) {
            out += fillColor(GREY_BRIGHT, band, caps)
            out += rect(BAR_X0, BAR_Y0, BAR_X0 + (BAR_X1 - BAR_X0) * pct / 100, BAR_Y1, filled = true)
        }
        val pwmStr = "PWM " + (if (s.connected) "$pct%" else "--")
        out += text(BAR_X0, PWM_Y, FONT_S, GREY_BRIGHT, if (s.connected) band else RG_LABEL, pwmStr, caps)

        // The two corners the rider picked: a dim tag over a bright value, each
        // in its own half. An empty one draws nothing.
        corner(s.left, M, MID - 6, s.connected, caps, out)
        corner(s.right, MID + 8, W - M, s.connected, caps, out)
    }

    private fun corner(
        c: EngoCell, x0: Int, x1: Int, connected: Boolean, caps: EngoCaps, out: MutableList<ByteArray>,
    ) {
        if (c.tag.isBlank()) return
        val value = if (connected) c.value else "--"
        out += text(x0, TAG_Y, FONT_S, GREY_DIM, RG_LABEL, clip(c.tag, FONT_S, x1 - x0), caps)
        val font = pick(value, x1 - x0, FONT_M)
        out += text(x0, VALUE_Y, font, GREY_BRIGHT, RG_TEXT, clip(value, font, x1 - x0), caps)
    }

    private fun renderNav(s: EngoSnapshot, caps: EngoCaps, out: MutableList<ByteArray>) {
        // Turn arrow (left), distance (top-right), street (bottom).
        out += fillColor(GREY_BRIGHT, RG_TEXT, caps)
        arrow(s.navManeuver, out)
        val dist = s.navDistanceText
        val font = pick(dist, W - M - BAR_X0, FONT_M)
        out += text(BAR_X0, BAR_Y0, font, GREY_BRIGHT, RG_TEXT, clip(dist, font, W - M - BAR_X0), caps)
        if (s.navStreet.isNotBlank()) {
            out += text(M, 196, FONT_S, GREY_BRIGHT, RG_TEXT, clip(s.navStreet, FONT_S, W - 2 * M), caps)
        }
    }

    /** Simple line-drawn arrow around a centre point, direction by maneuver. */
    private fun arrow(m: EngoManeuver, out: MutableList<ByteArray>) {
        val cx = 76
        val cy = 110
        val r = 44
        when (m) {
            EngoManeuver.LEFT, EngoManeuver.SLIGHT_LEFT, EngoManeuver.UTURN -> {
                out += line(cx + r, cy, cx - r, cy)
                out += line(cx - r, cy, cx - r / 2, cy - r / 2)
                out += line(cx - r, cy, cx - r / 2, cy + r / 2)
            }
            EngoManeuver.RIGHT, EngoManeuver.SLIGHT_RIGHT -> {
                out += line(cx - r, cy, cx + r, cy)
                out += line(cx + r, cy, cx + r / 2, cy - r / 2)
                out += line(cx + r, cy, cx + r / 2, cy + r / 2)
            }
            EngoManeuver.ARRIVE -> {
                out += ActiveLookProtocol.circf(dx(cx), dy(cy), r / 2)
            }
            EngoManeuver.STRAIGHT -> {
                out += line(cx, cy + r, cx, cy - r)
                out += line(cx, cy - r, cx - r / 2, cy - r / 2)
                out += line(cx, cy - r, cx + r / 2, cy - r / 2)
            }
        }
    }

    fun pwmColorRg(pct: Int): Int = when {
        pct >= PWM_DANGER -> RG_DANGER
        pct >= PWM_WARN -> RG_WARN
        else -> RG_OK
    }

    /** [preferred] font, or the small one when [s] would not fit [room] pixels. */
    private fun pick(s: String, room: Int, preferred: Int): Int =
        if (textWidth(preferred, s) <= room) preferred else FONT_S

    /** [s] cut so it fits [room] pixels in [font]. */
    private fun clip(s: String, font: Int, room: Int): String {
        val max = room / (FONT_W[font] ?: 15)
        return if (s.length <= max) s else s.take(max.coerceAtLeast(1))
    }

    // --- rider view -> device coordinates ---
    private fun dx(x: Int) = W - 1 - x
    private fun dy(y: Int) = H - 1 - y

    private fun line(x0: Int, y0: Int, x1: Int, y1: Int) =
        ActiveLookProtocol.line(dx(x0), dy(y0), dx(x1), dy(y1))

    /** Rectangle from rider-view corners, sent with device min/max corners. */
    private fun rect(x0: Int, y0: Int, x1: Int, y1: Int, filled: Boolean): ByteArray {
        val a = minOf(dx(x0), dx(x1)); val b = minOf(dy(y0), dy(y1))
        val c = maxOf(dx(x0), dx(x1)); val d = maxOf(dy(y0), dy(y1))
        return if (filled) ActiveLookProtocol.rectf(a, b, c, d) else ActiveLookProtocol.rect(a, b, c, d)
    }

    /** Text with its top-left at rider-view (x, y), in colour (ENGO 3) or grey (ENGO 2). */
    private fun text(
        x: Int, y: Int, font: Int, grey: Int, rg: Int, str: String, caps: EngoCaps,
    ): ByteArray =
        if (caps.colorRYG) ActiveLookProtocol.txtColor(dx(x), dy(y), ROT_READ, font, rg, str)
        else ActiveLookProtocol.txt(dx(x), dy(y), ROT_READ, font, grey, str)

    /** Set the fill/draw colour for the next shape, colour or grey per model. */
    private fun fillColor(grey: Int, rg: Int, caps: EngoCaps): ByteArray =
        if (caps.colorRYG) ActiveLookProtocol.color(rg) else ActiveLookProtocol.grayscale(grey)
}
