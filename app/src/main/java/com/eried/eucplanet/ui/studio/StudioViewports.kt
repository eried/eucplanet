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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
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

/**
 * Pane rectangles for [layout] given current [dividers] (clamped, ordered).
 * The layout is fixed — it does not transpose with device orientation, so the
 * geometry the rider picked is exactly what records. ([landscape] is kept for
 * call-site compatibility but no longer changes the result.)
 */
fun paneRects(
    layout: ViewportLayout,
    dividers: List<Float>,
    landscape: Boolean = false
): List<PaneRect> {
    fun d(i: Int, fallback: Float) = dividers.getOrNull(i)?.coerceIn(MIN_PANE, 1f - MIN_PANE)
        ?: fallback
    val rects: List<PaneRect> = when (layout) {
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
    // Fixed geometry: a "2 rows" split stays 2 rows whatever the device
    // orientation, so panes never change shape under the rider.
    return rects
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
        val landscape = LocalStudioRotation.current.let { it == 90 || it == 270 }
        val rects = paneRects(preset.layout, preset.dividers, landscape)

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
                    ViewportSourceType.IMAGE ->
                        ViewportImagePane(config.imageData, config)
                    ViewportSourceType.GRADIENT -> ViewportGradientPane(config)
                    else -> CameraPane(
                        hub = hub,
                        cameraKey = config?.cameraKey ?: "BACK",
                        hasPermission = hasCameraPermission,
                        config = config ?: ViewportConfig()
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
            landscape = landscape,
            editable = editable,
            onDividerChange = onDividerChange,
            onConfigDivider = onConfigDivider
        )
    }
}

/**
 * Maps a [ViewportConfig.fitMode] string to a Compose [ContentScale].
 * Pure GPU scaling — no per-frame pixel work.
 */
private fun contentScaleOf(fitMode: String): ContentScale = when (fitMode) {
    "FIT" -> ContentScale.Fit
    "CENTER" -> ContentScale.None
    else -> ContentScale.Crop
}

/**
 * Builds the colour-grading [ColorMatrix] for a viewport (filter preset +
 * saturation + contrast + brightness), or null when everything is neutral so
 * the [Image] can skip the colour filter entirely. Pure GPU — the matrix is
 * applied once by the renderer with [ColorFilter.colorMatrix], no pixel work.
 */
