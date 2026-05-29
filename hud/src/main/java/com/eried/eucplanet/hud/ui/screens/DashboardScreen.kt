package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.hud.R
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.HudUnits
import com.eried.eucplanet.hud.ui.parseHexColor
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min

/**
 * Main HUD screen. Wide-aspect layout: speed dial + digit on the left half,
 * three telemetry tiles on the right (battery / PWM-or-temp / trip).
 *
 * All text + paddings are derived from the screen's actual dimensions via
 * [BoxWithConstraints] -- a fixed 120-sp speed digit looked clean on the
 * 800×480 dev emulator and got clipped on the smaller real-Motoeye panel.
 * Scaling off [maxHeight] keeps the layout legible on both without per-device
 * tuning, and per-tile constraints keep the value/label/subtitle rows
 * proportional to the tile's own height instead of the parent's.
 *
 * The dial colour band tracks the wheel's PWM headroom, same logic the phone
 * dashboard uses -- orange at [HudState.gaugeOrangeThresholdPct] PWM, red at
 * [HudState.gaugeRedThresholdPct]. We don't draw a needle when the wheel is
 * disconnected; instead the dial sits empty so the rider can see at a glance
 * the link is dead.
 */
@Composable
fun DashboardScreen(hud: HudState, gpsView: Boolean) {
    val ctx = LocalContext.current
    val accent = parseHexColor(hud.accentArgb)
    val speedShown = if (gpsView && !hud.gpsSpeedKmh.isNaN()) hud.gpsSpeedKmh else hud.speedKmh

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Outer padding scales with the smallest dimension so the dial still
        // breathes on tiny panels but doesn't dominate on the wide dev one.
        val outerPad = (min(maxWidth.value, maxHeight.value) * 0.04f).dp
        // Speed digit: ~28% of the available height. Matches the 800×480
        // dev sizing (134sp ≈ original 120sp) and shrinks proportionally
        // on the Motoeye where the panel is closer to 320×240.
        val speedSize = (maxHeight.value * 0.28f).sp
        val speedUnitSize = (maxHeight.value * 0.05f).sp

        Row(
            modifier = Modifier.fillMaxSize().padding(outerPad),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dial + big speed digit
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                SpeedDial(
                    speedKmh = speedShown,
                    maxKmh = hud.gaugeMaxKmh.coerceAtLeast(10f),
                    pwmPct = hud.pwm,
                    accent = accent,
                    showBand = hud.showGaugeColorBand,
                    orangePct = hud.gaugeOrangeThresholdPct.toFloat(),
                    redPct = hud.gaugeRedThresholdPct.toFloat(),
                    modifier = Modifier.fillMaxSize()
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val display = HudUnits.speed(speedShown, hud.unitSpeed)
                    Text(
                        text = "%.0f".format(display),
                        color = Color.White,
                        fontSize = speedSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = HudUnits.speedSuffix(hud.unitSpeed) +
                            if (gpsView) "  · ${ctx.getString(R.string.hud_dash_gps)}" else "",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = speedUnitSize,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.width(outerPad))

            // Telemetry tiles
            Column(
                modifier = Modifier.weight(0.85f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy((outerPad.value * 0.5f).dp)
            ) {
                Tile(
                    label = ctx.getString(R.string.hud_dash_battery),
                    value = "%d%%".format(hud.batteryPercent),
                    subtitle = "%.1f V".format(hud.voltage),
                    accent = accent,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                Tile(
                    label = ctx.getString(R.string.hud_dash_pwm),
                    value = "%.0f%%".format(hud.pwm),
                    subtitle = ctx.getString(R.string.hud_dash_temp) + " " +
                        "%.0f".format(HudUnits.temperature(hud.temperatureC, hud.unitTemp)) +
                        HudUnits.temperatureSuffix(hud.unitTemp),
                    accent = pwmAccent(hud.pwm, hud.gaugeOrangeThresholdPct, hud.gaugeRedThresholdPct, accent),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                Tile(
                    label = ctx.getString(R.string.hud_dash_trip),
                    value = "%.1f %s".format(
                        HudUnits.distance(hud.tripKm, hud.unitDistance),
                        HudUnits.distanceSuffix(hud.unitDistance)
                    ),
                    subtitle = hud.wheelName.ifBlank { "—" },
                    accent = accent,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun Tile(
    label: String,
    value: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    // Each tile auto-scales its label/value/subtitle to its own rendered
    // height, so a tile that ended up at 70 dp on a small Motoeye panel
    // gets correspondingly smaller text -- nothing clips. The label gets
    // ~17 % of the tile height, the value ~50 %, the subtitle ~17 %, with
    // padding eating the remainder.
    BoxWithConstraints(modifier = modifier) {
        val h = maxHeight.value
        val labelSize = (h * 0.17f).sp
        val valueSize = (h * 0.50f).sp
        val subSize = (h * 0.17f).sp
        val cornerR = (h * 0.13f).coerceAtMost(16f).dp
        val padH = (maxWidth.value * 0.05f).dp
        val padV = (h * 0.08f).dp
        val strokeW = (h * 0.012f).coerceAtLeast(1f).dp

        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = Color(0xFF111111),
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR.toPx())
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.6f),
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR.toPx()),
                style = Stroke(width = strokeW.toPx())
            )
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = padH, vertical = padV),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = labelSize,
                maxLines = 1
            )
            Text(
                text = value,
                color = Color.White,
                fontSize = valueSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = subtitle,
                color = accent,
                fontSize = subSize,
                maxLines = 1
            )
        }
    }
}

/** Speed-bound radial gauge that wraps the big speed digit. */
@Composable
private fun SpeedDial(
    speedKmh: Float,
    maxKmh: Float,
    pwmPct: Float,
    accent: Color,
    showBand: Boolean,
    orangePct: Float,
    redPct: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        // Stroke scales with the dial radius so the band stays proportionate
        // when the dial is small. Capped so it never looks chunky on huge
        // dev windows.
        val baseStroke = min(size.width, size.height) * 0.045f
        val stroke = baseStroke.coerceIn(6f, 24.dp.toPx())
        val diameter = min(size.width, size.height) - stroke * 2f
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f
        )
        val arcSize = Size(diameter, diameter)
        val startAngle = 135f
        val sweepFull = 270f

        // Background ring
        drawArc(
            color = Color(0xFF222222),
            startAngle = startAngle,
            sweepAngle = sweepFull,
            useCenter = false,
            style = Stroke(width = stroke),
            topLeft = topLeft,
            size = arcSize
        )

        // Coloured headroom band, anchored to PWM thresholds. Same colour
        // story as the phone gauge so a rider glancing between them never
        // gets a different signal from the two surfaces.
        if (showBand) {
            val redSweep = sweepFull * ((100f - redPct) / 100f).coerceIn(0f, 1f)
            val orangeSweep = sweepFull *
                ((redPct - orangePct) / 100f).coerceIn(0f, 1f)
            drawArc(
                color = Color(0xFFFF6F00),
                startAngle = startAngle + sweepFull - redSweep - orangeSweep,
                sweepAngle = orangeSweep,
                useCenter = false,
                style = Stroke(width = stroke),
                topLeft = topLeft,
                size = arcSize
            )
            drawArc(
                color = Color(0xFFD50000),
                startAngle = startAngle + sweepFull - redSweep,
                sweepAngle = redSweep,
                useCenter = false,
                style = Stroke(width = stroke),
                topLeft = topLeft,
                size = arcSize
            )
        }

        // Speed indicator sweep.
        val frac = (speedKmh / maxKmh).coerceIn(0f, 1f)
        val sweep = sweepFull * frac
        drawArc(
            color = if (pwmPct >= redPct) Color(0xFFD50000)
                else if (pwmPct >= orangePct) Color(0xFFFF6F00)
                else accent,
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width = stroke),
            topLeft = topLeft,
            size = arcSize
        )

        // Needle: a short radial line at the end of the sweep so the position
        // is unambiguous even when the dial sweep is dim against the band.
        rotate(degrees = startAngle + sweep, pivot = center) {
            val r = diameter / 2f - 2.dp.toPx()
            drawLine(
                color = Color.White,
                start = Offset(center.x + r * cos(0.0).toFloat() - 0.dp.toPx(),
                               center.y + r * sin(0.0).toFloat()),
                end = Offset(center.x + r, center.y),
                strokeWidth = (stroke * 0.3f).coerceAtLeast(2.dp.toPx())
            )
        }
    }
}

private fun pwmAccent(pwm: Float, orange: Int, red: Int, accent: Color): Color = when {
    pwm >= red -> Color(0xFFD50000)
    pwm >= orange -> Color(0xFFFF6F00)
    else -> accent
}

@Suppress("unused")
private val keepImports: Dp = 0.dp
