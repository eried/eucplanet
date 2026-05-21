package com.eried.eucplanet.ui.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.data.model.OverlayPreset
import com.eried.eucplanet.data.model.ViewportConfig
import com.eried.eucplanet.data.model.ViewportLayout
import com.eried.eucplanet.data.model.ViewportSourceType
import com.eried.eucplanet.ui.studio.camera.StudioCameraHub
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A pane's rectangle, expressed as 0..1 fractions of the studio surface. */
data class PaneRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** Smallest fraction a divider may be dragged to, so no pane collapses away. */
private const val MIN_PANE = 0.12f

/** Pane rectangles for [layout] given current [dividers] (clamped, ordered). */
fun paneRects(layout: ViewportLayout, dividers: List<Float>): List<PaneRect> {
    fun d(i: Int, fallback: Float) = dividers.getOrNull(i)?.coerceIn(MIN_PANE, 1f - MIN_PANE)
        ?: fallback
    return when (layout) {
        ViewportLayout.SINGLE -> listOf(PaneRect(0f, 0f, 1f, 1f))
        ViewportLayout.ROWS_2 -> {
            val a = d(0, 0.5f)
            listOf(PaneRect(0f, 0f, 1f, a), PaneRect(0f, a, 1f, 1f - a))
        }
        ViewportLayout.COLUMNS_2 -> {
            val a = d(0, 0.5f)
            listOf(PaneRect(0f, 0f, a, 1f), PaneRect(a, 0f, 1f - a, 1f))
        }
        ViewportLayout.ROWS_3 -> {
            val a = d(0, 1f / 3f)
            val b = maxOf(d(1, 2f / 3f), a + MIN_PANE)
            listOf(
                PaneRect(0f, 0f, 1f, a),
                PaneRect(0f, a, 1f, b - a),
                PaneRect(0f, b, 1f, 1f - b)
            )
        }
        ViewportLayout.COLUMNS_3 -> {
            val a = d(0, 1f / 3f)
            val b = maxOf(d(1, 2f / 3f), a + MIN_PANE)
            listOf(
                PaneRect(0f, 0f, a, 1f),
                PaneRect(a, 0f, b - a, 1f),
                PaneRect(b, 0f, 1f - b, 1f)
            )
        }
        ViewportLayout.GRID_4 -> {
            val x = d(0, 0.5f)
            val y = d(1, 0.5f)
            listOf(
                PaneRect(0f, 0f, x, y),
                PaneRect(x, 0f, 1f - x, y),
                PaneRect(0f, y, x, 1f - y),
                PaneRect(x, y, 1f - x, 1f - y)
            )
        }
    }
}

/**
 * Draws the viewport panes (camera or solid backgrounds), the divider lines,
 * and — when [editable] — the per-pane source button and the divider handles.
 */
@Composable
fun StudioViewportLayer(
    preset: OverlayPreset,
    hub: StudioCameraHub,
    hasCameraPermission: Boolean,
    editable: Boolean,
    replayMode: Boolean = false,
    onDividerChange: (List<Float>) -> Unit,
    onConfigViewport: (Int) -> Unit,
    onConfigDivider: () -> Unit,
    onTapEmpty: () -> Unit,
    onDoubleTapEmpty: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (replayMode) {
        // Replay shows the overlays only — the viewport background is transparent.
        Box(modifier.fillMaxSize())
        return
    }
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val w = maxWidth
        val h = maxHeight
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val rects = paneRects(preset.layout, preset.dividers)

        rects.forEachIndexed { index, r ->
            val config = preset.viewports.getOrNull(index)
            Box(
                Modifier
                    .offset(x = w * r.left, y = h * r.top)
                    .size(width = w * r.width, height = h * r.height)
                    .clip(RectangleShape)
                    .then(
                        if (editable) Modifier.pointerInput(index) {
                            detectTapGestures(
                                onTap = { onTapEmpty() },
                                onDoubleTap = { onDoubleTapEmpty() }
                            )
                        } else Modifier
                    )
            ) {
                when (config?.source) {
                    ViewportSourceType.SOLID ->
                        Box(Modifier.fillMaxSize().background(Color(config.solidColor)))
                    ViewportSourceType.IMAGE -> ViewportImagePane(config.imageData)
                    ViewportSourceType.GRADIENT -> ViewportGradientPane(config)
                    else -> CameraPane(
                        hub = hub,
                        cameraKey = config?.cameraKey ?: "BACK",
                        hasPermission = hasCameraPermission
                    )
                }
                if (editable) {
                    PaneButton(
                        icon = Icons.Default.Build,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) { onConfigViewport(index) }
                }
            }
        }

        // Divider lines are always drawn; the drag/config handles only in edit.
        DividerLayer(
            preset = preset,
            widthPx = widthPx,
            heightPx = heightPx,
            editable = editable,
            onDividerChange = onDividerChange,
            onConfigDivider = onConfigDivider
        )
    }
}

