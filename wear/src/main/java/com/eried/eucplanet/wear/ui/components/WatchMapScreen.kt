package com.eried.eucplanet.wear.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.background as curvedBackground
import androidx.wear.compose.foundation.basicCurvedText
import androidx.wear.compose.foundation.sizeIn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.eried.eucplanet.hud.protocol.MapLayers
import com.eried.eucplanet.hud.protocol.WatchMapLocationStatus
import com.eried.eucplanet.hud.protocol.WatchMapProtocol
import com.eried.eucplanet.hud.protocol.WatchMapSnapshot
import com.eried.eucplanet.hud.protocol.WatchMapTileKey
import com.eried.eucplanet.wear.R
import com.eried.eucplanet.wear.bridge.WatchMapRepository
import com.eried.eucplanet.wear.bridge.WatchState
import com.eried.eucplanet.wear.bridge.WatchStateRepository
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import androidx.compose.ui.platform.LocalConfiguration
import com.eried.eucplanet.wear.ui.components.preview.WatchLargeRoundPreview
import com.eried.eucplanet.wear.ui.components.preview.WatchPreviewData
import com.eried.eucplanet.wear.ui.components.preview.WatchPreviewFixtures
import com.eried.eucplanet.wear.ui.components.preview.WatchSmallRoundPreview
import com.eried.eucplanet.wear.ui.utils.LocalWatchColors
import com.eried.eucplanet.wear.ui.utils.WatchTheme
import com.eried.eucplanet.wear.ui.utils.WatchUnits
import com.eried.eucplanet.wear.ui.utils.rememberSecondTick


@Composable
fun WatchMapScreen(
    snapshot: WatchMapSnapshot,
    zoom: Int,
    onZoomChange: (Int) -> Unit,
    showTelemetry: Boolean,
) {
    val bitmaps by WatchMapRepository.tiles.collectAsStateWithLifecycle()
    val localFailures by WatchMapRepository.tileFailures.collectAsStateWithLifecycle()
    val imageBitmaps: Map<WatchMapTileKey, ImageBitmap> = remember(bitmaps) {
        bitmaps.mapValues { it.value.asImageBitmap() }
    }
    WatchMapContent(
        snapshot = snapshot,
        zoom = zoom,
        onZoomChange = onZoomChange,
        showTelemetry = showTelemetry,
        imageBitmaps = imageBitmaps,
        localFailures = localFailures,
        onVisibleTilesChanged = WatchMapRepository::setVisibleTiles,
        telemetryOverlay = { MapTelemetryOverlay() },
    )
}

