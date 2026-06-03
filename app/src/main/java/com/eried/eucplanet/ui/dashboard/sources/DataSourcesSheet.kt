package com.eried.eucplanet.ui.dashboard.sources

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.remap
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
    speedUnit: String,
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
        containerColor = MaterialTheme.appColors.sheetBackground
    ) {
        // Source A in compare mode = the source tab the user picked last. The
        // user enters compare mode via the Compare chip; the bottom row then
        // lets them pick B. Picking the same source as A is allowed (we just
        // show a friendly "same source" message instead of compare panels)
        // so the user doesn't get stuck if they fat-finger their own A.
        // A/B selection lives on the ViewModel so it survives the sheet being
        // dismissed and re-opened. The rider's last pick (e.g. wheel vs
        // external) sticks until they change it; no more reselecting wheel
        // and external every time they reach for the calibration tool.
        val selectedSource by viewModel.selectedSource.collectAsState()
        val compareWith by viewModel.compareWith.collectAsState()
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
                onSelectSource = { viewModel.setSelectedSource(it) },
                onToggleCompare = { viewModel.toggleCompare() }
            )
            // Bottom row appears in compare mode: B picker. Same chip style
            // as the source tabs above (matches user's mental model: "the
            // tabs become the picker") so there's nothing new to learn.
            if (inCompareMode) {
                Spacer(Modifier.height(8.dp))
                CompareBPicker(
                    snapshots = snapshots,
                    selected = compareWith ?: DataSource.PHONE,
                    onSelect = { viewModel.setCompareWith(it) }
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
                    speedUnit = speedUnit
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
                        speedUnit = speedUnit
                    )
                }
            }
        }
    }
}

/**
 * Tab bar with an inline live/offline dot next to each source label.
 * Compare tab gets no dot, it's not a single source.
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
                    color = MaterialTheme.appColors.remap(src.color),
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
 * row's source tabs so the user reads "the tabs ARE the picker, the top
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
                    color = MaterialTheme.appColors.remap(src.color),
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
                        .border(1.dp, MaterialTheme.appColors.connectionIdle, CircleShape)
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
            // Outlined when unselected, no filled background, just a border.
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
    speedUnit: String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val speedUnitLabel = Units.speedUnit(context, speedUnit)
    // The source's baked palette hue, remapped onto the active theme's
    // metric tokens so the per-source colour follows the theme.
    val sourceColor = MaterialTheme.appColors.remap(source.color)
    // Vertical speed, accuracy and inter-fix distance are secondary GPS
    // fields with their own metric-vs-imperial helpers; an m/s phone
    // speed setting still reads metric there.
    val imperial = speedUnit == "mph"
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
            value = snapshot.speedKmh?.let { "%.1f %s".format(Units.speed(it, speedUnit), speedUnitLabel) },
            color = sourceColor
        )
        if (source.hasPosition) {
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_heading),
                value = snapshot.headingDeg?.let { "%.0f°".format(it) },
                color = sourceColor
            )
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_vertical_speed),
                value = snapshot.verticalSpeedMps?.let { formatVerticalSpeed(it, imperial) },
                color = sourceColor
            )
            // Position row with sats + accuracy folded into the label itself
            // so the metadata sits inline rather than wrapping to a second
            // line below the coords.
            val positionLabel = run {
                val base = stringResource(com.eried.eucplanet.R.string.sources_position)
                val sats = snapshot.numSatellites?.let {
                    context.getString(com.eried.eucplanet.R.string.sources_sats_fmt, it)
                }
                val acc = snapshot.accuracyMeters?.let { formatAccuracy(it, imperial) }
                val suffix = listOfNotNull(sats, acc).joinToString(" · ")
                if (suffix.isEmpty()) base else "$base ($suffix)"
            }
            ValueRow(
                label = positionLabel,
                value = snapshot.latitude?.let { lat ->
                    snapshot.longitude?.let { lon -> "%.5f, %.5f".format(lat, lon) }
                },
                color = sourceColor
            )
        }
        // Freshness row, "Last update 3s ago" / "Updated just now" / "--".
        FreshnessRow(snapshot.lastUpdateMs, nowMs)
        // G-force section, crosshair plus three numeric rows when the
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
                color = sourceColor,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .aspectRatio(1f)
                    .wrapContentSize(Alignment.Center)
            )
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_lateral_x),
                value = formatG(snapshot.accelXG), color = sourceColor
            )
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_vertical_y),
                value = formatG(snapshot.accelYG), color = sourceColor
            )
            ValueRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_forward_z),
                value = formatG(snapshot.accelZG), color = sourceColor
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
 *  in the caller). Shows "--" when the source has never sent anything. */
