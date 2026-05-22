package com.eried.eucplanet.ui.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.OverlayElement
import com.eried.eucplanet.data.model.OverlayElementType
import com.eried.eucplanet.data.model.WheelData
import kotlinx.coroutines.launch

/** Everything an overlay element needs to render itself with live data. */
data class StudioElementData(
    val wheelData: WheelData,
    val wheelName: String,
    val connected: Boolean,
    val history: List<StudioSample>,
    val cameraHub: com.eried.eucplanet.ui.studio.camera.StudioCameraHub,
    val speedUnit: String,
    val distanceUnit: String,
    val tempUnit: String,
    /** Wall-clock millis a CLOCK element shows — live now, or the replay row. */
    val clockTimeMs: Long = System.currentTimeMillis(),
    /** Elapsed millis for a CLOCK in STOPWATCH style. */
    val stopwatchMs: Long = 0L
)

/** Compose [Color] -> the 0xAARRGGBB [Long] stored in [OverlayElement]. */
fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

/** Fixed colour for the studio's own edit controls (selection chrome). */
val StudioControlAccent = Color(0xFF4FC3F7)

/**
 * Draws every overlay element in z-order. When [editable], elements can be
 * dragged, resized and selected; while recording / snapshotting they are inert.
 */
@Composable
fun androidx.compose.foundation.layout.BoxWithConstraintsScope.StudioElementLayer(
    elements: List<OverlayElement>,
    data: StudioElementData,
    editable: Boolean,
    selectedId: String?,
    replayMode: Boolean = false,
    onSelect: (String) -> Unit,
    onConfigure: (String) -> Unit,
    onDelete: (String) -> Unit,
    onChange: (OverlayElement) -> Unit
) {
    val containerW = maxWidth
    val containerH = maxHeight
    val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
    val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
    // Floating cameras have no feed during a trip replay, so they are hidden.
    val shown = if (replayMode) {
        elements.filterNot { it.type == OverlayElementType.FLOATING_CAMERA }
    } else {
        elements
    }
    shown.forEach { element ->
        StudioElementBox(
            element = element,
            containerW = containerW,
            containerH = containerH,
            widthPx = widthPx,
            heightPx = heightPx,
            editable = editable,
            selected = editable && element.id == selectedId,
            onSelect = { onSelect(element.id) },
            onConfigure = { onConfigure(element.id) },
            onDelete = { onDelete(element.id) },
            onChange = onChange
        ) {
            ElementContent(element, data)
        }
    }
}

