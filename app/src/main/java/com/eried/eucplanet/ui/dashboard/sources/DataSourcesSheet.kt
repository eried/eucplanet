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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Size as DpSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
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
        // Source A in compare mode = the source tab the user picked last. The
        // user enters compare mode via the Compare chip; the bottom row then
        // lets them pick B. Picking the same source as A is allowed (we just
        // show a friendly "same source" message instead of compare panels)
        // so the user doesn't get stuck if they fat-finger their own A.
        var selectedSource by remember { mutableStateOf(DataSource.PHONE) }
        var compareWith by remember { mutableStateOf<DataSource?>(null) }
        val inCompareMode = compareWith != null

        Column(
            modifier = Modifier
                .heightIn(min = 620.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(com.eried.eucplanet.R.string.sources_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            // Top row: A picker + Compare toggle.
            TabBar(
                snapshots = snapshots,
                selectedSource = selectedSource,
                inCompareMode = inCompareMode,
                onSelectSource = { selectedSource = it },
                onToggleCompare = {
                    compareWith = if (inCompareMode) null
                    else DataSource.values().first { it != selectedSource }
                }
            )
            // Bottom row appears in compare mode: B picker. Same chip style
            // as the source tabs above (matches user's mental model: "the
            // tabs become the picker") so there's nothing new to learn.
            if (inCompareMode) {
                Spacer(Modifier.height(8.dp))
                CompareBPicker(
                    snapshots = snapshots,
                    selected = compareWith ?: DataSource.PHONE,
                    onSelect = { compareWith = it }
                )
            }
            Spacer(Modifier.height(12.dp))
            // Body: single-source view, "same source" placeholder, or
            // comparison panels.
            if (!inCompareMode) {
                SourceTab(
                    source = selectedSource,
                    snapshot = snapshots[selectedSource] ?: SourceSnapshot(),
                    trail = when (selectedSource) {
                        DataSource.PHONE -> imuTrail
                        DataSource.RACEBOX -> racebox
                        else -> emptyList()
                    },
                    imperial = imperial
                )
            } else {
                val b = compareWith ?: DataSource.PHONE
                if (b == selectedSource) {
                    SameSourcePlaceholder()
                } else {
                    CompareTab(
                        viewModel = viewModel,
                        snapshots = snapshots,
                        a = selectedSource,
                        b = b,
                        imperial = imperial
                    )
                }
            }
        }
    }
}

/**
 * Tab bar with an inline live/offline dot next to each source label.
 * Compare tab gets no dot — it's not a single source.
 */
