package com.eried.eucplanet.ui.dashboard.sources

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as DpSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
 * BLE telemetry, optional External GPS box), plus a Compare tab that lets
 * the rider pick two sources and see their deltas as small line graphs.
 *
 * The sheet starts the phone IMU listener on open and stops it on dismiss
 * so the sensor isn't running when no one's looking at the readout.
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
        Column(
            modifier = Modifier
                .heightIn(min = 620.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                "Live data sources",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            TabBar(
                snapshots = snapshots,
                selected = selectedTab,
                onSelect = { selectedTab = it }
            )
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
                    viewModel = viewModel,
                    snapshots = snapshots,
                    imperial = imperial
                )
            }
        }
    }
}

private enum class TabKind { PHONE, WHEEL, RACEBOX, COMPARE }

/**
 * Tab bar with an inline live/offline dot next to each source label.
 * Compare tab gets no dot — it's not a single source.
 */
@Composable
private fun TabBar(
    snapshots: Map<DataSource, SourceSnapshot>,
    selected: TabKind,
    onSelect: (TabKind) -> Unit
) {
    data class Entry(
        val tab: TabKind,
        val label: String,
        val color: Color,
        val source: DataSource?
    )
    val entries = listOf(
        Entry(TabKind.PHONE, DataSource.PHONE.displayName, DataSource.PHONE.color, DataSource.PHONE),
        Entry(TabKind.WHEEL, DataSource.WHEEL.displayName, DataSource.WHEEL.color, DataSource.WHEEL),
        Entry(TabKind.RACEBOX, DataSource.RACEBOX.displayName, DataSource.RACEBOX.color, DataSource.RACEBOX),
        Entry(TabKind.COMPARE, "Compare", MaterialTheme.colorScheme.onSurface, null)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        entries.forEach { e ->
            val isSel = e.tab == selected
            val live = e.source?.let { snapshots[it]?.isLive == true }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSel) e.color.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(e.tab) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Live/offline dot — filled colour for live, hollow grey
                    // ring for offline. Skipped on the Compare tab.
                    if (live != null) {
                        if (live) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(e.color)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, Color(0xFF707070), CircleShape)
                            )
                        }
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = e.label,
                        fontSize = 12.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSel) e.color else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
        ValueRow(
            label = "Speed",
            value = snapshot.speedKmh?.let { "%.1f %s".format(Units.speed(it, imperial), speedUnit) },
            color = source.color
        )
        // Heading + vertical speed — shown right under Speed for sources
        // that report them so the per-source tab serves as a quick GPS dump.
        if (source.hasPosition) {
            ValueRow(
                label = "Heading",
                value = snapshot.headingDeg?.let { "%.0f°".format(it) },
                color = source.color
            )
            ValueRow(
                label = "Vertical speed",
                value = snapshot.verticalSpeedMps?.let { "%+.1f m/s".format(it) },
                color = source.color
            )
            ValueRow(
                label = "Position",
                value = snapshot.latitude?.let { lat ->
                    snapshot.longitude?.let { lon -> "%.5f, %.5f".format(lat, lon) }
                },
                color = source.color
            )
            // GPS quality line — only meaningful for sources that have a
            // GPS receiver. Compact "n sats · ±N m" string under the position.
            if (snapshot.numSatellites != null || snapshot.accuracyMeters != null) {
                val sats = snapshot.numSatellites?.let { "$it sats" }
                val acc = snapshot.accuracyMeters?.let { "±%.1f m".format(it) }
                val combined = listOfNotNull(sats, acc).joinToString(" · ")
                Text(
                    text = combined,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // G-force section — crosshair plus three numeric rows when the
        // source claims IMU support.
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
            val unit = (w.coerceAtMost(h) / 2f) / maxG

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

            if (trail.isNotEmpty()) {
                trail.forEachIndexed { idx, point ->
                    val alpha = (0.15f + 0.7f * (idx / trail.size.toFloat())).coerceIn(0.1f, 0.85f)
                    val px = cx + (point.x.coerceIn(-maxG, maxG)) * unit
                    val py = cy - (point.y.coerceIn(-maxG, maxG)) * unit
                    drawCircle(color = color.copy(alpha = alpha), radius = 3f, center = Offset(px, py))
                }
            }

            val x = snapshot.accelXG
            val z = snapshot.accelZG
            if (x != null && z != null) {
                val px = cx + x.coerceIn(-maxG, maxG) * unit
                val py = cy - z.coerceIn(-maxG, maxG) * unit
                drawCircle(color = color, radius = 7f, center = Offset(px, py))
                drawCircle(color = Color.White, radius = 2.5f, center = Offset(px, py))
            }
        }
    }
}

