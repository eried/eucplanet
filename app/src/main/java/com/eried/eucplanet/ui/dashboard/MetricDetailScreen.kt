package com.eried.eucplanet.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.MetricSample
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentOrange
import com.eried.eucplanet.ui.theme.AccentRed
import com.eried.eucplanet.util.GraphBounds
import com.eried.eucplanet.util.GraphScale

/**
 * Legacy enum kept for the 6 buffered metrics whose detail rendering has
 * bespoke graph bounds + unit conversion. The unified [MetricDetailScreen]
 * derives the rest of its metadata from [com.eried.eucplanet.data.model.MetricCatalog].
 */
enum class MetricType(val titleRes: Int, val unit: String, val color: Color) {
    BATTERY(R.string.metric_battery, "%", AccentGreen),
    TEMPERATURE(R.string.metric_temperature, "°C", AccentOrange),
    VOLTAGE(R.string.metric_voltage, "V", AccentBlue),
    CURRENT(R.string.metric_current, "A", AccentBlue),
    LOAD(R.string.metric_load, "%", AccentOrange),
    SPEED(R.string.metric_speed, "km/h", AccentGreen)
}

/**
 * Current raw value for any catalog metric key that doesn't have a
 * dedicated [MetricType] entry. Maps to the same WheelData fields the
 * dashboard's `displayValueFor` uses, returning 0 for keys the dashboard
 * doesn't yet source (GPS / phone-battery / derived aggregates).
 */
private fun rawCurrentValueFor(key: String, w: WheelData): Float = when (key) {
    "POWER", "BATTERY_POWER" -> w.batteryPower.toFloat()
    "MOTOR_POWER" -> w.motorPower.toFloat()
    "ODOMETER" -> w.totalDistance
    "BATTERY_1" -> w.battery1Percent
    "BATTERY_2" -> w.battery2Percent
    "PITCH" -> w.pitchAngle
    "ROLL" -> w.rollAngle
    "G_FORCE" -> w.gForce
    "LATERAL_G" -> w.accelX
    "FORWARD_G" -> w.accelY
    "TORQUE" -> w.torque
    "DYN_SPEED_LIMIT" -> w.dynamicSpeedLimit
    "DYN_CURRENT_LIMIT" -> w.dynamicCurrentLimit
    "MOTOR_TEMP" -> w.temperatures.getOrNull(0) ?: 0f
    "CONTROLLER_TEMP" -> w.temperatures.getOrNull(1) ?: 0f
    "BATTERY_TEMP" -> w.temperatures.getOrNull(2) ?: 0f
    else -> 0f
}

