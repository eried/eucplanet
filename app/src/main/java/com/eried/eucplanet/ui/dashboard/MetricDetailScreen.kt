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
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.remap
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
    "FORWARD_G" -> w.forwardGFromSpeed
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

    // Both legacyType.color (a baked MetricType palette Color) and catalogSpec.accent
    // are baked palette constants; remap each to the active theme's matching token.
    // The final fallback (blue, used as a metric accent) maps to metricVoltage.
    val accentColor = (legacyType?.color ?: catalogSpec?.accent)
        ?.let { MaterialTheme.appColors.remap(it) }
        ?: MaterialTheme.appColors.metricVoltage

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

        // Bipolar regen split: when the metric can take both signs (current,
        // power, forward-G), the trace below the zero line draws in green so
        // regen / braking reads at a glance the same way it does in the
        // trip-detail Amps chart. Other metrics stay single-colour.
        val isBipolar = legacyType == MetricType.CURRENT ||
            key == "POWER" || key == "BATTERY_POWER" ||
            key == "MOTOR_POWER" || key == "FORWARD_G"
        val regenColor = if (isBipolar) MaterialTheme.appColors.metricBattery else null

        MetricGraph(
            samples = windowSamples,
            color = accentColor,
            boundsFor = boundsFor,
            unitLabel = unitLabel,
            regenColor = regenColor,
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

/**
 * X-axis formatter mode. `Relative` is the default "1:30 ago / now" stamp used
 * by the live dashboard graphs. `Clock` switches to absolute clock-time labels
 * at 15-minute boundaries (locale-aware 12 h vs 24 h), useful for long-running
 * charge sessions where minutes-ago is hard to map back to a wall clock.
 */
enum class TimeAxisFormat { Relative, Clock }

/**
 * One ghost marker overlaid on top of [MetricGraph], typically used to plot
 * the history of running predictions on the Battery screen: each prediction
 * snapshot becomes a small dot at `(timestampMs, value)`. The set of markers
 * lets the rider see the prediction converging or drifting over time.
 */
data class PredictionMarker(val timestampMs: Long, val value: Float)

@Composable
internal fun MetricGraph(
    samples: List<MetricSample>,
    color: Color,
    boundsFor: (Float, Float) -> GraphBounds,
    unitLabel: String,
    baselineValue: Float? = null,
    baselineColor: Color = color,
    series2: List<MetricSample>? = null,
    color2: Color = color,
    unit2: String = "",
    timeAxisFormat: TimeAxisFormat = TimeAxisFormat.Relative,
    /**
     * Optional prediction-history overlay -- rendered as small, faint dots in
     * [predictionColor], in addition to the main line. When non-empty, the
     * chart's X-axis is extended to include the latest marker time so dots
     * that lie in the future (i.e. predicted finish times) stay on-canvas.
     */
    predictionMarkers: List<PredictionMarker>? = null,
    predictionColor: Color = Color(0xFFFF4081),
    /**
     * Ordered future points (e.g. the predicted 80% then 100% finish) that a
     * dashed poly-line connects to, starting from the last recorded sample. Each
     * leg is straight -- the 80% and 100% ETAs come from separate estimators with
     * different timing, so a single line to 100% would miss the 80% dot. Scrubbing
     * past "now" follows the legs and reports the projected value at the cursor's
     * future minute; past the final point the scrub reads nothing.
     */
    predictionPath: List<PredictionMarker> = emptyList(),
    /**
     * Bipolar split colour: when non-null AND the rendered Y-range crosses
     * zero, the trace above the zero baseline is drawn in [color] and the
     * trace below in [regenColor]. Mirrors the TripDetailScreen ChartCard
     * convention used for the current chart so regen vs draw reads at a
     * glance on the per-metric history viewer too. No-op when the data is
     * single-polarity or [baselineValue] is set (the baseline already
     * carries semantic meaning for that mode).
     */
    regenColor: Color? = null,
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
    // Optional secondary series (e.g. voltage) on its own auto-scaled axis.
    val s2 = series2?.takeIf { it.size >= 2 }
    val bounds2 = s2?.let { GraphScale.pad(it.minOf { p -> p.value }, it.maxOf { p -> p.value }, 1f) }
    val padded2 = bounds2?.let { it.max - it.min } ?: 1f

    var touchX by remember { mutableStateOf<Float?>(null) }
    val haptics = LocalHapticFeedback.current
    val tooltipBg = MaterialTheme.colorScheme.surface
    val tooltipFg = MaterialTheme.colorScheme.onSurface

    // Locale clock format for the Clock-axis path. We don't need a
    // SimpleDateFormat per draw -- remember one, keyed on the 24 h preference,
    // and the TimeZone update is automatic via Calendar.getInstance().
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val is24h = remember(ctx) { android.text.format.DateFormat.is24HourFormat(ctx) }
    val clockFmt = remember(is24h) {
        java.text.SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", java.util.Locale.getDefault())
    }
    // X-axis time range. When prediction markers exist, push the right edge
    // out to the latest marker so future-dated points (predicted ETAs)
    // remain on-canvas instead of getting clipped at samples.last().
    val markers = predictionMarkers?.takeIf { it.isNotEmpty() }
    val xMinMs = samples.first().timestampMs
    // Cap how far into the future the chart will stretch. Without this, one
    // bogus prediction marker (e.g. a transiently-tiny slope giving a 24-day
    // ETA on a stalled-percent window) would extend xMaxMs absurdly far and
    // the 15-min tick loop below would do hundreds of thousands of iterations
    // -- enough to lock the UI for several seconds when the detail pane
    // opens. The cap also clips the marker dots themselves so a single
    // outlier prediction doesn't visually compress the rest of the trace.
    val futureCapMs = samples.last().timestampMs + MAX_PREDICTION_HORIZON_MS
    val futureMaxMs = maxOf(
        markers?.maxOf { it.timestampMs } ?: Long.MIN_VALUE,
        predictionPath.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE,
    ).coerceAtMost(futureCapMs)
    val xMaxMs = maxOf(samples.last().timestampMs, futureMaxMs)
    val xSpanMs = (xMaxMs - xMinMs).coerceAtLeast(1L)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            // Keyed on Unit (not samples) so a live data update every few seconds
            // doesn't restart the gesture and drop an in-progress hold/scrub.
            .pointerInput(Unit) {
                // Arm scrubbing after a short hold (~300 ms); a quick tap/flick falls
                // through to the bottom sheet. Once armed, every event is consumed
                // until the finger lifts, so the sheet can't move while you track the
                // pointer across the chart.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val armed = withTimeoutOrNull(300L) { waitForUpOrCancellation() } == null
                    if (!armed) return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    touchX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change != null) touchX = change.position.x
                        event.changes.forEach { it.consume() }
                        if (change == null || !change.pressed) break
                    }
                    touchX = null
                }
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 44.dp, bottom = 28.dp, top = 16.dp, end = if (s2 != null) 44.dp else 12.dp)
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
                // Right axis = secondary series (e.g. voltage) on its own scale.
                if (bounds2 != null) {
                    val v2 = bounds2.min + padded2 * i / 4f
                    val r = textMeasurer.measure(
                        "%.0f".format(v2), TextStyle(fontSize = 10.sp, color = color2)
                    )
                    drawText(r, topLeft = Offset(w + 6f, y - r.size.height / 2f))
                }
            }
            // Left unit ("%") above the left axis; secondary unit drawn on the right.
            if (unitLabel.isNotBlank()) {
                val unitText = textMeasurer.measure(
                    unitLabel, TextStyle(fontSize = 10.sp, color = axisLabelColor, fontWeight = FontWeight.Bold)
                )
                drawText(unitText, topLeft = Offset(6f, -unitText.size.height - 2f))
            }
            when (timeAxisFormat) {
                TimeAxisFormat.Relative -> {
                    val timeSpanSec = ((samples.last().timestampMs - samples.first().timestampMs) / 1000).coerceAtLeast(1L)
                    for (i in 0..3) {
                        val x = w * i / 3f
                        drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f, pathEffect = dash)
                        val secondsAgo = timeSpanSec * (3 - i) / 3
                        val timeText = if (secondsAgo <= 0L) "now"
                            else "%d:%02d".format(secondsAgo / 60, secondsAgo % 60)
                        val label = textMeasurer.measure(
                            timeText, TextStyle(fontSize = 10.sp, color = axisLabelColor)
                        )
                        drawText(label, topLeft = Offset(x - label.size.width / 2f, h + 6f))
                    }
                }
                TimeAxisFormat.Clock -> {
                    // Vertical gridline + clock-time label every 15 min, snapped
                    // to the wall-clock :00 / :15 / :30 / :45. Bump the spacing
                    // to 30/60 min when the chart spans more than ~2.5 h so the
                    // labels don't overrun each other on a phone-width canvas.
                    val gridStepMs = 15L * 60_000L
                    val spanMin = xSpanMs / 60_000L
                    val labelStepMs = when {
                        spanMin > 6 * 60 -> 60L * 60_000L
                        spanMin > 150L   -> 30L * 60_000L
                        else             -> gridStepMs
                    }
                    val cal = java.util.Calendar.getInstance().apply {
                        timeInMillis = xMinMs
                        // Round UP to the next 15-min boundary so the first
                        // gridline lands on a "nice" wall-clock minute.
                        val rem = (get(java.util.Calendar.MINUTE) % 15) * 60_000L +
                            get(java.util.Calendar.SECOND) * 1000L +
                            get(java.util.Calendar.MILLISECOND)
                        if (rem > 0L) add(java.util.Calendar.MILLISECOND, (gridStepMs - rem).toInt())
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    var tickMs = cal.timeInMillis
                    // Hard ceiling on the tick loop -- a defensive backstop in
                    // case xMaxMs drifts off-the-rails despite the cap above.
                    // The grid auto-relaxes to 30 / 60 min for long spans so
                    // 250 is comfortably more than needed for any real chart
                    // (a 24 h span at 60 min ticks is 24).
                    var safety = 0
                    while (tickMs <= xMaxMs && safety < MAX_TICK_COUNT) {
                        val x = w * (tickMs - xMinMs).toFloat() / xSpanMs.toFloat()
                        drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f, pathEffect = dash)
                        if ((tickMs - cal.timeInMillis) % labelStepMs == 0L) {
                            val label = textMeasurer.measure(
                                clockFmt.format(java.util.Date(tickMs)),
                                TextStyle(fontSize = 10.sp, color = axisLabelColor),
                            )
                            drawText(label, topLeft = Offset(x - label.size.width / 2f, h + 6f))
                        }
                        tickMs += gridStepMs
                        safety++
                    }
                    // "now" tick at samples.last so the rider can tell history
                    // (left of it) from projected predictions (right of it).
                    if (markers != null) {
                        val nowMs = samples.last().timestampMs
                        val xNow = w * (nowMs - xMinMs).toFloat() / xSpanMs.toFloat()
                        drawLine(
                            predictionColor.copy(alpha = 0.55f),
                            Offset(xNow, 0f), Offset(xNow, h),
                            strokeWidth = 1.5f,
                        )
                    }
                }
            }

            // Build path. X positions are normalised against the *extended*
            // span so the data line ends at the "now" marker even when the
            // chart extends further right for prediction overlays.
            val path = androidx.compose.ui.graphics.Path()
            var lastX = 0f
            samples.forEachIndexed { idx, s ->
                val x = w * (s.timestampMs - xMinMs).toFloat() / xSpanMs.toFloat()
                val y = h - h * (s.value - bounds.min) / padded.coerceAtLeast(0.001f)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                lastX = x
            }
            val fillPath = androidx.compose.ui.graphics.Path()
            fillPath.addPath(path)
            fillPath.lineTo(lastX, h)
            fillPath.lineTo(0f, h)
            fillPath.close()
            clipRect(0f, 0f, w, h) {
                if (baselineValue != null) {
                    val baseY = (h - h * (baselineValue - bounds.min) / padded.coerceAtLeast(0.001f)).coerceIn(0f, h)
                    // Existing charge: a faint blue band below the session-start
                    // level, sparser diagonal hatch on top to read as "carried over".
                    drawRect(
                        color = color.copy(alpha = 0.10f),
                        topLeft = Offset(0f, baseY),
                        size = Size(w, h - baseY),
                    )
                    val bandH = h - baseY
                    if (bandH > 1f) {
                        clipRect(0f, baseY, w, h) {
                            val spacing = 26f
                            val stripe = baselineColor.copy(alpha = 0.22f)
                            var x = 0f
                            while (x < w + bandH) {
                                drawLine(
                                    stripe,
                                    Offset(x, baseY),
                                    Offset(x - bandH, h),
                                    strokeWidth = 1f,
                                )
                                x += spacing
                            }
                        }
                    }
                    // Added this session: fill only above the start level.
                    clipRect(0f, 0f, w, baseY) {
                        drawPath(fillPath, color = color.copy(alpha = 0.20f))
                    }
                    drawLine(
                        baselineColor.copy(alpha = 0.6f),
                        Offset(0f, baseY), Offset(w, baseY),
                        strokeWidth = 1.5f, pathEffect = dash,
                    )
                    drawPath(path, color = color, style = Stroke(width = 3f))
                } else if (regenColor != null && bounds.min < 0f && bounds.max > 0f) {
                    // Bipolar split at the zero baseline. Mirrors the trip-detail
                    // ChartCard convention so current / power / forward-G read the
                    // same way on the dashboard's per-metric history viewer.
                    val zeroY = (h - h * (0f - bounds.min) / padded.coerceAtLeast(0.001f)).coerceIn(0f, h)
                    clipRect(top = 0f, bottom = zeroY) {
                        drawPath(fillPath, color = color.copy(alpha = 0.18f))
                        drawPath(path, color = color, style = Stroke(width = 3f))
                    }
                    clipRect(top = zeroY, bottom = h) {
                        drawPath(fillPath, color = regenColor.copy(alpha = 0.18f))
                        drawPath(path, color = regenColor, style = Stroke(width = 3f))
                    }
                    // Subtle zero-line so the split is visually anchored.
                    drawLine(
                        color.copy(alpha = 0.4f),
                        Offset(0f, zeroY), Offset(w, zeroY),
                        strokeWidth = 1.5f,
                    )
                } else {
                    drawPath(fillPath, color = color.copy(alpha = 0.1f))
                    drawPath(path, color = color, style = Stroke(width = 3f))
                }
            }

            // Secondary series (e.g. voltage) on its own value scale, but
            // sharing the primary X-axis so both lines align on the same
            // wall-clock minute.
            if (s2 != null && bounds2 != null) {
                val p2 = androidx.compose.ui.graphics.Path()
                s2.forEachIndexed { idx, smp ->
                    val x = w * (smp.timestampMs - xMinMs).toFloat() / xSpanMs.toFloat()
                    val y = h - h * (smp.value - bounds2.min) / padded2.coerceAtLeast(0.001f)
                    if (idx == 0) p2.moveTo(x, y) else p2.lineTo(x, y)
                }
                clipRect(0f, 0f, w, h) { drawPath(p2, color = color2, style = Stroke(width = 2.5f)) }
                if (unit2.isNotBlank()) {
                    val u2 = textMeasurer.measure(
                        unit2, TextStyle(fontSize = 10.sp, color = color2, fontWeight = FontWeight.Bold)
                    )
                    drawText(u2, topLeft = Offset(w - u2.size.width - 6f, -u2.size.height - 2f))
                }
            }

            // Prediction line: a dashed straight segment from the last recorded
            // point to the predicted 100% target. The rider can scrub along it
            // and read the projected % at any future minute -- a plain linear
            // extrapolation, drawn under the marker dots so they sit on top.
            if (predictionPath.isNotEmpty()) {
                val lastS = samples.last()
                var prevX = w * (lastS.timestampMs - xMinMs).toFloat() / xSpanMs
                var prevY = (h - h * (lastS.value - bounds.min) / padded.coerceAtLeast(0.001f)).coerceIn(0f, h)
                val dash8 = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                clipRect(0f, 0f, w, h) {
                    for (p in predictionPath) {
                        val cx = w * (p.timestampMs - xMinMs).toFloat() / xSpanMs
                        val cy = (h - h * (p.value - bounds.min) / padded.coerceAtLeast(0.001f)).coerceIn(0f, h)
                        drawLine(
                            predictionColor.copy(alpha = 0.6f),
                            Offset(prevX, prevY), Offset(cx, cy),
                            strokeWidth = 2f, pathEffect = dash8,
                        )
                        prevX = cx; prevY = cy
                    }
                }
            }

            // Prediction-history markers: small faint dots clustered around
            // the running predicted finish time. As the prediction stabilises
            // they cluster tightly; if it drifts they spread along X. Draw
            // before the tooltip so the cursor still wins on top.
            if (markers != null) {
                clipRect(0f, 0f, w, h) {
                    for (m in markers) {
                        val x = w * (m.timestampMs - xMinMs).toFloat() / xSpanMs.toFloat()
                        val y = (h - h * (m.value - bounds.min) / padded.coerceAtLeast(0.001f))
                            .coerceIn(0f, h)
                        // Filled centre + a faint halo so the marker reads
                        // clearly against both the line and the background fill.
                        // Sized so each dot is glanceable on a phone screen
                        // without competing with the live trace.
                        drawCircle(predictionColor.copy(alpha = 0.20f), radius = 9f, center = Offset(x, y))
                        drawCircle(predictionColor, radius = 5f, center = Offset(x, y))
                    }
                }
            }

            // Scrub cursor + value tooltip (long-press drag). touchX is in Box
            // coords; the plot begins after the left-axis padding. Cursor time
            // is mapped against the extended xMinMs/xMaxMs so hovering past
            // "now" (into the prediction region) reports the correct future
            // clock minute.
            val tx = touchX
            if (tx != null && samples.size >= 2) {
                val cursorX = (tx - 44.dp.toPx()).coerceIn(0f, w)
                val tN = samples.last().timestampMs
                val lastVal = samples.last().value
                val frac = (cursorX / w).coerceIn(0f, 1f)
                val tTargetExtended = xMinMs + (frac * xSpanMs.toDouble()).toLong()
                val inHistory = tTargetExtended <= tN
                // Value at the cursor time. In the recorded region we interpolate
                // the samples; past "now" we follow the straight prediction line
                // to the target (100%); past the target it is null so nothing is
                // drawn (no more pinning the last recorded % across the future).
                val value: Float? = when {
                    inHistory -> {
                        val li = samples.indexOfLast { it.timestampMs <= tTargetExtended }.coerceIn(0, samples.size - 1)
                        val ri = (li + 1).coerceAtMost(samples.size - 1)
                        val a = samples[li]; val b = samples[ri]
                        val sp = (b.timestampMs - a.timestampMs).coerceAtLeast(1L)
                        val ff = ((tTargetExtended - a.timestampMs).toFloat() / sp).coerceIn(0f, 1f)
                        a.value + (b.value - a.value) * ff
                    }
                    predictionPath.isNotEmpty() && tTargetExtended <= predictionPath.last().timestampMs -> {
                        // Walk the poly-line legs (last sample -> each path point)
                        // and interpolate within the leg the cursor falls in.
                        var prevT = tN; var prevV = lastVal
                        var v = predictionPath.last().value
                        for (p in predictionPath) {
                            if (tTargetExtended <= p.timestampMs) {
                                val span = (p.timestampMs - prevT).coerceAtLeast(1L)
                                val ff = ((tTargetExtended - prevT).toFloat() / span).coerceIn(0f, 1f)
                                v = prevV + (p.value - prevV) * ff
                                break
                            }
                            prevT = p.timestampMs; prevV = p.value
                        }
                        v
                    }
                    else -> null
                }
                if (value != null) {
                    val cursorY = (h - h * (value - bounds.min) / padded.coerceAtLeast(0.001f)).coerceIn(0f, h)
                    drawLine(color.copy(alpha = 0.5f), Offset(cursorX, 0f), Offset(cursorX, h), strokeWidth = 1.5f)
                    drawCircle(color, radius = 4f, center = Offset(cursorX, cursorY))
                    drawCircle(Color.White, radius = 2f, center = Offset(cursorX, cursorY))

                    val labelText = buildString {
                        if (timeAxisFormat == TimeAxisFormat.Clock) {
                            // Wall-clock time under the cursor (extended, so a
                            // future minute in the prediction region reads right).
                            append(clockFmt.format(java.util.Date(tTargetExtended)))
                            append("  •  ")
                        }
                        append(if (unitLabel.isBlank()) "%.1f".format(value) else "%.1f %s".format(value, unitLabel))
                        // Secondary series only exists over the recorded region.
                        if (s2 != null && inHistory) {
                            val li2 = s2.indexOfLast { it.timestampMs <= tTargetExtended }.coerceIn(0, s2.size - 1)
                            val ri2 = (li2 + 1).coerceAtMost(s2.size - 1)
                            val a2 = s2[li2]; val b2 = s2[ri2]
                            val sp2 = (b2.timestampMs - a2.timestampMs).coerceAtLeast(1L)
                            val ff2 = ((tTargetExtended - a2.timestampMs).toFloat() / sp2).coerceIn(0f, 1f)
                            append("  •  %.1f %s".format(a2.value + (b2.value - a2.value) * ff2, unit2))
                        }
                    }
                    val measured = textMeasurer.measure(
                        labelText, TextStyle(fontSize = 10.sp, color = tooltipFg, fontWeight = FontWeight.Medium)
                    )
                    val padX = 5f
                    val padY = 2f
                    val boxW = measured.size.width + padX * 2
                    val boxH = measured.size.height + padY * 2
                    val boxX = (cursorX - boxW / 2f).coerceIn(0f, w - boxW)
                    val boxY = (cursorY - boxH - 6f).coerceAtLeast(0f)
                    drawRoundRect(tooltipBg, topLeft = Offset(boxX, boxY), size = Size(boxW, boxH), cornerRadius = CornerRadius(5f, 5f))
                    drawText(measured, topLeft = Offset(boxX + padX, boxY + padY))
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

/**
 * Maximum chart x-axis extension into the future, in ms. Caps how far the
 * chart will stretch to fit a prediction marker -- prevents one bogus ETA
 * (transiently-tiny slope on integer-percent BMSs) from stretching the
 * axis weeks into the future and blowing up the tick loop.
 */
private const val MAX_PREDICTION_HORIZON_MS = 8L * 60L * 60_000L  // 8 h

/**
 * Hard ceiling on the Clock-axis tick loop. A 24 h chart at 60-min ticks
 * only needs 24; 250 is a defence-in-depth backstop in case anything ever
 * sneaks past the prediction horizon cap.
 */
private const val MAX_TICK_COUNT = 250