/**
 * Compare tab. Two side-by-side picker rows joined by a "VS" badge, then a
 * stack of graphical comparison panels (line charts of the rolling time
 * series for each pair of sources that share a metric, plus the mini-map
 * for position).
 */
@Composable
private fun CompareTab(
    viewModel: DataSourcesViewModel,
    snapshots: Map<DataSource, SourceSnapshot>,
    imperial: Boolean
) {
    var pickerA by remember { mutableStateOf(DataSource.PHONE) }
    var pickerB by remember { mutableStateOf(DataSource.WHEEL) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val speedUnit = Units.speedUnit(context, imperial)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Picker row — A on the left, "VS" badge in the middle, B on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactPicker(
                selected = pickerA,
                exclude = pickerB,
                onChange = { pickerA = it },
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "VS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CompactPicker(
                selected = pickerB,
                exclude = pickerA,
                onChange = { pickerB = it },
                modifier = Modifier.weight(1f)
            )
        }

        // Speed comparison — both sources can always provide speed.
        val a = snapshots[pickerA] ?: SourceSnapshot()
        val b = snapshots[pickerB] ?: SourceSnapshot()
        ComparisonChart(
            title = "Speed",
            seriesA = viewModel.speedSeries[pickerA]?.collectAsState()?.value
                ?: DataSourcesViewModel.TimedSeries(),
            seriesB = viewModel.speedSeries[pickerB]?.collectAsState()?.value
                ?: DataSourcesViewModel.TimedSeries(),
            colorA = pickerA.color,
            colorB = pickerB.color,
            unit = speedUnit,
            transform = { Units.speed(it, imperial) },
            deltaCurrent = if (a.speedKmh != null && b.speedKmh != null)
                Units.speed(b.speedKmh - a.speedKmh, imperial) else null
        )

        // Heading comparison if both sources have it.
        if (a.headingDeg != null || b.headingDeg != null) {
            ComparisonChart(
                title = "Heading",
                seriesA = viewModel.headingSeries[pickerA]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                seriesB = viewModel.headingSeries[pickerB]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                colorA = pickerA.color,
                colorB = pickerB.color,
                unit = "°",
                transform = { it },
                deltaCurrent = if (a.headingDeg != null && b.headingDeg != null)
                    shortestArc(a.headingDeg, b.headingDeg) else null,
                deltaSuffix = "°"
            )
        }

        // Vertical speed comparison if both sources have it.
        if (a.verticalSpeedMps != null || b.verticalSpeedMps != null) {
            ComparisonChart(
                title = "Vertical speed",
                seriesA = viewModel.vertSpeedSeries[pickerA]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                seriesB = viewModel.vertSpeedSeries[pickerB]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                colorA = pickerA.color,
                colorB = pickerB.color,
                unit = "m/s",
                transform = { it },
                deltaCurrent = if (a.verticalSpeedMps != null && b.verticalSpeedMps != null)
                    b.verticalSpeedMps - a.verticalSpeedMps else null
            )
        }

        // G-force magnitude — chart if either source provides IMU.
        val gA = a.horizGMagnitude
        val gB = b.horizGMagnitude
        if (gA != null || gB != null) {
            ComparisonChart(
                title = "|G| (horizontal)",
                seriesA = viewModel.gMagnitudeSeries[pickerA]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                seriesB = viewModel.gMagnitudeSeries[pickerB]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                colorA = pickerA.color,
                colorB = pickerB.color,
                unit = "g",
                transform = { it },
                deltaCurrent = if (gA != null && gB != null) gB - gA else null
            )
        }

        // Position pair — distance + mini-map.
        if (pickerA.hasPosition && pickerB.hasPosition) {
            val lat1 = a.latitude
            val lon1 = a.longitude
            val lat2 = b.latitude
            val lon2 = b.longitude
            val distance = if (lat1 != null && lon1 != null && lat2 != null && lon2 != null)
                haversineMeters(lat1, lon1, lat2, lon2)
            else null
            Text(
                "Position",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ValueRow(
                label = "Distance between fixes",
                value = distance?.let { "%.1f m".format(it) },
                color = MaterialTheme.colorScheme.onSurface
            )
            if (lat1 != null && lon1 != null && lat2 != null && lon2 != null) {
                MiniMap(
                    points = listOf(lat1 to lon1 to pickerA.color, lat2 to lon2 to pickerB.color),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            }
        }
    }
}

/** Shortest signed arc difference between two compass headings [0, 360).
 *  Returns +90 if b is 90° clockwise of a, −90 if counter-clockwise. */
private fun shortestArc(a: Float, b: Float): Float {
    var d = b - a
    while (d > 180f) d -= 360f
    while (d < -180f) d += 360f
    return d
}

/**
 * Compact two-segment picker. Three rounded chips side-by-side, one per
 * source; the [exclude] one is greyed out and unclickable so A and B can
 * never be the same source.
 */
@Composable
private fun CompactPicker(
    selected: DataSource,
    exclude: DataSource,
    onChange: (DataSource) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
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
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = src.displayName,
                    fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (enabled) (if (sel) src.color else MaterialTheme.colorScheme.onSurface)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Side-by-side line graph for a single metric. Both lines share Y axis
 * (auto-fit), X axis is wall-clock time over the rolling [SERIES_WINDOW_MS]
 * window from the viewmodel. Header shows the current numeric Δ in the
 * metric's unit so the value is glanceable without staring at the lines.
 *
 * Empty series degrade to a flat dashed "no data" placeholder.
 */
@Composable
private fun ComparisonChart(
    title: String,
    seriesA: DataSourcesViewModel.TimedSeries,
    seriesB: DataSourcesViewModel.TimedSeries,
    colorA: Color,
    colorB: Color,
    unit: String,
    transform: (Float) -> Float,
    deltaCurrent: Float?,
    deltaSuffix: String = " $unit"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = deltaCurrent?.let { "Δ %+.1f%s".format(it, deltaSuffix) } ?: "—",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (deltaCurrent != null) MaterialTheme.colorScheme.onSurface
            else Color(0xFF707070)
        )
    }
    LineChart(
        seriesA = seriesA,
        seriesB = seriesB,
        colorA = colorA,
        colorB = colorB,
        transform = transform,
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
    )
}