@Composable
private fun FreshnessRow(lastUpdateMs: Long?, nowMs: Long) {
    val label = if (lastUpdateMs == null) "--" else {
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
            value ?: "--",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (value != null) color else MaterialTheme.appColors.textDisabled
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
                // Offset highlight, sells the 3D sphere look. Sits up-left
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
 * respectively, no in-body picker. Renders a stack of graphical comparison
 * panels: line charts of the rolling time series for each pair of sources
 * that share a metric, plus the mini-map for position.
 */
@Composable
private fun CompareTab(
    viewModel: DataSourcesViewModel,
    snapshots: Map<DataSource, SourceSnapshot>,
    a: DataSource,
    b: DataSource,
    speedUnit: String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val speedUnitLabel = Units.speedUnit(context, speedUnit)
    // Inter-fix distance uses its own metric-vs-imperial helper.
    val imperial = speedUnit == "mph"
    val snapA = snapshots[a] ?: SourceSnapshot()
    val snapB = snapshots[b] ?: SourceSnapshot()
    // Each source's baked palette hue remapped onto the active theme's tokens.
    val aColor = MaterialTheme.appColors.remap(a.color)
    val bColor = MaterialTheme.appColors.remap(b.color)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Speed is the headline comparison.
        //
        // Architecture: _speedSeries[WHEEL] in the ViewModel is the RAW
        // wheel speed (decalibrated at ingest), so the buffer is invariant
        // to the rider's calibration offset. We multiply the wheel series
        // back up by the CURRENT calibration here so the chart line shows
        // the same value the rider sees on the dial, once calibration
        // converges, Δ on the chart → 0, which is the verification view.
        // The proposal math uses the raw series directly, which makes
        // "Apply" idempotent (re-applying a perfect calibration proposes
        // the same %, not a creeping larger one).
        val curCalPct by viewModel.calibrationOffsetPct.collectAsState()
        val calMul = 1f + curCalPct / 100f
        val rawSpeedSeriesA = viewModel.speedSeries[a]?.collectAsState()?.value
            ?: DataSourcesViewModel.TimedSeries()
        val rawSpeedSeriesB = viewModel.speedSeries[b]?.collectAsState()?.value
            ?: DataSourcesViewModel.TimedSeries()
        fun calibrate(s: DataSourcesViewModel.TimedSeries): DataSourcesViewModel.TimedSeries =
            if (calMul == 1f) s
            else DataSourcesViewModel.TimedSeries(
                s.points.map { it.first to it.second * calMul }
            )
        // For chart + delta display we use CALIBRATED wheel values so the
        // line matches the dial; phone/external pass through untouched.
        val speedSeriesA = if (a == DataSource.WHEEL) calibrate(rawSpeedSeriesA) else rawSpeedSeriesA
        val speedSeriesB = if (b == DataSource.WHEEL) calibrate(rawSpeedSeriesB) else rawSpeedSeriesB
        // Snapshot speeds come from WheelRepository which already applies
        // the calibration (it's the value on the dial), so use them as-is.
        val speedDeltaCurrent = if (snapA.speedKmh != null && snapB.speedKmh != null)
            Units.speed(snapB.speedKmh - snapA.speedKmh, speedUnit) else null
        val speedDeltaAvg = computeAverageDeltaKmh(speedSeriesA, speedSeriesB)
            ?.let { Units.speed(it, speedUnit) }

        // Proposal math: use the RAW wheel buffer (not the calibrated one)
        // so the result is idempotent. Once the rider tunes calibration
        // such that raw × (1 + curPct/100) ≈ ref, the proposed % equals
        // the current curPct and re-applying is a no-op.
        val wheelConnected by viewModel.wheelConnected.collectAsState()
        val wheelInComparison = a == DataSource.WHEEL || b == DataSource.WHEEL
        val wheelSeriesRaw = when {
            a == DataSource.WHEEL -> rawSpeedSeriesA
            b == DataSource.WHEEL -> rawSpeedSeriesB
            else -> null
        }
        val refSeries = when {
            a == DataSource.WHEEL -> rawSpeedSeriesB
            b == DataSource.WHEEL -> rawSpeedSeriesA
            else -> null
        }
        val proposedCalPct = if (wheelSeriesRaw != null && refSeries != null) {
            val avgW = computeSeriesAverage(wheelSeriesRaw)
            val avgR = computeSeriesAverage(refSeries)
            if (avgW != null && avgR != null) viewModel.computeCalibrationPct(avgW, avgR) else null
        } else null
        var showApplyDialog by remember { mutableStateOf(false) }

        // Button is always rendered (so the header layout doesn't shift
        // when the user switches A/B), but only enabled when the wheel is
        // one of the two sources, the wheel is connected, and we have
        // enough samples on both sides to compute a meaningful offset.
        ComparisonChart(
            title = stringResource(com.eried.eucplanet.R.string.sources_speed),
            seriesA = speedSeriesA,
            seriesB = speedSeriesB,
            colorA = aColor,
            colorB = bColor,
            unit = speedUnitLabel,
            transform = { Units.speed(it, speedUnit) },
            deltaCurrent = speedDeltaCurrent,
            deltaAvg = speedDeltaAvg,
            chartHeight = 200.dp,
            showAverageLine = true,
            applyActionLabel = stringResource(com.eried.eucplanet.R.string.sources_apply_calibration),
            applyActionEnabled = wheelInComparison && wheelConnected && proposedCalPct != null,
            onApplyAction = { showApplyDialog = true },
            resetActionLabel = stringResource(com.eried.eucplanet.R.string.sources_reset_avg),
            onResetAction = { viewModel.resetRollingAverages() }
        )
        if (showApplyDialog && proposedCalPct != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showApplyDialog = false },
                title = {
                    Text(stringResource(com.eried.eucplanet.R.string.sources_apply_calibration_title))
                },
                text = {
                    Text(
                        stringResource(
                            com.eried.eucplanet.R.string.sources_apply_calibration_body_fmt,
                            proposedCalPct
                        )
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.applyCalibrationPct(proposedCalPct)
                        showApplyDialog = false
                    }) {
                        Text(stringResource(com.eried.eucplanet.R.string.sources_apply))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showApplyDialog = false
                    }) {
                        Text(stringResource(com.eried.eucplanet.R.string.sources_cancel))
                    }
                }
            )
        }

        // Everything else collapses to a compact table. Each row is the two
        // current values + the signed delta; no per-metric graph.
        Text(
            stringResource(com.eried.eucplanet.R.string.sources_other_metrics),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (snapA.headingDeg != null || snapB.headingDeg != null) {
            CompareTableRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_heading),
                valueA = snapA.headingDeg?.let { "%.0f°".format(it) },
                valueB = snapB.headingDeg?.let { "%.0f°".format(it) },
                delta = if (snapA.headingDeg != null && snapB.headingDeg != null)
                    "%+.0f°".format(shortestArc(snapA.headingDeg, snapB.headingDeg)) else null,
                colorA = aColor,
                colorB = bColor
            )
        }
        if (snapA.verticalSpeedMps != null || snapB.verticalSpeedMps != null) {
            CompareTableRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_vertical_speed),
                valueA = snapA.verticalSpeedMps?.let { "%+.2f m/s".format(it) },
                valueB = snapB.verticalSpeedMps?.let { "%+.2f m/s".format(it) },
                delta = if (snapA.verticalSpeedMps != null && snapB.verticalSpeedMps != null)
                    "%+.2f m/s".format(snapB.verticalSpeedMps - snapA.verticalSpeedMps) else null,
                colorA = aColor,
                colorB = bColor
            )
        }
        val gA = snapA.horizGMagnitude
        val gB = snapB.horizGMagnitude
        if (gA != null || gB != null) {
            CompareTableRow(
                label = stringResource(com.eried.eucplanet.R.string.sources_g_horizontal),
                valueA = gA?.let { "%.2f g".format(it) },
                valueB = gB?.let { "%.2f g".format(it) },
                delta = if (gA != null && gB != null) "%+.2f g".format(gB - gA) else null,
                colorA = aColor,
                colorB = bColor
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
                    points = listOf(lat1 to lon1 to aColor, lat2 to lon2 to bColor),
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
    deltaSuffix: String = " $unit",
    deltaAvg: Float? = null,
    chartHeight: androidx.compose.ui.unit.Dp = 110.dp,
    showAverageLine: Boolean = false,
    applyActionLabel: String? = null,
    applyActionEnabled: Boolean = false,
    onApplyAction: (() -> Unit)? = null,
    resetActionLabel: String? = null,
    onResetAction: (() -> Unit)? = null
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = deltaCurrent?.let { "Δ %+.1f%s".format(it, deltaSuffix) } ?: "--",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (deltaCurrent != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.appColors.textDisabled
                )
                if (deltaAvg != null) {
                    Text(
                        text = "avg %+.1f%s".format(deltaAvg, deltaSuffix),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (resetActionLabel != null && onResetAction != null) {
                Spacer(Modifier.width(4.dp))
                androidx.compose.material3.TextButton(
                    onClick = onResetAction,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 0.dp
                    )
                ) {
                    Text(resetActionLabel, fontSize = 11.sp)
                }
            }
            if (applyActionLabel != null) {
                Spacer(Modifier.width(4.dp))
                androidx.compose.material3.TextButton(
                    onClick = { onApplyAction?.invoke() },
                    enabled = applyActionEnabled && onApplyAction != null,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 0.dp
                    )
                ) {
                    Text(applyActionLabel, fontSize = 11.sp)
                }
            }
        }
    }
    val avgSeries = if (showAverageLine) computeTimedAverage(seriesA, seriesB)
                    else DataSourcesViewModel.TimedSeries()
    LineChart(
        seriesA = seriesA,
        seriesB = seriesB,
        seriesC = avgSeries,
        colorA = colorA,
        colorB = colorB,
        colorC = MaterialTheme.colorScheme.onSurface,
        transform = transform,
        centerOnLatestPct = if (showAverageLine) 10f else null,
        interactive = true,
        valueUnit = unit,
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
    )
}