@Composable
private fun WatchMapContent(
    snapshot: WatchMapSnapshot,
    zoom: Int,
    onZoomChange: (Int) -> Unit,
    showTelemetry: Boolean,
    imageBitmaps: Map<WatchMapTileKey, ImageBitmap>,
    localFailures: Set<WatchMapTileKey>,
    onVisibleTilesChanged: (List<WatchMapTileKey>) -> Unit,
    telemetryOverlay: @Composable () -> Unit,
){
    val colors = LocalWatchColors.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val frame = snapshot.frame
    val center = frame?.fix?.point ?: frame?.anchor
    val layer = remember(frame?.layerId) { MapLayers.byId(frame?.layerId.orEmpty()) }
    val cameraZoom = zoom.coerceIn(WatchMapProtocol.MIN_ZOOM, WatchMapProtocol.MAX_ZOOM)
    val nativeZoom = minOf(cameraZoom, layer.maxNativeZoom)
    val tileSize = 256.0 * 2.0.pow((cameraZoom - nativeZoom).toDouble())

    var headingTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(frame?.headingDeg) {
        frame?.headingDeg?.let { heading ->
            val normalizedCurrent = normalizeDegrees(headingTarget)
            val delta = shortestDegrees(normalizedCurrent, normalizeDegrees(heading))
            headingTarget += delta
        }
    }
    val animatedHeading by animateFloatAsState(
        targetValue = headingTarget,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "watchMapHeading",
    )
    val cameraRotation = if (frame?.headingUp == true && frame.headingDeg != null) {
        -animatedHeading
    } else {
        0f
    }

    val projection = remember(center, nativeZoom, tileSize, cameraRotation, viewport) {
        if (center == null || viewport.width <= 0 || viewport.height <= 0) {
            null
        } else {
            WatchMapProjection(center, nativeZoom, tileSize, cameraRotation, viewport)
        }
    }
    val renderTiles = remember(projection, layer.id) {
        projection?.visibleTiles(layer.id).orEmpty()
    }
    LaunchedEffect(renderTiles) {
        onVisibleTilesChanged(renderTiles.map { it.key })
    }
    val projectedRoute = remember(snapshot.route, nativeZoom) {
        projectRoute(snapshot.route, nativeZoom)
    }
    val routePath = remember(projection, projectedRoute) {
        projection?.let { camera ->
            var hasSegment = false
            Path().also { path ->
                camera.forEachVisibleRouteSegment(projectedRoute) { from, to ->
                    path.moveTo(from.x, from.y)
                    path.lineTo(to.x, to.y)
                    hasSegment = true
                }
            }.takeIf { hasSegment }
        }
    }
    val targetPosition = remember(projection, frame?.target) {
        val target = frame?.target
        if (target == null || projection == null) {
            null
        } else {
            projection.screenPosition(target)
                .takeIf { it.x in 0f..viewport.width.toFloat() && it.y in 0f..viewport.height.toFloat() }
        }
    }

    val visibleKeys = remember(renderTiles) { renderTiles.mapTo(mutableSetOf()) { it.key } }
    val remoteFailures = frame?.unavailableTiles.orEmpty().toSet()
    val missingKeys = visibleKeys.filterNotTo(mutableSetOf()) { it in imageBitmaps }
    val failedKeys = remember(remoteFailures, localFailures) {
        remoteFailures + localFailures
    }
    val drawPlan = remember(renderTiles, imageBitmaps, failedKeys) {
        tileDrawPlan(renderTiles, imageBitmaps.keys, failedKeys)
    }
    val mapFailed = missingKeys.any { it in remoteFailures || it in localFailures }
    val mapLoading = missingKeys.isNotEmpty() && !mapFailed
    val status = mapStatus(snapshot, mapFailed, mapLoading)
    val showCue = snapshot.linkLive && snapshot.fixLive && frame?.cue != null

    // The zoom buttons sit ON the dial, not in the corners. A 40 dp button
    // anchored 22 dp from the corner of a 227 dp round screen has its centre
    // 102 dp out and its rim at 122 dp, past the 113 dp bezel: the pointed,
    // cut-off shapes of the first build. Placing each centre on a circle of
    // radius (screen radius, button radius, a 4 dp margin), 35 degrees below
    // horizontal, keeps the whole button inside on every round size and clear
    // of the status pill at the bottom. The size follows the dashboard's
    // action buttons so the two pages match.
    val density = LocalDensity.current
    val screenDp = with(density) { minOf(viewport.width, viewport.height).toDp() }
    val zoomButtonSize = (screenDp * 0.127f).coerceIn(40.dp, 56.dp)
    val zoomIconSize = (screenDp * 0.060f).coerceIn(20.dp, 26.dp)
    val zoomButtonOffset = remember(screenDp, zoomButtonSize) {
        val radius = screenDp / 2 - zoomButtonSize / 2 - 4.dp
        val angle = Math.toRadians(35.0)
        DpOffset(radius * kotlin.math.cos(angle).toFloat(), radius * kotlin.math.sin(angle).toFloat())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .onSizeChanged { viewport = it },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pivot = Offset(size.width / 2f, size.height / 2f)
            rotate(cameraRotation, pivot) {
                drawPlan.forEach { draw ->
                    val bitmap = imageBitmaps[draw.sourceKey] ?: return@forEach
                    val destination = draw.destination
                    drawImage(
                        image = bitmap,
                        srcOffset = IntOffset(draw.source.left, draw.source.top),
                        srcSize = IntSize(draw.source.width, draw.source.height),
                        dstOffset = IntOffset(
                            floor(destination.left).toInt(),
                            floor(destination.top).toInt(),
                        ),
                        dstSize = IntSize(
                            ceil(destination.width + 1.0).toInt(),
                            ceil(destination.height + 1.0).toInt(),
                        ),
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            routePath?.let { path ->
                drawPath(path, color = colors.surface, style = Stroke(8f))
                drawPath(path, color = colors.accent, style = Stroke(4f))
            }
            targetPosition?.let { target ->
                drawCircle(colors.surface, radius = 8f, center = target)
                drawCircle(colors.accent, radius = 5f, center = target)
                drawCircle(colors.onAccent, radius = 2f, center = target)
            }
            if (center != null) {
                val markerColor = if (snapshot.fixLive) colors.gpsPhone else colors.textDisabled
                drawCircle(colors.surface, radius = 11f, center = pivot)
                if (frame?.headingDeg == null) {
                    drawCircle(markerColor, radius = 7f, center = pivot)
                } else {
                    val markerRotation = if (frame.headingUp) 0f else animatedHeading
                    rotate(markerRotation, pivot) {
                        val arrow = Path().apply {
                            moveTo(pivot.x, pivot.y - 10f)
                            lineTo(pivot.x + 7f, pivot.y + 8f)
                            lineTo(pivot.x, pivot.y + 5f)
                            lineTo(pivot.x - 7f, pivot.y + 8f)
                            close()
                        }
                        drawPath(arrow, markerColor)
                    }
                }
            }
        }
        if (showTelemetry) {
            telemetryOverlay()
        }

        if (showCue) {
            val cue = frame?.cue
            if (cue != null) {
                val cueDescription = when {
                    cue.primary.isBlank() -> cue.distance
                    cue.distance.isBlank() -> cue.primary
                    else -> "${cue.primary}, ${cue.distance}"
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (showTelemetry) 40.dp else 12.dp)
                        .widthIn(max = 88.dp)
                        .height(28.dp)
                        .clearAndSetSemantics { contentDescription = cueDescription }
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (cue.arrived) Icons.Filled.Flag else Icons.Filled.Navigation,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(if (cue.arrived) 0f else cue.angleDeg),
                    )
                    if (cue.distance.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = cue.distance,
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 54.dp),
                        )
                    }
                }
            }
        }

        status?.let { resourceId ->
            val statusText = stringResource(resourceId)
            val statusModifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 66.dp)
                .padding(bottom = 24.dp)
            if (
                resourceId == R.string.watch_map_loading ||
                resourceId == R.string.watch_map_route_loading
            ) {
                Box(
                    modifier = statusModifier,
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(12.dp)
                            .clearAndSetSemantics { contentDescription = statusText },
                        indicatorColor = colors.accent,
                        trackColor = colors.surface,
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                Text(
                    text = statusText,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = statusModifier
                        .clearAndSetSemantics { contentDescription = statusText }
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .padding(horizontal = 2.dp, vertical = 3.dp),
                )
            }
        }

        Button(
            onClick = { onZoomChange(cameraZoom - 1) },
            enabled = cameraZoom > WatchMapProtocol.MIN_ZOOM,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = colors.surface,
                contentColor = colors.textPrimary,
                disabledBackgroundColor = colors.surface,
                disabledContentColor = colors.textDisabled,
            ),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = -zoomButtonOffset.x, y = zoomButtonOffset.y)
                .size(zoomButtonSize),
        ) {
            Icon(Icons.Filled.Remove, stringResource(R.string.watch_map_zoom_out), Modifier.size(zoomIconSize))
        }
        Button(
            onClick = { onZoomChange(cameraZoom + 1) },
            enabled = cameraZoom < WatchMapProtocol.MAX_ZOOM,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = colors.surface,
                contentColor = colors.textPrimary,
                disabledBackgroundColor = colors.surface,
                disabledContentColor = colors.textDisabled,
            ),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = zoomButtonOffset.x, y = zoomButtonOffset.y)
                .size(zoomButtonSize),
        ) {
            Icon(Icons.Filled.Add, stringResource(R.string.watch_map_zoom_in), Modifier.size(zoomIconSize))
        }
        Text(
            text = layer.attributionShort,
            color = colors.textSecondary,
            fontSize = 8.sp,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 50.dp, end = 50.dp, bottom = 3.dp),
        )
    }
}

