package com.eried.eucplanet.ui.dashboard.sources

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.util.Units

/**
 * Bottom sheet opened by tapping the GPS indicator in the dashboard top-right.
 * Shows tabbed live data from each available source (Phone IMU + GPS, Wheel
 * BLE telemetry, optional RaceBox), plus a Compare tab that lets the rider
 * pick two sources and see their deltas.
 *
 * The sheet starts the phone IMU listener on open and stops it on dismiss so
 * the sensor isn't running when no one's looking at the readout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSourcesSheet(
    imperial: Boolean,
    onDismiss: () -> Unit,
    viewModel: DataSourcesViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    DisposableEffect(Unit) {
        viewModel.onSheetOpened()
        onDispose { viewModel.onSheetClosed() }
    }

    val snapshots by viewModel.snapshots.collectAsState()
    val imuTrail by viewModel.phoneImuTrail.collectAsState()
    val racebox by viewModel.raceboxTrail.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        var selectedTab by remember { mutableStateOf(TabKind.PHONE) }
        // Pin a minimum height so swapping tabs doesn't make the sheet
        // hop up and down between e.g. Phone (tall, has G-force crosshair)
        // and Wheel (short, speed only). 600 dp fits the tallest tab on
        // a typical phone without forcing an enormous sheet on tablets.
        Column(
            modifier = Modifier
                .heightIn(min = 600.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                "Live data sources",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            SourcePills(snapshots = snapshots)
            Spacer(Modifier.height(12.dp))
            TabBar(selected = selectedTab, onSelect = { selectedTab = it })
            Spacer(Modifier.height(12.dp))
            when (selectedTab) {
                TabKind.PHONE -> SourceTab(
                    source = DataSource.PHONE,
                    snapshot = snapshots[DataSource.PHONE] ?: SourceSnapshot(),
                    trail = imuTrail,
                    imperial = imperial
                )
                TabKind.WHEEL -> SourceTab(
                    source = DataSource.WHEEL,
                    snapshot = snapshots[DataSource.WHEEL] ?: SourceSnapshot(),
                    trail = emptyList(),
                    imperial = imperial
                )
                TabKind.RACEBOX -> SourceTab(
                    source = DataSource.RACEBOX,
                    snapshot = snapshots[DataSource.RACEBOX] ?: SourceSnapshot(),
                    trail = racebox,
                    imperial = imperial
                )
                TabKind.COMPARE -> CompareTab(
                    snapshots = snapshots,
                    imperial = imperial
                )
            }
        }
    }
}

private enum class TabKind { PHONE, WHEEL, RACEBOX, COMPARE }

@Composable
private fun SourcePills(snapshots: Map<DataSource, SourceSnapshot>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DataSource.values().forEach { src ->
            val live = snapshots[src]?.isLive == true
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(src.color.copy(alpha = if (live) 0.30f else 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (live) src.color else Color(0xFF606060))
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = src.displayName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (live) src.color else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TabBar(selected: TabKind, onSelect: (TabKind) -> Unit) {
    val labels = mapOf(
        TabKind.PHONE to DataSource.PHONE.displayName,
        TabKind.WHEEL to DataSource.WHEEL.displayName,
        TabKind.RACEBOX to DataSource.RACEBOX.displayName,
        TabKind.COMPARE to "Compare"
    )
    val colors = mapOf(
        TabKind.PHONE to DataSource.PHONE.color,
        TabKind.WHEEL to DataSource.WHEEL.color,
        TabKind.RACEBOX to DataSource.RACEBOX.color,
        TabKind.COMPARE to MaterialTheme.colorScheme.onSurface
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TabKind.values().forEach { tab ->
            val isSel = tab == selected
            val tint = colors[tab] ?: MaterialTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSel) tint.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = labels[tab] ?: tab.name,
                    fontSize = 12.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSel) tint else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Per-source tab. Renders speed + position rows when the snapshot carries
 * them, and the G-force crosshair when the source has an IMU. Rows the
 * source can't provide are dashed out rather than hidden so the user
 * understands what's missing at a glance.
 */
