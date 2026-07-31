package com.eried.eucplanet.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.TripMeterSplit
import com.eried.eucplanet.data.model.TripMeterState
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripMeterRepository
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.GraphScale
import com.eried.eucplanet.util.Units
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * View model for the trip-meter detail view. Reads the running [TripMeterState]
 * StateFlow and exposes the rider's distance / speed units for display.
 */
@HiltViewModel
class TripMeterDetailViewModel @Inject constructor(
    private val tripMeterRepository: TripMeterRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<TripMeterState> = tripMeterRepository.state

    val speedUnit: StateFlow<String> = settingsRepository.settings
        .map { Units.effectiveSpeedUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "kmh")

    val distanceUnit: StateFlow<String> = settingsRepository.settings
        .map { Units.effectiveDistanceUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "km")

    /** Zero the meter and clear the split log. */
    fun reset() = tripMeterRepository.reset()
}

private enum class TripMeterTab { SPEED, BATTERY, TIME }

/**
 * Distance-split detail view for the trip meter. Follows MetricDetailScreen's
 * "tabs across the top, chart + values below" pattern, but driven off the split
 * log rather than a rolling metric buffer, so there is no min / max / avg block
 * (those make no sense for a monotonic trip odometer). A Reset button (with a
 * confirm dialog) zeroes the meter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripMeterDetailScreen(
    onBack: () -> Unit,
    viewModel: TripMeterDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val speedUnit by viewModel.speedUnit.collectAsState()
    val distanceUnit by viewModel.distanceUnit.collectAsState()
    val context = LocalContext.current

    var tab by remember { mutableIntStateOf(0) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val speedUnitLabel = Units.speedUnit(context, speedUnit)
    val distUnitLabel = Units.distanceUnit(distanceUnit)

    val accent = MaterialTheme.appColors.metricPosition       // purple, matches the tile
    val maxAccent = MaterialTheme.appColors.metricVoltage

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trip_meter_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: total distance, total active time, overall avg speed.
            HeaderRow(
                totalDistance = "%.1f %s".format(
                    Units.distance(state.distanceKm, distanceUnit), distUnitLabel
                ),
                totalTime = Units.humanDuration(state.activeMs / 1000L),
                overallAvg = "%.1f %s".format(
                    Units.speed(state.overallAvgKmh, speedUnit), speedUnitLabel
                ),
                accent = accent,
                modifier = Modifier.padding(16.dp)
            )

            PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.fillMaxWidth()) {
                val titles = listOf(
                    R.string.trip_meter_tab_speed,
                    R.string.trip_meter_tab_battery,
                    R.string.trip_meter_tab_time,
                )
                titles.forEachIndexed { idx, res ->
                    Tab(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        text = {
                            Text(
                                stringResource(res).uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                val splits = state.splits
                if (splits.isEmpty()) {
                    EmptyMeter(accent)
                } else {
                    val active = TripMeterTab.entries[tab.coerceIn(0, 2)]
                    val xLabels = splits.map {
                        "%.0f".format(Units.distance(it.markDistanceKm, distanceUnit))
                    }
                    when (active) {
                        TripMeterTab.SPEED -> {
                            SplitBarChart(
                                values = splits.map { Units.speed(it.segmentAvgKmh, speedUnit) },
                                overlay = splits.map { Units.speed(it.segmentMaxKmh, speedUnit) },
                                xLabels = xLabels,
                                unitLabel = speedUnitLabel,
                                barColor = accent,
                                overlayColor = maxAccent,
                            )
                        }
                        TripMeterTab.BATTERY -> {
                            SplitBarChart(
                                values = splits.map { it.batteryPctAtMark.coerceAtLeast(0).toFloat() },
                                overlay = null,
                                xLabels = xLabels,
                                unitLabel = "%",
                                barColor = accent,
                                overlayColor = maxAccent,
                                fixedMax = 100f,
                            )
                        }
                        TripMeterTab.TIME -> {
                            SplitBarChart(
                                values = splits.map { it.cumulativeMs / 60_000f },
                                overlay = null,
                                xLabels = xLabels,
                                unitLabel = "min",
                                barColor = accent,
                                overlayColor = maxAccent,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    SplitTable(
                        splits = splits,
                        state = state,
                        speedUnit = speedUnit,
                        distanceUnit = distanceUnit,
                        distUnitLabel = distUnitLabel,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Reset button (confirm dialog). No accidental wipe: the meter is a
                // long-lived odometer.
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.12f))
                            .clickableReset { showResetConfirm = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = accent
                        )
                        Text(
                            // Screen is already titled "Trip meter", so the button
                            // just reads "Reset" (the confirm dialog carries the full
                            // "Reset trip meter" title + the danger copy).
                            stringResource(R.string.action_reset),
                            color = accent,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            if (showResetConfirm) {
                AlertDialog(
                    onDismissRequest = { showResetConfirm = false },
                    shape = RoundedCornerShape(12.dp),
                    title = { Text(stringResource(R.string.trip_meter_reset)) },
                    text = { Text(stringResource(R.string.trip_meter_reset_msg)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.reset()
                                showResetConfirm = false
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(stringResource(R.string.action_reset)) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showResetConfirm = false },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(stringResource(R.string.action_cancel)) }
                    }
                )
            }
        }
    }
}

/** Small clickable modifier wrapper so the reset icon + label share one target. */
private fun Modifier.clickableReset(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

@Composable
private fun HeaderRow(
    totalDistance: String,
    totalTime: String,
    overallAvg: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HeaderStat(stringResource(R.string.trip_meter_total), totalDistance, accent, Modifier.weight(1f))
        HeaderStat(stringResource(R.string.trip_meter_active), totalTime, accent, Modifier.weight(1f))
        HeaderStat(stringResource(R.string.trip_meter_avg), overallAvg, accent, Modifier.weight(1f))
    }
}