/**
 * Unified full-screen metric detail. Renders any list of metric keys
 * as tabs across the top with the selected tab's chart + stats below.
 *
 * A single-metric tap from the dashboard produces a 1-tab list — same
 * layout as before, just with an inert tab strip. A composite-tile tap
 * produces an N-tab list (one per sub-metric). One control, one route,
 * one mental model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricDetailScreen(
    metricKeys: List<String>,
    onBack: () -> Unit,
    initialTabIndex: Int = 0,
    viewModel: MetricDetailViewModel = hiltViewModel()
) {
    val keys = remember(metricKeys) { metricKeys.filter { it.isNotBlank() } }
    if (keys.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // Seed selectedIdx from the nav arg so a side-tap on a composite
    // dashboard tile pre-selects the matching tab without changing the
    // visible order of the tab strip.
    var selectedIdx by remember(metricKeys) {
        mutableIntStateOf(initialTabIndex.coerceIn(0, keys.lastIndex))
    }
    val safeIdx = selectedIdx.coerceIn(0, keys.lastIndex)
    val activeKey = keys[safeIdx]

    val fullHistory by viewModel.fullHistory.collectAsState()
    val wheelData by viewModel.wheelData.collectAsState()
    val speedUnit by viewModel.speedUnit.collectAsState()
    val tempUnit by viewModel.tempUnit.collectAsState()

    // Long-press Reset → confirmation dialog → wipe ALL history buffers.
    var showResetAllConfirm by remember { mutableStateOf(false) }

    // Title is the generic "History" — the active tab tells the rider
    // which metric they're inspecting, so re-stating the metric name in
    // the AppBar is redundant.
    val titleLabel = stringResource(R.string.metric_detail_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleLabel) },
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
            // Tab strip — always rendered for consistency, even with a
            // single metric (1 tab). Rider sees the same control whether
            // they tapped a standalone tile or a composite.
            PrimaryTabRow(
                selectedTabIndex = safeIdx,
                modifier = Modifier.fillMaxWidth()
            ) {
                keys.forEachIndexed { idx, key ->
                    val spec = com.eried.eucplanet.data.model.MetricCatalog.byKey(key)
                    val label = spec?.let { stringResource(it.labelRes) } ?: key
                    Tab(
                        selected = safeIdx == idx,
                        onClick = { selectedIdx = idx },
                        text = {
                            Text(
                                label.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                MetricDetailBody(
                    key = activeKey,
                    fullHistory = fullHistory,
                    wheelData = wheelData,
                    speedUnit = speedUnit,
                    tempUnit = tempUnit
                )

                Spacer(Modifier.height(16.dp))

                // Reset footer. Tap = reset active tab's metric history;
                // long-press = open confirm dialog, then wipe ALL buffers.
                // The old "Reset all" button is gone — collapsed into a
                // long-press gesture so the toolbar reads cleaner and the
                // destructive action is harder to fire accidentally.
                ResetWithLongPressConfirm(
                    onResetActive = { viewModel.resetHistory(activeKey) },
                    onRequestResetAll = { showResetAllConfirm = true }
                )

                Spacer(Modifier.height(8.dp))
            }

            if (showResetAllConfirm) {
                AlertDialog(
                    onDismissRequest = { showResetAllConfirm = false },
                    title = { Text(stringResource(R.string.metric_detail_reset_all_confirm_title)) },
                    text = { Text(stringResource(R.string.metric_detail_reset_all_confirm_body)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            viewModel.resetAllHistory()
                            showResetAllConfirm = false
                        }) {
                            Text(stringResource(R.string.metric_detail_reset_all))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showResetAllConfirm = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResetWithLongPressConfirm(
    onResetActive: () -> Unit,
    onRequestResetAll: () -> Unit
) {
    // Wrap so the button sits on the left edge instead of stretching.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .combinedClickable(
                    onClick = onResetActive,
                    onLongClick = onRequestResetAll
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Restore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.metric_detail_reset),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun MetricDetailBody(
    key: String,
    fullHistory: com.eried.eucplanet.data.repository.FullMetricHistory,
    wheelData: WheelData,
    speedUnit: String,
    tempUnit: String
) {
    val legacyType: MetricType? = runCatching { MetricType.valueOf(key) }.getOrNull()
    val catalogSpec = com.eried.eucplanet.data.model.MetricCatalog.byKey(key)

    val rawSamples: List<MetricSample> = when (legacyType) {
        MetricType.BATTERY -> fullHistory.battery
        MetricType.TEMPERATURE -> fullHistory.temperature
        MetricType.VOLTAGE -> fullHistory.voltage
        MetricType.CURRENT -> fullHistory.current
        MetricType.LOAD -> fullHistory.load
        MetricType.SPEED -> fullHistory.speed
        // Any other catalog metric pulls from the extras map populated
        // by WheelRepository's 1Hz sampler. Empty list when the metric
        // isn't sampled yet (cold boot) or the wheel doesn't report it.
        null -> fullHistory.extras[key].orEmpty()
    }

    fun convert(v: Float): Float = when (legacyType) {
        MetricType.TEMPERATURE -> com.eried.eucplanet.util.Units.temperature(v, tempUnit)
        MetricType.SPEED -> com.eried.eucplanet.util.Units.speed(v, speedUnit)
        else -> v
    }

    val samples: List<MetricSample> =
        if (legacyType == MetricType.TEMPERATURE || legacyType == MetricType.SPEED) {
            rawSamples.map { MetricSample(it.timestampMs, convert(it.value)) }
        } else rawSamples

    val unitLabel = when (legacyType) {
        MetricType.TEMPERATURE -> com.eried.eucplanet.util.Units.tempUnit(tempUnit)
        MetricType.SPEED -> com.eried.eucplanet.util.Units.speedUnit(
            androidx.compose.ui.platform.LocalContext.current, speedUnit
        )
        null -> ""
        else -> legacyType.unit
    }

    val currentValue = when (legacyType) {
        MetricType.BATTERY -> convert(wheelData.batteryPercent.toFloat())
        MetricType.TEMPERATURE -> convert(wheelData.maxTemperature)
        MetricType.VOLTAGE -> convert(wheelData.voltage)
        MetricType.CURRENT -> convert(wheelData.current)
        MetricType.LOAD -> convert(kotlin.math.abs(wheelData.pwm))
        MetricType.SPEED -> convert(kotlin.math.abs(wheelData.speed))
        null -> rawCurrentValueFor(key, wheelData)
    }

    val accentColor = legacyType?.color ?: catalogSpec?.accent ?: AccentBlue

    Text(
        "${"%.1f".format(currentValue)} $unitLabel",
        fontSize = 48.sp,
        fontWeight = FontWeight.Bold,
        color = accentColor,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(8.dp))

    // Stats region — always visible, two rows so the rider sees a
    // consistent dashboard regardless of whether the buffer is full.
    // Row 1 = central tendency + extremes; row 2 = percentiles + count
    // + window time. Cells render `--` when the buffer is empty so the
    // layout stays stable.
    val values = samples.map { it.value }
    val hasBuffer = values.size >= 2
    val placeholderStat = "--"
    val minStr = if (hasBuffer) "%.1f".format(values.min()) else placeholderStat
    val maxStr = if (hasBuffer) "%.1f".format(values.max()) else placeholderStat
    val avgStr = if (hasBuffer) "%.1f".format(values.average()) else placeholderStat
    val medStr = if (hasBuffer) "%.1f".format(
        com.eried.eucplanet.ui.settings.computeDashboardStatValue(
            com.eried.eucplanet.ui.settings.DashboardStat.MEDIAN, samples, currentValue
        ) ?: 0f
    ) else placeholderStat
    val p95Str = if (hasBuffer) "%.1f".format(
        com.eried.eucplanet.ui.settings.computeDashboardStatValue(
            com.eried.eucplanet.ui.settings.DashboardStat.P95, samples, currentValue
        ) ?: 0f
    ) else placeholderStat
    val countStr = if (samples.isNotEmpty()) values.size.toString() else placeholderStat
    val durationStr = if (hasBuffer) {
        formatDuration((samples.last().timestampMs - samples.first().timestampMs) / 1000)
    } else placeholderStat

    // Primary stats: three accent-tinted pills — Min / Avg / Max.
    // These are the headline numbers a rider cares about most.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatPill(
            label = stringResource(R.string.metric_min),
            value = minStr,
            unit = unitLabel,
            accent = accentColor,
            modifier = Modifier.weight(1f)
        )
        StatPill(
            label = stringResource(R.string.metric_avg),
            value = avgStr,
            unit = unitLabel,
            accent = accentColor,
            modifier = Modifier.weight(1f)
        )
        StatPill(
            label = stringResource(R.string.metric_max),
            value = maxStr,
            unit = unitLabel,
            accent = accentColor,
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(Modifier.height(10.dp))

    // Secondary stats: a single thin row with the supporting numbers.
    // Bullet-separated inline text reads as a footer, not a grid, so
    // it doesn't compete with the three pills above for attention.
    StatSecondaryRow(
        median = medStr,
        p95 = p95Str,
        count = countStr,
        duration = durationStr,
        unit = unitLabel
    )

    Spacer(Modifier.height(16.dp))

    if (hasBuffer) {
        val windowSamples = samples

        val boundsFor: (Float, Float) -> GraphBounds = when (legacyType) {
            MetricType.BATTERY -> { _, _ -> GraphScale.fixed(0f, 100f) }
            MetricType.TEMPERATURE -> { mn, mx -> GraphScale.absolute(mn, mx, 5f) }
            MetricType.LOAD -> { mn, mx -> GraphScale.absolute(mn, mx, 5f) }
            MetricType.CURRENT -> { mn, mx -> GraphScale.absolute(mn, mx, 1f) }
            MetricType.VOLTAGE -> { mn, mx -> GraphScale.pad(mn, mx, GraphScale.SPAN_VOLTAGE) }
            MetricType.SPEED -> { mn, mx ->
                GraphScale.pad(
                    mn, mx, when (speedUnit) {
                        "mph" -> GraphScale.SPAN_SPEED_MPH
                        "ms" -> GraphScale.SPAN_SPEED_MS
                        else -> GraphScale.SPAN_SPEED_KMH
                    }
                )
            }
            null -> { mn, mx -> GraphScale.pad(mn, mx, 1f) }
        }

        MetricGraph(
            samples = windowSamples,
            color = accentColor,
            boundsFor = boundsFor,
            unitLabel = unitLabel,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
        )
    } else {
        EmptyGraph(
            color = accentColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        )
    }
}

/**
 * Primary stat card: label small on top, big value below, accent-tinted
 * background so the card reads as a chip rather than a spreadsheet cell.
 * Unit always renders with a leading space ("12.3 mph"), and dash-only
 * placeholders ("--") drop the unit entirely so the empty state doesn't
 * read as "--mph".
 */
