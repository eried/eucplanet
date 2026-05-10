package com.eried.eucplanet.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Watch-side port of the phone dashboard's SpeedGauge: 260° arc, accent-tinted
 * safe band, optional orange/red danger zones, ticks, and scale labels. Sized
 * by the parent — pass a Modifier with the desired width and aspect ratio.
 *
 * Kept self-contained (no MaterialTheme dependency) so it works regardless of
 * the Wear theme wrapper, and so the file can stay near 1:1 with the phone
 * version when fixes flow either direction.
 */
@Composable
fun SpeedGauge(
    speed: Float,
    maxSpeed: Float,
    imperial: Boolean,
    accent: Color,
    showColorBand: Boolean = true,
    orangeThresholdPct: Int = 65,
    redThresholdPct: Int = 85,
    trackColor: Color = Color(0xFF2A2A2A),
    dimColor: Color = Color(0xFF9AA0A6),
    /** When true, the gauge traces near the bezel and scale labels render
     *  inside the arc — meant for watch faces where the dial wraps the
     *  whole display and overlay UI lives in the center. */
    fullBleed: Boolean = false,
    /** Skips drawing the speed number + unit label so the caller can place
     *  them as composables (useful when overlaying buttons that need to
     *  share the inner area). */
    drawSpeedText: Boolean = true,
    modifier: Modifier = Modifier
) {
    val speedColor = when {
        speed > maxSpeed * 0.85f -> GaugeAccentRed
        speed > maxSpeed * 0.65f -> GaugeAccentOrange
        speed > maxSpeed * 0.4f -> GaugeAccentYellow
        else -> GaugeAccentGreen
    }
    val textMeasurer = rememberTextMeasurer()

    val displaySpeed = WatchUnits.speed(speed, imperial)
    val displayMax = WatchUnits.speed(maxSpeed, imperial)
    val maxInt = displayMax.toInt()
    val step = (maxInt / 3f).toInt().coerceAtLeast(5)
    val scaleLabels = listOf(0, step, step * 2, maxInt)
    val unitLabel = WatchUnits.speedUnit(LocalContext.current, imperial)

    Canvas(modifier = modifier) {
        val dim = size.minDimension
        val arcThickness = if (fullBleed) dim * 0.05f else dim * 0.07f
        // Full-bleed: arc hugs the bezel. Standard: arc inset to leave room
        // for labels outside.
        val arcInset = if (fullBleed) dim * 0.025f else dim * 0.06f
        val arcRadius = dim / 2f - arcThickness - arcInset
        val center = Offset(size.width / 2f, size.height * 0.52f)

        val startAngle = 140f
        val sweepTotal = 260f
        val safeMaxSpeed = if (maxSpeed <= 0f) 1f else maxSpeed
        val speedFraction = (speed / safeMaxSpeed).coerceIn(0f, 1f)
        val speedSweep = sweepTotal * speedFraction

        // Background arc
        drawArc(
            color = trackColor,
            startAngle = startAngle, sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = Size(arcRadius * 2, arcRadius * 2),
            style = Stroke(width = arcThickness, cap = StrokeCap.Round)
        )

        // Thin color band behind the arc: safe (green) > warning (yellow) >
        // danger (red). Hardcoded green for the safe zone so the gauge keeps
        // its safety semantics regardless of which accent the user picked
        // — using accent here meant a teal accent matched the safe zone but
        // a red accent made the whole band look like one long danger zone.
        if (showColorBand) {
            val bandThickness = arcThickness * 0.35f
            val bandRadius = arcRadius + arcThickness * 0.55f + bandThickness * 0.5f
            val bandAlpha = 0.65f
            val orangeFrac = (orangeThresholdPct / 100f).coerceIn(0.25f, 0.95f)
            val redFrac = (redThresholdPct / 100f).coerceIn(orangeFrac + 0.01f, 1f)
            val orangeStart = startAngle + sweepTotal * orangeFrac
            val orangeSweep = sweepTotal * (redFrac - orangeFrac)
            val redStart = startAngle + sweepTotal * redFrac
            val redSweep = sweepTotal * (1f - redFrac)
            val bandTopLeft = Offset(center.x - bandRadius, center.y - bandRadius)
            val bandSize = Size(bandRadius * 2, bandRadius * 2)
            drawArc(color = GaugeAccentGreen.copy(alpha = bandAlpha),
                startAngle = startAngle, sweepAngle = sweepTotal * orangeFrac,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
            drawArc(color = GaugeAccentYellow.copy(alpha = bandAlpha),
                startAngle = orangeStart, sweepAngle = orangeSweep,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
            drawArc(color = GaugeAccentRed.copy(alpha = bandAlpha),
                startAngle = redStart, sweepAngle = redSweep,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
        }

        // Speed arc
        if (speedSweep > 0.5f) {
            drawArc(
                color = speedColor,
                startAngle = startAngle, sweepAngle = speedSweep,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = arcThickness, cap = StrokeCap.Round)
            )
        }

        // Tick marks on outside of arc
        val tickOuter = arcRadius + arcThickness * 0.7f
        val tickInner = arcRadius + arcThickness * 0.1f
        for (i in 0..24) {
            val angle = startAngle + (sweepTotal * i / 24f)
            val rad = Math.toRadians(angle.toDouble())
            val isMajor = i % 8 == 0
            val isMinor = i % 4 == 0
            if (!isMajor && !isMinor) continue
            drawLine(
                color = if (isMajor) dimColor else dimColor.copy(alpha = 0.35f),
                start = Offset(center.x + tickInner * cos(rad).toFloat(), center.y + tickInner * sin(rad).toFloat()),
                end = Offset(center.x + tickOuter * cos(rad).toFloat(), center.y + tickOuter * sin(rad).toFloat()),
                strokeWidth = if (isMajor) 2.5f else 1.2f
            )
        }

        // Scale labels — only in standard mode. In full-bleed they collide
        // with overlay UI (buttons, battery row), and the speed number
        // already tells the user the live value.
        if (!fullBleed) {
            val labelRadius = arcRadius + arcThickness + dim * 0.08f
            for ((idx, label) in scaleLabels.withIndex()) {
                val angle = startAngle + (sweepTotal * idx / (scaleLabels.size - 1).toFloat())
                val rad = Math.toRadians(angle.toDouble())
                val measured = textMeasurer.measure(
                    "$label",
                    style = TextStyle(fontSize = (dim * 0.05f).sp, color = dimColor)
                )
                drawText(
                    measured,
                    topLeft = Offset(
                        center.x + labelRadius * cos(rad).toFloat() - measured.size.width / 2f,
                        center.y + labelRadius * sin(rad).toFloat() - measured.size.height / 2f
                    )
                )
            }
        }

        if (!drawSpeedText) return@Canvas

        // Speed number — dead center.
        val speedText = "%.0f".format(displaySpeed)
        val baseFactor = if (speedText.length >= 3) 0.17f else 0.2f
        val innerRadius = arcRadius - arcThickness * 0.5f
        val maxTextHalfWidth = innerRadius * 0.72f
        var speedFontFactor = baseFactor
        var speedMeasured = textMeasurer.measure(
            speedText,
            style = TextStyle(
                fontSize = (dim * speedFontFactor).sp,
                fontWeight = FontWeight.Bold,
                color = speedColor
            )
        )
        if (speedMeasured.size.width / 2f > maxTextHalfWidth) {
            val scale = maxTextHalfWidth / (speedMeasured.size.width / 2f)
            speedFontFactor *= scale
            speedMeasured = textMeasurer.measure(
                speedText,
                style = TextStyle(
                    fontSize = (dim * speedFontFactor).sp,
                    fontWeight = FontWeight.Bold,
                    color = speedColor
                )
            )
        }
        drawText(
            speedMeasured,
            topLeft = Offset(
                center.x - speedMeasured.size.width / 2f,
                center.y - speedMeasured.size.height / 2f - dim * 0.03f
            )
        )

        // Unit label below speed number
        val unitMeasured = textMeasurer.measure(
            unitLabel,
            style = TextStyle(fontSize = (dim * 0.06f).sp, color = dimColor)
        )
        drawText(
            unitMeasured,
            topLeft = Offset(
                center.x - unitMeasured.size.width / 2f,
                center.y + speedMeasured.size.height / 2f - dim * 0.01f
            )
        )
    }
}
