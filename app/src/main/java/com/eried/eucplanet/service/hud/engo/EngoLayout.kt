package com.eried.eucplanet.service.hud.engo

/**
 * Pure layout for the ENGO HUD: turns an [EngoSnapshot] into the batched list of
 * ActiveLook command frames for one screen. No Android, no state - unit-tested.
 *
 * Two screens: a telemetry page (speed + PWM bar + battery + temp) and a nav
 * takeover (turn arrow + distance + street) chosen by [EngoSnapshot.navActive].
 * The whole screen is wrapped in holdFlush HOLD...FLUSH so it presents at once.
 *
 * Pixel positions, font ids and the RG colour values are tuning constants marked
 * below; they get dialled in on a real unit. The STRUCTURE (page choice, widget
 * set, colour-vs-grey path, batching) is what the tests lock down.
 */
object EngoLayout {
    const val W = 304
    const val H = 256

    // --- tunable constants (confirm on device) ---
    private const val PAD = 12
    private const val SPEED_X = 14
    private const val SPEED_Y = 30
    private const val UNIT_Y = 118
    private const val BAR_X0 = 176
    private const val BAR_X1 = 292
    private const val BAR_Y0 = 34
    private const val BAR_Y1 = 66
    private const val ROW2_Y = 210

    // Grey levels (ENGO 2). 15 = brightest.
    private const val GREY_BRIGHT = 15
    private const val GREY_DIM = 9

    // RG colours (ENGO 3), 8-bit RRGG (bits 7-6 red, 5-4 green). Confirm on device.
    private const val RG_WHITE = 0xFC // red+green
    private const val RG_GREEN = 0x0C
    private const val RG_YELLOW = 0xFC
    private const val RG_RED = 0xC0

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
        // Speed (primary) + unit.
        val speedStr = if (s.connected) s.speed.toString() else "--"
        out += text(SPEED_X, SPEED_Y, caps.speedFont, GREY_BRIGHT, RG_WHITE, speedStr, caps)
        out += text(SPEED_X, UNIT_Y, caps.labelFont, GREY_DIM, RG_WHITE, s.speedUnit, caps)

        // PWM bar (top-right): outline, then a fill proportional to PWM, coloured
        // green/yellow/red on ENGO 3 (grey fill on ENGO 2).
        out += ActiveLookProtocol.rect(BAR_X0, BAR_Y0, BAR_X1, BAR_Y1)
        if (s.connected && s.pwmPct > 0) {
            val pct = s.pwmPct.coerceIn(0, 100)
            val fillX1 = BAR_X0 + (BAR_X1 - BAR_X0) * pct / 100
            out += fillColor(pwmLevelGrey(pct), pwmColorRg(pct), caps)
            out += ActiveLookProtocol.rectf(BAR_X0, BAR_Y0, fillX1, BAR_Y1)
            // Reset draw colour so following widgets aren't tinted.
            out += fillColor(GREY_BRIGHT, RG_WHITE, caps)
        }
        out += text(BAR_X0, BAR_Y1 + 8, caps.labelFont, GREY_DIM, RG_WHITE,
            "PWM " + (if (s.connected) "${s.pwmPct}%" else "--"), caps)

        // Secondary row: battery + temp.
        out += text(PAD, ROW2_Y, caps.labelFont, GREY_BRIGHT, RG_WHITE,
            "BATT " + (if (s.connected) "${s.batteryPct}%" else "--"), caps)
        out += text(BAR_X0, ROW2_Y, caps.labelFont, GREY_BRIGHT, RG_WHITE,
            "TEMP " + (if (s.connected) "${s.temp}${s.tempUnit}" else "--"), caps)
    }

    private fun renderNav(s: EngoSnapshot, caps: EngoCaps, out: MutableList<ByteArray>) {
        // Turn arrow (centre-left), distance (top-right), street (bottom).
        arrow(s.navManeuver, out)
        out += text(BAR_X0, BAR_Y0, caps.speedFont, GREY_BRIGHT, RG_WHITE,
            s.navDistanceText, caps)
        if (s.navStreet.isNotBlank()) {
            out += text(PAD, ROW2_Y, caps.labelFont, GREY_BRIGHT, RG_WHITE,
                s.navStreet.take(24), caps)
        }
    }

    /** Simple line-drawn arrow around a centre point, direction by maneuver. */
    private fun arrow(m: EngoManeuver, out: MutableList<ByteArray>) {
        val cx = 80
        val cy = 120
        val r = 48
        when (m) {
            EngoManeuver.LEFT, EngoManeuver.SLIGHT_LEFT, EngoManeuver.UTURN -> {
                out += ActiveLookProtocol.line(cx + r, cy, cx - r, cy)
                out += ActiveLookProtocol.line(cx - r, cy, cx - r / 2, cy - r / 2)
                out += ActiveLookProtocol.line(cx - r, cy, cx - r / 2, cy + r / 2)
            }
            EngoManeuver.RIGHT, EngoManeuver.SLIGHT_RIGHT -> {
                out += ActiveLookProtocol.line(cx - r, cy, cx + r, cy)
                out += ActiveLookProtocol.line(cx + r, cy, cx + r / 2, cy - r / 2)
                out += ActiveLookProtocol.line(cx + r, cy, cx + r / 2, cy + r / 2)
            }
            EngoManeuver.ARRIVE -> {
                out += ActiveLookProtocol.circf(cx, cy, r / 2)
            }
            EngoManeuver.STRAIGHT -> {
                out += ActiveLookProtocol.line(cx, cy + r, cx, cy - r)
                out += ActiveLookProtocol.line(cx, cy - r, cx - r / 2, cy - r / 2)
                out += ActiveLookProtocol.line(cx, cy - r, cx + r / 2, cy - r / 2)
            }
        }
    }

    private fun pwmLevelGrey(pct: Int): Int = GREY_BRIGHT

    private fun pwmColorRg(pct: Int): Int = when {
        pct >= PWM_DANGER -> RG_RED
        pct >= PWM_WARN -> RG_YELLOW
        else -> RG_GREEN
    }

    /** Text in colour (ENGO 3) or grey (ENGO 2), same call site either way. */
    private fun text(
        x: Int, y: Int, font: Int, grey: Int, rg: Int, str: String, caps: EngoCaps,
    ): ByteArray =
        if (caps.colorRYG) ActiveLookProtocol.txtColor(x, y, 0, font, rg, str)
        else ActiveLookProtocol.txt(x, y, 0, font, grey, str)

    /** Set the fill/draw colour for the next shape, colour or grey per model. */
    private fun fillColor(grey: Int, rg: Int, caps: EngoCaps): ByteArray =
        if (caps.colorRYG) ActiveLookProtocol.color(rg) else ActiveLookProtocol.grayscale(grey)
}