private const val MAP_DASH = "--"

@Composable
private fun MapTelemetryOverlay() {
    val state by WatchStateRepository.state.collectAsStateWithLifecycle()
    val lastPushAtMs by WatchStateRepository.lastPushAtMs.collectAsStateWithLifecycle()
    MapTelemetryContent(
        state = state,
        lastPushAtMs = lastPushAtMs,
        nowMs = rememberSecondTick(),
    )
}

@Composable
private fun MapTelemetryContent(
    state: WatchState,
    lastPushAtMs: Long,
    nowMs: Long,
) {
    val colors = LocalWatchColors.current
    val context = LocalContext.current
    val ageMs = (nowMs - lastPushAtMs).coerceAtLeast(0L)
    val live = state.connected && lastPushAtMs > 0L && ageMs < 3_000L
    val locale = LocalConfiguration.current.locales[0]

    val pwmLive = live && state.pwmPercent.isFinite()
    val pwmText = if (pwmLive) {
        stringResource(
            R.string.watch_map_pwm_value,
            String.format(locale, "%.0f", state.pwmPercent),
        )
    } else {
        MAP_DASH
    }
    val displaySpeed = WatchUnits.speed(state.speedKmh, state.speedUnit)
    val speedLive = live && displaySpeed.isFinite()
    val speedNumber = if (speedLive) {
        String.format(locale, "%.0f", displaySpeed)
    } else {
        MAP_DASH
    }
    val speedUnit = if (state.phoneSynced) {
        when (state.speedUnit) {
            "kmh", "mph", "ms", "kn" -> WatchUnits.speedUnit(context, state.speedUnit)
            else -> ""
        }
    } else {
        ""
    }
    val speedText = stringResource(
        R.string.watch_map_speed_value,
        speedNumber,
        speedUnit,
    ).trimEnd()
    val voltageLive = live && state.voltage.isFinite()
    val voltageText = if (voltageLive) {
        stringResource(
            R.string.watch_map_voltage_value,
            String.format(locale, "%.1f", state.voltage),
        )
    } else {
        MAP_DASH
    }
    val telemetryDescription = stringResource(
        R.string.watch_map_telemetry_description,
        pwmText,
        speedText,
        voltageText,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics { contentDescription = telemetryDescription },
    ) {
        MapTelemetryArc(
            pwmText,
            anchor = 224f,
            maxSweepDegrees = 42f,
            textColor = if (pwmLive) colors.textPrimary else colors.textDisabled,
        )
        MapTelemetryArc(
            speedText,
            anchor = 270f,
            maxSweepDegrees = 48f,
            textColor = if (speedLive) colors.textPrimary else colors.textDisabled,
        )
        MapTelemetryArc(
            voltageText,
            anchor = 316f,
            maxSweepDegrees = 42f,
            textColor = if (voltageLive) colors.textPrimary else colors.textDisabled,
        )
    }
}