@Composable
private fun TabBar(
    snapshots: Map<DataSource, SourceSnapshot>,
    selectedSource: DataSource,
    inCompareMode: Boolean,
    onSelectSource: (DataSource) -> Unit,
    onToggleCompare: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Three source tabs in a tight cluster.
        Row(
            modifier = Modifier.weight(3f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DataSource.values().forEach { src ->
                SourceTabChip(
                    label = stringResource(src.labelRes),
                    color = src.color,
                    isSelected = src == selectedSource,
                    isLive = snapshots[src]?.isLive == true,
                    onClick = { onSelectSource(src) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Visual break before Compare: 10dp gap + a thin vertical divider.
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(28.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(10.dp))
        CompareTabChip(
            isSelected = inCompareMode,
            onClick = onToggleCompare,
            modifier = Modifier.weight(1.1f)
        )
    }
}

/**
 * Second tab row, only visible in compare mode. Same chip style as the top
 * row's source tabs so the user reads "the tabs ARE the picker — the top
 * one is A, the new one below is B."
 */
@Composable
private fun CompareBPicker(
    snapshots: Map<DataSource, SourceSnapshot>,
    selected: DataSource,
    onSelect: (DataSource) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(3f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DataSource.values().forEach { src ->
                SourceTabChip(
                    label = stringResource(src.labelRes),
                    color = src.color,
                    isSelected = src == selected,
                    isLive = snapshots[src]?.isLive == true,
                    onClick = { onSelect(src) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Padding column matched to the divider + Compare chip on the row
        // above so the source chips align column-for-column. Empty so the
        // bottom row doesn't grow a second Compare button.
        Spacer(Modifier.width(10.dp))
        Spacer(Modifier.width(1.dp))
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1.1f))
    }
}

@Composable
private fun SameSourcePlaceholder() {
    Text(
        stringResource(com.eried.eucplanet.R.string.sources_same_source),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SourceTabChip(
    label: String,
    color: Color,
    isSelected: Boolean,
    isLive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) color.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isLive) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(color)
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
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompareTabChip(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            // Outlined when unselected — no filled background, just a border.
            // When selected it fills like the source chips so the active
            // state still reads obviously.
            .then(
                if (isSelected) Modifier.background(accent.copy(alpha = 0.12f))
                else Modifier
            )
            .border(
                width = 1.dp,
                color = if (isSelected) accent.copy(alpha = 0.5f) else accent.copy(alpha = 0.30f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.CompareArrows,
                contentDescription = null,
                tint = if (isSelected) accent else accent.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(com.eried.eucplanet.R.string.sources_compare),
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) accent else accent.copy(alpha = 0.7f)
            )
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
    // 1-second wall-clock tick. Drives the "Last update Xs ago" line so the
    // elapsed time stays accurate without the snapshot re-emitting.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            nowMs = System.currentTimeMillis()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ValueRow(
            label = stringResource(com.eried.eucplanet.R.string.sources_speed),
            value = snapshot.speedKmh?.let { "%.1f %s".format(Units.speed(it, imperial), speedUnit) },
            color = source.color
        )
        if (source.hasPosition) {
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_heading),
                value = snapshot.headingDeg?.let { "%.0f°".format(it) },
                color = source.color
            )
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_vertical_speed),
                value = snapshot.verticalSpeedMps?.let { formatVerticalSpeed(it, imperial) },
                color = source.color
            )
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_position),
                value = snapshot.latitude?.let { lat ->
                    snapshot.longitude?.let { lon -> "%.5f, %.5f".format(lat, lon) }
                },
                color = source.color
            )
            if (snapshot.numSatellites != null || snapshot.accuracyMeters != null) {
                val sats = snapshot.numSatellites?.let {
                    context.getString(com.eried.eucplanet.R.string.sources_sats_fmt, it)
                }
                val acc = snapshot.accuracyMeters?.let { formatAccuracy(it, imperial) }
                val combined = listOfNotNull(sats, acc).joinToString(" · ")
                Text(
                    text = combined,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Freshness row — "Last update 3s ago" / "Updated just now" / "—".
        FreshnessRow(snapshot.lastUpdateMs, nowMs)
        // G-force section — crosshair plus three numeric rows when the
        // source claims IMU support.
        if (source.hasImu) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(com.eried.eucplanet.R.string.sources_gforce),
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
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_lateral_x),
                value = formatG(snapshot.accelXG), color = source.color
            )
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_vertical_y),
                value = formatG(snapshot.accelYG), color = source.color
            )
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_forward_z),
                value = formatG(snapshot.accelZG), color = source.color
            )
        }
    }
}

private fun formatG(g: Float?): String? = g?.let { "%+.2f g".format(it) }

/** Vertical speed display. m/s for metric, ft/s for imperial.
 *  1 m/s = 3.28084 ft/s. */
private fun formatVerticalSpeed(mps: Float, imperial: Boolean): String =
    if (imperial) "%+.1f ft/s".format(mps * 3.28084f)
    else "%+.1f m/s".format(mps)

/** GPS accuracy display. Metres for metric, feet for imperial. */
private fun formatAccuracy(meters: Float, imperial: Boolean): String =
    if (imperial) "±%.1f ft".format(meters * 3.28084f)
    else "±%.1f m".format(meters)