/** Simple mean of all sample values currently in the rolling-window buffer.
 *  Used to feed the apply-calibration math, where we need each source's
 *  averaged speed (not the delta between them). */
private fun computeSeriesAverage(s: DataSourcesViewModel.TimedSeries): Float? {
    val pts = s.points
    if (pts.isEmpty()) return null
    var sum = 0.0
    for ((_, v) in pts) sum += v.toDouble()
    return (sum / pts.size).toFloat()
}

/** Time-aligned per-sample average. For each A-timestamp we average with the
 *  nearest-in-time B value (skipped if the nearest is more than 2 s away). */
private fun computeTimedAverage(
    a: DataSourcesViewModel.TimedSeries,
    b: DataSourcesViewModel.TimedSeries
): DataSourcesViewModel.TimedSeries {
    val pa = a.points
    val pb = b.points
    if (pa.isEmpty() || pb.isEmpty()) return DataSourcesViewModel.TimedSeries()
    val out = ArrayList<Pair<Long, Float>>(pa.size)
    for ((t, va) in pa) {
        val nearestB = pb.minByOrNull { kotlin.math.abs(it.first - t) } ?: continue
        if (kotlin.math.abs(nearestB.first - t) > 2_000L) continue
        out.add(t to (va + nearestB.second) / 2f)
    }
    return DataSourcesViewModel.TimedSeries(out)
}

