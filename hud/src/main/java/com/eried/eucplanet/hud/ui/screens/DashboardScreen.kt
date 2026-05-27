package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
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
 * The dial colour band tracks the wheel's PWM headroom, same logic the phone
 * dashboard uses — orange at [HudState.gaugeOrangeThresholdPct] PWM, red at
 * [HudState.gaugeRedThresholdPct]. We don't draw a needle when the wheel is
 * disconnected; instead the dial sits empty so the rider can see at a glance
 * the link is dead.
 */
@Composable
fun DashboardScreen(hud: HudState, gpsView: Boolean) {
    val ctx = LocalContext.current
    val accent = parseHexColor(hud.accentArgb)
    val speedShown = if (gpsView && !hud.gpsSpeedKmh.isNaN()) hud.gpsSpeedKmh else hud.speedKmh

    Row(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = HudUnits.speedSuffix(hud.unitSpeed) +
                        if (gpsView) "  · ${ctx.getString(R.string.hud_dash_gps)}" else "",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 18.sp
                )
            }
        }

        Spacer(Modifier.width(24.dp))

        // Telemetry tiles
        Column(
            modifier = Modifier.weight(0.85f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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

@Composable
private fun Tile(
    label: String,
    value: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = Color(0xFF111111),
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.6f),
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            Text(value, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = accent, fontSize = 14.sp)
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
        val stroke = 14.dp.toPx()
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
                strokeWidth = 4.dp.toPx()
            )
        }
    }
}

private fun pwmAccent(pwm: Float, orange: Int, red: Int, accent: Color): Color = when {
    pwm >= red -> Color(0xFFD50000)
    pwm >= orange -> Color(0xFFFF6F00)
    else -> accent
}