@Composable
private fun HeaderStat(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label.uppercase(),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
    }
}

@Composable
private fun EmptyMeter(accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.trip_meter_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp)
        )
    }
}

/**
 * Compact per-split bar chart. One bar per split, x positioned by index and
 * labelled with the split's mark distance. An optional [overlay] series (the
 * segment max speed) is drawn as dots + a connecting line above the bars. Reuses
 * [GraphScale] for the y bounds, like the rest of the dashboard charts.
 */
@Composable
private fun SplitBarChart(
    values: List<Float>,
    overlay: List<Float>?,
    xLabels: List<String>,
    unitLabel: String,
    barColor: Color,
    overlayColor: Color,
    fixedMax: Float? = null,
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    if (values.isEmpty()) return
    val dataMax = maxOf(values.max(), overlay?.maxOrNull() ?: 0f)
    val bounds = if (fixedMax != null) GraphScale.fixed(0f, fixedMax)
        else GraphScale.pad(0f, dataMax, 1f).let { GraphScale.fixed(0f, it.max) }
    val span = (bounds.max - bounds.min).coerceAtLeast(0.001f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 44.dp, bottom = 28.dp, top = 16.dp, end = 12.dp)
        ) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

            // Horizontal gridlines + left-axis labels.
            for (i in 0..4) {
                val y = h - h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = dash)
                val v = bounds.min + span * i / 4f
                val label = textMeasurer.measure(
                    "%.0f".format(v), TextStyle(fontSize = 10.sp, color = axisLabelColor)
                )
                drawText(label, topLeft = Offset(-label.size.width - 6f, y - label.size.height / 2f))
            }
            if (unitLabel.isNotBlank()) {
                val u = textMeasurer.measure(
                    unitLabel, TextStyle(fontSize = 10.sp, color = axisLabelColor, fontWeight = FontWeight.Bold)
                )
                drawText(u, topLeft = Offset(6f, -u.size.height - 2f))
            }

            val n = values.size
            val slot = w / n
            val barW = (slot * 0.55f).coerceAtMost(48f)
            fun barY(v: Float) = h - h * (v - bounds.min) / span

            values.forEachIndexed { idx, v ->
                val cx = slot * idx + slot / 2f
                val top = barY(v).coerceIn(0f, h)
                drawRect(
                    color = barColor.copy(alpha = 0.75f),
                    topLeft = Offset(cx - barW / 2f, top),
                    size = androidx.compose.ui.geometry.Size(barW, h - top),
                )
                // X-axis label: the mark distance.
                val lbl = textMeasurer.measure(
                    xLabels.getOrElse(idx) { "" },
                    TextStyle(fontSize = 9.sp, color = axisLabelColor)
                )
                drawText(lbl, topLeft = Offset(cx - lbl.size.width / 2f, h + 6f))
            }

            // Overlay (segment max) as a dotted line + dots on top of the bars.
            if (overlay != null && overlay.size == n) {
                var prev: Offset? = null
                overlay.forEachIndexed { idx, v ->
                    val cx = slot * idx + slot / 2f
                    val cy = barY(v).coerceIn(0f, h)
                    val p = Offset(cx, cy)
                    prev?.let {
                        drawLine(overlayColor.copy(alpha = 0.7f), it, p, strokeWidth = 2f)
                    }
                    drawCircle(overlayColor, radius = 4f, center = p)
                    prev = p
                }
            }
        }
    }
}