/**
 * Time-aligned average of B minus A across the rolling window. We pair each
 * B sample with the nearest-in-time A sample and average the deltas. The
 * window is whatever the ViewModel keeps in the series buffers (~30 s today).
 */
private fun computeAverageDeltaKmh(
    a: DataSourcesViewModel.TimedSeries,
    b: DataSourcesViewModel.TimedSeries
): Float? {
    val pa = a.points
    val pb = b.points
    if (pa.isEmpty() || pb.isEmpty()) return null
    var sum = 0.0
    var n = 0
    for ((t, vb) in pb) {
        val nearestA = pa.minByOrNull { kotlin.math.abs(it.first - t) } ?: continue
        if (kotlin.math.abs(nearestA.first - t) > 2_000L) continue
        sum += (vb - nearestA.second).toDouble()
        n++
    }
    return if (n == 0) null else (sum / n).toFloat()
}

@Composable
private fun CompareTableRow(
    label: String,
    valueA: String?,
    valueB: String?,
    delta: String?,
    colorA: Color,
    colorB: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.30f),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            valueA ?: "--",
            modifier = Modifier.weight(0.25f),
            fontSize = 13.sp,
            color = colorA
        )
        Text(
            valueB ?: "--",
            modifier = Modifier.weight(0.25f),
            fontSize = 13.sp,
            color = colorB
        )
        Text(
            delta ?: "--",
            modifier = Modifier.weight(0.20f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (delta != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.appColors.textDisabled
        )
    }
}