@Composable
private fun SourceTab(
    source: DataSource,
    snapshot: SourceSnapshot,
    trail: List<Offset>,
    imperial: Boolean
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val speedUnit = Units.speedUnit(context, imperial)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Speed
        ValueRow(
            label = "Speed",
            value = snapshot.speedKmh?.let { "%.1f %s".format(Units.speed(it, imperial), speedUnit) },
            color = source.color
        )
        // Position (only for sources that have GPS).
        if (source.hasPosition) {
            ValueRow(
                label = "Position",
                value = snapshot.latitude?.let { lat ->
                    snapshot.longitude?.let { lon -> "%.5f, %.5f".format(lat, lon) }
                },
                color = source.color
            )
        }
        // G-force section — crosshair plus three numeric rows when the
        // source claims IMU support. Show stub dashes when the device hasn't
        // sent any sensor data yet (e.g. RaceBox in PVT-only mode).
        if (source.hasImu) {
            Spacer(Modifier.height(4.dp))
            Text(
                "G-force",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GForceCrosshair(
                snapshot = snapshot,
                trail = trail,
                color = source.color,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .aspectRatio(1f)
                    .wrapContentSize(Alignment.Center)
            )
            ValueRow(label = "Lateral (X)", value = formatG(snapshot.accelXG), color = source.color)
            ValueRow(label = "Vertical (Y)", value = formatG(snapshot.accelYG), color = source.color)
            ValueRow(label = "Forward (Z)", value = formatG(snapshot.accelZG), color = source.color)
        }
    }
}

private fun formatG(g: Float?): String? = g?.let { "%+.2f g".format(it) }

@Composable
private fun ValueRow(label: String, value: String?, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value ?: "—",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (value != null) color else Color(0xFF707070)
        )
    }
}

/**
 * Crosshair plot for lateral × forward G-force.
 *
 * X axis → lateral (snapshot.accelXG), Y axis → forward (snapshot.accelZG).
 * Range is ± 1.5 g, with grid rings at 0.5 / 1.0 / 1.5 g. The current sample
 * shows as a filled dot of the source colour; the [trail] is rendered as
 * fading older points so the rider sees the recent motion arc.
 */
@Composable
private fun GForceCrosshair(
    snapshot: SourceSnapshot,
    trail: List<Offset>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val ringLabel = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().padding(8.dp).aspectRatio(1f).align(Alignment.Center)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val maxG = 1.5f
            val unit = (w.coerceAtMost(h) / 2f) / maxG  // pixels per g

            val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            // Concentric rings 0.5, 1.0, 1.5 g
            listOf(0.5f, 1.0f, 1.5f).forEach { r ->
                drawCircle(
                    color = grid,
                    radius = r * unit,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1f, pathEffect = dash)
                )
            }
            // Cross lines
            drawLine(grid, Offset(0f, cy), Offset(w, cy), strokeWidth = 1f, pathEffect = dash)
            drawLine(grid, Offset(cx, 0f), Offset(cx, h), strokeWidth = 1f, pathEffect = dash)

            // Ring labels (top of each ring)
            listOf(0.5f, 1.0f, 1.5f).forEach { r ->
                val measured = measurer.measure(
                    "${r}g",
                    style = TextStyle(fontSize = 8.sp, color = ringLabel)
                )
                drawText(
                    measured,
                    topLeft = Offset(cx + 3f, cy - r * unit - measured.size.height)
                )
            }

            // Trail: oldest first → faintest. Each Offset stores the (xG, zG)
            // sample in source units; we map to canvas here.
            if (trail.isNotEmpty()) {
                trail.forEachIndexed { idx, point ->
                    val alpha = (0.15f + 0.7f * (idx / trail.size.toFloat())).coerceIn(0.1f, 0.85f)
                    val px = cx + (point.x.coerceIn(-maxG, maxG)) * unit
                    val py = cy - (point.y.coerceIn(-maxG, maxG)) * unit
                    drawCircle(color = color.copy(alpha = alpha), radius = 3f, center = Offset(px, py))
                }
            }

            // Live dot
            val x = snapshot.accelXG
            val z = snapshot.accelZG
            if (x != null && z != null) {
                val px = cx + x.coerceIn(-maxG, maxG) * unit
                val py = cy - z.coerceIn(-maxG, maxG) * unit
                // White core + colored ring for max contrast on dark theme
                drawCircle(color = color, radius = 7f, center = Offset(px, py))
                drawCircle(color = Color.White, radius = 2.5f, center = Offset(px, py))
            }
        }
    }
}

/**
 * Compare tab. User picks two sources from the available ones, and we render
 * each metric they share with the delta between them. Position-bearing pairs
 * also get a mini-map showing both points.
 */