@Composable
private fun StatPill(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val showUnit = unit.isNotBlank() && value.any { it.isDigit() }
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
        Text(
            buildString {
                append(value)
                if (showUnit) {
                    append(' ')
                    append(unit)
                }
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1
        )
    }
}

/**
 * Secondary stats footer — Median / P95 / N samples / time-window. Rendered
 * as small label·value chips in a single row so the supporting numbers
 * stay reachable without competing with the three primary pills above.
 */
@Composable
private fun StatSecondaryRow(
    median: String,
    p95: String,
    count: String,
    duration: String,
    unit: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SecondaryStatChip("Median", median, unit, modifier = Modifier.weight(1f))
        SecondaryStatChip("P95", p95, unit, modifier = Modifier.weight(1f))
        SecondaryStatChip("N", count, "", modifier = Modifier.weight(1f))
        SecondaryStatChip("Time", duration, "", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SecondaryStatChip(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    val showUnit = unit.isNotBlank() && value.any { it.isDigit() }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label.uppercase(),
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
        Text(
            buildString {
                append(value)
                if (showUnit) {
                    append(' ')
                    append(unit)
                }
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyGraph(
    color: Color,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val noDataLabel = stringResource(R.string.metric_no_data)
    val textMeasurer = rememberTextMeasurer()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 44.dp, bottom = 28.dp, top = 12.dp, end = 12.dp)
        ) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

            for (i in 0..4) {
                val y = h - h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = dash)
            }
            for (i in 0..3) {
                val x = w * i / 3f
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f, pathEffect = dash)
            }

            drawLine(
                color = color.copy(alpha = 0.3f),
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )

            val noData = textMeasurer.measure(noDataLabel, TextStyle(fontSize = 14.sp, color = labelColor))
            drawText(noData, topLeft = Offset(w / 2f - noData.size.width / 2f, h / 2f - noData.size.height - 8f))
        }
    }
}

