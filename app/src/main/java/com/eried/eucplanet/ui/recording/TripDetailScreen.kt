package com.eried.eucplanet.ui.recording

import android.annotation.SuppressLint
import android.os.Build
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.eried.eucplanet.util.GraphScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.theme.appColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    trip: TripRecord,
    onBack: () -> Unit,
    onViewOnline: ((Long) -> Unit)? = null,
    onReplayTrip: ((Long) -> Unit)? = null,
    viewModel: RecordingViewModel = hiltViewModel()
) {
    var dataPoints by remember { mutableStateOf<List<TripDataPoint>>(emptyList()) }
    var showShareDialog by remember { mutableStateOf(false) }
    // The in-progress trip can't be shared, its CSV isn't finalised yet.
    // null = not yet known; only once it resolves to a definite false do we let
    // the self-heal touch the stored row (so a live trip is never finalised
    // early by the detail screen).
    val liveState by viewModel.isTripLiveRecording(trip).collectAsState(initial = null)
    val isLiveTrip = liveState == true
    // Landscape split: the rider chooses whether the map docks left or right.
    val tripMapSide by viewModel.tripMapSide.collectAsState()

    // Render the ViewModel's messages (e.g. "Preparing the link…", share
    // failures) here too — sharing is launched straight from this screen, which
    // otherwise has no host so the snackbars went nowhere.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.toasts.collect { snackbar.showSnackbar(it) }
    }

    if (showShareDialog) {
        val dropboxLinked by viewModel.dropboxLinked.collectAsState()
        TripActionDialog(
            onShareFile = { viewModel.shareTrip(trip) },
            onViewOnline = { onViewOnline?.invoke(trip.id) },
            onReplay = { onReplayTrip?.invoke(trip.id) },
            onDismiss = { showShareDialog = false },
            dropboxLinked = dropboxLinked,
            onShareViaDropbox = { viewModel.shareViaDropbox(trip) },
            onInspectOnline = { viewModel.inspectOnline(trip) },
        )
    }

    LaunchedEffect(trip.id) {
        dataPoints = viewModel.readTripData(trip)
    }

    val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Trip metrics and the header date range (start -> end) are hoisted so the
    // landscape top bar can show the range centred (the landscape body gives its
    // height to the permanent map + the scrollable charts, with no room for it).
    val metrics = remember(dataPoints) { viewModel.tripMetrics(dataPoints) }
    LaunchedEffect(metrics, liveState) {
        if (liveState == false) viewModel.healTripMetrics(trip, metrics)
    }
    val startMs = if (metrics.valid) metrics.startMs else trip.startTime
    val duration = if (metrics.valid) metrics.durationMs / 1000
        else ((trip.endTime ?: trip.startTime) - trip.startTime) / 1000
    val endMs = startMs + duration * 1000
    val headerDateTime = remember(startMs, endMs) {
        val dayFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        // Same-day rides show just the end HH:mm; a ride crossing midnight shows
        // the full end date too.
        val endText = if (dayFormat.format(Date(startMs)) == dayFormat.format(Date(endMs)))
            timeFormat.format(Date(endMs)) else dateFormat.format(Date(endMs))
        "${dateFormat.format(Date(startMs))} → $endText"
    }

    Scaffold(
        topBar = {
            if (landscape) {
                // Landscape bar: back + title on the left, the date range centred,
                // share on the right.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .height(56.dp)
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterStart),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                        Text(
                            stringResource(R.string.recording_detail_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    if (dataPoints.isNotEmpty()) {
                        Text(
                            headerDateTime,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    IconButton(
                        onClick = { showShareDialog = true },
                        enabled = !isLiveTrip,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                    }
                }
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.recording_detail_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showShareDialog = true },
                            enabled = !isLiveTrip
                        ) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (dataPoints.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                HintText(stringResource(R.string.recording_no_data))
            }
        } else {
            // Content-only derived values (the header metrics are hoisted above so
            // the landscape top bar can show the date range).
            // Distance: trust the stored value (wheel odometer at finalize) when
            // present; recompute from the CSV only for trips that never got one.
            val distanceKm = if (trip.distanceKm > 0f) trip.distanceKm else metrics.distanceKm
            // Top speed as a SUSTAINED value, not a lone GPS/sensor spike: the
            // fastest the wheel actually held for ~2 s (see sustainedTopSpeed).
            val maxSpeedRaw = remember(dataPoints, duration) {
                val n = dataPoints.size
                val window = if (n >= 2 && duration > 0)
                    kotlin.math.ceil(SUSTAINED_TOP_SPEED_MS / (duration * 1000.0 / (n - 1)))
                        .toInt().coerceIn(2, n)
                else 1
                sustainedTopSpeed(dataPoints.map { it.speed }, window)
            }
            val avgSpeedRaw = dataPoints.map { it.speed }.average().toFloat()
            // Avg moving speed: mean over genuinely-moving samples (> 1 km/h).
            val movingSpeeds = dataPoints.map { it.speed }.filter { it > 1f }
            val avgMovingRaw = if (movingSpeeds.isNotEmpty()) movingSpeeds.average().toFloat() else 0f
            // Max temp over plausible readings only (drop empty-slot junk values).
            val maxTempRaw = dataPoints.map { it.temperature }
                .filter { com.eried.eucplanet.util.MetricSanity.isPlausibleTempC(it) }
                .maxOrNull() ?: 0f
            // Battery/voltage stats over a validity mask (drops wheel-off garbage).
            val batteryStats = remember(dataPoints) { computeBatteryStats(dataPoints) }
            val speedUnit by viewModel.speedUnit.collectAsState()
            val distanceUnit by viewModel.distanceUnit.collectAsState()
            val tempUnit by viewModel.tempUnit.collectAsState()
            val speedUnitLabel = com.eried.eucplanet.util.Units.speedUnit(
                androidx.compose.ui.platform.LocalContext.current, speedUnit
            )
            val distanceUnitLabel = com.eried.eucplanet.util.Units.distanceUnit(distanceUnit)
            val tempUnitLabel = com.eried.eucplanet.util.Units.tempUnit(tempUnit)
            val maxSpeed = com.eried.eucplanet.util.Units.speed(maxSpeedRaw, speedUnit)
            val avgSpeed = com.eried.eucplanet.util.Units.speed(avgSpeedRaw, speedUnit)
            val avgMoving = com.eried.eucplanet.util.Units.speed(avgMovingRaw, speedUnit)
            val tripDistance = com.eried.eucplanet.util.Units.distance(distanceKm, distanceUnit)
            val maxTemp = com.eried.eucplanet.util.Units.temperature(maxTempRaw, tempUnit)

            // Shared scrub index: scrubbing any chart moves the map marker and the
            // cursor on every other chart to the same sample.
            var scrubIndex by remember { mutableStateOf<Int?>(null) }
            val onScrub: (Int?) -> Unit = { scrubIndex = it }

            val gpsPoints = remember(dataPoints) {
                dataPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
            }
            val isLive by viewModel.isTripLiveRecording(trip).collectAsState(initial = false)
            val liveLocation by viewModel.liveLocation.collectAsState()
            val hasMap = gpsPoints.size >= 2 || (isLive && liveLocation != null)
            // The scrubbed sample's own GPS fix (from the full dataPoints, which the
            // chart index maps onto), or null if it had none.
            val scrubPoint = scrubIndex?.let { i ->
                dataPoints.getOrNull(i)?.takeIf { it.latitude != 0.0 && it.longitude != 0.0 }
            }

            // Speed chart overlays: wheel speed (main line) vs GPS / RaceBox speed,
            // NaN where a series has no reading so the line breaks instead of zeroing.
            val gpsSpeedSeries = dataPoints.map {
                if (it.gpsSpeed <= 0f) Float.NaN
                else com.eried.eucplanet.util.Units.speed(it.gpsSpeed, speedUnit)
            }
            val extSpeedSeries = dataPoints.map {
                if (it.extGpsSpeed.isNaN()) Float.NaN
                else com.eried.eucplanet.util.Units.speed(it.extGpsSpeed, speedUnit)
            }
            val speedOverlays = buildList {
                if (gpsSpeedSeries.any { !it.isNaN() })
                    add(ChartOverlay(gpsSpeedSeries, MaterialTheme.appColors.metricPosition, label = "GPS"))
                if (extSpeedSeries.any { !it.isNaN() })
                    add(ChartOverlay(extSpeedSeries, MaterialTheme.appColors.metricTemp, label = "Ext"))
            }
            val speedMinSpan = when (speedUnit) {
                "mph" -> GraphScale.SPAN_SPEED_MPH
                "ms" -> GraphScale.SPAN_SPEED_MS
                else -> GraphScale.SPAN_SPEED_KMH
            }
            val speedPeakRaw = dataPoints.map { it.speed }.maxOrNull() ?: 0f
            val speedPeak = com.eried.eucplanet.util.Units.speed(speedPeakRaw, speedUnit)
            val tempMinSpan = if (tempUnit == "F") GraphScale.SPAN_TEMPERATURE_F
                else GraphScale.SPAN_TEMPERATURE_C

            // Route map, reused inline (portrait) and permanent-left (landscape).
            val routeMap: @Composable (Modifier) -> Unit = { mod ->
                RouteMapView(
                    points = gpsPoints,
                    isLive = isLive,
                    liveLat = liveLocation?.latitude,
                    liveLon = liveLocation?.longitude,
                    scrubLat = scrubPoint?.latitude,
                    scrubLon = scrubPoint?.longitude,
                    modifier = mod,
                )
            }

            // Summary cards.
            val summaryCards: @Composable ColumnScope.() -> Unit = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard(stringResource(R.string.recording_summary_distance), "%.1f %s".format(tripDistance, distanceUnitLabel), MaterialTheme.appColors.metricVoltage, Modifier.weight(1f))
                    SummaryCard(stringResource(R.string.recording_summary_duration), com.eried.eucplanet.util.Units.humanDuration(duration), MaterialTheme.appColors.metricVoltage, Modifier.weight(1f))
                    SummaryCard(stringResource(R.string.recording_summary_points), "${dataPoints.size}", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard(stringResource(R.string.recording_summary_top_speed), "%.0f %s".format(maxSpeed, speedUnitLabel), MaterialTheme.appColors.metricTemp, Modifier.weight(1f))
                    SummaryCard(stringResource(R.string.recording_summary_avg_speed), "%.0f %s".format(avgSpeed, speedUnitLabel), MaterialTheme.appColors.metricBattery, Modifier.weight(1f))
                    SummaryCard(stringResource(R.string.recording_summary_avg_moving), "%.0f %s".format(avgMoving, speedUnitLabel), MaterialTheme.appColors.metricBattery, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard(
                        stringResource(R.string.recording_summary_battery, batteryStats.batteryConsumption),
                        stringResource(R.string.recording_summary_battery_fmt, batteryStats.batteryMax, batteryStats.batteryMin),
                        if (batteryStats.batteryMin < 20) MaterialTheme.appColors.statusDanger else MaterialTheme.appColors.statusGood,
                        Modifier.weight(1f)
                    )
                    SummaryCard(
                        stringResource(R.string.recording_summary_voltage),
                        stringResource(R.string.recording_summary_voltage_fmt, batteryStats.voltageMax, batteryStats.voltageMin),
                        MaterialTheme.appColors.metricPosition,
                        Modifier.weight(1f)
                    )
                    SummaryCard(stringResource(R.string.recording_summary_max_temp),
                        "%.0f%s".format(maxTemp, tempUnitLabel),
                        if (maxTempRaw > 60) MaterialTheme.appColors.statusDanger else MaterialTheme.appColors.metricTemp, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard(
                        stringResource(R.string.recording_summary_max_pwm),
                        if (batteryStats.maxPwm.isNaN()) "--" else "%.0f%%".format(batteryStats.maxPwm),
                        if (!batteryStats.maxPwm.isNaN() && batteryStats.maxPwm > 80) MaterialTheme.appColors.statusDanger
                        else MaterialTheme.appColors.metricTemp,
                        Modifier.weight(1f)
                    )
                    SummaryCard(
                        stringResource(R.string.recording_summary_max_current),
                        if (batteryStats.maxCurrent.isNaN()) "--" else "%.1f A".format(batteryStats.maxCurrent),
                        MaterialTheme.appColors.metricVoltage,
                        Modifier.weight(1f)
                    )
                    SummaryCard(
                        stringResource(R.string.recording_summary_max_power),
                        if (batteryStats.maxPower.isNaN()) "--" else "%.0f W".format(batteryStats.maxPower),
                        MaterialTheme.appColors.metricPosition,
                        Modifier.weight(1f)
                    )
                }
            }

            // Scrub-synced charts.
            val chartsContent: @Composable ColumnScope.() -> Unit = {
                ChartCard(stringResource(R.string.recording_chart_speed, speedUnitLabel),
                    dataPoints.map { com.eried.eucplanet.util.Units.speed(it.speed, speedUnit) },
                    MaterialTheme.appColors.metricBattery, unitLabel = speedUnitLabel, minSpan = speedMinSpan,
                    overlays = speedOverlays, axisMax = maxSpeed, peak = speedPeak,
                    scrubIndex = scrubIndex, onScrub = onScrub)
                Spacer(Modifier.height(12.dp))
                ChartCard(stringResource(R.string.recording_chart_battery), dataPoints.map { it.battery.toFloat() },
                    MaterialTheme.appColors.metricVoltage, unitLabel = "%", minSpan = GraphScale.SPAN_BATTERY,
                    scrubIndex = scrubIndex, onScrub = onScrub)
                Spacer(Modifier.height(12.dp))
                ChartCard(stringResource(R.string.recording_chart_temp, tempUnitLabel),
                    dataPoints.map { com.eried.eucplanet.util.Units.temperature(it.temperature, tempUnit) },
                    MaterialTheme.appColors.metricTemp, unitLabel = tempUnitLabel, minSpan = tempMinSpan,
                    scrubIndex = scrubIndex, onScrub = onScrub)
                Spacer(Modifier.height(12.dp))
                ChartCard(stringResource(R.string.recording_chart_voltage), dataPoints.map { it.voltage },
                    MaterialTheme.appColors.statusDanger, unitLabel = "V", minSpan = GraphScale.SPAN_VOLTAGE,
                    scrubIndex = scrubIndex, onScrub = onScrub)
                if (dataPoints.any { !it.current.isNaN() }) {
                    Spacer(Modifier.height(12.dp))
                    ChartCard(stringResource(R.string.recording_chart_current),
                        dataPoints.map { it.current },
                        MaterialTheme.appColors.metricVoltage, unitLabel = "A", minSpan = GraphScale.SPAN_CURRENT,
                        regenColor = MaterialTheme.appColors.metricBattery,
                        scrubIndex = scrubIndex, onScrub = onScrub)
                }
                if (dataPoints.any { !it.pwm.isNaN() }) {
                    Spacer(Modifier.height(12.dp))
                    ChartCard(stringResource(R.string.recording_chart_pwm),
                        dataPoints.map { it.pwm },
                        MaterialTheme.appColors.metricTemp, unitLabel = "%", minSpan = GraphScale.SPAN_LOAD,
                        scrubIndex = scrubIndex, onScrub = onScrub)
                }
            }

            if (landscape) {
                // Landscape: a permanent map docked on one side, everything else
                // scrollable on the other, so scrubbing a chart updates the
                // always-visible map. The rider picks which side the map sits on.
                val mapOnLeft = tripMapSide != "RIGHT"
                val mapPane: @Composable RowScope.() -> Unit = {
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .padding(
                                start = if (mapOnLeft) 16.dp else 0.dp,
                                end = if (mapOnLeft) 0.dp else 16.dp,
                                top = 8.dp, bottom = 16.dp,
                            )
                    ) { routeMap(Modifier.fillMaxSize()) }
                }
                val infoPane: @Composable RowScope.() -> Unit = {
                    Column(
                        (if (hasMap) Modifier.weight(1f) else Modifier.fillMaxWidth())
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                    ) {
                        Spacer(Modifier.height(8.dp))
                        summaryCards()
                        Spacer(Modifier.height(16.dp))
                        chartsContent()
                        Spacer(Modifier.height(16.dp))
                    }
                }
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (hasMap && mapOnLeft) mapPane()
                    infoPane()
                    if (hasMap && !mapOnLeft) mapPane()
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        headerDateTime,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    summaryCards()
                    if (hasMap) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.recording_route), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        routeMap(Modifier.fillMaxWidth().height(250.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    chartsContent()
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium)
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

// The rider's Trip-details map-style pick (LIGHT / DARK / SAT) sticks for the rest of
// the app session, across trips, then resets on restart (in-memory, not persisted) and
// re-defaults from the theme. Process-scoped, mirroring the alarm constant-tone prompt.
private var tripMapTypeSession: String? = null

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RouteMapView(
    points: List<TripDataPoint>,
    isLive: Boolean = false,
    liveLat: Double? = null,
    liveLon: Double? = null,
    // When a chart is being scrubbed, the GPS position of that sample (or null to
    // hide the marker). Drives a dot on the map synced with the chart cursor.
    scrubLat: Double? = null,
    scrubLon: Double? = null,
    modifier: Modifier = Modifier
) {
    // rememberSaveable so a rotation (which recreates the composition) keeps the
    // map fullscreen instead of dropping back to the trip details.
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // Map style (light / dark / satellite) is shared between the inline and the
    // fullscreen map so opening fullscreen keeps the style the rider picked,
    // rather than resetting to light.
    // Default the style from the active theme's background luminance: a dark theme
    // (including any custom theme with a dark background) gets the dark map, a light
    // one gets the white map. A rider's explicit pick overrides this for the session.
    val themeMapDefault = if (MaterialTheme.appColors.appBackground.luminance() < 0.5f) "DARK" else "LIGHT"
    var mapType by rememberSaveable { mutableStateOf(tripMapTypeSession ?: themeMapDefault) }

    MapSurface(
        points = points, isLive = isLive, liveLat = liveLat, liveLon = liveLon,
        scrubLat = scrubLat, scrubLon = scrubLon,
        fullscreen = false, onToggleFullscreen = { fullscreen = true },
        mapType = mapType, onMapTypeChange = { mapType = it; tripMapTypeSession = it },
        modifier = modifier,
    )

    if (fullscreen) {
        // Fullscreen map: no parent scroll fights the gestures, so panning and
        // pinch-zoom are unencumbered. Back button or the exit icon closes it.
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // usePlatformDefaultWidth = false alone still leaves the dialog window
            // short of the edges (most visible in landscape, where the content
            // behind shows in the system-bar insets around the map). Fill the
            // window, drop the scrim, draw edge-to-edge, and hide the system bars
            // so the map is truly immersive and nothing shows behind it. Swiping
            // from an edge brings the bars back transiently; closing restores them.
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                dialogWindow?.let { w ->
                    w.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    w.setDimAmount(0f)
                    // Draw into the display cutout too, otherwise in landscape the
                    // notch side stays letterboxed and the content behind shows.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        w.attributes = w.attributes.apply {
                            layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }
                    WindowCompat.setDecorFitsSystemWindows(w, false)
                    WindowInsetsControllerCompat(w, w.decorView).apply {
                        hide(WindowInsetsCompat.Type.systemBars())
                        systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            }
            MapSurface(
                points = points, isLive = isLive, liveLat = liveLat, liveLon = liveLon,
                scrubLat = scrubLat, scrubLon = scrubLon,
                fullscreen = true, onToggleFullscreen = { fullscreen = false },
                mapType = mapType, onMapTypeChange = { mapType = it; tripMapTypeSession = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun MapSurface(
    points: List<TripDataPoint>,
    isLive: Boolean,
    liveLat: Double?,
    liveLon: Double?,
    scrubLat: Double?,
    scrubLon: Double?,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    mapType: String,
    onMapTypeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coordsJson = remember(points) {
        points.joinToString(",") { "[${it.latitude},${it.longitude}]" }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val mapTypes = listOf("LIGHT", "DARK", "SAT")
    // Rebuild the WebView only when the historical trace changes or when we first
    // enter/leave live mode. Bake the CURRENT style into the initial HTML so a
    // freshly-opened surface (e.g. fullscreen) starts on the shared style rather
    // than flashing light first; style cycles afterwards go through JS.
    val html = remember(coordsJson, isLive) { buildMapHtml(coordsJson, isLive, mapType) }

    Box(modifier.clip(RoundedCornerShape(if (fullscreen) 0.dp else 12.dp))) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    setBackgroundColor(android.graphics.Color.parseColor("#0b0f19"))
                    // Own drag gestures on the map: ask the Compose scroll
                    // container (which honours requestDisallowInterceptTouchEvent)
                    // not to steal them, so a one-finger drag pans the map instead
                    // of scrolling the page. Released on UP/CANCEL so a drag that
                    // starts off the map still scrolls the page normally.
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    webView = this
                }
            },
            update = { wv -> webView = wv },
            modifier = Modifier.fillMaxSize()
        )
        // Controls: fullscreen toggle over the map-style cycler.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapButton(
                icon = if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                desc = "Fullscreen map",
                onClick = onToggleFullscreen,
            )
            MapButton(
                icon = Icons.Default.Layers,
                desc = "Map style",
                onClick = {
                    onMapTypeChange(mapTypes[(mapTypes.indexOf(mapType) + 1) % mapTypes.size])
                },
            )
        }
    }

    // Apply the shared style to this WebView whenever it changes (the initial
    // style is already baked into the HTML; this keeps both the inline and the
    // fullscreen surface in sync when either cycles it).
    LaunchedEffect(mapType, webView) {
        webView?.evaluateJavascript("if(window.setMapType)setMapType('$mapType');", null)
    }

    // Push live GPS updates into the map via a JS hook defined in the HTML.
    LaunchedEffect(isLive, liveLat, liveLon, webView) {
        val wv = webView ?: return@LaunchedEffect
        if (!isLive) return@LaunchedEffect
        val lat = liveLat ?: return@LaunchedEffect
        val lon = liveLon ?: return@LaunchedEffect
        wv.evaluateJavascript("if (window.updateLivePoint) updateLivePoint($lat,$lon);", null)
    }

    // Chart-scrub marker: move a dot to the scrubbed sample's GPS position, or
    // hide it when scrubbing stops (or the sample had no fix).
    LaunchedEffect(scrubLat, scrubLon, webView) {
        val wv = webView ?: return@LaunchedEffect
        if (scrubLat != null && scrubLon != null) {
            wv.evaluateJavascript("if(window.updateScrubPoint)updateScrubPoint($scrubLat,$scrubLon);", null)
        } else {
            wv.evaluateJavascript("if(window.clearScrubPoint)clearScrubPoint();", null)
        }
    }
}

@Composable
private fun MapButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurface)
    }
}

