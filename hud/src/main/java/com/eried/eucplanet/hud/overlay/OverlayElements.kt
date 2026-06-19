package com.eried.eucplanet.hud.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.hud.protocol.OverlayElement
import com.eried.eucplanet.hud.protocol.OverlayElementType
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.PI

/**
 * HUD-side port of the phone's StudioOverlayElements renderers. Identical
 * geometry / typography / colour story so a preset built in the Overlay
 * Studio reads pixel-identical when the rider mirrors it to the HUD's
 * Custom screen.
 *
 * Differences from the phone source:
 *   - APP_BADGE shows a wordmark only (no app icon resource on the HUD).
 *   - CLOCK ANALOG / STOPWATCH styles fall back to a digital readout
 *     (those need shared AnalogClock / SevenSegmentDisplay composables
 *     that are not yet ported).
 *   - FloatingCamera + Image element types are NOT rendered (the HUD has
 *     dedicated Camera and Custom + camera screens already; preset
 *     images would need base64 + chroma key, which is heavier than the
 *     value).
 *   - DATA_GRAPH samples come from a per-element rolling buffer the HUD
 *     maintains itself (the phone has replay history, the HUD doesn't).
 */
@Composable
fun OverlayElementRenderer(element: OverlayElement, data: StudioElementData) {
    when (element.type) {
        OverlayElementType.WHEEL_NAME -> WheelNameElement(element, data)
        OverlayElementType.APP_BADGE -> AppBadgeElement(element)
        OverlayElementType.TEXT -> FreeTextElement(element, data)
        OverlayElementType.DATA_VALUE -> DataValueElement(element, data)
        OverlayElementType.DATA_GRAPH -> DataGraphElement(element, data)
        OverlayElementType.DATA_DIAL -> DataDialElement(element, data)
        OverlayElementType.DATA_BAR -> DataBarElement(element, data)
        OverlayElementType.CLOCK -> ClockElement(element, data)
        OverlayElementType.G_FORCE -> GForceElement(element, data)
        OverlayElementType.MAP -> MapElement(element, data)
        OverlayElementType.RADAR -> RadarElement(element, data)
        // FLOATING_CAMERA + IMAGE skipped on the HUD; silently no-op.
        else -> Unit
    }
}

// ---------- DATA_VALUE --------------------------------------------------

