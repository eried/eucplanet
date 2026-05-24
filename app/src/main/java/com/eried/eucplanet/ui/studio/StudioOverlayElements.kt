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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeJoin
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
    val stopwatchMs: Long = 0L,
    /**
     * Live (~50 Hz) rolling G-Force trail buffer, shared by the G-Force dot
     * AND its comet trail — the dot is just the last sample in this list, so
     * they always stay visually connected (the dashboard's crosshair uses the
     * same trick). Empty in replay mode; the overlay then derives its trail
     * from [history] and the dot from the scrubbed wheelData row.
     */
    val liveGForceTrail: List<Offset> = emptyList(),
    /**
     * Base64 `data:image/png` URL of the rider's custom marker photo (set
     * in the Navigator), or null when none. The MAP overlay element draws
     * this in place of its default dot marker when its
     * [OverlayElement.mapUseCustomMarker] flag is on and a photo is set.
     */
    val riderMarkerPhotoDataUrl: String? = null
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
    snapToGrid: Boolean = false,
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
    // Draw the selected element LAST so it (and its handle chrome — delete,
    // configure, resize, rotate) lands on top of every other element. Without
    // this, the chrome of a back-of-z-order element can be occluded by other
    // elements in front of it, and tapping the configure icon ends up hitting
    // whatever is drawn over it. Original list order is preserved among the
    // non-selected siblings so the rider's chosen stacking is otherwise kept.
    val ordered = if (editable && selectedId != null &&
        shown.any { it.id == selectedId }
    ) {
        shown.filter { it.id != selectedId } + shown.first { it.id == selectedId }
    } else {
        shown
    }
    ordered.forEach { element ->
        // Keyed by id so Compose never reuses one element's box (and its edit
        // chrome) for another — that reuse made a stale onConfigure / onDelete
        // fire for the wrong element.
        key(element.id) {
            StudioElementBox(
                element = element,
                containerW = containerW,
                containerH = containerH,
                widthPx = widthPx,
                heightPx = heightPx,
                editable = editable,
                selected = editable && element.id == selectedId,
                snapToGrid = snapToGrid,
                onSelect = { onSelect(element.id) },
                onConfigure = { onConfigure(element.id) },
                onDelete = { onDelete(element.id) },
                onChange = onChange
            ) {
                ElementContent(element, data)
            }
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
    snapToGrid: Boolean = false,
    onSelect: () -> Unit,
    onConfigure: () -> Unit,
    onDelete: () -> Unit,
    onChange: (OverlayElement) -> Unit,
    content: @Composable () -> Unit
) {
    // 10 dp grid in canvas-fraction units. Used by both the drag and the
    // resize handlers when snapToGrid is on, so all four numbers (x, y,
    // width, height) land on the same 10 dp lattice everywhere. Density-
    // aware so the grid feels the same on phones and tablets (raw pixels
    // would be sub-dp on a high-density screen and snap to nothing
    // visible). 10 dp is coarse enough that the snap is immediately
    // obvious on drag without preventing fine positioning.
    val gridStepPx = with(LocalDensity.current) { 10.dp.toPx() }
    val gridX = gridStepPx / widthPx
    val gridY = gridStepPx / heightPx
    fun snapFx(v: Float, step: Float) = if (snapToGrid) (kotlin.math.round(v / step) * step) else v
    // Lower bound for resize: keep the rotate handle (bottom-start) and the
    // resize grip (bottom-end) visually separated. Each is 30 dp; below ~50 dp
    // the two grips start to overlap visually.
    val minWidthFrac = with(LocalDensity.current) { 50.dp.toPx() } / widthPx
    // While snap-to-grid is ON, render every element AT its snapped position
    // and size -- non-destructively, the saved values stay exact until the
    // rider actually moves / resizes the element themselves. Drag and resize
    // handlers compute their delta from these snapped values so the first
    // touch doesn't visually jump back to the un-snapped origin.
    val effX = snapFx(element.x, gridX)
    val effY = snapFx(element.y, gridY)
    val effW = snapFx(element.width, gridX)
    val effH = if (element.height > 0f) snapFx(element.height, gridY) else 0f
    val live by rememberUpdatedState(element)
    val accent = StudioControlAccent
    // A distinct accent for the rotate handle so it is never confused with the
    // resize grip (the studio's standard blue accent).
    val rotateAccent = Color(0xFFFFB74D)
    // Size of the element's content in px, captured from the rotated marquee
    // layer — i.e. the unrotated content bounds, so its centre is exact.
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .offset(x = containerW * effX, y = containerH * effY)
            // Belt-and-suspenders with the list reorder in StudioElementLayer:
            // give the selected element an explicit zIndex so even if some
            // ancestor compositor reshuffles drawing order, the element and
            // its handle chrome still float above un-selected siblings.
            .then(if (selected) Modifier.zIndex(2f) else Modifier)
    ) {
        Box(
            // widthIn(max) — not width() — so the selection frame and border
            // wrap the element's real content (text pills size to their text;
            // graphs / camera / image still fill the chosen width).
            //
            // heightIn(max) is applied ONLY when element.height > 0 (the rider
            // explicitly stretched it vertically); without that, the renderer
            // keeps using its natural aspect ratio and the bounding Box wraps
            // accordingly. With height > 0, the renderer fills the constrained
            // height and the fixed-ratio default is overridden.
            Modifier
                .widthIn(max = containerW * effW)
                .then(
                    if (effH > 0f)
                        // Floor the rendered height at the element's own
                        // minimum so over-zealous resizing can't shrink a
                        // widget (e.g. a linear bar) to nothing visible.
                        Modifier.heightIn(
                            min = minRenderedHeightDp(element.type),
                            max = containerH * effH
                        )
                    else Modifier
                )
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
                            // Drag starts from the SNAPPED visual position when
                            // snap-mode is on, not from the (possibly off-grid)
                            // saved value — otherwise the very first drag tick
                            // would jump the element back to its raw coords.
                            val baseX = snapFx(e.x, gridX)
                            val baseY = snapFx(e.y, gridY)
                            val nx = (baseX + drag.x / widthPx).coerceIn(-1f, 1f)
                            val ny = (baseY + drag.y / heightPx).coerceIn(-1f, 1f)
                            onChange(
                                e.copy(
                                    x = snapFx(nx, gridX),
                                    y = snapFx(ny, gridY)
                                )
                            )
                        }
                    } else Modifier
                )
                .then(
                    if (editable) Modifier.pointerInput(element.id) {
                        // Single tap selects the element (so the rider can
                        // drag / resize it); double-tap opens the Properties
                        // pane directly -- it's the "I want to edit THIS"
                        // gesture, parallel to double-tap-to-zoom on photos.
                        detectTapGestures(
                            onTap = { onSelect() },
                            onDoubleTap = { onConfigure() }
                        )
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
                        // GPU-cheap shadow: render the content() twice, once
                        // into an offscreen compositing layer that is
                        // translated and tinted to be the shadow, then
                        // normally on top as the foreground. The shadow Box
                        // uses matchParentSize so it sizes to whatever the
                        // foreground content's natural bounds turn out to be
                        // (the Box parent's size is determined by the
                        // foreground render below).
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
                                    compositingStrategy =
                                        CompositingStrategy.Offscreen
                                }
                                .blur(2.dp)
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
                    //
                    // wrapContentWidth(unbounded) lets the Row keep its full
                    // intrinsic width even when the element is narrower than
                    // the two buttons combined -- without it, a thin element
                    // (small badge, low-height bar) would clip the Close
                    // button down to nothing.
                    Row(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = (-42).dp)
                            .wrapContentWidth(
                                align = Alignment.End,
                                unbounded = true
                            ),
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
                                    // Drag the bottom-right grip in BOTH axes:
                                    // horizontal updates width, vertical updates
                                    // height (height stored as a fraction of the
                                    // canvas like width). If the rider has only
                                    // moved horizontally so far, height stays 0
                                    // and the natural aspect-ratio rule keeps
                                    // applying. As soon as they drag vertically
                                    // the value snaps to a non-zero height and
                                    // the renderer switches to free-resize.
                                    // Like the drag handler above: base the
                                    // delta off the SNAPPED dimensions so the
                                    // first resize tick doesn't jump back to
                                    // the raw saved size.
                                    val baseW = snapFx(e.width, gridX)
                                    val baseH = if (e.height > 0f)
                                        snapFx(e.height, gridY)
                                    else contentSize.height / heightPx
                                    val nw = (baseW + drag.x / widthPx)
                                        .coerceIn(minWidthFrac, 1.5f)
                                    val nh = (baseH + drag.y / heightPx)
                                        .coerceIn(0.04f, 1.5f)
                                    // Dials are hard-locked to an aspect (1:1
                                    // for full, 2:1 for semicircle); storing a
                                    // height does nothing useful and only
                                    // surfaces stale values in Manage. Keep
                                    // height at 0 so the renderer follows
                                    // aspectRatio. Other widgets store both.
                                    val newH = if (
                                        e.type == OverlayElementType.DATA_DIAL
                                    ) 0f else snapFx(nh, gridY)
                                    onChange(
                                        e.copy(
                                            width = snapFx(nw, gridX),
                                            height = newH
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
                }
            }
            // Rotate handle — a sibling of the rotated marquee, so it lives in
            // the un-rotated frame where the drag math has a stable coordinate
            // system. Measuring it INSIDE the rotated layer fed the rotation
            // back into the angle it was computing and spun wildly. It is still
            // POSITIONED at the element's rotated bottom-left corner so it looks
            // attached at any angle — the offset is frozen mid-drag (below) so
            // the coordinate frame can't move while the gesture computes angles.
            if (selected) {
                val density = LocalDensity.current
                val handlePx = with(density) { 30.dp.toPx() }

                // Element-content offset of the bottom-left corner rotated by
                // [deg] about the content centre, minus half the handle so the
                // handle's CENTRE lands on the corner. Pure layout maths — no
                // graphicsLayer — so the drag's coordinate frame stays stable.
                fun cornerOffset(deg: Float): IntOffset {
                    val w = contentSize.width.toFloat()
                    val h = contentSize.height.toFloat()
                    val cx = w / 2f
                    val cy = h / 2f
                    val rad = Math.toRadians(deg.toDouble())
                    val cos = kotlin.math.cos(rad).toFloat()
                    val sin = kotlin.math.sin(rad).toFloat()
                    // Bottom-left corner (0, h) relative to the centre.
                    val dx = 0f - cx
                    val dy = h - cy
                    val rx = cx + dx * cos - dy * sin
                    val ry = cy + dx * sin + dy * cos
                    return IntOffset(
                        (rx - handlePx / 2f).roundToInt(),
                        (ry - handlePx / 2f).roundToInt()
                    )
                }

                var startAngle = 0f
                var startRotation = 0f
                Box(
                    Modifier
                        // Follows the live rotated corner, so the handle turns
                        // with the element as it is dragged.
                        .offset { cornerOffset(live.rotationDeg) }
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(rotateAccent)
                        .pointerInput(element.id) {
                            // Pointer angle about the element centre, in the
                            // un-rotated frame. The origin is the handle's LIVE
                            // top-left, so origin + local recovers the true
                            // pointer position wherever the handle has turned
                            // to — it can follow the rotation without feeding
                            // back into the angle being computed.
                            fun angleAt(local: Offset): Float {
                                val origin = cornerOffset(live.rotationDeg)
                                val px = origin.x + local.x
                                val py = origin.y + local.y
                                return Math.toDegrees(
                                    kotlin.math.atan2(
                                        (py - contentSize.height / 2f).toDouble(),
                                        (px - contentSize.width / 2f).toDouble()
                                    )
                                ).toFloat()
                            }
                            detectDragGestures(
                                onDragStart = { pos ->
                                    startAngle = angleAt(pos)
                                    startRotation = live.rotationDeg
                                }
                            ) { change, _ ->
                                change.consume()
                                var next = startRotation +
                                    (angleAt(change.position) - startAngle)
                                // Snap to 15-degree increments.
                                next = Math.round(next / 15f) * 15f
                                next = ((next % 360f) + 360f) % 360f
                                onChange(live.copy(rotationDeg = next))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = stringResource(R.string.studio_cd_rotate),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
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
    // pointerInput(Unit) never restarts, so capture onClick through a state
    // holder — otherwise a reused button keeps calling a stale callback.
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0xDD1E1E26))
            .border(1.dp, tint, CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/**
 * Per-type lower bound on the rendered height. Free-resize lets riders shrink
 * elements vertically, but each type has a point below which it stops being a
 * usable widget (a linear bar with no visible fill, a label with clipped text).
 * Applied as a floor on the heightIn modifier so the saved value can still go
 * lower without breaking the render.
 */
private fun minRenderedHeightDp(type: OverlayElementType): androidx.compose.ui.unit.Dp = when (type) {
    OverlayElementType.DATA_BAR -> 32.dp
    OverlayElementType.DATA_GRAPH -> 36.dp
    OverlayElementType.MAP -> 60.dp
    OverlayElementType.FLOATING_CAMERA -> 60.dp
    else -> 16.dp
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

/** Max source samples used to build a G-Force trail Path. With the studio
 *  history sampled at ~10 Hz and trails capped at 20 s, the upper bound is
 *  ~200 points — we keep all of them up to this ceiling so the curve has
 *  proper resolution. The cost is still small (one quadratic-Bezier Path,
 *  built once per dial per frame, drawn in five overlapping passes). */
private const val TRAIL_MAX_POINTS = 200

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
    // Single source of truth for both trail and dot:
    //   • Live mode → the viewModel's rolling 50 Hz IMU trail. The dot is the
    //     last entry; the comet is the preceding samples. They cannot visually
    //     disconnect because they're the same data — the dashboard's
    //     crosshair uses this exact pattern.
    //   • Replay mode → derive the same shape from the trip CSV row history
    //     (already in `data.history` at ~10 Hz), so scrubbing the timeline
    //     animates the trail back through the recorded motion.
    val windowSec = element.graphWindowSec.coerceIn(2, 20)
    val trail = if (data.liveGForceTrail.isNotEmpty()) {
        // 50 Hz × seconds, plus 15% exit-fade buffer so the oldest segments
        // can fade their alpha to 0 *before* being removed from the array —
        // a hard cutoff makes the curve geometry visibly snap.
        val take = ((windowSec * 50f) * 1.15f).toInt().coerceAtLeast(2)
        val src = data.liveGForceTrail
        if (src.size > take) src.subList(src.size - take, src.size) else src
    } else {
        // Replay path: derive from the scrubbed trip history.
        val windowMs = windowSec * 1000L
        val extWindowMs = (windowMs * 115L) / 100L
        val last = data.history.lastOrNull()?.timeMs ?: 0L
        data.history
            .filter { it.timeMs >= last - extWindowMs }
            .map { Offset(it.data.accelX, it.data.accelY) }
    }
    // Outer-ring g value — the scale goes down to 0.25 g for tiny movements.
    val maxG = element.gForceScale.coerceIn(0.25f, 6f)
    // The dot sits at the most recent trail sample — that's how the dashboard
    // does it and why the two stay rigidly connected. No tween, no smoothing,
    // no separate source of dot position.
    val head = trail.lastOrNull()
    val liveG = if (head != null) {
        Offset(head.x.coerceIn(-maxG, maxG), head.y.coerceIn(-maxG, maxG))
    } else {
        Offset.Zero
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(element.background), CircleShape)
            .padding(10.dp)
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val unit = (w.coerceAtMost(h) / 2f) / maxG

            // Three concentric dashed rings at 1/3, 2/3 and the full scale.
            val grid = trailColor.copy(alpha = 0.4f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            listOf(maxG / 3f, maxG * 2f / 3f, maxG).forEach { r ->
                drawCircle(
                    color = grid,
                    radius = r * unit,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1f, pathEffect = dash)
                )
            }
            drawLine(grid, Offset(0f, cy), Offset(w, cy), strokeWidth = 1f, pathEffect = dash)
            drawLine(grid, Offset(cx, 0f), Offset(cx, h), strokeWidth = 1f, pathEffect = dash)

            // Comet tail. Two-pass smooth path:
            //   1) the *full* trail as a quadratic-smoothed Path — drawn once
            //      with a moderate stroke / low alpha (the older fading body).
            //   2) the most-recent third of the trail as a second Path — drawn
            //      thicker / brighter on top (the bright comet head).
            // One Path-build + two drawPath calls per dial replaces the per-
            // segment drawLine loop that was making four dials look glitchy and
            // expensive. drawPath uses Skia's native stroker so the curve looks
            // smooth even with a modest point count.
            if (trail.size >= 2) {
                val srcN = trail.size
                val stride = (srcN / TRAIL_MAX_POINTS).coerceAtLeast(1)
                // Down-sampled, screen-space trail. FloatArray pair avoids the
                // ~N Offset allocations the previous map { Offset(...) } made.
                val visN = ((srcN + stride - 1) / stride).coerceAtLeast(2)
                val xs = FloatArray(visN)
                val ys = FloatArray(visN)
                var w = 0
                var i = 0
                while (i < srcN && w < visN) {
                    val p = trail[i]
                    xs[w] = cx + p.x.coerceIn(-maxG, maxG) * unit
                    ys[w] = cy - p.y.coerceIn(-maxG, maxG) * unit
                    w++
                    i += stride
                }
                val n = w

                // Per-segment rendering with a continuous alpha curve AND a
                // smooth Bezier shape. Each iteration draws ONE quadratic
                // Bezier sub-path centred on a single source point — the path
                // starts at the midpoint with the previous point, curves
                // through the current point as the Bezier control, and ends
                // at the midpoint with the next point. Consecutive segments
                // share their endpoint midpoints, giving C1 continuity (no
                // visible kinks). Each segment gets its own alpha + width, so
                // the exit fade and head taper both work at the per-sample
                // resolution we need. The Path is reused across iterations
                // (reset() clears it) so there's no per-frame allocation.
                //
                // ~100 drawPath calls per dial × 4 dials = ~400 per frame.
                val maxAlpha = 0.85f       // head alpha (freshest)
                val exitFraction = 0.15f   // oldest 15% = exit fade
                val boundaryAlpha = 0.10f  // alpha at exit/body boundary
                val maxWidth = 14f         // stroke width at the tail
                val minWidth = 10f         // stroke width at the head
                val path = Path()
                for (k in 0 until n) {
                    val pos = k.toFloat() / (n - 1).coerceAtLeast(1)
                    val alpha = if (pos < exitFraction) {
                        (pos / exitFraction) * boundaryAlpha
                    } else {
                        boundaryAlpha + (pos - exitFraction) /
                            (1f - exitFraction) * (maxAlpha - boundaryAlpha)
                    }
                    if (alpha < 0.01f) continue
                    val width = maxWidth - pos * (maxWidth - minWidth)
                    path.reset()
                    // Start: midpoint with the previous sample (or the very
                    // first sample itself when there is no previous).
                    if (k == 0) {
                        path.moveTo(xs[0], ys[0])
                    } else {
                        path.moveTo(
                            (xs[k - 1] + xs[k]) * 0.5f,
                            (ys[k - 1] + ys[k]) * 0.5f
                        )
                    }
                    // Bezier through the current sample toward the midpoint
                    // with the next; degenerates to a lineTo for the tail.
                    if (k == n - 1) {
                        if (k > 0) path.lineTo(xs[k], ys[k])
                    } else {
                        path.quadraticBezierTo(
                            xs[k], ys[k],
                            (xs[k] + xs[k + 1]) * 0.5f,
                            (ys[k] + ys[k + 1]) * 0.5f
                        )
                    }
                    drawPath(
                        path = path,
                        color = trailColor.copy(alpha = alpha),
                        style = Stroke(
                            width = width,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            // Live dot — eased toward the current lateral / forward G-force.
            // Slightly larger than the previous radii so the head reads as the
            // focal point even with the brighter, thicker comet tail.
            val gx = liveG.x.coerceIn(-maxG, maxG)
            val gy = liveG.y.coerceIn(-maxG, maxG)
            val center = Offset(cx + gx * unit, cy - gy * unit)
            drawCircle(color = fg.copy(alpha = 0.15f), radius = 30f, center = center)
            drawCircle(color = fg.copy(alpha = 0.30f), radius = 23f, center = center)
            drawCircle(color = fg, radius = 17f, center = center)
            drawCircle(
                color = Color.White,
                radius = 17f,
                center = center,
                style = Stroke(width = 2.5f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 5f,
                center = Offset(center.x - 5f, center.y - 5f)
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
                val unit = metric.unitText(
                    context, data.speedUnit, data.distanceUnit, data.tempUnit
                )
                val valueText = metric.formatted(
                    data.wheelData, data.speedUnit, data.distanceUnit, data.tempUnit
                )
                val unitOnLeft = element.unitPosition == "LEFT"
                if (unit.isNotEmpty() && unitOnLeft) {
                    androidx.compose.material3.Text(
                        text = "$unit ",
                        color = Color(element.foreground).copy(alpha = 0.75f),
                        fontSize = (w * 0.12f).coerceIn(9f, 40f).sp,
                        modifier = Modifier.padding(bottom = (w * 0.04f).dp),
                        maxLines = 1
                    )
                }
                androidx.compose.material3.Text(
                    text = valueText,
                    color = Color(element.foreground),
                    fontWeight = FontWeight.Bold,
                    fontSize = (w * 0.34f).coerceIn(18f, 120f).sp,
                    maxLines = 1
                )
                if (unit.isNotEmpty() && !unitOnLeft) {
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
            .then(
                // Free-resize when the rider has dragged the bottom edge
                // (element.height > 0). Otherwise fall back to the default
                // 2.2:1 reading-friendly aspect.
                if (element.height > 0f) Modifier.fillMaxHeight()
                else Modifier.aspectRatio(2.2f)
            )
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
    val bg = Color(element.background)
    val isSemi = element.dialStyle == "SEMICIRCLE"
    // Aspect is HARD-LOCKED, see comment in StudioElementBox: the dial's
    // geometry only makes sense at 1:1 (full ring) or ~1.82:1 (semicircle).
    // The semicircle box is intentionally a touch TALLER than a pure 2:1
    // dome -- a small rounded-bottom strip below the diameter hides the
    // round-cap overshoot at the progress arc's endpoints and gives the
    // widget a finished silhouette instead of a hard-cut flat bottom.
    // 0.58 = (dome radius w/2) + (strip 8% of width) divided by w. The
    // strip is generous enough to fully enclose the progress-arc round
    // caps and give the dial a substantial flat bottom that doesn't read
    // as a hairline.
    val aspect = if (isSemi) (1f / 0.58f) else 1f
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect),
        contentAlignment = if (isSemi) Alignment.BottomCenter else Alignment.Center
    ) {
        val w = maxWidth.value
        // Background + progress arc share one Canvas so the geometry stays in
        // perfect lockstep at every size.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val pad = 12.dp.toPx()
            if (isSemi) {
                // Dome (top half of a circle radius = w/2 centred at
                // (w/2, w/2)) plus a strip below it that fills the remaining
                // height with rounded bottom corners. Strip height = total
                // height - dome radius.
                val w = size.width
                val h = size.height
                val r = w / 2f
                val cx = w / 2f
                val domeBottom = r
                val stripH = (h - r).coerceAtLeast(0f)
                val cornerR = stripH.coerceAtMost(12.dp.toPx())
                val bgPath = androidx.compose.ui.graphics.Path().apply {
                    // Traverse clockwise: start at left end of diameter,
                    // up over the dome, down the right strip, round the
                    // bottom-right, across, round the bottom-left, back up
                    // to the start.
                    moveTo(0f, domeBottom)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(0f, 0f, w, w),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = 180f,
                        forceMoveTo = false
                    )
                    lineTo(w, h - cornerR)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(
                            w - 2f * cornerR, h - 2f * cornerR, w, h
                        ),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(cornerR, h)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(
                            0f, h - 2f * cornerR, 2f * cornerR, h
                        ),
                        startAngleDegrees = 90f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    close()
                }
                drawPath(bgPath, color = bg)
                // Progress arc -- a stroked half-circle whose diameter sits
                // exactly on the dome's flat edge (the meeting line with
                // the bottom strip). The strip below absorbs any round-cap
                // overshoot.
                val strokeW = (r * 0.18f).coerceAtMost(r * 0.5f)
                val progR = r - pad - strokeW / 2f
                val cy = domeBottom
                val progRect = androidx.compose.ui.geometry.Rect(
                    cx - progR, cy - progR, cx + progR, cy + progR
                )
                drawArc(
                    color = track,
                    startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = progRect.topLeft, size = progRect.size,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
                drawArc(
                    color = fill,
                    startAngle = 180f, sweepAngle = 180f * fraction, useCenter = false,
                    topLeft = progRect.topLeft, size = progRect.size,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
            } else {
                // Square dial: circle inscribed, 270 deg arc starting at
                // 135 deg (bottom-left), sweeping clockwise to bottom-right.
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
                drawArc(
                    color = track,
                    startAngle = 135f, sweepAngle = 270f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
                drawArc(
                    color = fill,
                    startAngle = 135f, sweepAngle = 270f * fraction, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
            }
        }
        // Value + unit text. SEMICIRCLE places them just above the flat
        // diameter (where the user expects to read the speedometer-style
        // gauge); FULL centres them in the ring as before. Font sizes scale
        // off the dial's drawable width so the readout is legible at every
        // size between the new 50 dp minimum and a full-canvas dial.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Lift the readout above the strip so the text sits inside the
            // dome, not over the rounded-bottom strip. ~14% of w lands it
            // safely above the (now thicker) strip's top edge at every size.
            modifier = if (isSemi)
                Modifier.padding(bottom = (w * 0.14f).dp)
            else Modifier
        ) {
            androidx.compose.material3.Text(
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
            androidx.compose.material3.Text(
                text = unit,
                color = fill.copy(alpha = 0.7f),
                fontSize = (w * (if (isSemi) 0.07f else 0.085f)).coerceIn(8f, 22f).sp,
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
                if (element.barShowValue) {
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
            .then(
                if (element.height > 0f) Modifier.fillMaxHeight()
                else Modifier.aspectRatio(1f)
            )
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

        // Decode the rider's custom marker photo once per data-url so the
        // canvas can stamp it on every frame without re-parsing base64. The
        // result already has a circular alpha mask burned in (see
        // UserMarkerCropDialog.renderCircleCrop), so we just draw it as-is.
        // Null when the toggle is off OR no photo is set OR decode failed.
        val markerPhoto: ImageBitmap? = remember(
            element.mapUseCustomMarker, data.riderMarkerPhotoDataUrl
        ) {
            if (!element.mapUseCustomMarker) return@remember null
            val url = data.riderMarkerPhotoDataUrl ?: return@remember null
            val comma = url.indexOf(',')
            if (comma <= 0) return@remember null
            runCatching {
                val bytes = android.util.Base64.decode(
                    url.substring(comma + 1), android.util.Base64.DEFAULT
                )
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?.asImageBitmap()
            }.getOrNull()
        }

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
                    // A dark casing under the bright line so it reads on any
                    // tile. Both strokes are deliberately bold so the trace
                    // is easy to follow on a video frame: bright line 10 px,
                    // casing 16 px (≈ 2.5x the original 4 / 7 widths).
                    drawPath(
                        path = path,
                        color = Color.Black.copy(alpha = 0.45f),
                        style = Stroke(width = 16f, cap = StrokeCap.Round)
                    )
                    drawPath(
                        path = path,
                        color = fg,
                        style = Stroke(width = 10f, cap = StrokeCap.Round)
                    )
                }
            }

            // Position marker — mirrors the Navigator's idle / customized
            // marker styling so the rider's "you are here" reads the same in
            // both places. Size is proportional to the widget (this widget
            // can be tiny or huge depending on element.width), so the marker
            // always lands at the same visual weight relative to its frame
            // rather than getting lost on a big widget or eating a small one.
            // Clamped to sane absolute bounds so it doesn't disappear on a
            // mini widget or balloon on a maximised one.
            val photo = markerPhoto
            val widgetMin = kotlin.math.min(w, h)
            val headD = if (photo != null)
                (widgetMin * 0.14f).coerceIn(46f, 96f)
            else
                // Slightly smaller for the plain marker -- 8% of widget min,
                // clamped 26..54 -- so it sits modestly between map labels
                // rather than dominating the trace.
                (widgetMin * 0.08f).coerceIn(26f, 54f)
            val headR = headD / 2f
            // Thin black outline + soft drop shadow, just like the Navigator's
            // .user-pin-body CSS, so the marker pops on any tile style.
            val borderW = (headD * 0.10f).coerceAtLeast(2.5f)
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = headR + 2.5f,
                center = Offset(cx, cy + 2f)   // soft drop shadow
            )
            if (photo != null) {
                // White outline first so the photo separates from busy tiles.
                drawCircle(color = Color.White, radius = headR, center = Offset(cx, cy))
                // Force the photo into a circular clip rather than trusting the
                // bitmap's own alpha mask -- riders complained the customized
                // marker rendered as "a square with a circle on top", which
                // happens when the stored PNG's transparent corners didn't make
                // it through (older crop-dialog output, hardware-accelerated
                // PorterDuff quirks, etc.). The clip is authoritative now.
                val clipPath = Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            cx - headR, cy - headR, cx + headR, cy + headR
                        )
                    )
                }
                clipPath(clipPath) {
                    drawImage(
                        image = photo,
                        dstOffset = androidx.compose.ui.unit.IntOffset(
                            (cx - headR).roundToInt(), (cy - headR).roundToInt()
                        ),
                        dstSize = androidx.compose.ui.unit.IntSize(
                            headD.roundToInt(), headD.roundToInt()
                        )
                    )
                }
                drawCircle(
                    color = Color.Black, radius = headR,
                    center = Offset(cx, cy),
                    style = Stroke(width = borderW)
                )
            } else {
                drawCircle(color = fg, radius = headR, center = Offset(cx, cy))
                drawCircle(
                    color = Color.Black, radius = headR,
                    center = Offset(cx, cy),
                    style = Stroke(width = borderW)
                )
            }
        }
    }
}
