package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.HudUnits
import com.eried.eucplanet.hud.ui.parseHexColor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.PI

/**
 * Riding-data overlay screen. Four panels:
 *  - Top-left: artificial horizon driven by wheel pitch + roll.
 *  - Top-right: PWM sparkline over the last ~60 seconds.
 *  - Bottom-left: G-force vector (lateral derived from roll angle,
 *    longitudinal derived from speed change over the last second).
 *  - Bottom-right: altitude sparkline + numeric climb rate (m/s).
 *
 * All four panels keep their own short rolling buffers in remembered
 * state. The phone only ships the instantaneous value of each metric --
 * the HUD does the history.
 */
@Composable
fun TelemetryScreen(hud: HudState) {
    val accent = parseHexColor(hud.accentArgb)

    // Rolling buffers. Sized for a 60s window at the 5 Hz publish rate the
    // phone uses, with a touch of headroom.
    val pwmBuf = remember { java.util.ArrayDeque<Float>(360) }
    val altBuf = remember { java.util.ArrayDeque<Float>(360) }
    var lastSpeedKmh by remember { mutableStateOf(hud.speedKmh) }
    var lastSpeedTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var longitudinalG by remember { mutableStateOf(0f) }

    LaunchedEffect(hud.timestampMs) {
        // Pump current values into the buffers each frame. Cap at 360
        // samples (~72 s at 5 Hz) so the deque stays bounded.
        if (!hud.pwm.isNaN()) {
            pwmBuf.addLast(hud.pwm)
            while (pwmBuf.size > 360) pwmBuf.removeFirst()
        }
        if (!hud.gpsAltitudeM.isNaN()) {
            altBuf.addLast(hud.gpsAltitudeM)
            while (altBuf.size > 360) altBuf.removeFirst()
        }
        // Longitudinal G: ΔSpeed / Δtime, converted to m/s² then divided
        // by g (9.81 m/s²). Lightly smoothed (0.7 prev + 0.3 new) so an
        // occasional BLE blip doesn't make the arrow flick.
        val nowMs = hud.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val dtSec = (nowMs - lastSpeedTimeMs) / 1000f
        if (dtSec > 0f) {
            val dvMs = (hud.speedKmh - lastSpeedKmh) / 3.6f
            val accelG = (dvMs / dtSec) / 9.81f
            longitudinalG = longitudinalG * 0.7f + accelG * 0.3f
            lastSpeedKmh = hud.speedKmh
            lastSpeedTimeMs = nowMs
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        val w = maxWidth.value
        val h = maxHeight.value
        val gap = (min(w, h) * 0.02f).dp
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                Panel(modifier = Modifier.weight(1f).fillMaxHeight(), accent = accent, title = "ATTITUDE") {
                    ArtificialHorizon(
                        rollDeg = hud.wheelRollDeg,
                        pitchDeg = hud.wheelPitchDeg,
                        accent = accent
                    )
                }
                Panel(modifier = Modifier.weight(1f).fillMaxHeight(), accent = accent, title = "PWM") {
                    Sparkline(
                        samples = pwmBuf,
                        min = 0f,
                        max = 100f,
                        currentLabel = "%.0f%%".format(hud.pwm),
                        lineColor = pwmColor(hud.pwm, hud.gaugeOrangeThresholdPct, hud.gaugeRedThresholdPct, accent),
                        accent = accent
                    )
                }
            }
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                Panel(modifier = Modifier.weight(1f).fillMaxHeight(), accent = accent, title = "G / POWER") {
                    GAndPowerPanel(
                        longitudinalG = longitudinalG,
                        lateralG = lateralGFromRoll(hud.wheelRollDeg),
                        currentAmps = hud.current,
                        voltage = hud.voltage,
                        accent = accent
                    )
                }
                Panel(modifier = Modifier.weight(1f).fillMaxHeight(), accent = accent, title = "ALTITUDE") {
                    AltitudePanel(
                        samples = altBuf,
                        currentM = hud.gpsAltitudeM,
                        accent = accent
                    )
                }
            }
        }
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    accent: Color,
    title: String,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF101010))
    ) {
        val outerH = maxHeight.value
        val titleSize = (outerH * 0.10f).coerceAtMost(14f).sp
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            Text(
                text = title,
                color = accent.copy(alpha = 0.7f),
                fontSize = titleSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopStart)
            )
            Box(
                modifier = Modifier.fillMaxSize().padding(top = (outerH * 0.14f).dp),
                content = { content() }
            )
        }
    }
}