private fun buildMapHtml(coordsJson: String, isLive: Boolean, initialType: String): String = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  html,body,#map{margin:0;padding:0;width:100%;height:100%;background:#0b0f19;}
  .half-marker{
    width:18px;height:18px;border-radius:50%;border:2px solid #000;
    background: linear-gradient(to right,#66BB6A 50%,#EF5350 50%);
    box-sizing:border-box;
  }
  .live-marker{
    width:14px;height:14px;border-radius:50%;border:2px solid #fff;
    background:#FFC107;
    box-shadow:0 0 6px rgba(255,193,7,0.9);
  }
</style>
</head><body>
<div id="map"></div>
<script>
  var coords=[$coordsJson];
  var map=L.map('map',{zoomControl:false,attributionControl:false});
  var baseLayer=null;
  var tileUrls={
    LIGHT:'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png',
    DARK:'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
    SAT:'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'
  };
  window.setMapType=function(t){
    if(baseLayer) map.removeLayer(baseLayer);
    baseLayer=L.tileLayer(tileUrls[t]||tileUrls.LIGHT,{maxZoom:19,subdomains:'abcd'}).addTo(map);
    baseLayer.bringToBack();
  };
  window.setMapType('$initialType');

  var hasRoute = coords.length >= 2;
  var start=null, end=null, overlap=null, line=null;

  function render(){
    if (hasRoute){
      line = L.polyline(coords,{color:'#4FC3F7',weight:4}).addTo(map);
      map.fitBounds(line.getBounds().pad(0.2));
      placeEndpoints();
      map.on('zoomend moveend', placeEndpoints);
    } else if (coords.length === 1) {
      map.setView(coords[0], 17);
    } else {
      map.setView([0,0], 2);
    }
  }

  function placeEndpoints(){
    if (!hasRoute) return;
    if (start){ map.removeLayer(start); start=null; }
    if (end){ map.removeLayer(end); end=null; }
    if (overlap){ map.removeLayer(overlap); overlap=null; }

    var a = coords[0], b = coords[coords.length-1];
    var pa = map.latLngToContainerPoint(a);
    var pb = map.latLngToContainerPoint(b);
    var dist = pa.distanceTo(pb);
    var r = 7; // circleMarker radius in px
    // Overlap is more than 50% of a marker's width: dist < 2*r*(1-0.5) = r.
    if (dist < r){
      overlap = L.marker(a,{icon:L.divIcon({className:'half-marker',iconSize:[18,18],iconAnchor:[9,9]})}).addTo(map);
    } else {
      start = L.circleMarker(a,{radius:r,color:'#000',weight:2,fillColor:'#66BB6A',fillOpacity:1}).addTo(map);
      end   = L.circleMarker(b,{radius:r,color:'#000',weight:2,fillColor:'#EF5350',fillOpacity:1}).addTo(map);
    }
  }

  // Live marker API (called from Kotlin via evaluateJavascript).
  var live=null, livePath=null;
  window.updateLivePoint = function(lat, lon){
    var p = [lat, lon];
    if (!live){
      live = L.marker(p,{icon:L.divIcon({className:'live-marker',iconSize:[14,14],iconAnchor:[7,7]})}).addTo(map);
      if (!hasRoute) map.setView(p, 17);
    } else {
      live.setLatLng(p);
    }
  };

  // Scrub marker API: a dot synced with the chart cursor. Pans into view only
  // if the point is off-screen, so scrubbing doesn't jerk the map around.
  var scrub=null;
  window.updateScrubPoint = function(lat, lon){
    var p = [lat, lon];
    if (!scrub){
      scrub = L.circleMarker(p,{radius:7,color:'#fff',weight:2,fillColor:'#FFC107',fillOpacity:1}).addTo(map);
    } else {
      scrub.setLatLng(p);
    }
    if (!map.getBounds().contains(p)) map.panTo(p,{animate:true,duration:0.25});
  };
  window.clearScrubPoint = function(){
    if (scrub){ map.removeLayer(scrub); scrub=null; }
  };

  render();
  ${if (isLive) "/* live mode: waiting for updateLivePoint() */" else ""}
</script></body></html>
""".trimIndent()

/**
 * Optional secondary series drawn behind the main chart line. Used by the
 * speed chart to overlay external GPS speed (RaceBox) when available, with
 * NaN values treated as breaks in the line so missing samples don't pull
 * the curve down to zero. [label] tags the series in the scrub tooltip
 * (e.g. "GPS", "Ext"); null = overlay shown on the chart but not labelled
 * in the tooltip.
 */
data class ChartOverlay(val values: List<Float>, val color: Color, val label: String? = null)

/**
 * Single-metric line chart card.
 *
 * NaN values in [values] are treated as gaps (the line breaks), so a trip CSV
 * that predates a column or has empty cells doesn't pull the curve to zero.
 *
 * When [regenColor] is non-null and the data crosses zero, the chart switches
 * to a bipolar two-colour split: the area + line above the zero baseline use
 * [color], everything below zero (regen braking, for the current chart) uses
 * [regenColor]. This mirrors the MetricGraph zero-baseline split on the
 * dashboard. Single-polarity data (no zero crossing) just draws the plain line.
 */
@Composable
private fun ChartCard(
    title: String,
    values: List<Float>,
    color: Color,
    unitLabel: String,
    minSpan: Float,
    overlays: List<ChartOverlay> = emptyList(),
    regenColor: Color? = null,
    // Cap the y-axis at a realistic value (e.g. the speed chart passes the
    // spike-rejected sustained top speed) so one lone GPS/sensor spike doesn't
    // squash the whole ride into the floor. The spike then clips at the top and
    // [peak], the true maximum, is shown in the corner label instead.
    axisMax: Float? = null,
    peak: Float? = null,
    // Shared scrub cursor: [scrubIndex] is the sample index highlighted across
    // every chart and the map; [onScrub] reports this chart's own scrub position
    // (or null on release) so the other charts and the map marker follow along.
    scrubIndex: Int? = null,
    onScrub: ((Int?) -> Unit)? = null,
) {
    if (values.isEmpty()) return

    // Y-axis bounds include any overlay min/max so secondary lines stay on-scale.
    // Filter NaN out of all reductions because NaN means "no data this row" , 
    // those rows shouldn't push the bounds.
    val finiteValues = values.filter { !it.isNaN() }
    val allFinite = (overlays.flatMap { it.values.filter { v -> !v.isNaN() } }) + finiteValues
    val dataMin = allFinite.minOrNull() ?: 0f
    val dataMaxRaw = allFinite.maxOrNull() ?: 0f
    // Axis upper bound: a caller-supplied realistic cap when given (never below
    // the data floor), otherwise the raw maximum as before.
    val dataMax = axisMax?.coerceAtLeast(dataMin) ?: dataMaxRaw
    val bounds = GraphScale.pad(dataMin, dataMax, minSpan)
    val textMeasurer = rememberTextMeasurer()
    val tooltipBg = MaterialTheme.colorScheme.surface
    val tooltipFg = MaterialTheme.colorScheme.onSurface

    var touchX by remember { mutableStateOf<Float?>(null) }
    val haptics = LocalHapticFeedback.current

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium)
                // When a spike was clipped by [axisMax], show the true peak too so
                // the rider still sees it (e.g. "0.0 – 35.0 (peak 80)").
                val rangeLabel = if (peak != null && peak > dataMax + 0.5f)
                    "%.1f – %.1f (peak %.0f)".format(dataMin, dataMax, peak)
                else
                    "%.1f – %.1f".format(dataMin, dataMax)
                Text(rangeLabel, fontSize = 11.sp,
                    color = color, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .pointerInput(values) {
                        // Long-press to scrub. A simple down-and-drag does NOT
                        // activate the cursor, that gesture is reserved for the
                        // parent column's vertical scroll. Once the rider holds
                        // their finger for ~longPressTimeoutMillis without lifting
                        // or moving past touchSlop, we vibrate, claim the gesture,
                        // and start tracking horizontal drag until they lift.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val longPress =
                                awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            // Long-press confirmed, the chart now owns the gesture.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            longPress.consume()
                            // Track locally for this chart's smooth cursor AND
                            // publish the integer sample index so the other charts
                            // and the map marker highlight the same moment.
                            fun report(x: Float) {
                                touchX = x
                                if (onScrub != null && values.size > 1) {
                                    val sx = size.width / (values.size - 1).toFloat()
                                    onScrub((x / sx + 0.5f).toInt().coerceIn(0, values.size - 1))
                                }
                            }
                            report(longPress.position.x)
                            drag(longPress.id) { change ->
                                report(change.position.x)
                                change.consume()
                            }
                            touchX = null
                            onScrub?.invoke(null)
                        }
                    }
            ) {
                if (values.size < 2) return@Canvas
                val w = size.width
                val h = size.height
                val range = bounds.range
                val stepX = w / (values.size - 1).toFloat()

                // Overlay series first so the main line draws on top. NaN values
                // break the line so empty CSV cells don't pull the curve to zero.
                overlays.forEach { overlay ->
                    if (overlay.values.size < 2) return@forEach
                    val overlayPath = Path()
                    var penDown = false
                    overlay.values.forEachIndexed { idx, value ->
                        if (value.isNaN()) {
                            penDown = false
                            return@forEachIndexed
                        }
                        val x = idx * stepX
                        // Clamp to the chart box so a value above the (capped) axis
                        // clips at the top edge instead of drawing outside.
                        val y = (h - ((value - bounds.min) / range) * h).coerceIn(0f, h)
                        if (!penDown) {
                            overlayPath.moveTo(x, y)
                            penDown = true
                        } else {
                            overlayPath.lineTo(x, y)
                        }
                    }
                    drawPath(
                        overlayPath,
                        color = overlay.color,
                        style = Stroke(width = 1.5f)
                    )
                }

                // Main series. NaN values break the line into segments so empty
                // CSV cells don't draw spurious connectors through the chart.
                val segments = mutableListOf<Path>()
                var segment: Path? = null
                values.forEachIndexed { idx, value ->
                    if (value.isNaN()) {
                        segment = null
                        return@forEachIndexed
                    }
                    val x = idx * stepX
                    // Clamp to the chart box so a value above the (capped) axis
                    // clips at the top edge instead of drawing outside.
                    val y = (h - ((value - bounds.min) / range) * h).coerceIn(0f, h)
                    val seg = segment
                    if (seg == null) {
                        val p = Path()
                        p.moveTo(x, y)
                        segments.add(p)
                        segment = p
                    } else {
                        seg.lineTo(x, y)
                    }
                }

                val regen = regenColor
                val zeroCrosses = bounds.min < 0f && bounds.max > 0f
                if (regen != null && zeroCrosses) {
                    // Bipolar split at the zero baseline. Build the filled area
                    // per segment (curve closed down to the zero line), then
                    // clip above/below zero into the two colours.
                    val zeroY = h - ((0f - bounds.min) / range) * h
                    segments.forEach { seg ->
                        val fill = Path()
                        fill.addPath(seg)
                        val b = seg.getBounds()
                        fill.lineTo(b.right, zeroY)
                        fill.lineTo(b.left, zeroY)
                        fill.close()
                        clipRect(top = 0f, bottom = zeroY) {
                            drawPath(fill, color = color.copy(alpha = 0.18f))
                            drawPath(seg, color = color, style = Stroke(width = 2f))
                        }
                        clipRect(top = zeroY, bottom = h) {
                            drawPath(fill, color = regen.copy(alpha = 0.18f))
                            drawPath(seg, color = regen, style = Stroke(width = 2f))
                        }
                    }
                    drawLine(color.copy(alpha = 0.4f), Offset(0f, zeroY), Offset(w, zeroY),
                        strokeWidth = 1.5f)
                } else {
                    segments.forEach { seg ->
                        drawPath(seg, color = color, style = Stroke(width = 2f))
                    }
                }

                // Cursor position: this chart's own touch while it is being
                // scrubbed, otherwise the shared index driven by whichever chart
                // the rider is touching (so every chart shows the same moment).
                val tx = touchX ?: scrubIndex?.let { it.toFloat() * stepX }
                if (tx != null) {
                    val cursorX = tx.coerceIn(0f, w)
                    val floatIdx = (cursorX / stepX).coerceIn(0f, (values.size - 1).toFloat())
                    val leftIdx = floatIdx.toInt().coerceIn(0, values.size - 1)
                    val rightIdx = (leftIdx + 1).coerceAtMost(values.size - 1)
                    val frac = floatIdx - leftIdx
                    // Tooltip skips NaN gaps: if either bracketing sample is NaN,
                    // fall back to the nearest finite one so the readout stays sane.
                    val lv = values[leftIdx]
                    val rv = values[rightIdx]
                    val interpValue = when {
                        lv.isNaN() && rv.isNaN() -> return@Canvas
                        lv.isNaN() -> rv
                        rv.isNaN() -> lv
                        else -> lv + (rv - lv) * frac
                    }
                    val cursorY = (h - ((interpValue - bounds.min) / range) * h).coerceIn(0f, h)

                    drawLine(color.copy(alpha = 0.5f), Offset(cursorX, 0f), Offset(cursorX, h), strokeWidth = 1.5f)
                    drawCircle(color, radius = 4f, center = Offset(cursorX, cursorY))
                    drawCircle(Color.White, radius = 2f, center = Offset(cursorX, cursorY))

                    // Sample each labelled overlay at the cursor too. NaN
                    // values are treated as "no sample here" and skipped,
                    // matching how the overlay line itself breaks on NaN.
                    fun sampleAt(series: List<Float>): Float? {
                        if (series.size != values.size) return null
                        val lv2 = series[leftIdx]
                        val rv2 = series[rightIdx]
                        return when {
                            lv2.isNaN() && rv2.isNaN() -> null
                            lv2.isNaN() -> rv2
                            rv2.isNaN() -> lv2
                            else -> lv2 + (rv2 - lv2) * frac
                        }
                    }
                    val labelStyle = TextStyle(
                        fontSize = 10.sp, color = tooltipFg, fontWeight = FontWeight.Medium
                    )
                    // Lines for the tooltip: main value first (always present),
                    // each labelled overlay with its colour, then a delta row
                    // per overlay so the rider sees "wheel vs GPS" at a glance.
                    data class TLine(val text: String, val tint: Color)
                    val lines = mutableListOf<TLine>()
                    lines += TLine("%.1f %s".format(interpValue, unitLabel), tooltipFg)
                    overlays.forEach { ov ->
                        val lbl = ov.label ?: return@forEach
                        val s = sampleAt(ov.values) ?: return@forEach
                        lines += TLine("$lbl %.1f".format(s), ov.color)
                        lines += TLine("Δ %+.1f".format(s - interpValue), ov.color)
                    }
                    val measuredLines = lines.map { line ->
                        line to textMeasurer.measure(line.text, labelStyle.copy(color = line.tint))
                    }
                    val padX = 5f
                    val padY = 2f
                    val lineGap = 1f
                    val boxW = (measuredLines.maxOf { it.second.size.width } + padX * 2)
                    val boxH = measuredLines.sumOf { it.second.size.height } + padY * 2 +
                        lineGap * (measuredLines.size - 1).coerceAtLeast(0)
                    val boxX = (cursorX - boxW / 2f).coerceIn(0f, w - boxW)
                    val boxY = (cursorY - boxH - 6f).coerceAtLeast(0f)
                    drawRoundRect(
                        color = tooltipBg,
                        topLeft = Offset(boxX, boxY),
                        size = Size(boxW, boxH.toFloat()),
                        cornerRadius = CornerRadius(5f, 5f)
                    )
                    var rowY = boxY + padY
                    measuredLines.forEach { (_, layout) ->
                        drawText(layout, topLeft = Offset(boxX + padX, rowY))
                        rowY += layout.size.height + lineGap
                    }
                }
            }
        }
    }
}

/**
 * Smart battery / voltage summary for a trip, computed over a validity mask.
 *
 * All values are RAW metric units (battery %, voltage V). Display-side unit
 * conversion happens in the screen, consistent with the rest of the summary.
 */
data class TripBatteryStats(
    val batteryMax: Int,
    val batteryMin: Int,
    val batteryConsumption: Int,
    val voltageMax: Float,
    val voltageMin: Float,
    /** Peak PWM / motor load (%) over valid non-NaN points. NaN when the trip has no PWM data. */
    val maxPwm: Float,
    /** Peak signed current (A) over valid non-NaN points. NaN when the trip has no current data. */
    val maxCurrent: Float,
    /** Peak instantaneous power (W = voltage * current) over valid points with non-NaN current. NaN when no current data. */
    val maxPower: Float
)

/**
 * Computes battery and voltage extremes over a validity mask, walking the
 * trip's data points in time order.
 *
 * Two filters apply:
 *
 *  1. **End-of-trip trim** ([trimEndIndex]). Anything from a voltage-cliff
 *     onward is excluded entirely. The cliff catches BLE-frozen disconnect
 *     tails (last frame echoes for minutes at a sagged voltage) AND wheel
 *     power-off capacitor discharges (controller's V rail collapses ~80 V
 *     in seconds and then sticks at a fake-low value while the pack is
 *     still healthy). Both used to poison voltage min, battery min, and
 *     peak power downward without it. Cliff = one-sample voltage drop
 *     ≥ 5 % of the prior reading while current is light (<5 A or NaN);
 *     gated to the trip's second half so a wheel still settling after
 *     boot doesn't trigger it.
 *
 *  2. **Per-sample glitch mask**. A point inside the kept range is
 *     additionally invalid when `battery <= 0` / `voltage <= 0`, or when
 *     `battery` dropped more than 10 percentage points below the last
 *     valid sample (physically impossible at ~1 Hz). Regen-driven upward
 *     jumps always accepted; `lastValidBattery` only advances on valid
 *     points.
 *
 * Battery max/min, voltage max/min, and peak PWM/current/power are reduced
 * over the kept-and-valid points. NaN PWM/current samples are skipped for
 * their respective peak; when a trip has no data at all for a column the
 * corresponding peak stays NaN and the screen renders a "--" placeholder.
 *
 * Degenerate fallback: when no point survives both filters, the raw min/max
 * over all points is used so the card still shows something instead of crashing.
 */
private fun computeBatteryStats(points: List<TripDataPoint>): TripBatteryStats {
    if (points.isEmpty()) {
        return TripBatteryStats(0, 0, 0, 0f, 0f, Float.NaN, Float.NaN, Float.NaN)
    }

    val endIdx = trimEndIndex(points)
    val ridePoints = if (endIdx in 1 until points.size) points.subList(0, endIdx) else points

    val validBatteries = mutableListOf<Int>()
    val validVoltages = mutableListOf<Float>()
    var lastValidBattery: Int? = null
    // Peak PWM / current / power over the same validity mask. Tracked as a
    // running max so a single walk feeds every maximum; NaN samples are skipped.
    var maxPwm = Float.NaN
    var maxCurrent = Float.NaN
    var maxPower = Float.NaN

    for (p in ridePoints) {
        val valid = p.battery > 0 &&
            p.voltage > 0f &&
            (lastValidBattery == null || p.battery >= lastValidBattery!! - 10)
        if (valid) {
            validBatteries.add(p.battery)
            validVoltages.add(p.voltage)
            lastValidBattery = p.battery
            if (!p.pwm.isNaN()) {
                maxPwm = if (maxPwm.isNaN()) p.pwm else maxOf(maxPwm, p.pwm)
            }
            if (!p.current.isNaN()) {
                maxCurrent = if (maxCurrent.isNaN()) p.current else maxOf(maxCurrent, p.current)
                val power = p.voltage * p.current
                maxPower = if (maxPower.isNaN()) power else maxOf(maxPower, power)
            }
        }
    }

    if (validBatteries.isEmpty()) {
        // Degenerate trip: fall back to raw extremes, don't crash.
        val rawBatMax = points.maxOf { it.battery }
        val rawBatMin = points.minOf { it.battery }
        val rawVoltMax = points.maxOf { it.voltage }
        val rawVoltMin = points.minOf { it.voltage }
        return TripBatteryStats(
            batteryMax = rawBatMax,
            batteryMin = rawBatMin,
            batteryConsumption = (rawBatMax - rawBatMin).coerceAtLeast(0),
            voltageMax = rawVoltMax,
            voltageMin = rawVoltMin,
            maxPwm = maxPwm,
            maxCurrent = maxCurrent,
            maxPower = maxPower
        )
    }

    val batMax = validBatteries.max()
    val batMin = validBatteries.min()
    return TripBatteryStats(
        batteryMax = batMax,
        batteryMin = batMin,
        batteryConsumption = (batMax - batMin).coerceAtLeast(0),
        voltageMax = validVoltages.max(),
        voltageMin = validVoltages.min(),
        maxPwm = maxPwm,
        maxCurrent = maxCurrent,
        maxPower = maxPower
    )
}

/** Returns the index where a trip-end voltage cliff begins, or `points.size`
 *  if no cliff was found. A cliff is a one-sample voltage drop ≥ 5 % of the
 *  prior valid voltage while current is light (|I| < 5 A or NaN). Caller
 *  excludes the cliff sample and everything after it from trip stats.
 *
 *  Two real cases this catches:
 *   - Wheel power-off: the controller's V rail capacitors discharge ~80 V
 *     to ~14 V over five seconds at zero current, then the BLE freezes its
 *     last frame for a couple of minutes while the rider walks away. The
 *     pack itself never dropped — using those frames in voltage min /
 *     battery min reports a fake catastrophic drain.
 *   - BLE-frozen disconnect tail: the last good frame echoes for many
 *     seconds with no current and an artificially-low voltage. Same
 *     symptom in stats.
 *
 *  Gated to the trip's second half so the wheel's normal post-boot voltage
 *  settling doesn't trigger it; gated to light current so a normal sag dip
 *  during an acceleration is preserved. Trips shorter than 30 samples skip
 *  the check entirely — there's not enough data for the half-gate to mean
 *  anything. */
private fun trimEndIndex(points: List<TripDataPoint>): Int {
    if (points.size < TRIM_MIN_TRIP_SAMPLES) return points.size
    val half = points.size / 2
    var lastValidV = 0f
    for (i in points.indices) {
        val v = points[i].voltage
        if (v <= 0f) continue
        if (i > half && lastValidV > 0f) {
            val dropFrac = (lastValidV - v) / lastValidV
            val current = points[i].current
            val lightCurrent = current.isNaN() || kotlin.math.abs(current) < TRIM_LIGHT_CURRENT_A
            if (dropFrac >= TRIM_CLIFF_DROP_FRAC && lightCurrent) {
                return i
            }
        }
        lastValidV = v
    }
    return points.size
}

private const val TRIM_MIN_TRIP_SAMPLES: Int = 30
private const val TRIM_CLIFF_DROP_FRAC: Float = 0.05f
private const val TRIM_LIGHT_CURRENT_A: Float = 5f

/** How long a speed must be held to count as the trip's top speed. */
private const val SUSTAINED_TOP_SPEED_MS: Double = 2000.0

/**
 * Top speed the wheel actually *held* for ~[SUSTAINED_TOP_SPEED_MS], not a lone
 * GPS/sensor spike. Slides a window of [windowSamples] across the speed series
 * and takes the window MINIMUM (every sample in the window must be at least this
 * fast to qualify), then returns the max over all windows. A one- or two-sample
 * spike can't survive, because any window covering it also covers its slower
 * neighbours. Falls back to the plain peak for trips too short to fill a window.
 * O(n) via a monotonic deque of window-minimum candidates.
 */
internal fun sustainedTopSpeed(speeds: List<Float>, windowSamples: Int): Float {
    if (speeds.isEmpty()) return 0f
    val w = windowSamples.coerceIn(1, speeds.size)
    if (w <= 1) return speeds.maxOrNull() ?: 0f
    var best = 0f
    val dq = ArrayDeque<Int>()  // indices; the speeds at them strictly increasing from front
    for (i in speeds.indices) {
        while (dq.isNotEmpty() && speeds[dq.last()] >= speeds[i]) dq.removeLast()
        dq.addLast(i)
        if (dq.first() <= i - w) dq.removeFirst()
        if (i >= w - 1) best = maxOf(best, speeds[dq.first()])
    }
    return best
}