/** Distance between two GPS fixes. Metres for metric, feet for imperial
 *  when under a mile, miles above. Keeps the number readable across scales. */
private fun formatDistance(meters: Double, imperial: Boolean): String {
    return if (imperial) {
        val ft = meters * 3.28084
        if (ft < 1000.0) "%.1f ft".format(ft)
        else "%.2f mi".format(ft / 5280.0)
    } else {
        if (meters < 1000.0) "%.1f m".format(meters)
        else "%.2f km".format(meters / 1000.0)
    }
}

/** "Last update Xs ago" line, recomputed against [nowMs] (ticks every 1s
 *  in the caller). Shows "—" when the source has never sent anything. */
@Composable
private fun FreshnessRow(lastUpdateMs: Long?, nowMs: Long) {
    val label = if (lastUpdateMs == null) "—" else {
        val elapsed = (nowMs - lastUpdateMs).coerceAtLeast(0L)
        when {
            elapsed < 1500L -> stringResource(com.eried.eucplanet.R.string.sources_fresh_just_now)
            elapsed < 60_000L -> stringResource(
                com.eried.eucplanet.R.string.sources_fresh_seconds_fmt,
                (elapsed / 1000).toInt()
            )
            elapsed < 3_600_000L -> stringResource(
                com.eried.eucplanet.R.string.sources_fresh_minutes_fmt,
                (elapsed / 60_000).toInt()
            )
            else -> stringResource(
                com.eried.eucplanet.R.string.sources_fresh_hours_fmt,
                (elapsed / 3_600_000).toInt()
            )
        }
    }
    ValueRow(
        label = stringResource(com.eried.eucplanet.R.string.sources_last_update),
        value = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

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

            if (trail.size >= 2) {
                // Comet tail rendered as a Catmull-Rom-smoothed curve. We
                // sample each segment at SUB sub-points using the standard
                // four-point Catmull-Rom basis (tension 0.5) and draw small
                // line strokes between successive samples. With SUB = 12 this
                // turns a 120-point trail into ~1440 tiny segments that
                // visually read as one continuous curve through the data
                // points, not a polyline of straight chords. Stroke width
                // and alpha taper along the tail (sqrt curve) so the body
                // stays readable while only the oldest 15% really fades out.
                //
                // Trail colour is the source hue mixed toward black so the
                // tail reads as a darker shade and the bright live sphere
                // remains the focal point.
                val trailColor = androidx.compose.ui.graphics.lerp(color, Color.Black, 0.45f)
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
                    // Stroke tapers from 2.5 px at the oldest end up to
                    // 9 px at the freshest segment, which sits flush with
                    // the 14-px live dot for visual continuity.
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

            val x = snapshot.accelXG
            val z = snapshot.accelZG
            if (x != null && z != null) {
                val px = cx + x.coerceIn(-maxG, maxG) * unit
                val py = cy - z.coerceIn(-maxG, maxG) * unit
                val center = Offset(px, py)
                // Two soft halo rings for a glow. The outer ring is barely
                // visible, the inner one carries most of the bloom.
                drawCircle(color = color.copy(alpha = 0.15f), radius = 24f, center = center)
                drawCircle(color = color.copy(alpha = 0.30f), radius = 18f, center = center)
                // Solid colored ball.
                drawCircle(color = color, radius = 13f, center = center)
                // Crisp white border ring so the ball stays readable against
                // any background colour the source palette throws at us.
                drawCircle(
                    color = Color.White,
                    radius = 13f,
                    center = center,
                    style = Stroke(width = 2f)
                )
                // Offset highlight — sells the 3D sphere look. Sits up-left
                // of centre as if a light source is above the dial.
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = 4f,
                    center = Offset(px - 4f, py - 4f)
                )
            }
        }
    }
}

/**
 * Compare body. A and B are picked from the top and bottom tab rows
 * respectively — no in-body picker. Renders a stack of graphical comparison
 * panels: line charts of the rolling time series for each pair of sources
 * that share a metric, plus the mini-map for position.
 */