@Composable
private fun MapTelemetryArc(
    text: String,
    anchor: Float,
    maxSweepDegrees: Float,
    textColor: Color,
) {
    val colors = LocalWatchColors.current
    CurvedLayout(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp),
        anchor = anchor,
        anchorType = AnchorType.Center,
    ) {
        basicCurvedText(
            text = text,
            modifier = CurvedModifier
                .sizeIn(maxSweepDegrees = maxSweepDegrees)
                .curvedBackground(colors.surface, cap = StrokeCap.Round),
            overflow = TextOverflow.Ellipsis,
            style = CurvedTextStyle(
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun mapStatus(
    snapshot: WatchMapSnapshot,
    mapFailed: Boolean,
    mapLoading: Boolean,
): Int? {
    val frame = snapshot.frame
    if (!snapshot.linkLive) return R.string.watch_map_phone_disconnected
    when (frame?.locationStatus) {
        WatchMapLocationStatus.OPEN_PHONE -> return R.string.watch_map_open_phone
        WatchMapLocationStatus.PERMISSION_REQUIRED -> return R.string.watch_map_gps_permission
        WatchMapLocationStatus.WAITING_FIX -> return R.string.watch_map_waiting_gps
        WatchMapLocationStatus.STALE_FIX -> return R.string.watch_map_gps_stale
        WatchMapLocationStatus.LIVE -> if (!snapshot.fixLive) return R.string.watch_map_gps_stale
        null -> return R.string.watch_map_phone_disconnected
    }
    if (frame.navigationActive && frame.routeRevision == 0L) {
        return R.string.watch_map_route_unavailable
    }
    if (frame.navigationActive && snapshot.route == null) {
        return R.string.watch_map_route_loading
    }
    if (mapFailed) return R.string.watch_map_unavailable
    if (mapLoading) return R.string.watch_map_loading
    return null
}

private fun normalizeDegrees(value: Float): Float = ((value % 360f) + 360f) % 360f

private fun shortestDegrees(from: Float, to: Float): Float =
    ((to - from + 540f) % 360f) - 180f


@Composable
internal fun WatchMapPreview(data: WatchPreviewData) {
    var previewZoom by remember { mutableIntStateOf(WatchMapProtocol.DEFAULT_ZOOM) }
    WatchTheme(data.state) {
        WatchMapContent(
            snapshot = data.mapSnapshot,
            zoom = previewZoom,
            onZoomChange = {
                previewZoom = it.coerceIn(
                    WatchMapProtocol.MIN_ZOOM,
                    WatchMapProtocol.MAX_ZOOM,
                )
            },
            showTelemetry = data.state.mapShowTelemetry,
            imageBitmaps = emptyMap(),
            localFailures = emptySet(),
            onVisibleTilesChanged = {},
            telemetryOverlay = {
                MapTelemetryContent(
                    state = data.state,
                    lastPushAtMs = data.lastPushAtMs,
                    nowMs = data.nowMs,
                )
            },
        )
    }
}

@WatchLargeRoundPreview
@Composable
private fun WatchMapTilesLoadingPreview() {
    val data = remember { WatchPreviewFixtures.live }
    WatchMapPreview(data)
}

@WatchLargeRoundPreview
@Composable
private fun WatchMapRouteLoadingPreview() {
    val data = remember {
        WatchPreviewFixtures.live.copy(
            mapSnapshot = WatchPreviewFixtures.live.mapSnapshot.copy(route = null),
        )
    }
    WatchMapPreview(data)
}

@WatchLargeRoundPreview
@Composable
private fun WatchMapWithoutTelemetryPreview() {
    val data = remember {
        WatchPreviewFixtures.live.copy(
            state = WatchPreviewFixtures.live.state.copy(mapShowTelemetry = false),
        )
    }
    WatchMapPreview(data)
}

@WatchLargeRoundPreview
@Composable
private fun WatchMapDisconnectedPreview() {
    val data = remember { WatchPreviewFixtures.stale }
    WatchMapPreview(data)
}

@WatchLargeRoundPreview
@Composable
private fun WatchMapStaleGpsPreview() {
    val data = remember {
        val live = WatchPreviewFixtures.live
        live.copy(
            mapSnapshot = live.mapSnapshot.copy(
                frame = live.mapSnapshot.frame?.copy(
                    locationStatus = WatchMapLocationStatus.STALE_FIX,
                ),
                fixLive = false,
            ),
        )
    }
    WatchMapPreview(data)
}

@WatchLargeRoundPreview
@Composable
private fun WatchMapArrivedPreview() {
    val data = remember {
        val live = WatchPreviewFixtures.live
        val cue = live.mapSnapshot.frame?.cue
        live.copy(
            mapSnapshot = live.mapSnapshot.copy(
                frame = live.mapSnapshot.frame?.copy(
                    cue = cue?.copy(arrived = true, distance = ""),
                ),
            ),
        )
    }
    WatchMapPreview(data)
}

@WatchLargeRoundPreview
@WatchSmallRoundPreview
@Composable
private fun MapTelemetryValuesPreview() {
    val data = remember { WatchPreviewFixtures.maximum }
    WatchTheme(data.state) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalWatchColors.current.background),
        ) {
            MapTelemetryContent(
                data.state,
                lastPushAtMs = data.lastPushAtMs,
                nowMs = data.nowMs,
            )
        }
    }
}

@WatchLargeRoundPreview
@Composable
private fun MapTelemetryStalePreview() {
    val data = remember { WatchPreviewFixtures.stale }
    WatchTheme(data.state) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalWatchColors.current.background),
        ) {
            MapTelemetryContent(
                data.state,
                lastPushAtMs = data.lastPushAtMs,
                nowMs = data.nowMs,
            )
        }
    }
}