@Composable
private fun SplitTable(
    splits: List<TripMeterSplit>,
    state: TripMeterState,
    speedUnit: String,
    distanceUnit: String,
    distUnitLabel: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header row.
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            TableCell(stringResource(R.string.trip_meter_col_split), weight = 0.6f, header = true)
            TableCell(stringResource(R.string.trip_meter_col_dist), weight = 1f, header = true)
            TableCell(stringResource(R.string.trip_meter_col_time), weight = 1f, header = true)
            TableCell(stringResource(R.string.trip_meter_avg), weight = 1f, header = true)
            TableCell(stringResource(R.string.trip_meter_col_max), weight = 1f, header = true)
            TableCell(stringResource(R.string.trip_meter_col_batt), weight = 0.9f, header = true)
        }
        splits.forEach { s ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                TableCell(s.index.toString(), weight = 0.6f)
                TableCell("%.0f %s".format(Units.distance(s.markDistanceKm, distanceUnit), distUnitLabel), weight = 1f)
                TableCell(Units.humanDuration(s.segmentMs / 1000L), weight = 1f)
                TableCell("%.1f".format(Units.speed(s.segmentAvgKmh, speedUnit)), weight = 1f)
                TableCell("%.1f".format(Units.speed(s.segmentMaxKmh, speedUnit)), weight = 1f)
                TableCell(if (s.batteryPctAtMark >= 0) "${s.batteryPctAtMark}%" else "--", weight = 0.9f)
            }
        }
        // In-progress partial segment, shown so the rider sees the live tail.
        val lastMark = splits.lastOrNull()?.markDistanceKm ?: 0f
        val lastCumMs = splits.lastOrNull()?.cumulativeMs ?: 0L
        val partialKm = state.distanceKm - lastMark
        if (partialKm > 0.01f) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                TableCell(stringResource(R.string.trip_meter_partial), weight = 0.6f)
                TableCell("%.1f %s".format(Units.distance(state.distanceKm, distanceUnit), distUnitLabel), weight = 1f)
                TableCell(Units.humanDuration(((state.activeMs - lastCumMs).coerceAtLeast(0L)) / 1000L), weight = 1f)
                TableCell("--", weight = 1f)
                TableCell("--", weight = 1f)
                TableCell("--", weight = 0.9f)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(
    text: String,
    weight: Float,
    header: Boolean = false,
) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        fontSize = if (header) 10.sp else 12.sp,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}
