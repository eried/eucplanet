package com.eried.eucplanet.ui.studio

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.hudlink.OverlayElementType
import com.eried.eucplanet.hudlink.OverlayPreset
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders the Overlay Studio layout to a flat **draw-list** (a JSON array of
 * primitive ops in absolute pixel coords) so a thin platform interpreter can burn
 * it onto recorded video frames. Reuses [StudioMetric] for value extraction +
 * formatting; the iOS `StudioCameraRecorder` executes the ops with Core Graphics.
 *
 * Ops:
 *  - rect:  {op,x,y,w,h,c(argb),r(corner)}
 *  - text:  {op,x,y,s(px),c,a(0=left,1=center,2=right),b(bold 0/1),t}
 *  - arc:   {op,cx,cy,r,a0(deg),sw(deg),c,lw}
 *  - bar:   {op,x,y,w,h,frac,bg,fg}
 */
object StudioOverlayDrawList {

    fun build(
        preset: OverlayPreset,
        wd: WheelData,
        wheelName: String,
        canvasW: Int,
        canvasH: Int,
        unitSpeed: String,
        unitDistance: String,
        unitTemp: String,
        accentArgb: Long,
    ): String {
        val w = canvasW.toFloat()
        val h = canvasH.toFloat()
        return buildJsonArray {
            preset.elements.forEach { el ->
                val ex = el.x * w
                val ey = el.y * h
                val ew = el.width * w
                // Scale fonts/strokes to the element width, mirroring StudioElementView's
                // 150-unit design width (canvas px ≈ Compose dp on a 1x design).
                val s = (ew / 150f).coerceIn(0.5f, 6f)
                val pad = 8f * s
                val cx0 = ex + pad
                var cy0 = ey + pad

                // Element background panel (when visible).
                if ((el.background ushr 24) != 0L) {
                    val eh = if (el.height > 0f) el.height * h else (60f * s)
                    add(rect(ex, ey, ew, eh, el.background, 6f * s))
                }
                val fg = el.foreground
                when (el.type) {
                    OverlayElementType.DATA_VALUE -> {
                        val m = StudioMetric.byKey(el.metric)
                        val (v, unit) = m.display(wd, unitSpeed, unitDistance, unitTemp)
                        if (el.showLabel) { add(text(cx0, cy0 + 10f * s, 10f * s, dim(fg), 0, 0, m.label.uppercase())); cy0 += 14f * s }
                        add(text(cx0, cy0 + 30f * s, 30f * s, fg, 0, 1, v))
                        add(text(cx0 + textW(v, 30f * s) + 4f * s, cy0 + 30f * s, 13f * s, dim(fg), 0, 0, unit))
                    }
                    OverlayElementType.DATA_DIAL -> {
                        val m = StudioMetric.byKey(el.metric)
                        val frac = (m.raw(wd) / el.gaugeMax).coerceIn(0f, 1f)
                        val (v, _) = m.display(wd, unitSpeed, unitDistance, unitTemp)
                        val r = 36f * s
                        val ccx = cx0 + r; val ccy = cy0 + r
                        val semi = el.dialStyle == "SEMICIRCLE"
                        val start = if (semi) 180f else 135f
                        val sweep = if (semi) 180f else 270f
                        add(arc(ccx, ccy, r, start, sweep, withAlpha(fg, 0.18f), 7f * s))
                        val band = if (el.dialShowColorBand) {
                            if (frac >= el.dialRedThresholdPct / 100f) 0xFFE53935L
                            else if (frac >= el.dialOrangeThresholdPct / 100f) 0xFFFB8C00L else accentArgb
                        } else accentArgb
                        add(arc(ccx, ccy, r, start, sweep * frac, band, 7f * s))
                        add(text(ccx, ccy + 6f * s, 18f * s, fg, 1, 1, v))
                    }
                    OverlayElementType.DATA_BAR -> {
                        val m = StudioMetric.byKey(el.metric)
                        val frac = (m.raw(wd) / el.gaugeMax).coerceIn(0f, 1f)
                        val (v, unit) = m.display(wd, unitSpeed, unitDistance, unitTemp)
                        if (el.barShowValue) { add(text(cx0, cy0 + 14f * s, 14f * s, fg, 0, 1, "$v $unit")); cy0 += 20f * s }
                        add(bar(cx0, cy0, ew - 2 * pad, 8f * s, frac, withAlpha(fg, 0.2f), accentArgb))
                    }
                    OverlayElementType.TEXT ->
                        add(text(cx0, cy0 + 18f * s, 18f * s, fg, alignCode(el.textAlign), 1, renderTemplate(el.text, wd, wheelName, unitSpeed, unitDistance, unitTemp)))
                    OverlayElementType.WHEEL_NAME ->
                        add(text(cx0, cy0 + 18f * s, 18f * s, fg, 0, 1, wheelName.ifBlank { "EUC Planet" }))
                    OverlayElementType.APP_BADGE ->
                        add(text(cx0, cy0 + 16f * s, 15f * s, fg, 0, 1, "◎ EUC Planet" + if (el.badgeShowVersion) " v0.1" else ""))
                    OverlayElementType.CLOCK ->
                        add(text(cx0, cy0 + 24f * s, 24f * s, fg, 0, 1, if (el.clock24Hour) "--:--" else "--:-- -"))
                    // Heavy widgets (map/image/camera/graph/gforce) draw a label placeholder.
                    else -> add(text(cx0, cy0 + 12f * s, 11f * s, dim(fg), 0, 0, el.type.name.replace("_", " ")))
                }
            }
        }.toString()
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float, c: Long, r: Float) = buildJsonObject {
        put("op", "rect"); put("x", x); put("y", y); put("w", w); put("h", h); put("c", c); put("r", r)
    }
    private fun text(x: Float, y: Float, size: Float, c: Long, align: Int, bold: Int, t: String) = buildJsonObject {
        put("op", "text"); put("x", x); put("y", y); put("s", size); put("c", c); put("a", align); put("b", bold); put("t", t)
    }
    private fun arc(cx: Float, cy: Float, r: Float, a0: Float, sw: Float, c: Long, lw: Float) = buildJsonObject {
        put("op", "arc"); put("cx", cx); put("cy", cy); put("r", r); put("a0", a0); put("sw", sw); put("c", c); put("lw", lw)
    }
    private fun bar(x: Float, y: Float, w: Float, h: Float, frac: Float, bg: Long, fg: Long) = buildJsonObject {
        put("op", "bar"); put("x", x); put("y", y); put("w", w); put("h", h); put("frac", frac); put("bg", bg); put("fg", fg)
    }

    private fun dim(c: Long): Long = withAlpha(c, 0.7f)
    private fun withAlpha(c: Long, a: Float): Long {
        val rgb = c and 0xFFFFFFL
        val alpha = (((c ushr 24) and 0xFF) * a).roundToInt().coerceIn(0, 255).toLong()
        return (alpha shl 24) or rgb
    }
    private fun alignCode(a: String) = when (a) { "CENTER" -> 1; "END" -> 2; else -> 0 }
    private fun textW(t: String, size: Float) = t.length * size * 0.55f

    private fun renderTemplate(text: String, wd: WheelData, wheelName: String, us: String, ud: String, ut: String): String {
        var out = text
        fun sub(token: String, value: String) { out = out.replace("{$token}", value) }
        sub("speed", StudioMetric.SPEED.display(wd, us, ud, ut).first)
        sub("battery", wd.batteryPercent.toString())
        sub("temp", StudioMetric.TEMPERATURE.display(wd, us, ud, ut).first)
        sub("pwm", min(999, wd.pwm.roundToInt()).toString())
        sub("wheel", wheelName.ifBlank { "EUC Planet" })
        return out
    }
}