@Composable
private fun CameraPane(hub: StudioCameraHub, cameraKey: String, hasPermission: Boolean) {
    Box(Modifier.fillMaxSize().background(Color(0xFF14141A)), Alignment.Center) {
        val frame = hub.frame(cameraKey)
        when {
            frame != null -> Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            !hasPermission -> PaneMessage(Icons.Default.VideocamOff, "Camera access needed")
            hub.isLive(cameraKey) -> Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = Color(0xFF555555),
                modifier = Modifier.size(40.dp)
            )
            hub.info(cameraKey) != null -> PaneMessage(
                Icons.Default.VideocamOff,
                "${hub.info(cameraKey)?.label ?: "Camera"} can't run alongside " +
                    "another camera on this device"
            )
            else -> PaneMessage(Icons.Default.VideocamOff, "Camera unavailable")
        }
    }
}

/** Builds the [Brush] for a gradient viewport, sized to the current draw area. */
fun DrawScope.studioGradientBrush(config: ViewportConfig): Brush {
    val stops = config.gradientColors.indices.map { i ->
        config.gradientStops.getOrElse(i) { i.toFloat() }.coerceIn(0f, 1f) to
            Color(config.gradientColors[i])
    }.sortedBy { it.first }
    if (stops.isEmpty()) return SolidColor(Color.Black)
    if (stops.size == 1) return SolidColor(stops[0].second)
    val arr = stops.toTypedArray()
    return if (config.gradientRadial) {
        Brush.radialGradient(
            *arr,
            center = Offset(size.width / 2f, size.height / 2f),
            radius = (maxOf(size.width, size.height) / 2f).coerceAtLeast(1f)
        )
    } else {
        val rad = Math.toRadians(config.gradientAngle.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = (abs(dx) * size.width + abs(dy) * size.height) / 2f
        Brush.linearGradient(
            *arr,
            start = Offset(cx - dx * half, cy - dy * half),
            end = Offset(cx + dx * half, cy + dy * half)
        )
    }
}

@Composable
private fun ViewportGradientPane(config: ViewportConfig) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(brush = studioGradientBrush(config)) }
    )
}

@Composable
private fun ViewportImagePane(imageData: String?) {
    Box(Modifier.fillMaxSize().background(Color(0xFF14141A)), Alignment.Center) {
        val bitmap = remember(imageData) {
            imageData?.let { StudioImages.decode(it)?.asImageBitmap() }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PaneMessage(Icons.Default.Image, "Tap the wrench to choose an image")
        }
    }
}

@Composable
private fun PaneMessage(icon: ImageVector, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = Color(0xFF888888),
            modifier = Modifier.size(40.dp))
        Text(
            text,
            color = Color(0xFF888888),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PaneButton(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    // A quarter-circle tucked into the pane's top-right corner — its two
    // straight edges sit flush with the source frame, only the inner
    // (bottom-left) corner is rounded.
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(bottomStart = 44.dp))
            .background(Color(0x99000000))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.TopEnd
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .padding(7.dp)
                .size(18.dp)
                .rotate(-LocalStudioRotation.current.toFloat())
        )
    }
}

// --------------------------------------------------------------------------
// Dividers — the line is purely visual; resizing is only via the drag handle.
// --------------------------------------------------------------------------