@Composable
private fun CompareTab(
    viewModel: DataSourcesViewModel,
    snapshots: Map<DataSource, SourceSnapshot>,
    a: DataSource,
    b: DataSource,
    imperial: Boolean
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val speedUnit = Units.speedUnit(context, imperial)
    val snapA = snapshots[a] ?: SourceSnapshot()
    val snapB = snapshots[b] ?: SourceSnapshot()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ComparisonChart(
            title = stringResource(com.eried.eucplanet.R.string.sources_speed),
            seriesA = viewModel.speedSeries[a]?.collectAsState()?.value
                ?: DataSourcesViewModel.TimedSeries(),
            seriesB = viewModel.speedSeries[b]?.collectAsState()?.value
                ?: DataSourcesViewModel.TimedSeries(),
            colorA = a.color,
            colorB = b.color,
            unit = speedUnit,
            transform = { Units.speed(it, imperial) },
            deltaCurrent = if (snapA.speedKmh != null && snapB.speedKmh != null)
                Units.speed(snapB.speedKmh - snapA.speedKmh, imperial) else null
        )

        if (snapA.headingDeg != null || snapB.headingDeg != null) {
            ComparisonChart(
                title = stringResource(com.eried.eucplanet.R.string.sources_heading),
                seriesA = viewModel.headingSeries[a]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                seriesB = viewModel.headingSeries[b]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                colorA = a.color,
                colorB = b.color,
                unit = "°",
                transform = { it },
                deltaCurrent = if (snapA.headingDeg != null && snapB.headingDeg != null)
                    shortestArc(snapA.headingDeg, snapB.headingDeg) else null,
                deltaSuffix = "°"
            )
        }

        if (snapA.verticalSpeedMps != null || snapB.verticalSpeedMps != null) {
            ComparisonChart(
                title = stringResource(com.eried.eucplanet.R.string.sources_vertical_speed),
                seriesA = viewModel.vertSpeedSeries[a]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                seriesB = viewModel.vertSpeedSeries[b]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                colorA = a.color,
                colorB = b.color,
                unit = "m/s",
                transform = { it },
                deltaCurrent = if (snapA.verticalSpeedMps != null && snapB.verticalSpeedMps != null)
                    snapB.verticalSpeedMps - snapA.verticalSpeedMps else null
            )
        }

        val gA = snapA.horizGMagnitude
        val gB = snapB.horizGMagnitude
        if (gA != null || gB != null) {
            ComparisonChart(
                title = stringResource(com.eried.eucplanet.R.string.sources_g_horizontal),
                seriesA = viewModel.gMagnitudeSeries[a]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                seriesB = viewModel.gMagnitudeSeries[b]?.collectAsState()?.value
                    ?: DataSourcesViewModel.TimedSeries(),
                colorA = a.color,
                colorB = b.color,
                unit = "g",
                transform = { it },
                deltaCurrent = if (gA != null && gB != null) gB - gA else null
            )
        }

        if (a.hasPosition && b.hasPosition) {
            val lat1 = snapA.latitude
            val lon1 = snapA.longitude
            val lat2 = snapB.latitude
            val lon2 = snapB.longitude
            val distance = if (lat1 != null && lon1 != null && lat2 != null && lon2 != null)
                haversineMeters(lat1, lon1, lat2, lon2)
            else null
            Text(
                stringResource(com.eried.eucplanet.R.string.sources_position),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_distance_between_fixes),
                value = distance?.let { formatDistance(it, imperial) },
                color = MaterialTheme.colorScheme.onSurface
            )
            if (lat1 != null && lon1 != null && lat2 != null && lon2 != null) {
                MiniMap(
                    points = listOf(lat1 to lon1 to a.color, lat2 to lon2 to b.color),
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
    val collectingLabel = stringResource(com.eried.eucplanet.R.string.sources_collecting)
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
                    collectingLabel,
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