@Composable
private fun LineChart(
    seriesA: DataSourcesViewModel.TimedSeries,
    seriesB: DataSourcesViewModel.TimedSeries,
    colorA: Color,
    colorB: Color,
    transform: (Float) -> Float,
    modifier: Modifier = Modifier,
    seriesC: DataSourcesViewModel.TimedSeries = DataSourcesViewModel.TimedSeries(),
    colorC: Color = Color.Gray,
    /**
     * When non-null, the Y axis centers on the most recent A-or-B value with
     * top/bottom at ±[centerOnLatestPct] % of that value. Lets two near-equal
     * but oscillating sources show as a visible spread instead of flattening
     * into a thin band at the top of the chart. Null = auto-fit min/max.
     */
    centerOnLatestPct: Float? = null,
    /** Render with units appended next to each value label in the tooltip. */
    interactive: Boolean = false,
    valueUnit: String = ""
) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val axisLabel = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val collectingLabel = stringResource(com.eried.eucplanet.R.string.sources_collecting)
    // Scrub X in pixels (relative to the Canvas's draw area), null when no
    // finger is down. Updated by the pointer modifier attached to the
    // Canvas; consumed in the drawScope to render a vertical scrub line +
    // per-series value bubbles.
    var scrubX by remember { mutableStateOf<Float?>(null) }
    val canvasPointerModifier = if (interactive) {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull()
                    if (change == null || !change.pressed) {
                        scrubX = null
                    } else {
                        scrubX = change.position.x.coerceIn(0f, size.width.toFloat())
                        // Consume so the parent doesn't try to scroll the
                        // bottom sheet while the rider is scrubbing.
                        change.consume()
                    }
                }
            }
        }
    } else Modifier
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .then(canvasPointerModifier)
        ) {
            val w = size.width
            val h = size.height

            // Find the union of points to compute the Y range.
            val pointsA = seriesA.points
            val pointsB = seriesB.points
            val pointsC = seriesC.points
            val allPoints = pointsA + pointsB + pointsC
            if (allPoints.size < 2) {
                // Not enough data, paint a midline placeholder.
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
            // Dynamic time window: grows from 20 s up to a 2-min cap as
            // samples accumulate. Without this the chart shows a tiny
            // squiggle pinned to the right edge on a fresh open. Now: on
            // a fresh open the rider sees the last 20 s in detail; the
            // window widens automatically as the buffer fills, capped at
            // 2 min so the chart still resolves recent dynamics at speed.
            val minWindowMs = 20_000L
            val maxWindowMs = 120_000L
            val oldest = allPoints.minOfOrNull { it.first } ?: now
            val windowMs = (now - oldest).coerceIn(minWindowMs, maxWindowMs)
            val tMin = now - windowMs
            val tMax = now
            val tRange = (tMax - tMin).coerceAtLeast(1L)

            // Only consider points within the visible time window so the
            // Y-axis zooms to what's actually drawn rather than to the full
            // buffer (which can include older outliers).
            val visiblePoints = allPoints.filter { it.first in tMin..tMax }
            val transformedValues = (if (visiblePoints.size >= 2) visiblePoints else allPoints)
                .map { transform(it.second) }

            val yMin: Float
            val yMax: Float
            if (centerOnLatestPct != null) {
                // Fit the full visible window's spread (not just the latest
                // A/B values) so the rider can see the whole 5-minute speed
                // history with breathing room above and below. Earlier we
                // centred tightly on the most recent two points, which
                // collapsed the chart to a narrow band and hid the rest of
                // the ride. Now: take min/max across every transformed
                // value in view, expand by 25 %, with a floor of |centre|
                // × centerOnLatestPct % so a flat parked emulator (both
                // values near zero) doesn't collapse to a hairline.
                val active = transformedValues.filter { it.isFinite() }
                val vMin = active.minOrNull() ?: 0f
                val vMax = active.maxOrNull() ?: 0f
                val center = (vMin + vMax) / 2f
                val halfData = (vMax - vMin) / 2f
                val halfTen = kotlin.math.abs(center) * centerOnLatestPct / 100f
                val halfRange = maxOf(halfData * 1.25f, halfTen, 2f)
                yMin = center - halfRange
                yMax = center + halfRange
            } else {
                // Auto-fit min/max with small padding.
                val yMinRaw = transformedValues.min()
                val yMaxRaw = transformedValues.max()
                val yRangeRaw = (yMaxRaw - yMinRaw).coerceAtLeast(0.5f)
                val padY = yRangeRaw * 0.04f
                yMin = yMinRaw - padY
                yMax = yMaxRaw + padY
            }
            val yRangePadded = (yMax - yMin).coerceAtLeast(0.5f)

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
            // Optional third series (average between A and B). Drawn dashed so
            // it reads as a derived reference line, not another source.
            pathFor(pointsC)?.let {
                drawPath(
                    path = it,
                    color = colorC.copy(alpha = 0.85f),
                    style = Stroke(
                        width = 1.6f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))
                    )
                )
            }

            // y-min / y-max labels on the left edge for context.
            listOf(yMax to 2f, yMin to h - 12f).forEach { (v, ty) ->
                val measured = measurer.measure(
                    "%.1f".format(v),
                    style = TextStyle(fontSize = 8.sp, color = axisLabel)
                )
                drawText(measured, topLeft = Offset(3f, ty))
            }

            // Scrub overlay (interactive == true), draw a vertical line at
            // the rider's touch X, find the nearest sample on each series,
            // mark it with a dot, and stack value labels next to the line.
            val sx = scrubX
            if (sx != null) {
                drawLine(
                    color = onSurface.copy(alpha = 0.45f),
                    start = Offset(sx, 0f),
                    end = Offset(sx, h),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
                )
                fun nearest(points: List<Pair<Long, Float>>): Pair<Float, Float>? {
                    if (points.isEmpty()) return null
                    var bestDx = Float.MAX_VALUE
                    var bestVal = 0f
                    var bestY = 0f
                    points.forEach { (ts, v) ->
                        val x = ((ts - tMin).toFloat() / tRange) * w
                        val dx = kotlin.math.abs(x - sx)
                        if (dx < bestDx) {
                            bestDx = dx
                            bestVal = transform(v)
                            bestY = h - ((transform(v) - yMin) / yRangePadded) * h
                        }
                    }
                    return bestY to bestVal
                }
                val unitSuffix = if (valueUnit.isNotEmpty()) " $valueUnit" else ""
                val hits = listOfNotNull(
                    nearest(pointsA)?.let { Triple(it.first, it.second, colorA) },
                    nearest(pointsB)?.let { Triple(it.first, it.second, colorB) }
                )
                // Marker dots first so the labels can overlap them cleanly.
                hits.forEach { (y, _, col) ->
                    drawCircle(color = col, radius = 4f, center = Offset(sx, y))
                    drawCircle(color = surface, radius = 1.5f, center = Offset(sx, y))
                }
                // Stack labels at the top-right of the line, flipping to the
                // left if there isn't room on the right.
                val labelsRightOfLine = (sx + 8f) < (w - 50f)
                var labelTop = 2f
                hits.forEachIndexed { idx, (_, v, col) ->
                    val measured = measurer.measure(
                        "%.1f%s".format(v, unitSuffix),
                        style = TextStyle(
                            fontSize = 10.sp,
                            color = col,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    val tx = if (labelsRightOfLine) sx + 6f
                              else sx - measured.size.width - 6f
                    // Faint background pill so the number is legible over the
                    // grid lines.
                    drawRoundRect(
                        color = surface.copy(alpha = 0.85f),
                        topLeft = Offset(tx - 2f, labelTop - 1f),
                        size = androidx.compose.ui.geometry.Size(
                            measured.size.width.toFloat() + 4f,
                            measured.size.height.toFloat() + 2f
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                    )
                    drawText(measured, topLeft = Offset(tx, labelTop))
                    labelTop += measured.size.height + 3f
                }
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

    // Connector line between the two fixes; captured into a val since the
    // Canvas DrawScope can't read MaterialTheme directly.
    val connectorColor = MaterialTheme.appColors.outline

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
            drawLine(connectorColor, pa, pb, strokeWidth = 1.5f)
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