@Composable
private fun DataValueElement(element: OverlayElement, data: StudioElementData) {
    val context = LocalContext.current
    val metric = StudioMetric.fromKey(element.metric)
    BoxWithConstraints(
        Modifier
            .background(Color(element.background), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val w = maxWidth.value
        val align = when (element.textAlign) {
            "CENTER" -> Alignment.CenterHorizontally
            "END" -> Alignment.End
            else -> Alignment.Start
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
            if (element.showLabel) {
                Text(
                    text = metric.displayName().uppercase(),
                    color = Color(element.foreground).copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (w * 0.09f).coerceIn(9f, 26f).sp,
                    maxLines = 1
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                val unit = metric.unitText(
                    context, data.speedUnit, data.distanceUnit, data.tempUnit
                )
                val valueText = metric.formatted(
                    data.wheelData, data.speedUnit, data.distanceUnit, data.tempUnit
                )
                val unitOnLeft = element.unitPosition == "LEFT"
                if (unit.isNotEmpty() && unitOnLeft) {
                    Text(
                        text = "$unit ",
                        color = Color(element.foreground).copy(alpha = 0.75f),
                        fontSize = (w * 0.12f).coerceIn(9f, 40f).sp,
                        modifier = Modifier.padding(bottom = (w * 0.04f).dp),
                        maxLines = 1
                    )
                }
                Text(
                    text = valueText,
                    color = Color(element.foreground),
                    fontWeight = FontWeight.Bold,
                    fontSize = (w * 0.34f).coerceIn(18f, 120f).sp,
                    maxLines = 1
                )
                if (unit.isNotEmpty() && !unitOnLeft) {
                    Text(
                        text = " $unit",
                        color = Color(element.foreground).copy(alpha = 0.75f),
                        fontSize = (w * 0.12f).coerceIn(9f, 40f).sp,
                        modifier = Modifier.padding(bottom = (w * 0.04f).dp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ---------- DATA_DIAL ---------------------------------------------------

@Composable
private fun DataDialElement(element: OverlayElement, data: StudioElementData) {
    val context = LocalContext.current
    val metric = StudioMetric.fromKey(element.metric)
    val value = metric.displayValue(
        data.wheelData, data.speedUnit, data.distanceUnit, data.tempUnit
    )
    val fraction = (value / element.gaugeMax.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val fill = Color(element.foreground)
    val track = fill.copy(alpha = 0.2f)
    val bg = Color(element.background)
    val isSemi = element.dialStyle == "SEMICIRCLE"
    // Optional 3-colour band that replaces the flat track when the
    // preset enables it. Hex values mirror the phone-side AccentGreen
    // / AccentOrange / AccentRed in ui.theme.Color.kt so the dial reads
    // the same on the prism as it does in the Studio preview on the
    // phone. Alpha=0.55 so the band sits as a backing tint rather than
    // competing with the foreground arc the rider is actually tracking.
    val showBand = element.dialShowColorBand
    val orangeFrac = (element.dialOrangeThresholdPct / 100f).coerceIn(0f, 1f)
    val redFrac = (element.dialRedThresholdPct / 100f).coerceIn(orangeFrac, 1f)
    // FULL alpha colours; the band + end caps are composited into an
    // offscreen layer at 0.55 so overlap between the arc and end-cap
    // disc doesn't compound into a darker half-circle when element
    // opacity is < 1. Inside the layer every pixel is fully opaque.
    val bandSafe = Color(0xFF66BB6A)
    val bandWarn = Color(0xFFFFA726)
    val bandDanger = Color(0xFFEF5350)

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .then(if (isSemi) Modifier else Modifier.aspectRatio(1f)),
        contentAlignment = if (isSemi) Alignment.BottomCenter else Alignment.Center
    ) {
        val dialW: Dp = maxWidth
        val stripDp: Dp = if (isSemi) (dialW * 0.12f).coerceIn(10.dp, 24.dp) else 0.dp
        val targetH: Dp = if (isSemi) dialW / 2f + stripDp else dialW
        val w = dialW.value

        Canvas(Modifier.fillMaxWidth().height(targetH)) {
            val pad = 12.dp.toPx()
            if (isSemi) {
                val sw = size.width
                val sh = size.height
                val r = sw / 2f
                val cx = sw / 2f
                val domeBottom = r
                val stripH = (sh - r).coerceAtLeast(0f)
                val cornerR = stripH.coerceAtMost(12.dp.toPx())
                val bgPath = Path().apply {
                    moveTo(0f, domeBottom)
                    arcTo(
                        rect = Rect(0f, 0f, sw, sw),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = 180f,
                        forceMoveTo = false
                    )
                    lineTo(sw, sh - cornerR)
                    arcTo(
                        rect = Rect(sw - 2f * cornerR, sh - 2f * cornerR, sw, sh),
                        startAngleDegrees = 0f, sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(cornerR, sh)
                    arcTo(
                        rect = Rect(0f, sh - 2f * cornerR, 2f * cornerR, sh),
                        startAngleDegrees = 90f, sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    close()
                }
                drawPath(bgPath, color = bg)

                val strokeW = (r * 0.18f).coerceAtMost(r * 0.5f)
                val progR = r - pad - strokeW / 2f
                val cy = domeBottom
                val progRect = Rect(cx - progR, cy - progR, cx + progR, cy + progR)
                if (showBand) {
                    drawContext.canvas.saveLayer(
                        androidx.compose.ui.geometry.Rect(
                            0f, 0f, size.width, size.height
                        ),
                        androidx.compose.ui.graphics.Paint().apply { alpha = 0.55f }
                    )
                    val bandStroke = Stroke(width = strokeW, cap = StrokeCap.Butt)
                    drawArc(
                        color = bandSafe, startAngle = 180f,
                        sweepAngle = 180f * orangeFrac, useCenter = false,
                        topLeft = progRect.topLeft, size = progRect.size, style = bandStroke
                    )
                    drawArc(
                        color = bandWarn, startAngle = 180f + 180f * orangeFrac,
                        sweepAngle = 180f * (redFrac - orangeFrac), useCenter = false,
                        topLeft = progRect.topLeft, size = progRect.size, style = bandStroke
                    )
                    drawArc(
                        color = bandDanger, startAngle = 180f + 180f * redFrac,
                        sweepAngle = 180f * (1f - redFrac), useCenter = false,
                        topLeft = progRect.topLeft, size = progRect.size, style = bandStroke
                    )
                    val capR = strokeW / 2f
                    drawCircle(
                        color = bandSafe, radius = capR,
                        center = Offset(cx - progR, cy)
                    )
                    drawCircle(
                        color = bandDanger, radius = capR,
                        center = Offset(cx + progR, cy)
                    )
                    drawContext.canvas.restore()
                } else {
                    drawArc(
                        color = track,
                        startAngle = 180f, sweepAngle = 180f, useCenter = false,
                        topLeft = progRect.topLeft, size = progRect.size,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                }
                // Fill stroke is narrower than the band stroke so a rim of
                // colour stays visible on both sides of the needle arc.
                val fillStrokeW = if (showBand) strokeW * 0.55f else strokeW
                drawArc(
                    color = fill,
                    startAngle = 180f, sweepAngle = 180f * fraction, useCenter = false,
                    topLeft = progRect.topLeft, size = progRect.size,
                    style = Stroke(width = fillStrokeW, cap = StrokeCap.Round)
                )
            } else {
                val strokeW = size.minDimension * 0.12f
                val side = size.minDimension - strokeW - pad
                val topLeft = Offset(
                    (size.width - side) / 2f,
                    (size.height - side) / 2f
                )
                drawArc(
                    color = bg,
                    startAngle = 0f, sweepAngle = 360f, useCenter = true,
                    topLeft = Offset(
                        (size.width - size.minDimension) / 2f,
                        (size.height - size.minDimension) / 2f
                    ),
                    size = Size(size.minDimension, size.minDimension)
                )
                val arcSize = Size(side, side)
                if (showBand) {
                    drawContext.canvas.saveLayer(
                        androidx.compose.ui.geometry.Rect(
                            0f, 0f, size.width, size.height
                        ),
                        androidx.compose.ui.graphics.Paint().apply { alpha = 0.55f }
                    )
                    val bandStroke = Stroke(width = strokeW, cap = StrokeCap.Butt)
                    drawArc(
                        color = bandSafe, startAngle = 135f,
                        sweepAngle = 270f * orangeFrac, useCenter = false,
                        topLeft = topLeft, size = arcSize, style = bandStroke
                    )
                    drawArc(
                        color = bandWarn, startAngle = 135f + 270f * orangeFrac,
                        sweepAngle = 270f * (redFrac - orangeFrac), useCenter = false,
                        topLeft = topLeft, size = arcSize, style = bandStroke
                    )
                    drawArc(
                        color = bandDanger, startAngle = 135f + 270f * redFrac,
                        sweepAngle = 270f * (1f - redFrac), useCenter = false,
                        topLeft = topLeft, size = arcSize, style = bandStroke
                    )
                    val capR = strokeW / 2f
                    val ac = Offset(topLeft.x + side / 2f, topLeft.y + side / 2f)
                    val ar = side / 2f
                    val rad135 = (135.0 * PI / 180.0)
                    val rad45 = (45.0 * PI / 180.0)
                    drawCircle(
                        color = bandSafe, radius = capR,
                        center = Offset(
                            ac.x + ar * cos(rad135).toFloat(),
                            ac.y + ar * sin(rad135).toFloat()
                        )
                    )
                    drawCircle(
                        color = bandDanger, radius = capR,
                        center = Offset(
                            ac.x + ar * cos(rad45).toFloat(),
                            ac.y + ar * sin(rad45).toFloat()
                        )
                    )
                    drawContext.canvas.restore()
                } else {
                    drawArc(
                        color = track,
                        startAngle = 135f, sweepAngle = 270f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                }
                val fillStrokeW = if (showBand) strokeW * 0.55f else strokeW
                drawArc(
                    color = fill,
                    startAngle = 135f, sweepAngle = 270f * fraction, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = fillStrokeW, cap = StrokeCap.Round)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (isSemi) Modifier.padding(bottom = stripDp / 2f) else Modifier
        ) {
            Text(
                text = metric.formatted(
                    data.wheelData, data.speedUnit, data.distanceUnit, data.tempUnit
                ),
                color = fill,
                fontWeight = FontWeight.Bold,
                fontSize = (w * (if (isSemi) 0.18f else 0.26f)).coerceIn(14f, 90f).sp,
                maxLines = 1
            )
            val unit = metric.unitText(
                context, data.speedUnit, data.distanceUnit, data.tempUnit
            ).ifEmpty { metric.displayName() }
            Text(
                text = unit,
                color = fill.copy(alpha = 0.7f),
                fontSize = (w * (if (isSemi) 0.07f else 0.085f)).coerceIn(8f, 22f).sp,
                maxLines = 1
            )
        }
    }
}

// ---------- DATA_BAR ----------------------------------------------------

@Composable
private fun DataBarElement(element: OverlayElement, data: StudioElementData) {
    val context = LocalContext.current
    val metric = StudioMetric.fromKey(element.metric)
    val value = metric.displayValue(
        data.wheelData, data.speedUnit, data.distanceUnit, data.tempUnit
    )
    val fraction = (value / element.gaugeMax.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val fill = Color(element.foreground)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Color(element.background), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val w = maxWidth.value
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                if (element.showLabel) {
                    Text(
                        text = metric.displayName().uppercase(),
                        color = fill.copy(alpha = 0.7f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (w * 0.07f).coerceIn(9f, 22f).sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                }
                if (element.barShowValue) {
                    val unit = metric.unitText(
                        context, data.speedUnit, data.distanceUnit, data.tempUnit
                    )
                    Text(
                        text = metric.formatted(
                            data.wheelData, data.speedUnit, data.distanceUnit, data.tempUnit
                        ) + if (unit.isEmpty()) "" else " $unit",
                        color = fill,
                        fontWeight = FontWeight.Bold,
                        fontSize = (w * 0.10f).coerceIn(10f, 30f).sp,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((w * 0.11f).coerceIn(8f, 40f).dp)
                    .clip(RoundedCornerShape(50))
                    .background(fill.copy(alpha = 0.2f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(fill)
                )
            }
        }
    }
}

// ---------- DATA_GRAPH (HUD-local rolling buffer) -----------------------

@Composable
private fun DataGraphElement(element: OverlayElement, data: StudioElementData) {
    val metric = StudioMetric.fromKey(element.metric)
    val buf = remember(element.id) { mutableStateListOf<Pair<Long, Float>>() }
    val now = data.wheelData.timestamp
    LaunchedEffect(data.wheelData.timestamp, element.metric) {
        val v = metric.displayValue(
            data.wheelData, data.speedUnit, data.distanceUnit, data.tempUnit
        )
        buf.add(now to v)
        val windowMs = element.graphWindowSec * 1000L
        while (buf.isNotEmpty() && buf.first().first < now - windowMs) {
            buf.removeAt(0)
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Color(element.background), RoundedCornerShape(8.dp))
            .then(
                if (element.height > 0f) Modifier.fillMaxHeight()
                else Modifier.aspectRatio(2.2f)
            )
            .padding(8.dp)
    ) {
        val w = maxWidth.value
        Column {
            Text(
                text = "${metric.displayName().uppercase()}  ·  ${element.graphWindowSec}s",
                color = Color(element.foreground).copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold,
                fontSize = (w * 0.07f).coerceIn(8f, 20f).sp,
                maxLines = 1
            )
            Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
                if (buf.size < 2) return@Canvas
                val values = buf.map { it.second }
                val minV = values.min()
                val maxV = values.max()
                val span = (maxV - minV).takeIf { it > 0.01f } ?: 1f
                val firstT = buf.first().first
                val lastT = buf.last().first
                val tSpan = (lastT - firstT).takeIf { it > 0 } ?: 1L
                val line = Color(element.foreground)
                val path = Path()
                buf.forEachIndexed { i, (t, v) ->
                    val px = ((t - firstT).toFloat() / tSpan) * size.width
                    val py = size.height - ((v - minV) / span) * size.height
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path = path, color = line,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

// ---------- TEXT --------------------------------------------------------

@Composable
private fun FreeTextElement(element: OverlayElement, data: StudioElementData) {
    val context = LocalContext.current
    val rendered = remember(
        element.text, data.wheelData, data.wheelName,
        data.speedUnit, data.distanceUnit, data.tempUnit
    ) { renderTextTemplate(element.text, data, context) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Color(element.background), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = rendered.ifEmpty { " " },
            color = Color(element.foreground),
            fontWeight = FontWeight.SemiBold,
            fontSize = (maxWidth.value * 0.11f).coerceIn(9f, 54f).sp,
            textAlign = when (element.textAlign) {
                "CENTER" -> TextAlign.Center
                "END" -> TextAlign.End
                else -> TextAlign.Start
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun renderTextTemplate(
    template: String,
    data: StudioElementData,
    context: android.content.Context
): String {
    var s = template
    StudioMetric.entries.forEach { m ->
        val token = "{${m.key.lowercase()}}"
        if (s.contains(token, ignoreCase = true)) {
            val value = m.formatted(
                data.wheelData, data.speedUnit, data.distanceUnit, data.tempUnit
            )
            val unit = m.unitText(
                context, data.speedUnit, data.distanceUnit, data.tempUnit
            )
            s = s.replace(
                token,
                if (unit.isEmpty()) value else "$value $unit",
                ignoreCase = true
            )
        }
    }
    return s.replace("{wheel}", data.wheelName, ignoreCase = true)
}

// ---------- WHEEL_NAME --------------------------------------------------

@Composable
private fun WheelNameElement(element: OverlayElement, data: StudioElementData) {
    BoxWithConstraints(
        Modifier
            .background(Color(element.background), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        val name = data.wheelName.ifBlank { "Not connected" }
        Text(
            text = name,
            color = Color(element.foreground),
            fontWeight = FontWeight.Bold,
            fontSize = (maxWidth.value * 0.13f).coerceIn(12f, 64f).sp,
            maxLines = 1
        )
    }
}

// ---------- APP_BADGE ---------------------------------------------------

@Composable
private fun AppBadgeElement(element: OverlayElement) {
    BoxWithConstraints(
        Modifier
            .background(Color(element.background), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        val w = maxWidth.value
        val stacked = element.badgeStacked
        val iconSize = (w * if (stacked) 0.42f else 0.24f).coerceIn(20f, 130f).dp
        val nameSize = (w * if (stacked) 0.15f else 0.13f).coerceIn(11f, 48f).sp
        val versionSize = (w * if (stacked) 0.10f else 0.09f).coerceIn(8f, 28f).sp

        @Composable
        fun Texts(center: Boolean) {
            Column(
                horizontalAlignment =
                    if (center) Alignment.CenterHorizontally else Alignment.Start
            ) {
                if (element.showLabel) {
                    Text(
                        // The APP_BADGE represents the data SOURCE app
                        // (the phone running EUC Planet), not the HUD
                        // device. Show the phone-app brand here so a
                        // rider's preset reads the same on phone and
                        // HUD.
                        text = "EUC Planet",
                        color = Color(element.foreground),
                        fontWeight = FontWeight.Bold,
                        fontSize = nameSize,
                        maxLines = 1
                    )
                }
                if (element.badgeShowVersion) {
                    Text(
                        text = "v${com.eried.eucplanet.hud.BuildConfig.VERSION_NAME}",
                        color = Color(element.foreground).copy(alpha = 0.75f),
                        fontSize = versionSize,
                        maxLines = 1
                    )
                }
            }
        }

        if (stacked) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        id = com.eried.eucplanet.hud.R.drawable.ic_eucplanet_logo
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize)
                )
                Texts(center = true)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        id = com.eried.eucplanet.hud.R.drawable.ic_eucplanet_logo
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize)
                )
                Box(Modifier.padding(start = 8.dp)) {
                    Texts(center = false)
                }
            }
        }
    }
}

// ---------- CLOCK -------------------------------------------------------

@Composable
private fun ClockElement(element: OverlayElement, data: StudioElementData) {
    val fg = Color(element.foreground)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Color(element.background), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        val w = maxWidth.value
        // Pump the clock once a second so seconds tick visibly.
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1_000L)
                nowMs = System.currentTimeMillis()
            }
        }
        // Pattern picked off the per-element clock24Hour flag rather
        // than the device's system 12/24 setting -- the rider owns the
        // PRESET, and a HUD lent to a friend with a different system
        // preference shouldn't silently re-render the rider's preset
        // in a different format. The wall-clock badge at the root of
        // HudApp does the opposite (follows system) because the rider
        // running the HUD is the same person whose phone settings
        // would apply to that badge.
        val pattern = if (element.clock24Hour) "HH:mm:ss" else "h:mm:ss a"
        Column {
            Text(
                text = formatClockTime(nowMs, pattern),
                color = fg,
                fontWeight = FontWeight.Bold,
                fontSize = (w * 0.16f).coerceIn(14f, 72f).sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            if (element.clockShowDate) {
                Text(
                    text = formatClockTime(nowMs, "EEE d MMM yyyy"),
                    color = fg.copy(alpha = 0.75f),
                    fontSize = (w * 0.07f).coerceIn(8f, 30f).sp,
                    maxLines = 1
                )
            }
        }
    }
}

private fun formatClockTime(ms: Long, pattern: String): String =
    java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
        .format(java.util.Date(ms))

// ---------- G_FORCE (simplified -- lateral derived from roll) ----------

@Composable
private fun GForceElement(element: OverlayElement, data: StudioElementData) {
    val fg = Color(element.foreground)
    val bg = Color(element.background)
    val maxG = element.gForceScale.coerceIn(0.25f, 6f)
    // Lateral G from wheel roll angle (approximation), longitudinal from
    // sample-over-sample speed change.
    val lateralG = tan(data.wheelData.rollAngle * PI.toFloat() / 180f).coerceIn(-maxG, maxG)
    val longG = 0f // HUD doesn't track this yet; can extend with speed delta
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(bg, CircleShape)
            .padding(10.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = min(size.width, size.height) / 2f
            val unit = r / maxG
            val grid = fg.copy(alpha = 0.4f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            listOf(maxG / 3f, maxG * 2f / 3f, maxG).forEach { ring ->
                drawCircle(
                    color = grid, radius = ring * unit, center = Offset(cx, cy),
                    style = Stroke(width = 1f, pathEffect = dash)
                )
            }
            drawLine(grid, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1f, pathEffect = dash)
            drawLine(grid, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1f, pathEffect = dash)
            val tip = Offset(cx + lateralG * unit, cy - longG * unit)
            drawCircle(color = fg.copy(alpha = 0.30f), radius = 23f, center = tip)
            drawCircle(color = fg, radius = 17f, center = tip)
            drawCircle(
                color = Color.White, radius = 17f, center = tip,
                style = Stroke(width = 2.5f)
            )
        }
    }
}

// ---------- MAP ---------------------------------------------------------

@Composable
private fun MapElement(element: OverlayElement, data: StudioElementData) {
    val cache = remember { com.eried.eucplanet.hud.net.HudTileCache() }
    var tick by remember { mutableStateOf(0) }
    val z = element.mapZoom.coerceIn(10, 19)
    val hasFix = data.latitude != 0.0 || data.longitude != 0.0
    val accent = Color(element.foreground)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(element.background))
    ) {
        if (!hasFix) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No GPS",
                    color = accent.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
            return@Box
        }
        val (cx, cy) = lonLatToTileFloat(data.longitude, data.latitude, z)
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") tick
            val cols = (size.width / 256f).toInt() + 2
            val rows = (size.height / 256f).toInt() + 2
            val originX = floor(cx).toInt() - cols / 2
            val originY = floor(cy).toInt() - rows / 2
            val centerPx = Offset(size.width / 2f, size.height / 2f)
            val originTilePx = Offset(
                centerPx.x - ((cx - originX) * 256f),
                centerPx.y - ((cy - originY) * 256f)
            )
            for (dy in 0 until rows) for (dx in 0 until cols) {
                val tx = originX + dx
                val ty = originY + dy
                if (tx < 0 || ty < 0) continue
                val bm = cache.peek(z, tx, ty)
                val topLeft = Offset(
                    originTilePx.x + dx * 256f,
                    originTilePx.y + dy * 256f
                )
                if (bm != null) {
                    drawImage(image = bm.asImageBitmap(), topLeft = topLeft)
                } else {
                    drawRect(color = Color(0xFF1A1A1A), topLeft = topLeft,
                        size = Size(256f, 256f))
                }
            }
            drawCircle(color = Color.White, radius = 9.dp.toPx(), center = centerPx)
            drawCircle(color = accent, radius = 7.dp.toPx(), center = centerPx)
        }
        // Trigger tile fetches when the rider moves or the rider swaps
        // map styles on the phone (styleVersion bumps in that case).
        LaunchedEffect(z, cx.toInt(), cy.toInt(), cache.styleVersion) {
            val cols = 4
            val rows = 4
            val originX = floor(cx).toInt() - cols / 2
            val originY = floor(cy).toInt() - rows / 2
            for (dy in 0 until rows) for (dx in 0 until cols) {
                val tx = originX + dx
                val ty = originY + dy
                if (tx < 0 || ty < 0) continue
                cache.requestTile(z, tx, ty) { tick++ }
            }
        }
    }
}

private fun lonLatToTileFloat(lon: Double, lat: Double, z: Int): Pair<Float, Float> {
    val n = (1 shl z).toDouble()
    val x = (lon + 180.0) / 360.0 * n
    val latRad = lat * PI / 180.0
    val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
    return x.toFloat() to y.toFloat()
}

// ---------- RADAR (Garmin Varia rear-view) ------------------------------
// HUD-side twin of the phone's RadarElement. Same geometry/colours so a
// radar widget added in the Overlay Studio reads identical when mirrored
// to the Custom screen. Three modes: LANE (proximity bar), MIRROR
// (blind-spot chevrons), MINIMAL (compact readout). The Varia reports
// range + closing speed only, no bearing, so MIRROR lights both sides
// together by the worst threat level.

// Threat-band colours: same hex as the DATA_DIAL colour band above, so
// green/amber/red read consistently across widgets.
private val RADAR_GREEN = Color(0xFF66BB6A)
private val RADAR_AMBER = Color(0xFFFFA726)
private val RADAR_RED = Color(0xFFEF5350)

private fun radarLevelColor(level: Int): Color = when {
    level >= 2 -> RADAR_RED
    level == 1 -> RADAR_AMBER
    else -> RADAR_GREEN
}

@Composable
private fun RadarElement(element: OverlayElement, data: StudioElementData) {
    when (element.radarMode) {
        "MIRROR" -> RadarMirror(element, data)
        "MINIMAL" -> RadarMinimal(element, data)
        else -> RadarLane(element, data)
    }
}

@Composable
private fun RadarLane(element: OverlayElement, data: StudioElementData) {
    val fg = Color(element.foreground)
    val range = element.radarRangeM.coerceAtLeast(20f)
    val targets = data.radarTargets
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.5f)
            .background(Color(element.background), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        val w = maxWidth.value
        Column(Modifier.fillMaxSize()) {
            if (element.showLabel) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "RADAR",
                        color = fg.copy(alpha = 0.7f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (w * 0.09f).coerceIn(9f, 22f).sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    if (data.radarBatteryPercent >= 0) {
                        Text(
                            text = "${data.radarBatteryPercent}%",
                            color = fg.copy(alpha = 0.6f),
                            fontSize = (w * 0.08f).coerceIn(8f, 18f).sp,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (!data.radarConnected) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No radar",
                            color = fg.copy(alpha = 0.5f),
                            fontSize = (w * 0.10f).coerceIn(10f, 22f).sp
                        )
                    }
                    return@Box
                }
                Canvas(Modifier.fillMaxSize()) {
                    val cw = size.width
                    val ch = size.height
                    val cx = cw / 2f
                    val topHalf = cw * 0.13f
                    val botHalf = cw * 0.44f
                    // Lane fill + edges (far = narrow top, near = wide bottom).
                    val lane = Path().apply {
                        moveTo(cx - topHalf, 0f)
                        lineTo(cx + topHalf, 0f)
                        lineTo(cx + botHalf, ch)
                        lineTo(cx - botHalf, ch)
                        close()
                    }
                    drawPath(lane, color = fg.copy(alpha = 0.10f))
                    drawLine(
                        fg.copy(alpha = 0.35f),
                        Offset(cx - topHalf, 0f), Offset(cx - botHalf, ch), 2f
                    )
                    drawLine(
                        fg.copy(alpha = 0.35f),
                        Offset(cx + topHalf, 0f), Offset(cx + botHalf, ch), 2f
                    )
                    // Distance gridlines at 25/50/75% of range.
                    listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                        val y = ch * (1f - f)
                        val half = topHalf + (botHalf - topHalf) * (1f - f)
                        drawLine(
                            fg.copy(alpha = 0.15f),
                            Offset(cx - half, y), Offset(cx + half, y), 1.2f
                        )
                    }
                    // Rider marker (you) at the bottom.
                    val rsz = cw * 0.10f
                    val rider = Path().apply {
                        moveTo(cx, ch - rsz)
                        lineTo(cx - rsz * 0.8f, ch)
                        lineTo(cx + rsz * 0.8f, ch)
                        close()
                    }
                    drawPath(rider, color = fg.copy(alpha = 0.9f))
                    // Targets, far drawn first so nearer cars sit on top.
                    targets.sortedByDescending { it.distanceM }.forEach { t ->
                        val ratio = (t.distanceM / range).coerceIn(0f, 1f)
                        val y = ch * (1f - ratio)
                        val near = 1f - ratio
                        val r = cw * 0.07f + cw * 0.09f * near
                        val c = radarLevelColor(t.level)
                        drawCircle(c.copy(alpha = 0.25f), radius = r * 1.6f, center = Offset(cx, y))
                        drawCircle(c, radius = r, center = Offset(cx, y))
                        if (element.radarShowDistanceLabels) {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = r * 1.0f
                                isAntiAlias = true
                                textAlign = android.graphics.Paint.Align.CENTER
                                isFakeBoldText = true
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                t.distanceM.toString(), cx, y + paint.textSize * 0.35f, paint
                            )
                        }
                    }
                }
                if (targets.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "CLEAR",
                            color = RADAR_GREEN,
                            fontWeight = FontWeight.Bold,
                            fontSize = (w * 0.11f).coerceIn(11f, 26f).sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarMirror(element: OverlayElement, data: StudioElementData) {
    val fg = Color(element.foreground)
    val targets = data.radarTargets
    val maxLevel = targets.maxOfOrNull { it.level } ?: 0
    val closest = targets.minByOrNull { it.distanceM }
    val active = data.radarConnected && targets.isNotEmpty()
    val markColor = if (active) radarLevelColor(maxLevel) else fg.copy(alpha = 0.22f)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2.0f)
            .background(Color(element.background), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        val w = maxWidth.value
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            ChevronSide(left = true, color = markColor, modifier = Modifier.fillMaxHeight().weight(1f))
            Column(
                Modifier.weight(1.5f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    !data.radarConnected -> Text(
                        text = "No radar",
                        color = fg.copy(alpha = 0.5f),
                        fontSize = (w * 0.07f).coerceIn(10f, 22f).sp,
                        maxLines = 1
                    )
                    closest == null -> Text(
                        text = "CLEAR",
                        color = RADAR_GREEN,
                        fontWeight = FontWeight.Bold,
                        fontSize = (w * 0.10f).coerceIn(12f, 30f).sp,
                        maxLines = 1
                    )
                    else -> {
                        Text(
                            text = "${closest.distanceM} m",
                            color = fg,
                            fontWeight = FontWeight.Bold,
                            fontSize = (w * 0.13f).coerceIn(16f, 44f).sp,
                            maxLines = 1
                        )
                        Text(
                            text = "+${targets.maxOf { it.approachSpeedKmh }} km/h",
                            color = markColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = (w * 0.06f).coerceIn(9f, 22f).sp,
                            maxLines = 1
                        )
                    }
                }
            }
            ChevronSide(left = false, color = markColor, modifier = Modifier.fillMaxHeight().weight(1f))
        }
    }
}

@Composable
private fun ChevronSide(left: Boolean, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val cw = size.width
        val ch = size.height
        val cy = ch / 2f
        val half = ch * 0.30f
        val chevW = cw * 0.45f
        val gap = cw * 0.26f
        val strokeW = cw * 0.13f
        for (i in 0 until 3) {
            val baseX = if (left) cw - i * gap else i * gap
            val tipX = if (left) baseX - chevW else baseX + chevW
            val a = (color.alpha * (1f - i * 0.3f)).coerceIn(0f, 1f)
            val p = Path().apply {
                moveTo(baseX, cy - half)
                lineTo(tipX, cy)
                lineTo(baseX, cy + half)
            }
            drawPath(
                p,
                color = color.copy(alpha = a),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun RadarMinimal(element: OverlayElement, data: StudioElementData) {
    val fg = Color(element.foreground)
    val targets = data.radarTargets
    val maxLevel = targets.maxOfOrNull { it.level } ?: 0
    val closest = targets.minByOrNull { it.distanceM }
    val dotColor: Color
    val line1: String
    val line2: String?
    when {
        !data.radarConnected -> {
            dotColor = fg.copy(alpha = 0.4f); line1 = "No radar"; line2 = null
        }
        closest == null -> {
            dotColor = RADAR_GREEN; line1 = "Clear"; line2 = null
        }
        else -> {
            dotColor = radarLevelColor(maxLevel)
            line1 = "${closest.distanceM} m"
            line2 = "+${targets.maxOf { it.approachSpeedKmh }} km/h"
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Color(element.background), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val w = maxWidth.value
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size((w * 0.12f).coerceIn(10f, 28f).dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = line1,
                    color = fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = (w * 0.16f).coerceIn(14f, 40f).sp,
                    maxLines = 1
                )
                if (line2 != null) {
                    Text(
                        text = line2,
                        color = dotColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (w * 0.09f).coerceIn(9f, 22f).sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

