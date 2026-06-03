package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.HudSessionState
import com.eried.eucplanet.hud.ui.HudUnits
import com.eried.eucplanet.hud.ui.parseHexColor

/**
 * Watts + range estimate. Big instantaneous power readout, 60-second
 * sparkline below, range estimate at the bottom honestly framed as
 * "based on last 60 s" so a rider doesn't trust it past the moment.
 *
 * Data flow: voltage * current = watts. We buffer the last ~300 samples
 * (60 s at 5 Hz) HUD-side so the sparkline + the range-estimate
 * denominator both see the same window.
 */
@Composable
fun PowerScreen(hud: HudState, session: HudSessionState) {
    val accent = parseHexColor(hud.accentArgb)
    val watts = (hud.voltage * hud.current)
    val isRegen = hud.current < 0f

    // Rolling 60s buffer + running average live in HudSessionState so
    // they survive screen switches. Reading session.wattsTick here
    // registers a Compose dependency on the tick counter -- Canvas
    // re-runs every time a new sample is pushed.
    val buf = session.wattsBuffer
    val tick = session.wattsTick
    @Suppress("UNUSED_EXPRESSION") tick
    val avgW: Float = if (session.avgWatts.isNaN()) watts else session.avgWatts

    // Range estimate: very rough.
    //   batteryPercent gives us "how much pack is left" (0-100).
    //   PACK_WH is a stand-in for "energy when the pack is full" --
    //   testers will tweak this once we wire up a per-wheel value.
    //   Then divide by recent avg watts to get hours, multiply by
    //   recent avg speed to get distance.
    val packWh = PACK_WH_DEFAULT
    val remainingWh = (hud.batteryPercent / 100f) * packWh
    val rangeKm: Float? = if (avgW > 10f && !hud.speedKmh.isNaN() && hud.speedKmh > 1f) {
        (remainingWh / avgW) * hud.speedKmh
    } else null

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = maxHeight.value
            val w = maxWidth.value
            Column(Modifier.fillMaxSize()) {
                // Big watt readout, signed.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "%+.0f".format(watts).removePrefix("+"),
                        color = if (isRegen) REGEN_COLOR else Color.White,
                        fontSize = (h * 0.22f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Text(
                        text = " W",
                        color = Color(0xFFB0B0B0),
                        fontSize = (h * 0.07f).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = (h * 0.04f).dp)
                    )
                }
                Text(
                    text = if (isRegen) "REGEN" else "CONSUMING",
                    color = if (isRegen) REGEN_COLOR.copy(alpha = 0.7f)
                        else Color.White.copy(alpha = 0.5f),
                    fontSize = (h * 0.04f).sp,
                    fontWeight = FontWeight.SemiBold
                )

                // 60s sparkline.
                Box(modifier = Modifier.fillMaxWidth().height((h * 0.18f).dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        if (buf.size < 2) return@Canvas
                        var minV = Float.POSITIVE_INFINITY
                        var maxV = Float.NEGATIVE_INFINITY
                        for (v in buf) {
                            if (v < minV) minV = v
                            if (v > maxV) maxV = v
                        }
                        val range = (maxV - minV).coerceAtLeast(50f)
                        val step = size.width / (buf.size - 1).coerceAtLeast(1)
                        val p = Path()
                        var i = 0
                        for (v in buf) {
                            val x = i * step
                            val y = size.height * (1f - ((v - minV) / range).coerceIn(0f, 1f))
                            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                            i++
                        }
                        drawPath(p, color = accent, style = Stroke(width = 2.dp.toPx()))
                    }
                }

                // Range estimate with honest caveat.
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = rangeKm?.let {
                                "%.0f".format(HudUnits.distance(it / 1f, hud.unitDistance) * 1f)
                            } ?: "--",
                            color = Color.White,
                            fontSize = (h * 0.18f).sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        Text(
                            text = " ${HudUnits.distanceSuffix(hud.unitDistance)} LEFT",
                            color = Color(0xFFB0B0B0),
                            fontSize = (h * 0.05f).sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = (h * 0.025f).dp)
                        )
                    }
                    Text(
                        text = "Based on last 60 s avg ${avgW.toInt()} W",
                        color = Color(0xFF808080),
                        fontSize = (h * 0.035f).sp
                    )
                }
            }
        }
    }
}

private const val PACK_WH_DEFAULT: Float = 1800f
private val REGEN_COLOR = Color(0xFF4CAF50)