@Composable
private fun BoxWithConstraintsScope.DividerLayer(
    preset: OverlayPreset,
    widthPx: Float,
    heightPx: Float,
    editable: Boolean,
    onDividerChange: (List<Float>) -> Unit,
    onConfigDivider: () -> Unit
) {
    val w = maxWidth
    val h = maxHeight
    val layout = preset.layout
    val color = Color(preset.dividerColor)
    val thickness = preset.dividerThickness.dp
    val dividers = preset.dividers

    fun replace(index: Int, value: Float): List<Float> =
        dividers.toMutableList().also { if (index in it.indices) it[index] = value }

    if (layout == ViewportLayout.GRID_4) {
        // The two dividers cross at the centre, so their handles are pushed
        // off-centre (vertical handle high, horizontal handle right) to keep
        // them from overlapping.
        VerticalDivider(
            fraction = dividers.getOrElse(0) { 0.5f },
            w = w, h = h, widthPx = widthPx, color = color, thickness = thickness,
            editable = editable, handleCross = 0.25f, onConfig = onConfigDivider
        ) { v -> onDividerChange(listOf(v, dividers.getOrElse(1) { 0.5f })) }
        HorizontalDivider(
            fraction = dividers.getOrElse(1) { 0.5f },
            w = w, h = h, heightPx = heightPx, color = color, thickness = thickness,
            editable = editable, handleCross = 0.75f, onConfig = onConfigDivider
        ) { v -> onDividerChange(listOf(dividers.getOrElse(0) { 0.5f }, v)) }
        return
    }

    val vertical = layout == ViewportLayout.COLUMNS_2 || layout == ViewportLayout.COLUMNS_3
    val horizontal = layout == ViewportLayout.ROWS_2 || layout == ViewportLayout.ROWS_3
    for (i in 0 until layout.dividerCount) {
        val value = dividers.getOrElse(i) { (i + 1f) / (layout.dividerCount + 1f) }
        if (vertical) {
            VerticalDivider(
                fraction = value, w = w, h = h, widthPx = widthPx,
                color = color, thickness = thickness, editable = editable,
                onConfig = onConfigDivider
            ) { v -> onDividerChange(replace(i, v)) }
        } else if (horizontal) {
            HorizontalDivider(
                fraction = value, w = w, h = h, heightPx = heightPx,
                color = color, thickness = thickness, editable = editable,
                onConfig = onConfigDivider
            ) { v -> onDividerChange(replace(i, v)) }
        }
    }
}

@Composable
private fun VerticalDivider(
    fraction: Float,
    w: Dp,
    h: Dp,
    widthPx: Float,
    color: Color,
    thickness: Dp,
    editable: Boolean,
    handleCross: Float = 0.5f,
    onConfig: () -> Unit,
    onChange: (Float) -> Unit
) {
    Box(
        Modifier
            .offset(x = w * fraction - thickness / 2)
            .size(width = thickness, height = h)
            .background(color)
    )
    if (editable) {
        Box(
            Modifier
                .offset(x = w * fraction - 40.dp, y = h * handleCross - 21.dp)
                .size(width = 80.dp, height = 42.dp),
            contentAlignment = Alignment.Center
        ) {
            DividerControls(
                resizeIcon = Icons.Default.DragIndicator,
                onConfig = onConfig,
                onDrag = { delta ->
                    onChange((fraction + delta / widthPx).coerceIn(MIN_PANE, 1f - MIN_PANE))
                },
                dragAxisX = true
            )
        }
    }
}

@Composable
private fun HorizontalDivider(
    fraction: Float,
    w: Dp,
    h: Dp,
    heightPx: Float,
    color: Color,
    thickness: Dp,
    editable: Boolean,
    handleCross: Float = 0.5f,
    onConfig: () -> Unit,
    onChange: (Float) -> Unit
) {
    Box(
        Modifier
            .offset(y = h * fraction - thickness / 2)
            .size(width = w, height = thickness)
            .background(color)
    )
    if (editable) {
        Box(
            Modifier
                .offset(x = w * handleCross - 40.dp, y = h * fraction - 21.dp)
                .size(width = 80.dp, height = 42.dp),
            contentAlignment = Alignment.Center
        ) {
            DividerControls(
                resizeIcon = Icons.Default.DragIndicator,
                onConfig = onConfig,
                onDrag = { delta ->
                    onChange((fraction + delta / heightPx).coerceIn(MIN_PANE, 1f - MIN_PANE))
                },
                dragAxisX = false
            )
        }
    }
}

/** The handle pill: left half drags to resize, right half opens line options. */
@Composable
private fun DividerControls(
    resizeIcon: ImageVector,
    onConfig: () -> Unit,
    onDrag: (Float) -> Unit,
    dragAxisX: Boolean
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xDD2A2A33)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .pointerInput(dragAxisX) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onDrag(if (dragAxisX) drag.x else drag.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(resizeIcon, contentDescription = "Resize section", tint = Color.White,
                modifier = Modifier.size(20.dp))
        }
        Box(
            Modifier
                .size(38.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { onConfig() }) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Build, contentDescription = "Divider options", tint = Color.White,
                modifier = Modifier.size(17.dp)
                    .rotate(-LocalStudioRotation.current.toFloat()))
        }
    }
}