@Composable
private fun MetricGraph(
    samples: List<MetricSample>,
    color: Color,
    boundsFor: (Float, Float) -> GraphBounds,
    unitLabel: String,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val values = samples.map { it.value }
    val minRaw = values.min()
    val maxRaw = values.max()
    val bounds = boundsFor(minRaw, maxRaw)
    val padded = bounds.max - bounds.min

    var hoverX by remember { mutableStateOf<Float?>(null) }
    val updatedSamples = rememberUpdatedState(samples)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(samples) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    hoverX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val anyPressed = event.changes.any { it.pressed }
                        if (!anyPressed) break
                        event.changes.firstOrNull()?.let { hoverX = it.position.x }
                    }
                    hoverX = null
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { hoverX = null })
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 44.dp, bottom = 28.dp, top = 12.dp, end = 12.dp)
        ) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

            for (i in 0..4) {
                val y = h - h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = dash)
                val value = bounds.min + padded * i / 4f
                val label = textMeasurer.measure(
                    "%.0f".format(value), TextStyle(fontSize = 10.sp, color = axisLabelColor)
                )
                drawText(label, topLeft = Offset(-label.size.width - 6f, y - label.size.height / 2f))
            }
            val timeSpanSec = ((samples.last().timestampMs - samples.first().timestampMs) / 1000).coerceAtLeast(1L)
            for (i in 0..3) {
                val x = w * i / 3f
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f, pathEffect = dash)
                val secondsAgo = timeSpanSec * (3 - i) / 3
                val label = textMeasurer.measure(
                    "-${secondsAgo}s", TextStyle(fontSize = 10.sp, color = axisLabelColor)
                )
                drawText(label, topLeft = Offset(x - label.size.width / 2f, h + 6f))
            }

            // Build path
            val path = androidx.compose.ui.graphics.Path()
            samples.forEachIndexed { idx, s ->
                val x = w * (s.timestampMs - samples.first().timestampMs).toFloat() /
                    (samples.last().timestampMs - samples.first().timestampMs).coerceAtLeast(1).toFloat()
                val y = h - h * (s.value - bounds.min) / padded.coerceAtLeast(0.001f)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val fillPath = androidx.compose.ui.graphics.Path()
            fillPath.addPath(path)
            fillPath.lineTo(w, h)
            fillPath.lineTo(0f, h)
            fillPath.close()
            clipRect(0f, 0f, w, h) {
                drawPath(fillPath, color = color.copy(alpha = 0.1f))
                drawPath(path, color = color, style = Stroke(width = 3f))
            }
        }

        // Crosshair + tooltip
        AnimatedVisibility(visible = hoverX != null, enter = fadeIn(animationSpec = tween(150)), exit = fadeOut()) {
            val x = hoverX ?: return@AnimatedVisibility
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize().padding(start = 44.dp, bottom = 28.dp, top = 12.dp, end = 12.dp)) {
                    val padLeft = 0f
                    val padRight = size.width
                    val xClamped = (x - 44f).coerceIn(padLeft, padRight)
                    drawLine(
                        color = Color.White.copy(alpha = 0.4f),
                        start = Offset(xClamped, 0f), end = Offset(xClamped, size.height),
                        strokeWidth = 1.5f
                    )
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m}:${"%02d".format(s)}"
}
