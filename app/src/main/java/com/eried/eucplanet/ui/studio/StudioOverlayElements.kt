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
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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

/** Everything an overlay element needs to render itself with live data. */
data class StudioElementData(
    val wheelData: WheelData,
    val wheelName: String,
    val connected: Boolean,
    val history: List<StudioSample>,
    val cameraHub: com.eried.eucplanet.ui.studio.camera.StudioCameraHub,
    val speedUnit: String,
    val distanceUnit: String,
    val tempUnit: String
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
                        detectDragGestures { change, drag ->
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
                .then(
                    // Dashed marquee so the selection reads clearly over any
                    // background without hiding the element's own edges.
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
            // Opacity/rotation only affect the content, not the edit chrome.
            Box(
                Modifier.graphicsLayer {
                    alpha = element.opacity.coerceIn(0f, 1f)
                    rotationZ = element.rotationDeg
                }
            ) { content() }
        }

        if (selected) {
            // Config + delete buttons — clamped to stay on-screen even when the
            // element itself is dragged partly or fully off an edge.
            val density = LocalDensity.current
            val rowWpx = with(density) { 72.dp.toPx() }
            val rowHpx = with(density) { 34.dp.toPx() }
            val marginPx = with(density) { 6.dp.toPx() }
            val gapPx = with(density) { 8.dp.toPx() }
            val elemXpx = widthPx * element.x
            val elemYpx = heightPx * element.y
            val elemWpx = widthPx * element.width
            val rowX = (elemXpx + elemWpx - rowWpx)
                .coerceIn(marginPx, (widthPx - rowWpx - marginPx).coerceAtLeast(marginPx))
            val rowY = (elemYpx - rowHpx - gapPx)
                .coerceIn(marginPx, (heightPx - rowHpx - marginPx).coerceAtLeast(marginPx))
            Row(
                Modifier.offset {
                    IntOffset(
                        (rowX - elemXpx).roundToInt(),
                        (rowY - elemYpx).roundToInt()
                    )
                },
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ChromeButton(Icons.Default.Build, accent, onConfigure)
                ChromeButton(Icons.Default.Close, Color(0xFFE53935), onDelete)
            }
            // Resize handle, bottom-right corner of the element.
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
                    contentDescription = "Resize",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp).rotate(90f)
                )
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
    }
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
        Column(horizontalAlignment = Alignment.Start) {
            if (element.showLabel) {
                androidx.compose.material3.Text(
                    text = metric.label.uppercase(),
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
                text = "${metric.label.uppercase()}  ·  ${element.graphWindowSec}s",
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
            ).ifEmpty { metric.label }
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
                        text = metric.label.uppercase(),
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