@Composable
private fun LineChart(
    seriesA: DataSourcesViewModel.TimedSeries,
    seriesB: DataSourcesViewModel.TimedSeries,
    colorA: Color,
    colorB: Color,
    transform: (Float) -> Float,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val axisLabel = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().padding(8.dp).fillMaxWidth().padding(0.dp)) {
            val w = size.width
            val h = size.height

            // Find the union of points to compute the Y range.
            val pointsA = seriesA.points
            val pointsB = seriesB.points
            val allPoints = pointsA + pointsB
            if (allPoints.size < 2) {
                // Not enough data — paint a midline placeholder.
                val midY = h / 2f
                drawLine(
                    color = grid,
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
                val measured = measurer.measure(
                    "Collecting…",
                    style = TextStyle(fontSize = 9.sp, color = axisLabel)
                )
                drawText(
                    measured,
                    topLeft = Offset(
                        w / 2f - measured.size.width / 2f,
                        h / 2f - measured.size.height - 2f
                    )
                )
                return@Canvas
            }

            val now = System.currentTimeMillis()
            // 30-second window matching the ViewModel buffer.
            val tMin = now - 30_000L
            val tMax = now
            val tRange = (tMax - tMin).coerceAtLeast(1L)

            val transformedValues = allPoints.map { transform(it.second) }
            val yMinRaw = transformedValues.min()
            val yMaxRaw = transformedValues.max()
            val yRange = (yMaxRaw - yMinRaw).coerceAtLeast(0.01f)
            // 10 % vertical padding so peaks/troughs don't touch the box.
            val padY = yRange * 0.1f
            val yMin = yMinRaw - padY
            val yMax = yMaxRaw + padY
            val yRangePadded = (yMax - yMin).coerceAtLeast(0.02f)

            // Horizontal gridlines (3).
            for (i in 0..2) {
                val y = h * i / 2f
                drawLine(
                    color = grid,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 0.7f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                )
            }

            fun pathFor(points: List<Pair<Long, Float>>): Path? {
                if (points.size < 2) return null
                val p = Path()
                points.forEachIndexed { idx, (ts, v) ->
                    val x = ((ts - tMin).toFloat() / tRange) * w
                    val y = h - ((transform(v) - yMin) / yRangePadded) * h
                    if (idx == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                return p
            }

            pathFor(pointsA)?.let { drawPath(it, color = colorA, style = Stroke(width = 2.2f)) }
            pathFor(pointsB)?.let { drawPath(it, color = colorB, style = Stroke(width = 2.2f)) }

            // y-min / y-max labels on the left edge for context.
            listOf(yMax to 2f, yMin to h - 12f).forEach { (v, ty) ->
                val measured = measurer.measure(
                    "%.1f".format(v),
                    style = TextStyle(fontSize = 8.sp, color = axisLabel)
                )
                drawText(measured, topLeft = Offset(3f, ty))
            }
        }
    }
}

/** Tiny two-point map. Equirectangular projection inside the box. */
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
            drawLine(Color(0xFF606060), pa, pb, strokeWidth = 1.5f)
            drawCircle(colorA, radius = 7f, center = pa)
            drawCircle(Color.White, radius = 2.5f, center = pa)
            drawCircle(colorB, radius = 7f, center = pb)
            drawCircle(Color.White, radius = 2.5f, center = pb)
        }
    }
}

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