@Composable
private fun StudioElementBox(
    element: OverlayElement,
    containerW: Dp,
    containerH: Dp,
    widthPx: Float,
    heightPx: Float,
    editable: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onConfigure: () -> Unit,
    onDelete: () -> Unit,
    onChange: (OverlayElement) -> Unit,
    content: @Composable () -> Unit
) {
    val live by rememberUpdatedState(element)
    val accent = StudioControlAccent
    // A distinct accent for the rotate handle so it is never confused with the
    // resize grip (the studio's standard blue accent).
    val rotateAccent = Color(0xFFFFB74D)
    // Size of the element's content in px, captured from the rotated marquee
    // layer — i.e. the unrotated content bounds, so its centre is exact.
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier.offset(x = containerW * element.x, y = containerH * element.y)
    ) {
        Box(
            // widthIn(max) — not width() — so the selection frame and border
            // wrap the element's real content (text pills size to their text;
            // graphs / camera / image still fill the chosen width).
            Modifier
                .widthIn(max = containerW * element.width)
                .then(
                    if (editable) Modifier.pointerInput(element.id) {
                        detectDragGestures(
                            // A drag grabs — and selects — whatever it lands on,
                            // so you never silently move an unselected element.
                            onDragStart = { onSelect() }
                        ) { change, drag ->
                            change.consume()
                            val e = live
                            // Elements may sit partly / fully off-screen; the
                            // edit chrome stays clamped on-screen (below) so a
                            // stray element is always still reachable.
                            onChange(
                                e.copy(
                                    x = (e.x + drag.x / widthPx).coerceIn(-1f, 1f),
                                    y = (e.y + drag.y / heightPx).coerceIn(-1f, 1f)
                                )
                            )
                        }
                    } else Modifier
                )
                .then(
                    if (editable) Modifier.pointerInput(element.id) {
                        detectTapGestures(onTap = { onSelect() })
                    } else Modifier
                )
        ) {
            // The dashed marquee shares the element's rotation so it always
            // frames the content squarely, whatever angle the element is at.
            Box(
                Modifier
                    .graphicsLayer { rotationZ = element.rotationDeg }
                    .onSizeChanged { contentSize = it }
                    .then(
                        if (selected) Modifier.drawBehind {
                            drawRoundRect(
                                color = accent,
                                cornerRadius = CornerRadius(5.dp.toPx()),
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(14f, 9f), 0f
                                    )
                                )
                            )
                        } else Modifier
                    )
            ) {
                Box(
                    Modifier.graphicsLayer { alpha = element.opacity.coerceIn(0f, 1f) }
                ) {
                    // Drop shadow — the content emitted a second time BEHIND
                    // the real copy, offset / tinted by the element's shadow
                    // settings. The graphicsLayer forces an offscreen layer
                    // (offset + alpha), then a rect drawn with SrcAtop recolours
                    // only the content's silhouette to the shadow colour. It
                    // follows the actual content shape (text, gauges) — pure
                    // GPU, no per-frame pixel work; small elements make it
                    // effectively free.
                    if (element.shadow) {
                        val shadowRad =
                            Math.toRadians(element.shadowAngle.toDouble())
                        val shadowTint = Color(element.shadowColor)
                        Box(
                            Modifier
                                .matchParentSize()
                                .graphicsLayer {
                                    val dist = element.shadowDistance.dp.toPx()
                                    translationX =
                                        dist * kotlin.math.cos(shadowRad).toFloat()
                                    translationY =
                                        dist * kotlin.math.sin(shadowRad).toFloat()
                                    alpha = element.shadowStrength.coerceIn(0f, 1f)
                                    compositingStrategy = CompositingStrategy.Offscreen
                                }
                                .drawWithContent {
                                    drawContent()
                                    drawRect(
                                        color = shadowTint,
                                        blendMode = BlendMode.SrcAtop
                                    )
                                }
                        ) { content() }
                    }
                    content()
                }

                if (selected) {
                    // The edit chrome lives inside the rotated layer, so the
                    // config / delete row and the resize grip track the element
                    // at whatever angle it sits.
                    Row(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = (-42).dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ChromeButton(Icons.Default.Build, accent, onConfigure)
                        ChromeButton(Icons.Default.Close, Color(0xFFE53935), onDelete)
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 14.dp, y = 14.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .pointerInput(element.id) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    val e = live
                                    onChange(
                                        e.copy(
                                            width = (e.width + drag.x / widthPx)
                                                .coerceIn(0.08f, 1.5f)
                                        )
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.OpenInFull,
                            contentDescription = stringResource(R.string.studio_cd_resize),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp).rotate(90f)
                        )
                    }
                    // Rotate handle — opposite corner to the resize grip. The
                    // chrome sits INSIDE the element's rotated graphicsLayer, so
                    // a drag delta is already in the element's local frame: the
                    // angular change of the pointer about the content centre is
                    // added straight onto rotationDeg.
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .offset(x = (-14).dp, y = (-14).dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(rotateAccent)
                            .pointerInput(element.id) {
                                // Pointer angle (about the content centre) at
                                // the moment the drag started — the delta from
                                // this is what moves the element, so the handle
                                // never "jumps" on grab.
                                var startPointerAngle = 0f
                                var startRotation = 0f
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        val cx = contentSize.width / 2f
                                        val cy = contentSize.height / 2f
                                        // Handle centre is offset (-14,-14) from
                                        // the content's TopStart corner.
                                        val hx = (-14).dp.toPx() + 15.dp.toPx()
                                        val hy = (-14).dp.toPx() + 15.dp.toPx()
                                        startPointerAngle = Math.toDegrees(
                                            kotlin.math.atan2(
                                                (hy + pos.y) - cy,
                                                (hx + pos.x) - cx
                                            ).toDouble()
                                        ).toFloat()
                                        startRotation = live.rotationDeg
                                    }
                                ) { change, _ ->
                                    change.consume()
                                    val cx = contentSize.width / 2f
                                    val cy = contentSize.height / 2f
                                    val hx = (-14).dp.toPx() + 15.dp.toPx()
                                    val hy = (-14).dp.toPx() + 15.dp.toPx()
                                    val angle = Math.toDegrees(
                                        kotlin.math.atan2(
                                            (hy + change.position.y) - cy,
                                            (hx + change.position.x) - cx
                                        ).toDouble()
                                    ).toFloat()
                                    var next = startRotation + (angle - startPointerAngle)
                                    // Normalise to 0..360.
                                    next = ((next % 360f) + 360f) % 360f
                                    // Detent — snap to 0/90/180/270 within ±6°.
                                    listOf(0f, 90f, 180f, 270f, 360f).forEach { d ->
                                        if (kotlin.math.abs(next - d) <= 6f) {
                                            next = d % 360f
                                        }
                                    }
                                    onChange(live.copy(rotationDeg = next))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Rotate90DegreesCw,
                            contentDescription = stringResource(R.string.studio_cd_rotate),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChromeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0xDD1E1E26))
            .border(1.dp, tint, CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** Dispatch to the per-type renderer. */
@Composable
private fun ElementContent(element: OverlayElement, data: StudioElementData) {
    when (element.type) {
        OverlayElementType.WHEEL_NAME -> WheelNameElement(element, data)
        OverlayElementType.APP_BADGE -> AppBadgeElement(element)
        OverlayElementType.TEXT -> FreeTextElement(element, data)
        OverlayElementType.DATA_VALUE -> DataValueElement(element, data)
        OverlayElementType.DATA_GRAPH -> DataGraphElement(element, data)
        OverlayElementType.DATA_DIAL -> DataDialElement(element, data)
        OverlayElementType.DATA_BAR -> DataBarElement(element, data)
        OverlayElementType.FLOATING_CAMERA -> FloatingCameraElement(element, data)
        OverlayElementType.IMAGE -> ImageElement(element)
        OverlayElementType.CLOCK -> ClockElement(element, data)
        OverlayElementType.G_FORCE -> GForceTrailElement(element, data)
        OverlayElementType.MAP -> MapElement(element, data)
    }
}

/**
 * A circular crosshair plotting live lateral × forward G-force with a fading
 * comet trail — the studio twin of the dashboard's GForceCrosshair. The trail
 * is rebuilt from [StudioElementData.history] each frame, so it works for both
 * live recording and trip replay (history is the trip up to the scrub point).
 */
@Composable
private fun GForceTrailElement(element: OverlayElement, data: StudioElementData) {
    val fg = Color(element.foreground)
    // Trail hue is the dot colour mixed toward black so the tail reads as a
    // darker shade and the bright live dot stays the focal point.
    val trailColor = androidx.compose.ui.graphics.lerp(fg, Color.Black, 0.45f)
    // Comet trail points (lateral, forward) within the configured window,
    // anchored to the latest sample so a stale clock doesn't freeze scrolling.
    val windowMs = element.graphWindowSec * 1000L
    val trail = remember(data.history, element.graphWindowSec) {
        val last = data.history.lastOrNull()?.timeMs ?: 0L
        data.history
            .filter { it.timeMs >= last - windowMs }
            .map { Offset(it.data.accelX, it.data.accelY) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(element.background), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val maxG = 1.5f
            val unit = (w.coerceAtMost(h) / 2f) / maxG

            // Concentric dashed rings at 0.5 / 1.0 / 1.5 g + a crosshair.
            val grid = trailColor.copy(alpha = 0.4f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            listOf(0.5f, 1.0f, 1.5f).forEach { r ->
                drawCircle(
                    color = grid,
                    radius = r * unit,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1f, pathEffect = dash)
                )
            }
            drawLine(grid, Offset(0f, cy), Offset(w, cy), strokeWidth = 1f, pathEffect = dash)
            drawLine(grid, Offset(cx, 0f), Offset(cx, h), strokeWidth = 1f, pathEffect = dash)

            // Comet tail — Catmull-Rom-smoothed curve through the trail points
            // (tension 0.5), each segment sub-sampled into small strokes so it
            // reads as one continuous curve. Stroke width + alpha taper along
            // the tail (sqrt curve) so only the oldest end really fades out.
            if (trail.size >= 2) {
                val n = trail.size
                val mapped = trail.map { p ->
                    Offset(
                        cx + p.x.coerceIn(-maxG, maxG) * unit,
                        cy - p.y.coerceIn(-maxG, maxG) * unit
                    )
                }
                val sub = 12
                for (i in 1 until n) {
                    val p0 = mapped[(i - 2).coerceAtLeast(0)]
                    val p1 = mapped[i - 1]
                    val p2 = mapped[i]
                    val p3 = mapped[(i + 1).coerceAtMost(n - 1)]
                    val age = i / (n - 1).toFloat()
                    val visible = kotlin.math.sqrt(age)
                    val alpha = 0.06f + 0.24f * visible
                    val stroke = 2.5f + 6.5f * visible
                    var prev = p1
                    for (s in 1..sub) {
                        val t = s / sub.toFloat()
                        val t2 = t * t
                        val t3 = t2 * t
                        val x = 0.5f * (
                            (2f * p1.x) +
                            (-p0.x + p2.x) * t +
                            (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 +
                            (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3
                        )
                        val y = 0.5f * (
                            (2f * p1.y) +
                            (-p0.y + p2.y) * t +
                            (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                            (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3
                        )
                        val cur = Offset(x, y)
                        drawLine(
                            color = trailColor.copy(alpha = alpha),
                            start = prev,
                            end = cur,
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                        prev = cur
                    }
                }
            }

            // Live dot at the current lateral / forward G-force.
            val gx = data.wheelData.accelX.coerceIn(-maxG, maxG)
            val gy = data.wheelData.accelY.coerceIn(-maxG, maxG)
            val center = Offset(cx + gx * unit, cy - gy * unit)
            drawCircle(color = fg.copy(alpha = 0.15f), radius = 24f, center = center)
            drawCircle(color = fg.copy(alpha = 0.30f), radius = 18f, center = center)
            drawCircle(color = fg, radius = 13f, center = center)
            drawCircle(
                color = Color.White,
                radius = 13f,
                center = center,
                style = Stroke(width = 2f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 4f,
                center = Offset(center.x - 4f, center.y - 4f)
            )
        }
    }
}

@Composable
private fun ClockElement(element: OverlayElement, data: StudioElementData) {
    val fg = Color(element.foreground)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(element.background), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        when (element.clockStyle) {
            "ANALOG" -> {
                val cal = java.util.Calendar.getInstance()
                    .apply { timeInMillis = data.clockTimeMs }
                AnalogClock(
                    hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                    minute = cal.get(java.util.Calendar.MINUTE),
                    second = cal.get(java.util.Calendar.SECOND),
                    color = fg
                )
            }
            "STOPWATCH" -> SevenSegmentDisplay(formatStopwatch(data.stopwatchMs), fg)
            "TEXT" -> BoxWithConstraints {
                val w = maxWidth.value
                Column {
                    androidx.compose.material3.Text(
                        text = formatClockTime(data.clockTimeMs, "HH:mm:ss"),
                        color = fg,
                        fontWeight = FontWeight.Bold,
                        fontSize = (w * 0.16f).coerceIn(14f, 72f).sp,
                        maxLines = 1
                    )
                    if (element.clockShowDate) {
                        androidx.compose.material3.Text(
                            text = formatClockTime(data.clockTimeMs, "EEE d MMM yyyy"),
                            color = fg.copy(alpha = 0.75f),
                            fontSize = (w * 0.07f).coerceIn(8f, 30f).sp,
                            maxLines = 1
                        )
                    }
                }
            }
            else -> SevenSegmentDisplay(
                formatClockTime(data.clockTimeMs, "HH:mm:ss"), fg
            )
        }
    }
}

private fun formatClockTime(ms: Long, pattern: String): String =
    java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
        .format(java.util.Date(ms))

private fun formatStopwatch(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val total = clamped / 1000
    val millis = clamped % 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d.%03d".format(h, m, s, millis)
    else "%02d:%02d.%03d".format(m, s, millis)
}

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
        androidx.compose.material3.Text(
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

/** Replaces {speed}-style tokens in [template] with live telemetry values. */
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

@Composable
private fun WheelNameElement(element: OverlayElement, data: StudioElementData) {
    BoxWithConstraints(
        Modifier
            .background(Color(element.background), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        val name = data.wheelName.ifBlank {
            stringResource(R.string.studio_not_connected)
        }
        androidx.compose.material3.Text(
            text = name,
            color = Color(element.foreground),
            fontWeight = FontWeight.Bold,
            fontSize = (maxWidth.value * 0.13f).coerceIn(12f, 64f).sp,
            maxLines = 1
        )
    }
}

@Composable
private fun AppBadgeElement(element: OverlayElement) {
    val context = LocalContext.current
    // The launcher icon is an adaptive-icon drawable, which painterResource
    // cannot load — rasterise it to a bitmap once instead.
    val icon = remember {
        runCatching {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(192, 192)
                .asImageBitmap()
        }.getOrNull()
    }
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
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.app_name),
                        color = Color(element.foreground),
                        fontWeight = FontWeight.Bold,
                        fontSize = nameSize,
                        maxLines = 1
                    )
                }
                if (element.badgeShowVersion) {
                    androidx.compose.material3.Text(
                        text = "v${com.eried.eucplanet.BuildConfig.VERSION_NAME}",
                        color = Color(element.foreground).copy(alpha = 0.75f),
                        fontSize = versionSize,
                        maxLines = 1
                    )
                }
            }
        }

        if (stacked) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null,
                        modifier = Modifier.size(iconSize))
                }
                Texts(center = true)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null,
                        modifier = Modifier.size(iconSize))
                }
                Box(Modifier.padding(start = if (icon != null) 8.dp else 0.dp)) {
                    Texts(center = false)
                }
            }
        }
    }
}

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
        // Fill the configured width so the pill stays a fixed size as the live
        // value changes digits — otherwise it jitters and reads as distracting.
        val align = when (element.textAlign) {
            "CENTER" -> Alignment.CenterHorizontally
            "END" -> Alignment.End
            else -> Alignment.Start
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
            if (element.showLabel) {
                androidx.compose.material3.Text(
                    text = metric.displayName().uppercase(),
                    color = Color(element.foreground).copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (w * 0.09f).coerceIn(9f, 26f).sp,
                    maxLines = 1
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                androidx.compose.material3.Text(
                    text = metric.formatted(
                        data.wheelData, data.speedUnit, data.distanceUnit, data.tempUnit
                    ),
                    color = Color(element.foreground),
                    fontWeight = FontWeight.Bold,
                    fontSize = (w * 0.34f).coerceIn(18f, 120f).sp,
                    maxLines = 1
                )
                val unit = metric.unitText(
                    context, data.speedUnit, data.distanceUnit, data.tempUnit
                )
                if (unit.isNotEmpty()) {
                    androidx.compose.material3.Text(
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

@Composable
private fun DataGraphElement(element: OverlayElement, data: StudioElementData) {
    val metric = StudioMetric.fromKey(element.metric)
    // Anchor the window to the latest sample so a disconnected wheel doesn't
    // freeze the graph mid-scroll on a stale wall-clock reading.
    val now = data.history.lastOrNull()?.timeMs ?: System.currentTimeMillis()
    val windowMs = element.graphWindowSec * 1000L
    val series = remember(data.history, element.metric, element.graphWindowSec) {
        data.history
            .filter { it.timeMs >= now - windowMs }
            .map {
                it.timeMs to metric.displayValue(
                    it.data, data.speedUnit, data.distanceUnit, data.tempUnit
                )
            }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Color(element.background), RoundedCornerShape(8.dp))
            .aspectRatio(2.2f)
            .padding(8.dp)
    ) {
        val w = maxWidth.value
        Column {
            androidx.compose.material3.Text(
                text = "${metric.displayName().uppercase()}  ·  ${element.graphWindowSec}s",
                color = Color(element.foreground).copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold,
                fontSize = (w * 0.07f).coerceIn(8f, 20f).sp,
                maxLines = 1
            )
            androidx.compose.foundation.Canvas(
                Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)
            ) {
                if (series.size < 2) return@Canvas
                val values = series.map { it.second }
                val minV = values.min()
                val maxV = values.max()
                val span = (maxV - minV).takeIf { it > 0.01f } ?: 1f
                val firstT = series.first().first
                val lastT = series.last().first
                val tSpan = (lastT - firstT).takeIf { it > 0 } ?: 1L
                val line = Color(element.foreground)
                val path = androidx.compose.ui.graphics.Path()
                series.forEachIndexed { i, (t, v) ->
                    val px = ((t - firstT).toFloat() / tSpan) * size.width
                    val py = size.height - ((v - minV) / span) * size.height
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path = path,
                    color = line,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

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
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(element.background), CircleShape)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth.value
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val strokeW = size.minDimension * 0.12f
            val side = size.minDimension - strokeW
            val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
            val arcSize = Size(side, side)
            drawArc(
                color = track, startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
            drawArc(
                color = fill, startAngle = 135f, sweepAngle = 270f * fraction,
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Text(
                text = metric.formatted(
                    data.wheelData, data.speedUnit, data.distanceUnit, data.tempUnit
                ),
                color = fill,
                fontWeight = FontWeight.Bold,
                fontSize = (w * 0.26f).coerceIn(14f, 90f).sp,
                maxLines = 1
            )
            val unit = metric.unitText(
                context, data.speedUnit, data.distanceUnit, data.tempUnit
            ).ifEmpty { metric.displayName() }
            androidx.compose.material3.Text(
                text = unit,
                color = fill.copy(alpha = 0.7f),
                fontSize = (w * 0.085f).coerceIn(8f, 22f).sp,
                maxLines = 1
            )
        }
    }
}

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
                    androidx.compose.material3.Text(
                        text = metric.displayName().uppercase(),
                        color = fill.copy(alpha = 0.7f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (w * 0.07f).coerceIn(9f, 22f).sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                }
                val unit = metric.unitText(
                    context, data.speedUnit, data.distanceUnit, data.tempUnit
                )
                androidx.compose.material3.Text(
                    text = metric.formatted(
                        data.wheelData, data.speedUnit, data.distanceUnit, data.tempUnit
                    ) + if (unit.isEmpty()) "" else " $unit",
                    color = fill,
                    fontWeight = FontWeight.Bold,
                    fontSize = (w * 0.10f).coerceIn(10f, 30f).sp,
                    maxLines = 1
                )
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

@Composable
private fun FloatingCameraElement(element: OverlayElement, data: StudioElementData) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF14141A))
            .border(2.dp, Color(0x88FFFFFF), RoundedCornerShape(10.dp))
    ) {
        val frame = data.cameraHub.frame(element.cameraKey)
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Default.Image,
                contentDescription = null,
                tint = Color(0xFF555555),
                modifier = Modifier.align(Alignment.Center).size(28.dp)
            )
        }
    }
}

@Composable
private fun ImageElement(element: OverlayElement) {
    val bitmap = remember(
        element.imageData,
        element.chromaKeyEnabled,
        element.chromaKeyColor,
        element.chromaKeyTolerance
    ) {
        val data = element.imageData ?: return@remember null
        val decoded = StudioImages.decode(data) ?: return@remember null
        val processed = if (element.chromaKeyEnabled) {
            StudioImages.applyChromaKey(
                decoded, Color(element.chromaKeyColor), element.chromaKeyTolerance
            )
        } else decoded
        processed.asImageBitmap()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color(0x44FFFFFF), RoundedCornerShape(8.dp))
                .border(
                    1.dp, Color(0x88FFFFFF), RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Image,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

// --------------------------------------------------------------------------
// MAP — a live mini-map drawn entirely with a Compose Canvas so the studio's
// GraphicsLayer capture records it (a real MapView is SurfaceView-backed and
// would not appear in the recording). Raster "slippy map" tiles are fetched
// over HTTP, cached, and painted onto the Canvas; the GPS trace and position
// marker are projected with the same Web-Mercator maths.
// --------------------------------------------------------------------------

/** Side of a single map tile, in pixels (standard slippy-map tile size). */
private const val MAP_TILE_SIZE = 256

/** Most recent trace points to draw — keeps the polyline cheap on long trips. */
private const val MAP_TRACE_CAP = 400

/**
 * Process-wide raster tile cache + async loader. Tiles are immutable for a
 * given (style, z, x, y), so one shared LRU serves every MAP element.
 */
private object MapTileCache {
    // ~64 tiles ≈ 16 MB of ARGB_8888 bitmaps — plenty for a 3x3 view plus pans.
    private val cache = android.util.LruCache<String, ImageBitmap>(64)
    // URLs currently being fetched, so two recompositions don't double-load.
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    fun get(url: String): ImageBitmap? = cache.get(url)

    fun isLoading(url: String): Boolean = inFlight.contains(url)

    /** Loads [url] off the main thread; returns true once a bitmap is cached. */
    suspend fun load(url: String): Boolean {
        if (cache.get(url) != null) return true
        if (!inFlight.add(url)) return false
        return try {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val conn = (java.net.URL(url).openConnection()
                        as java.net.HttpURLConnection).apply {
                        // Tile servers (OSM in particular) reject blank UAs.
                        setRequestProperty("User-Agent", "EUC Planet")
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bmp != null) {
                cache.put(url, bmp.asImageBitmap())
                true
            } else false
        } finally {
            inFlight.remove(url)
        }
    }
}

/** Tile URL for a style + z/x/y. */
private fun mapTileUrl(style: String, z: Int, x: Int, y: Int): String = when (style) {
    "DARK" -> "https://basemaps.cartocdn.com/dark_all/$z/$x/$y.png"
    "SATELLITE" ->
        "https://server.arcgisonline.com/ArcGIS/rest/services/" +
            "World_Imagery/MapServer/tile/$z/$y/$x"
    else -> "https://tile.openstreetmap.org/$z/$x/$y.png"
}

/** Fractional tile X for a longitude at [zoom] (slippy-map / Web Mercator). */
private fun lonToTileX(lon: Double, zoom: Int): Double =
    (lon + 180.0) / 360.0 * (1 shl zoom)

/** Fractional tile Y for a latitude at [zoom] (slippy-map / Web Mercator). */
private fun latToTileY(lat: Double, zoom: Int): Double {
    val rad = Math.toRadians(lat)
    return (1.0 - kotlin.math.ln(
        kotlin.math.tan(rad) + 1.0 / kotlin.math.cos(rad)
    ) / Math.PI) / 2.0 * (1 shl zoom)
}

@Composable
private fun MapElement(element: OverlayElement, data: StudioElementData) {
    val zoom = element.mapZoom.coerceIn(10, 19)
    val centerLat = data.wheelData.latitude
    val centerLon = data.wheelData.longitude
    val hasFix = centerLat != 0.0 || centerLon != 0.0

    // Trace points with a real fix, capped to the most recent stretch. Built
    // from history so it works identically for live recording and replay.
    val trace = remember(data.history, element.mapTrace) {
        if (!element.mapTrace) emptyList()
        else data.history
            .asSequence()
            .map { it.data }
            .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .toList()
            .takeLast(MAP_TRACE_CAP)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            // A neutral fill — only ever seen for the moment before the first
            // tiles arrive. The configurable colour is the border.
            .background(Color(0xFFE6E6EA))
            .border(
                element.mapBorderWidth.dp, Color(element.foreground),
                RoundedCornerShape(12.dp)
            )
    ) {
        if (!hasFix) {
            // No GPS yet — leave the styled background, nothing to project.
            return@Box
        }

        // Fractional tile coordinates of the centre at this zoom.
        val centerTx = lonToTileX(centerLon, zoom)
        val centerTy = latToTileY(centerLat, zoom)
        val maxTile = (1 shl zoom) - 1

        // Which tiles are needed: enough rings around the centre tile to cover
        // the (square) canvas at any rotation. 2 rings (5x5) is ample.
        val ring = 2
        val baseTx = kotlin.math.floor(centerTx).toInt()
        val baseTy = kotlin.math.floor(centerTy).toInt()
        val tileKeys = remember(element.mapStyle, zoom, baseTx, baseTy) {
            buildList {
                for (dx in -ring..ring) for (dy in -ring..ring) {
                    val tx = baseTx + dx
                    val ty = baseTy + dy
                    if (ty in 0..maxTile) {
                        // Wrap X around the antimeridian so panning never gaps.
                        val wx = ((tx % (maxTile + 1)) + (maxTile + 1)) % (maxTile + 1)
                        add(Triple(tx, ty, mapTileUrl(element.mapStyle, zoom, wx, ty)))
                    }
                }
            }
        }

        // Bump this on every tile completion so the Canvas redraws. Each tile
        // is fetched in its own child coroutine so they download in parallel.
        var tilesReady by remember { mutableStateOf(0) }
        LaunchedEffect(tileKeys) {
            kotlinx.coroutines.coroutineScope {
                tileKeys.forEach { (_, _, url) ->
                    if (MapTileCache.get(url) == null) {
                        launch {
                            if (MapTileCache.load(url)) tilesReady++
                        }
                    }
                }
            }
        }

        val fg = Color(element.foreground)

        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            // Touch tilesReady so a tile finishing schedules a redraw.
            tilesReady
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f

            // Bearing of recent travel (degrees, 0 = north, clockwise) from the
            // last two distinct trace points — drives optional heading-up mode.
            val bearing: Float = if (trace.size >= 2) {
                var b = 0f
                val last = trace.last()
                for (i in trace.size - 2 downTo 0) {
                    val p = trace[i]
                    if (p.latitude != last.latitude || p.longitude != last.longitude) {
                        val dLon = Math.toRadians(last.longitude - p.longitude)
                        val lat1 = Math.toRadians(p.latitude)
                        val lat2 = Math.toRadians(last.latitude)
                        val yb = kotlin.math.sin(dLon) * kotlin.math.cos(lat2)
                        val xb = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
                            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) *
                            kotlin.math.cos(dLon)
                        b = Math.toDegrees(kotlin.math.atan2(yb, xb)).toFloat()
                        break
                    }
                }
                b
            } else 0f
            val rotation = if (element.mapRotateWithHeading) -bearing else 0f

            // Project a (lat, lon) to canvas pixels: pixel offset from centre
            // tile coords, scaled by the tile size.
            fun project(lat: Double, lon: Double): Offset {
                val px = (lonToTileX(lon, zoom) - centerTx) * MAP_TILE_SIZE
                val py = (latToTileY(lat, zoom) - centerTy) * MAP_TILE_SIZE
                return Offset(cx + px.toFloat(), cy + py.toFloat())
            }

            // Everything (tiles + trace) rotates together for heading-up; the
            // marker is drawn after, unrotated, so it stays a fixed dot.
            rotate(degrees = rotation, pivot = Offset(cx, cy)) {
                // Tiles — top-left of each tile is its (tileX - centerTx) offset.
                tileKeys.forEach { (tx, ty, url) ->
                    val bmp = MapTileCache.get(url) ?: return@forEach
                    val left = cx + ((tx - centerTx) * MAP_TILE_SIZE).toFloat()
                    val top = cy + ((ty - centerTy) * MAP_TILE_SIZE).toFloat()
                    drawImage(
                        image = bmp,
                        dstOffset = androidx.compose.ui.unit.IntOffset(
                            left.roundToInt(), top.roundToInt()
                        ),
                        dstSize = androidx.compose.ui.unit.IntSize(
                            MAP_TILE_SIZE, MAP_TILE_SIZE
                        )
                    )
                }

                // Trace polyline.
                if (element.mapTrace && trace.size >= 2) {
                    val path = androidx.compose.ui.graphics.Path()
                    trace.forEachIndexed { i, p ->
                        val o = project(p.latitude, p.longitude)
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    // A dark casing under the bright line so it reads on any tile.
                    drawPath(
                        path = path,
                        color = Color.Black.copy(alpha = 0.45f),
                        style = Stroke(width = 7f, cap = StrokeCap.Round)
                    )
                    drawPath(
                        path = path,
                        color = fg,
                        style = Stroke(width = 4f, cap = StrokeCap.Round)
                    )
                }
            }

            // Position marker — always at the canvas centre, white ring + dot.
            drawCircle(color = fg.copy(alpha = 0.20f), radius = 18f, center = Offset(cx, cy))
            drawCircle(color = Color.White, radius = 11f, center = Offset(cx, cy))
            drawCircle(color = fg, radius = 8f, center = Offset(cx, cy))
        }
    }
}