@Composable
private fun CompareTab(
    snapshots: Map<DataSource, SourceSnapshot>,
    imperial: Boolean
) {
    var pickerA by remember { mutableStateOf(DataSource.PHONE) }
    var pickerB by remember { mutableStateOf(DataSource.WHEEL) }
    val a = snapshots[pickerA] ?: SourceSnapshot()
    val b = snapshots[pickerB] ?: SourceSnapshot()
    val context = androidx.compose.ui.platform.LocalContext.current
    val speedUnit = Units.speedUnit(context, imperial)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Pick two sources",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SourcePicker("A", pickerA, exclude = pickerB, onChange = { pickerA = it }, modifier = Modifier.weight(1f))
            SourcePicker("B", pickerB, exclude = pickerA, onChange = { pickerB = it }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))

        // Speed delta — always shown (every source has speed).
        val sA = a.speedKmh
        val sB = b.speedKmh
        val speedDelta = if (sA != null && sB != null)
            "%+.1f %s".format(Units.speed(sB - sA, imperial), speedUnit)
        else "—"
        ValueRow(label = "Speed Δ (B − A)", value = speedDelta, color = MaterialTheme.colorScheme.onSurface)

        // Position delta — only meaningful when both sources expose GPS.
        if (pickerA.hasPosition && pickerB.hasPosition) {
            val lat1 = a.latitude
            val lon1 = a.longitude
            val lat2 = b.latitude
            val lon2 = b.longitude
            val distance = if (lat1 != null && lon1 != null && lat2 != null && lon2 != null)
                haversineMeters(lat1, lon1, lat2, lon2)
            else null
            ValueRow(
                label = "Distance between fixes",
                value = distance?.let { "%.1f m".format(it) } ?: "—",
                color = MaterialTheme.colorScheme.onSurface
            )
            // Mini-map.
            if (lat1 != null && lon1 != null && lat2 != null && lon2 != null) {
                MiniMap(
                    points = listOf(lat1 to lon1 to pickerA.color, lat2 to lon2 to pickerB.color),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            }
        }

        // G-force delta — only when both sources have IMU values.
        val gA = a.horizGMagnitude
        val gB = b.horizGMagnitude
        if (gA != null && gB != null) {
            ValueRow(
                label = "|G| Δ (B − A)",
                value = "%+.2f g".format(gB - gA),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SourcePicker(
    label: String,
    selected: DataSource,
    exclude: DataSource,
    onChange: (DataSource) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DataSource.values().forEach { src ->
                val enabled = src != exclude
                val sel = src == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (sel) src.color.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.4f)
                        )
                        .clickable(enabled = enabled) { onChange(src) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        src.displayName,
                        fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (enabled) (if (sel) src.color else MaterialTheme.colorScheme.onSurface)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Tiny two-point map. Equirectangular projection inside the box — fine for
 * the cm/m scale we're showing between two GPS samples. Auto-fits the two
 * points with 20% padding.
 */
@Composable
private fun MiniMap(
    points: List<Pair<Pair<Double, Double>, Color>>,
    modifier: Modifier = Modifier
) {
    if (points.size != 2) return
    val (a, colorA) = points[0]
    val (b, colorB) = points[1]
    val lats = listOf(a.first, b.first)
    val lons = listOf(a.second, b.second)
    val midLat = (lats.min() + lats.max()) / 2.0
    val cosLat = kotlin.math.cos(Math.toRadians(midLat))

    // Bounding box in metres around midLat
    val padFactor = 1.2
    val latSpanDeg = ((lats.max() - lats.min()).coerceAtLeast(1e-6) * padFactor)
    val lonSpanDeg = ((lons.max() - lons.min()).coerceAtLeast(1e-6) * padFactor)
    val latSpanM = latSpanDeg * 111_320.0
    val lonSpanM = lonSpanDeg * 111_320.0 * cosLat

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            val w = size.width
            val h = size.height
            // Project each lat/lon onto canvas centered on midLat/midLon.
            val midLon = (lons.min() + lons.max()) / 2.0
            val scaleX = (w / lonSpanM).coerceAtMost(h / latSpanM)
            fun toXY(lat: Double, lon: Double): Offset {
                val dxM = (lon - midLon) * 111_320.0 * cosLat
                val dyM = (lat - midLat) * 111_320.0
                return Offset(
                    x = (w / 2f + dxM * scaleX).toFloat(),
                    y = (h / 2f - dyM * scaleX).toFloat()
                )
            }
            val pa = toXY(a.first, a.second)
            val pb = toXY(b.first, b.second)
            // Connecting line
            drawLine(Color(0xFF606060), pa, pb, strokeWidth = 1.5f)
            // Coloured dots
            drawCircle(colorA, radius = 7f, center = pa)
            drawCircle(Color.White, radius = 2.5f, center = pa)
            drawCircle(colorB, radius = 7f, center = pb)
            drawCircle(Color.White, radius = 2.5f, center = pb)
        }
    }
}

/** Haversine distance in metres. Good enough at the scale of two GPS fixes. */
private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2).let { it * it }
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return r * c
}
