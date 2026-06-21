package com.eried.eucplanet.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.hudlink.OverlayElement
import com.eried.eucplanet.hudlink.OverlayElementType
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Live preview of one [OverlayElement] in the Overlay Studio, driven by [wd].
 * Faithful-ish to the HUD's renderers for the lightweight widgets; the heavy
 * ones the HUD owns (MAP / IMAGE / FLOATING_CAMERA) draw as a labelled
 * placeholder here since the HUD — not the phone — renders them for real.
 */
@Composable
internal fun StudioElementView(
    el: OverlayElement,
    wd: WheelData,
    wheelName: String,
    accent: Color,
    unitSpeed: String,
    unitDistance: String,
    unitTemp: String,
    modifier: Modifier = Modifier,
) {
    val fg = Color(el.foreground)
    val bg = Color(el.background)
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 6.dp)) {
        when (el.type) {
            OverlayElementType.DATA_VALUE -> {
                val m = StudioMetric.byKey(el.metric)
                val (v, unit) = m.display(wd, unitSpeed, unitDistance, unitTemp)
                Column {
                    if (el.showLabel) Text(m.label.uppercase(), color = fg.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(v, color = fg, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(" $unit", color = fg.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
            OverlayElementType.DATA_DIAL -> {
                val m = StudioMetric.byKey(el.metric)
                val frac = (m.raw(wd) / el.gaugeMax).coerceIn(0f, 1f)
                val (v, _) = m.display(wd, unitSpeed, unitDistance, unitTemp)
                Box(contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(72.dp)) {
                        val sweep = if (el.dialStyle == "SEMICIRCLE") 0.5f else 0.75f
                        val start = if (el.dialStyle == "SEMICIRCLE") 180f else 135f
                        drawArc(fg.copy(alpha = 0.18f), start, sweep * 360f, false, style = Stroke(7f, cap = StrokeCap.Round))
                        val band = if (el.dialShowColorBand) {
                            if (frac >= el.dialRedThresholdPct / 100f) Color(0xFFE53935)
                            else if (frac >= el.dialOrangeThresholdPct / 100f) Color(0xFFFB8C00) else accent
                        } else accent
                        drawArc(band, start, sweep * 360f * frac, false, style = Stroke(7f, cap = StrokeCap.Round))
                    }
                    Text(v, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            OverlayElementType.DATA_BAR -> {
                val m = StudioMetric.byKey(el.metric)
                val frac = (m.raw(wd) / el.gaugeMax).coerceIn(0f, 1f)
                val (v, unit) = m.display(wd, unitSpeed, unitDistance, unitTemp)
                Column {
                    if (el.barShowValue) Text("$v $unit", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(fg.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(accent))
                    }
                }
            }
            OverlayElementType.DATA_GRAPH -> {
                val m = StudioMetric.byKey(el.metric)
                Column {
                    if (el.showLabel) Text(m.label.uppercase(), color = fg.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                        // Stylised line — the HUD draws the real rolling history.
                        val pts = listOf(0.6f, 0.45f, 0.7f, 0.4f, 0.55f, 0.3f, 0.5f)
                        val dx = size.width / (pts.size - 1)
                        var prev = Offset(0f, size.height * pts[0])
                        for (i in 1 until pts.size) {
                            val cur = Offset(dx * i, size.height * pts[i])
                            drawLine(accent, prev, cur, strokeWidth = 3f, cap = StrokeCap.Round)
                            prev = cur
                        }
                    }
                }
            }
            OverlayElementType.TEXT -> Text(renderTemplate(el.text, wd, wheelName, unitSpeed, unitDistance, unitTemp), color = fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            OverlayElementType.WHEEL_NAME -> Text(wheelName.ifBlank { "EUC Planet" }, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OverlayElementType.APP_BADGE -> {
                if (el.badgeStacked) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("◎", color = accent, fontSize = 22.sp)
                        Text("EUC Planet" + if (el.badgeShowVersion) " v0.1" else "", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("◎ ", color = accent, fontSize = 18.sp)
                        Text("EUC Planet" + if (el.badgeShowVersion) " v0.1" else "", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            OverlayElementType.CLOCK -> when (el.clockStyle) {
                "ANALOG" -> Canvas(Modifier.size(56.dp)) {
                    val r = min(size.width, size.height) / 2f - 3f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(fg.copy(alpha = 0.25f), r, c, style = Stroke(3f))
                    drawLine(fg, c, Offset(c.x, c.y - r * 0.6f), 4f, StrokeCap.Round) // 12 o'clock-ish
                    drawLine(accent, c, Offset(c.x + r * 0.7f, c.y), 3f, StrokeCap.Round)
                }
                "STOPWATCH" -> Text("00:00.0", color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                else -> Column {
                    Text(if (el.clock24Hour) "10:08" else "10:08 AM", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (el.clockShowDate) Text("Mon 21 Jun", color = fg.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }
            OverlayElementType.G_FORCE -> Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(64.dp)) {
                    val r = min(size.width, size.height) / 2f - 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(fg.copy(alpha = 0.2f), r, c, style = Stroke(2f))
                    drawCircle(fg.copy(alpha = 0.15f), r * 0.5f, c, style = Stroke(1.5f))
                    drawLine(fg.copy(alpha = 0.2f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
                    drawLine(fg.copy(alpha = 0.2f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
                    drawCircle(accent, 4f, Offset(c.x + r * 0.3f, c.y - r * 0.2f))
                }
            }
            OverlayElementType.MAP -> Placeholder("MAP", "${el.mapStyle.lowercase()} · drawn on HUD", fg, accent)
            OverlayElementType.IMAGE -> Placeholder("IMAGE", "set on Android app", fg, accent)
            OverlayElementType.FLOATING_CAMERA -> Placeholder("CAMERA", el.cameraKey.lowercase(), fg, accent)
        }
    }
}

@Composable
private fun Placeholder(title: String, sub: String, fg: Color, accent: Color) {
    Column(Modifier.fillMaxWidth().height(54.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = fg.copy(alpha = 0.7f), fontSize = 9.sp)
    }
}

/** Resolve {speed}/{battery}/{wheel}-style tokens in a TEXT element's template. */
private fun renderTemplate(text: String, wd: WheelData, wheelName: String, us: String, ud: String, ut: String): String {
    var out = text
    fun sub(token: String, value: String) { out = out.replace("{$token}", value) }
    sub("speed", StudioMetric.SPEED.display(wd, us, ud, ut).first)
    sub("battery", wd.batteryPercent.toString())
    sub("temp", StudioMetric.TEMPERATURE.display(wd, us, ud, ut).first)
    sub("pwm", wd.pwm.roundToInt().toString())
    sub("voltage", StudioMetric.VOLTAGE.display(wd, us, ud, ut).first)
    sub("trip", StudioMetric.TRIP.display(wd, us, ud, ut).first)
    sub("wheel", wheelName.ifBlank { "EUC Planet" })
    return out
}

/** ARGB Long → Compose Color helper. */
internal fun Long.toOverlayColor(): Color = Color(this)