/** Wheel-grade artificial horizon. Sky/ground bands tilt with roll;
 *  horizon line offsets vertically with pitch. Roll degrees written at
 *  the top-left for read-off precision. */
@Composable
private fun ArtificialHorizon(rollDeg: Float, pitchDeg: Float, accent: Color) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            // Pitch maps to vertical offset; 1° = 1% of canvas height.
            val pitchOffset = (pitchDeg / 45f) * (size.height / 2f)

            rotate(degrees = rollDeg, pivot = Offset(cx, cy)) {
                // Sky (top half, blueish gray)
                drawRect(
                    color = Color(0xFF1F3C4D),
                    topLeft = Offset(-size.width, -size.height + cy + pitchOffset),
                    size = Size(size.width * 3f, size.height * 2f)
                )
                // Ground (bottom half, brownish)
                drawRect(
                    color = Color(0xFF4D331F),
                    topLeft = Offset(-size.width, cy + pitchOffset),
                    size = Size(size.width * 3f, size.height * 2f)
                )
                // Horizon line
                drawLine(
                    color = Color.White,
                    start = Offset(-size.width, cy + pitchOffset),
                    end = Offset(size.width * 2f, cy + pitchOffset),
                    strokeWidth = 2.dp.toPx()
                )
                // Pitch ladder ticks (±10°, ±20°, ±30°)
                listOf(-30, -20, -10, 10, 20, 30).forEach { deg ->
                    val y = cy + pitchOffset - (deg / 45f) * (size.height / 2f)
                    val tickW = size.width * 0.15f
                    drawLine(
                        color = Color.White.copy(alpha = 0.6f),
                        start = Offset(cx - tickW, y),
                        end = Offset(cx + tickW, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
            // Fixed nose marker (drawn over the rotated world)
            val noseW = size.width * 0.18f
            val noseH = size.height * 0.025f
            drawRect(
                color = accent,
                topLeft = Offset(cx - noseW, cy - noseH / 2f),
                size = Size(noseW * 2f, noseH)
            )
        }
        Text(
            text = "%+.0f°".format(rollDeg),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

/** Minimalist sparkline. samples is a rolling deque, oldest first. */
@Composable
private fun Sparkline(
    samples: java.util.ArrayDeque<Float>,
    min: Float,
    max: Float,
    currentLabel: String,
    lineColor: Color,
    accent: Color
) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (samples.size < 2) return@Canvas
            val pad = 4f
            val w = size.width - pad * 2f
            val h = size.height - pad * 2f
            val range = (max - min).coerceAtLeast(0.0001f)
            val step = w / (samples.size - 1).coerceAtLeast(1)
            val path = Path()
            samples.forEachIndexed { i, v ->
                val x = pad + i * step
                val norm = ((v - min) / range).coerceIn(0f, 1f)
                val y = pad + h * (1f - norm)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
        }
        Text(
            text = currentLabel,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

/** Combined G-vector + regen/consumption bar.
 *  Top half: 2D circle with an arrow indicating combined G direction.
 *  Bottom half: horizontal split bar (regen left, consumption right).
 */
@Composable
private fun GAndPowerPanel(
    longitudinalG: Float,
    lateralG: Float,
    currentAmps: Float,
    voltage: Float,
    accent: Color
) {
    Column(Modifier.fillMaxSize()) {
        // G-circle
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = min(size.width, size.height) / 2f - 4f
                // Background rings
                listOf(0.33f, 0.66f, 1.0f).forEach { f ->
                    drawCircle(
                        color = Color.White.copy(alpha = 0.15f),
                        radius = r * f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                // Crosshair
                drawLine(Color.White.copy(alpha = 0.2f),
                    Offset(cx - r, cy), Offset(cx + r, cy), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.2f),
                    Offset(cx, cy - r), Offset(cx, cy + r), 1.dp.toPx())
                // G vector
                val maxG = 1.5f
                val gx = (lateralG / maxG).coerceIn(-1f, 1f)
                val gy = -(longitudinalG / maxG).coerceIn(-1f, 1f)
                val tip = Offset(cx + gx * r, cy + gy * r)
                drawLine(
                    color = accent,
                    start = Offset(cx, cy),
                    end = tip,
                    strokeWidth = 3.dp.toPx()
                )
                drawCircle(color = accent, radius = 4.dp.toPx(), center = tip)
            }
        }
        // Regen/consumption bar
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            Canvas(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                val barH = 8.dp.toPx()
                val cy = size.height / 2f
                val cx = size.width / 2f
                // Background
                drawRect(
                    color = Color(0xFF222222),
                    topLeft = Offset(0f, cy - barH / 2f),
                    size = Size(size.width, barH)
                )
                // Centre tick
                drawLine(
                    color = Color.White.copy(alpha = 0.4f),
                    start = Offset(cx, cy - barH / 2f - 2f),
                    end = Offset(cx, cy + barH / 2f + 2f),
                    strokeWidth = 1.dp.toPx()
                )
                // Bar: positive amps = consumption (right, green), negative = regen (left, blue/orange).
                val absA = abs(currentAmps).coerceAtMost(100f)
                val frac = absA / 100f
                val barLen = (size.width / 2f) * frac
                if (currentAmps >= 0f) {
                    drawRect(
                        color = Color(0xFF4CAF50),
                        topLeft = Offset(cx, cy - barH / 2f),
                        size = Size(barLen, barH)
                    )
                } else {
                    drawRect(
                        color = Color(0xFFFFA726),
                        topLeft = Offset(cx - barLen, cy - barH / 2f),
                        size = Size(barLen, barH)
                    )
                }
            }
        }
        // Numeric: combined G + amps/W
        val wattage = (currentAmps * voltage).toInt()
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "%+.1fg".format(magnitude(longitudinalG, lateralG)),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${wattage} W",
                color = if (wattage >= 0) Color(0xFF4CAF50) else Color(0xFFFFA726),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Altitude sparkline + climb-rate number. Climb rate is derived from
 *  the first and last samples in the buffer with a basic linear fit. */
@Composable
private fun AltitudePanel(
    samples: java.util.ArrayDeque<Float>,
    currentM: Float,
    accent: Color
) {
    val climbRateMps = remember(samples.size) {
        if (samples.size < 2) 0f
        else (samples.last() - samples.first()) / (samples.size / 5f) // 5 Hz
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()) {
                if (samples.size < 2) return@Canvas
                val pad = 4f
                val w = size.width - pad * 2f
                val h = size.height - pad * 2f
                val sMin = samples.min()
                val sMax = samples.max()
                val range = (sMax - sMin).coerceAtLeast(0.5f)
                val step = w / (samples.size - 1).coerceAtLeast(1)
                val path = Path()
                samples.forEachIndexed { i, v ->
                    val x = pad + i * step
                    val norm = ((v - sMin) / range).coerceIn(0f, 1f)
                    val y = pad + h * (1f - norm)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = accent, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (currentM.isNaN()) "--" else "%.0f m".format(currentM),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "%+.1f m/s".format(climbRateMps),
                color = if (climbRateMps >= 0f) Color(0xFF4CAF50) else Color(0xFFFFA726),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun magnitude(a: Float, b: Float): Float = kotlin.math.sqrt(a * a + b * b)

/** Lateral G proxy from wheel roll angle. tan(rollRad) approximates the
 *  side load ratio when leaning into a steady turn. Capped at 1.5g so
 *  edge-case sensor noise doesn't pin the indicator off-scale. */
private fun lateralGFromRoll(rollDeg: Float): Float {
    if (rollDeg == 0f) return 0f
    val rad = rollDeg * PI.toFloat() / 180f
    return tan(rad).coerceIn(-1.5f, 1.5f)
}

private fun pwmColor(pwm: Float, orange: Int, red: Int, accent: Color): Color = when {
    pwm >= red -> Color(0xFFD50000)
    pwm >= orange -> Color(0xFFFF6F00)
    else -> accent
}
