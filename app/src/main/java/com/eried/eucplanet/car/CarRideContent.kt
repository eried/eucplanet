package com.eried.eucplanet.car

import android.graphics.Rect
import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AndroidAutoSettings
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WidgetMetricType
import com.eried.eucplanet.hud.protocol.MapLayers
import com.eried.eucplanet.hud.protocol.WebMercator
import com.eried.eucplanet.map.MapTileCache
import com.eried.eucplanet.ui.navigator.mapTypeInitialBg
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.Units
import com.eried.eucplanet.widget.WidgetMetricFormat
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Everything the car screen reads, as the phone's own flows. */
class CarRideState(
    val wheel: StateFlow<WheelData>,
    val connection: StateFlow<ConnectionState>,
    val location: StateFlow<Location?>,
    val route: StateFlow<List<GeoPoint>?>,
    val settings: StateFlow<AppSettings>,
    val phoneBattery: () -> Int,
)

/**
 * The car screen: the map filling it, and a stats panel on the side Android
 * Auto leaves free. Colors follow the car's day/night signal through the dark
 * and light built-in themes, so the panel reads like the phone app does.
 */
@Composable
fun CarRideContent(state: CarRideState, dark: Boolean, visibleArea: Rect?) {
    EucPlanetTheme(colors = if (dark) BuiltInThemes.dark.colors else BuiltInThemes.light.colors) {
        val c = MaterialTheme.appColors
        val settings by state.settings.collectAsState()
        val wheel by state.wheel.collectAsState()
        val connection by state.connection.collectAsState()
        val connected = connection == ConnectionState.CONNECTED

        // The last battery seen this session, for the waiting card.
        var lastBattery by remember { mutableStateOf<Int?>(null) }
        if (connected && wheel.batteryPercent > 0) lastBattery = wheel.batteryPercent

        val density = LocalDensity.current
        var rootPx by remember { mutableStateOf(IntSize.Zero) }
        // Android Auto's own chrome (action strip, turn card, car bars) covers
        // part of the surface; everything we draw on top stays in the rest.
        val free = with(density) {
            val a = visibleArea
            if (a == null || rootPx == IntSize.Zero) PaddingValues(0.dp)
            else PaddingValues(
                start = a.left.toDp(), top = a.top.toDp(),
                end = (rootPx.width - a.right).coerceAtLeast(0).toDp(),
                bottom = (rootPx.height - a.bottom).coerceAtLeast(0).toDp(),
            )
        }

        Box(Modifier.fillMaxSize().background(c.appBackground).onGloballyPositioned { rootPx = it.size }) {
            CarMap(state, settings.navMapType, c.primary, c.surface, c.textSecondary, free)
            val panel = Modifier
                .padding(free)
                .padding(start = 12.dp, top = 12.dp)
                .width(232.dp)
                .background(c.surface.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
                .padding(14.dp)
            if (connected) StatsPanel(panel, wheel, settings, state.phoneBattery())
            else WaitingCard(panel, lastBattery)
        }
    }
}

/**
 * The map, drawn natively from the shared tile cache the Overlay Studio uses.
 * A WebView loads its tiles on the car's virtual display but never paints
 * them there, so the car screen does not use one.
 *
 * North up, the rider centred, their heading as an arrow, the route on top,
 * and the layer's credit in the corner: the tile licences require it.
 */
@Composable
private fun CarMap(state: CarRideState, mapType: String, accent: Color, ring: Color, credit: Color, free: PaddingValues) {
    val location by state.location.collectAsState()
    val route by state.route.collectAsState()
    val context = LocalContext.current
    val layer = MapLayers.byId(mapType)
    val zoom = CAR_ZOOM.coerceAtMost(layer.maxNativeZoom)
    val tilePx = with(LocalDensity.current) { (TILE_DP.dp.toPx()).toInt() } shl (CAR_ZOOM - zoom)
    val l = location
    Box(Modifier.fillMaxSize().background(Color(android.graphics.Color.parseColor(mapTypeInitialBg(mapType))))) {
        if (l != null) {
            val centerTx = WebMercator.tileX(l.longitude, zoom)
            val centerTy = WebMercator.tileY(l.latitude, zoom)
            val maxTile = (1 shl zoom) - 1
            val rings = TILE_RINGS
            val baseTx = kotlin.math.floor(centerTx).toInt()
            val baseTy = kotlin.math.floor(centerTy).toInt()
            val tiles = remember(mapType, zoom, baseTx, baseTy) {
                buildList {
                    for (dx in -rings..rings) for (dy in -rings..rings) {
                        val tx = baseTx + dx
                        val ty = baseTy + dy
                        if (ty in 0..maxTile) {
                            val wx = ((tx % (maxTile + 1)) + (maxTile + 1)) % (maxTile + 1)
                            add(Triple(tx, ty, MapLayers.tileUrl(mapType, zoom, wx, ty)))
                        }
                    }
                }
            }
            var tilesReady by remember { mutableStateOf(0) }
            LaunchedEffect(tiles) {
                kotlinx.coroutines.coroutineScope {
                    tiles.forEach { (_, _, url) ->
                        if (MapTileCache.get(url) == null) {
                            launch { if (MapTileCache.load(context, url)) tilesReady++ }
                        }
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                tilesReady
                val cx = size.width / 2f
                val cy = size.height / 2f
                fun project(lat: Double, lng: Double) = Offset(
                    cx + ((WebMercator.tileX(lng, zoom) - centerTx) * tilePx).toFloat(),
                    cy + ((WebMercator.tileY(lat, zoom) - centerTy) * tilePx).toFloat(),
                )
                tiles.forEach { (tx, ty, url) ->
                    val bmp = MapTileCache.get(url) ?: return@forEach
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(
                            (cx + (tx - centerTx) * tilePx).roundToInt(),
                            (cy + (ty - centerTy) * tilePx).roundToInt(),
                        ),
                        dstSize = IntSize(tilePx, tilePx),
                    )
                }
                route?.takeIf { it.size > 1 }?.let { pts ->
                    val path = Path()
                    pts.forEachIndexed { i, p ->
                        val o = project(p.lat, p.lng)
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    drawPath(path, accent.copy(alpha = 0.85f), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round))
                }
                drawCircle(ring, radius = 14.dp.toPx(), center = Offset(cx, cy))
                rotate(l.bearing, pivot = Offset(cx, cy)) {
                    val r = 10.dp.toPx()
                    val arrow = Path().apply {
                        moveTo(cx, cy - r)
                        lineTo(cx + r * 0.75f, cy + r * 0.8f)
                        lineTo(cx, cy + r * 0.4f)
                        lineTo(cx - r * 0.75f, cy + r * 0.8f)
                        close()
                    }
                    drawPath(arrow, accent)
                }
            }
        }
        Text(
            layer.attribution, color = credit, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(free).padding(6.dp),
        )
    }
}

@Composable
private fun StatsPanel(modifier: Modifier, wheel: WheelData, s: AppSettings, phoneBattery: Int) {
    val c = MaterialTheme.appColors
    val context = LocalContext.current
    val speedUnit = Units.effectiveSpeedUnit(s)
    val distUnit = Units.effectiveDistanceUnit(s)
    val tempUnit = Units.effectiveTempUnit(s)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "%.0f".format(Units.speed(wheel.speed, speedUnit)),
                color = c.textPrimary, fontSize = 56.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                Units.speedUnit(context, speedUnit),
                color = c.textSecondary, fontSize = 18.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
            )
        }
        AndroidAutoSettings.metricSlots(s.androidAuto.metrics).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { key ->
                    val type = WidgetMetricType.byKey(key)
                    Column(
                        Modifier.weight(1f)
                            .background(c.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(vertical = 6.dp, horizontal = 8.dp)
                    ) {
                        if (type != null) {
                            Text(stringResource(type.pickerLabel), color = c.textSecondary, fontSize = 12.sp, maxLines = 1)
                            Text(
                                WidgetMetricFormat.value(type, wheel, speedUnit, distUnit, tempUnit, phoneBattery) +
                                    " " + WidgetMetricFormat.unit(context, type, speedUnit, distUnit, tempUnit),
                                color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaitingCard(modifier: Modifier, lastBattery: Int?) {
    val c = MaterialTheme.appColors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.car_waiting), color = c.textPrimary,
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start,
        )
        if (lastBattery != null) {
            Text(stringResource(R.string.car_last_battery, lastBattery), color = c.textSecondary, fontSize = 15.sp)
        }
    }
}

/** Close enough to read street names on a car screen at a glance. */
private const val CAR_ZOOM = 17
private const val TILE_DP = 256
/** Rings of tiles around the centre: enough to cover a wide car screen. */
private const val TILE_RINGS = 3