private fun viewportColorMatrix(config: ViewportConfig): ColorMatrix? {
    val preset = config.colorFilter
    val b = config.brightness.coerceIn(-1f, 1f)
    val c = config.contrast.coerceIn(0f, 2f)
    val s = config.saturation.coerceIn(0f, 2f)
    // Neutral on every axis — no colour filter needed at all.
    if (preset == "NONE" && b == 0f && c == 1f && s == 1f) return null

    // 1. Preset base matrix.
    val m = when (preset) {
        "BW" -> ColorMatrix().apply { setToSaturation(0f) }
        "SEPIA" -> ColorMatrix().apply {
            setToSaturation(0f)
            // Warm brown tint mapped onto the now-grey image.
            timesAssign(
                ColorMatrix(
                    floatArrayOf(
                        1.07f, 0f, 0f, 0f, 0f,
                        0f, 0.74f, 0f, 0f, 0f,
                        0f, 0f, 0.43f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        "WARM" -> ColorMatrix(
            floatArrayOf(
                1.10f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 0.90f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        "COOL" -> ColorMatrix(
            floatArrayOf(
                0.90f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1.10f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        "VIVID" -> ColorMatrix().apply {
            // Punchy — saturation x1.4, then contrast x1.15.
            setToSaturation(1.4f)
            val vc = 1.15f
            val vco = (1f - vc) * 0.5f * 255f
            timesAssign(
                ColorMatrix(
                    floatArrayOf(
                        vc, 0f, 0f, 0f, vco,
                        0f, vc, 0f, 0f, vco,
                        0f, 0f, vc, 0f, vco,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        "NOIR" -> ColorMatrix().apply {
            // High-contrast black & white — saturation 0, then contrast x1.35.
            setToSaturation(0f)
            val nc = 1.35f
            val nco = (1f - nc) * 0.5f * 255f
            timesAssign(
                ColorMatrix(
                    floatArrayOf(
                        nc, 0f, 0f, 0f, nco,
                        0f, nc, 0f, 0f, nco,
                        0f, 0f, nc, 0f, nco,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        "VINTAGE" -> ColorMatrix().apply {
            // Faded film — saturation x0.75, a warm tint, lifted blacks (+12).
            setToSaturation(0.75f)
            timesAssign(
                ColorMatrix(
                    floatArrayOf(
                        1.10f, 0f, 0f, 0f, 12f,
                        0f, 1f, 0f, 0f, 12f,
                        0f, 0f, 0.88f, 0f, 12f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        "MATTE" -> {
            // Modern faded look — contrast x0.82 with lifted blacks (+18).
            val mc = 0.82f
            val mco = (1f - mc) * 0.5f * 255f + 18f
            ColorMatrix(
                floatArrayOf(
                    mc, 0f, 0f, 0f, mco,
                    0f, mc, 0f, 0f, mco,
                    0f, 0f, mc, 0f, mco,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        else -> ColorMatrix() // NONE — identity
    }

    // 2. Saturation.
    m.timesAssign(ColorMatrix().apply { setToSaturation(s) })

    // 3. Contrast — diagonal scale c, offset (1-c)*0.5 on the 0..255 scale.
    val contrastOffset = (1f - c) * 0.5f * 255f
    m.timesAssign(
        ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, contrastOffset,
                0f, c, 0f, 0f, contrastOffset,
                0f, 0f, c, 0f, contrastOffset,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    // 4. Brightness — add b*255 to the RGB translate column.
    val brightOffset = b * 255f
    m.timesAssign(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, brightOffset,
                0f, 1f, 0f, 0f, brightOffset,
                0f, 0f, 1f, 0f, brightOffset,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    return m
}

@Composable
private fun CameraPane(
    hub: StudioCameraHub,
    cameraKey: String,
    hasPermission: Boolean,
    config: ViewportConfig
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF14141A)), Alignment.Center) {
        val frame = hub.frame(cameraKey)
        val grade = remember(
            config.colorFilter, config.brightness, config.contrast, config.saturation
        ) { viewportColorMatrix(config) }
        val zoom = config.zoom.coerceIn(1f, 3f)
        when {
            // Mirror / orientation / zoom are GPU graphicsLayer transforms and
            // the colour grade a single GPU ColorMatrix — no per-frame pixel
            // work — applied to the camera image itself so the recording (which
            // captures pane content) picks them up.
            frame != null -> Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = contentScaleOf(config.fitMode),
                colorFilter = grade?.let { ColorFilter.colorMatrix(it) },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = (if (config.cameraMirror) -1f else 1f) * zoom
                        scaleY = zoom
                        rotationZ = config.cameraOrientation.toFloat()
                    }
            )
            !hasPermission -> PaneMessage(Icons.Default.VideocamOff, stringResource(R.string.studio_pane_camera_access_needed))
            hub.isLive(cameraKey) -> Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = Color(0xFF555555),
                modifier = Modifier.size(40.dp)
            )
            hub.info(cameraKey) != null -> PaneMessage(
                Icons.Default.VideocamOff,
                stringResource(R.string.studio_pane_camera_conflict, hub.info(cameraKey)?.label ?: stringResource(R.string.studio_viewport_camera_label))
            )
            else -> PaneMessage(Icons.Default.VideocamOff, stringResource(R.string.studio_pane_camera_unavailable))
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
private fun ViewportImagePane(imageData: String?, config: ViewportConfig) {
    Box(Modifier.fillMaxSize().background(Color(0xFF14141A)), Alignment.Center) {
        val bitmap = remember(imageData) {
            imageData?.let { StudioImages.decode(it)?.asImageBitmap() }
        }
        val grade = remember(
            config.colorFilter, config.brightness, config.contrast, config.saturation
        ) { viewportColorMatrix(config) }
        val zoom = config.zoom.coerceIn(1f, 3f)
        if (bitmap != null) {
            // Colour grade is a single GPU ColorMatrix, zoom a graphicsLayer
            // scale — no per-frame pixel work.
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = contentScaleOf(config.fitMode),
                colorFilter = grade?.let { ColorFilter.colorMatrix(it) },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                    }
            )
        } else {
            PaneMessage(Icons.Default.Image, stringResource(R.string.studio_pane_choose_image))
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
    // A tab on the pane's right edge, dropped well below the top so the
    // phone's rounded screen corner never hides it. Left corners fully
    // rounded, right corners flush square with the frame edge.
    Box(
        modifier
            .padding(top = 68.dp)
            .height(40.dp)
            .width(48.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
            .background(Color(0xCC000000))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
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
    landscape: Boolean,
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

    // Fixed split — columns always draw vertical dividers, rows horizontal,
    // regardless of device orientation (matches paneRects).
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
    // pointerInput keeps the closure it captured on first composition; without
    // this the drag would keep calling the original onDrag (built from the
    // *starting* fraction), so the divider nudged once and then stuck.
    val latestOnDrag by rememberUpdatedState(onDrag)
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
                        latestOnDrag(if (dragAxisX) drag.x else drag.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(resizeIcon, contentDescription = stringResource(R.string.studio_cd_resize_section), tint = Color.White,
                modifier = Modifier.size(20.dp))
        }
        Box(
            Modifier
                .size(38.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { onConfig() }) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Build, contentDescription = stringResource(R.string.studio_cd_divider_options), tint = Color.White,
                modifier = Modifier.size(17.dp)
                    .rotate(-LocalStudioRotation.current.toFloat()))
        }
    }
}
