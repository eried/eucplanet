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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.eried.eucplanet.ui.common.TrimTimeDialog
import com.eried.eucplanet.util.Smoothing
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedSwitchColors
import sh.calvin.reorderable.ReorderableColumn
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
    // The ride as recorded. Never filtered, so the heal path and the map's
    // faded context track always see the real trip.
    var allPoints by remember { mutableStateOf<List<TripDataPoint>>(emptyList()) }
    // Elapsed-ms window into the ride, null when the full trip is shown.
    var trimRange by remember { mutableStateOf<LongRange?>(null) }
    var showTrim by remember { mutableStateOf(false) }
    val elapsedMs = remember(allPoints) { TripTrim.elapsedOffsets(allPoints) }
    // Everything else on this screen reads dataPoints, so filtering here trims
    // the tiles, the charts, the header and the map in one move.
    val dataPoints = remember(allPoints, elapsedMs, trimRange) {
        TripTrim.apply(allPoints, elapsedMs, trimRange)
    }
    val trimmed = trimRange != null
    var showShareDialog by remember { mutableStateOf(false) }
    // Trip Details customizer sheet (pencil in the top bar). Hoisted here so the
    // top bar action and the sheet body (rendered in the content) share it.
    var showCustomize by remember { mutableStateOf(false) }
    // The in-progress trip can't be shared, its CSV isn't finalised yet.
    // null = not yet known; only once it resolves to a definite false do we let
    // the self-heal touch the stored row (so a live trip is never finalised
    // early by the detail screen).
    val liveState by viewModel.isTripLiveRecording(trip).collectAsState(initial = null)
    val isLiveTrip = liveState == true
    // Landscape split: the rider chooses whether the map docks left or right.
    val tripMapSide by viewModel.tripMapSide.collectAsState()
    // Persisted Trip-details base map pick (blank = follow the theme default).
    val savedMapType by viewModel.tripMapType.collectAsState()
    // Trip Details customizer: which stat tiles are hidden, the tile and chart
    // order, and which graphs (plus the pinned "extra" block) are hidden.
    val hiddenTiles by viewModel.tripHiddenTiles.collectAsState()
    val savedTileOrder by viewModel.tripTileOrder.collectAsState()
    val savedChartOrder by viewModel.tripChartOrder.collectAsState()
    val hiddenCharts by viewModel.tripHiddenCharts.collectAsState()
    // Opt-in extra graphs (smoothed variants, power, altitude) and the window
    // the smoothed ones average over.
    val extraCharts by viewModel.tripExtraCharts.collectAsState()
    val smoothWindow by viewModel.smoothingWindowSamples.collectAsState()

    // Render the ViewModel's messages (e.g. "Preparing the link…", share
    // failures) here too — sharing is launched straight from this screen, which
    // otherwise has no host so the snackbars went nowhere.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.toasts.collect { snackbar.showSnackbar(it) }
    }

    if (showTrim) {
        val full = if (elapsedMs.isEmpty()) 0L else elapsedMs.last()
        TrimTimeDialog(
            startMs = trimRange?.first ?: 0L,
            endMs = trimRange?.last ?: full,
            durationMs = full,
            // Not Overlay Studio's "Trim trip": nothing here is modified, the
            // recording is untouched and only what is on screen narrows.
            title = stringResource(R.string.trip_trim_title),
            minPoints = TripTrim.MIN_POINTS,
            pointsInRange = { r -> TripTrim.countInRange(elapsedMs, r) },
            onConfirm = { s, e ->
                // Reset comes back as the full span, which is "no trim".
                trimRange = if (s <= 0L && e >= full) null else s..e
                showTrim = false
            },
            onDismiss = { showTrim = false },
        )
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
        allPoints = viewModel.readTripData(trip)
        // Without this, opening a second trip from the same screen instance
        // would carry the previous trip's window across.
        trimRange = null
    }

    val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Trip metrics and the header date range (start -> end) are hoisted so the
    // landscape top bar can show the range centred (the landscape body gives its
    // height to the permanent map + the scrollable charts, with no room for it).
    // Two metrics values, deliberately. healTripMetrics WRITES startTime,
    // endTime and distanceKm back onto the trip row, and the trip list reads
    // those stored fields rather than the CSV. Feeding it trimmed numbers would
    // overwrite the ride's real identity with whatever window happened to be
    // showing, with no way back. It gets the full trip, always.
    val fullMetrics = remember(allPoints) { viewModel.tripMetrics(allPoints) }
    val metrics = remember(dataPoints) { viewModel.tripMetrics(dataPoints) }
    LaunchedEffect(fullMetrics, liveState) {
        if (liveState == false) viewModel.healTripMetrics(trip, fullMetrics)
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
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dataPoints.isNotEmpty()) {
                            IconButton(onClick = { showCustomize = true }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trip_customize))
                            }
                            TrimAction(trimmed = trimmed, onClick = { showTrim = true })
                        }
                        IconButton(
                            onClick = { showShareDialog = true },
                            enabled = !isLiveTrip,
                        ) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                        }
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
                        if (dataPoints.isNotEmpty()) {
                            IconButton(onClick = { showCustomize = true }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trip_customize))
                            }
                            TrimAction(trimmed = trimmed, onClick = { showTrim = true })
                        }
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
            // The whole ride's fixes, for the faded context track behind a trim.
            val fullGpsPoints = remember(allPoints) {
                allPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
            }
            // Whether the ride's real start and end survived the trim. A window
            // that begins at 0:00 still contains the start, so its marker must
            // stay solid; only an endpoint the trim cut away is ghosted.
            val startIncluded = remember(gpsPoints, fullGpsPoints) {
                fullGpsPoints.isEmpty() || gpsPoints.firstOrNull() == fullGpsPoints.firstOrNull()
            }
            val endIncluded = remember(gpsPoints, fullGpsPoints) {
                fullGpsPoints.isEmpty() || gpsPoints.lastOrNull() == fullGpsPoints.lastOrNull()
            }
            // Extra-column events: shown in the Extra details section, and the
            // wheel identity blocks drive the map (start popup, a colour switch +
            // purple circle at a real wheel change, a small dot at a same-wheel
            // reconnect).
            val extraEvents = remember(dataPoints) { extractExtraEvents(dataPoints) }
            val wheelStarts = remember(extraEvents) { extraEvents.filter { it.isWheelStart } }
            // Whole-ride identity blocks. The map's start and end markers describe
            // where the RIDE began and finished, so their popups have to come from
            // the full trip, not from whatever section is currently on screen.
            val fullExtraEvents = remember(allPoints) { extractExtraEvents(allPoints) }
            // Localized, readable marker popups. ctx.getString runs inside
            // remember (the strings only change with the locale, which recreates
            // the composition anyway, so ctx is a remember key too).
            val ctx = androidx.compose.ui.platform.LocalContext.current
            // Green start marker: the first connection = ride start.
            val startMarkerLabel = remember(fullExtraEvents, ctx) {
                fullExtraEvents.firstOrNull { it.isWheelStart }?.let { e ->
                    val name = e.text.substringAfter('=')
                    "${e.time} - ${ctx.getString(R.string.trip_map_ride_start, name)}"
                } ?: ""
            }
            // Red end marker: the last GPS fix of the ride.
            val endMarkerLabel = remember(fullGpsPoints, ctx) {
                fullGpsPoints.lastOrNull()?.let { p ->
                    "${timePartOf(p.date)} - ${ctx.getString(R.string.trip_map_ride_end)}"
                } ?: ""
            }
            // Every identity block after the ride start becomes a map marker:
            // isChange drives whether it also switches the trace colour (a real
            // wheel swap = "New wheel") or is just a reconnect dot on the
            // unbroken trace (= "Reconnected").
            val wheelSwitches = remember(wheelStarts, dataPoints, ctx) {
                wheelStarts.drop(1).filter { it.lat != 0.0 && it.lon != 0.0 }.map { e ->
                    // Position of the row within the GPS-bearing points: the row
                    // itself has a fix, so its slot equals the count of fixes
                    // before it. A real change cuts the trace colour here.
                    val gpsIdx = dataPoints.subList(0, e.index)
                        .count { it.latitude != 0.0 && it.longitude != 0.0 }
                    val name = e.text.substringAfter('=')
                    val label = if (e.isWheelChange)
                        ctx.getString(R.string.trip_map_new_wheel, name)
                    else
                        ctx.getString(R.string.trip_map_reconnected, name)
                    WheelSwitchMarker(gpsIdx, e.lat, e.lon, "${e.time} - $label", e.isWheelChange)
                }
            }
            // The same identity markers for the stretches a trim cut away, drawn
            // faint on the map so the section still reads in the context of the
            // whole ride. gpsIndex is unused for these: they never cut a trace
            // colour, they are context only.
            val fadedSwitches = remember(fullExtraEvents, elapsedMs, trimRange, ctx) {
                val r = trimRange ?: return@remember emptyList<WheelSwitchMarker>()
                fullExtraEvents
                    .filter { it.isWheelStart }
                    .drop(1)
                    .filter { it.lat != 0.0 && it.lon != 0.0 }
                    .filter { e -> elapsedMs.getOrNull(e.index)?.let { it !in r } ?: false }
                    .map { e ->
                        val name = e.text.substringAfter('=')
                        val label = if (e.isWheelChange)
                            ctx.getString(R.string.trip_map_new_wheel, name)
                        else
                            ctx.getString(R.string.trip_map_reconnected, name)
                        WheelSwitchMarker(0, e.lat, e.lon, "${e.time} - $label", e.isWheelChange)
                    }
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
                    fadedPoints = if (trimmed) fullGpsPoints else emptyList(),
                    fadedSwitches = fadedSwitches,
                    startIncluded = startIncluded,
                    endIncluded = endIncluded,
                    isLive = isLive,
                    liveLat = liveLocation?.latitude,
                    liveLon = liveLocation?.longitude,
                    scrubLat = scrubPoint?.latitude,
                    scrubLon = scrubPoint?.longitude,
                    wheelSwitches = wheelSwitches,
                    startLabel = startMarkerLabel,
                    endLabel = endMarkerLabel,
                    savedMapType = savedMapType,
                    onPersistMapType = viewModel::setTripMapType,
                    modifier = mod,
                )
            }

            // Stat tiles, as a keyed list so the customizer can hide any of them.
            // Each tile keeps the exact SummaryCard content and colors it had
            // before. Order matches the original 4 rows of 3.
            val allTiles: List<Pair<String, @Composable RowScope.() -> Unit>> = listOf(
                "distance" to { SummaryCard(stringResource(R.string.recording_summary_distance), "%.1f %s".format(tripDistance, distanceUnitLabel), MaterialTheme.appColors.metricVoltage, Modifier.weight(1f)) },
                "duration" to { SummaryCard(stringResource(R.string.recording_summary_duration), com.eried.eucplanet.util.Units.humanDuration(duration), MaterialTheme.appColors.metricVoltage, Modifier.weight(1f)) },
                "points" to { SummaryCard(stringResource(R.string.recording_summary_points), "${dataPoints.size}", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)) },
                "topSpeed" to { SummaryCard(stringResource(R.string.recording_summary_top_speed), "%.0f %s".format(maxSpeed, speedUnitLabel), MaterialTheme.appColors.metricTemp, Modifier.weight(1f)) },
                "avgSpeed" to { SummaryCard(stringResource(R.string.recording_summary_avg_speed), "%.0f %s".format(avgSpeed, speedUnitLabel), MaterialTheme.appColors.metricBattery, Modifier.weight(1f)) },
                "avgMoving" to { SummaryCard(stringResource(R.string.recording_summary_avg_moving), "%.0f %s".format(avgMoving, speedUnitLabel), MaterialTheme.appColors.metricBattery, Modifier.weight(1f)) },
                "battery" to {
                    SummaryCard(
                        stringResource(R.string.recording_summary_battery, batteryStats.batteryConsumption),
                        stringResource(R.string.recording_summary_battery_fmt, batteryStats.batteryMax, batteryStats.batteryMin),
                        if (batteryStats.batteryMin < 20) MaterialTheme.appColors.statusDanger else MaterialTheme.appColors.statusGood,
                        Modifier.weight(1f)
                    )
                },
                "voltage" to {
                    SummaryCard(
                        stringResource(R.string.recording_summary_voltage),
                        stringResource(R.string.recording_summary_voltage_fmt, batteryStats.voltageMax, batteryStats.voltageMin),
                        MaterialTheme.appColors.metricPosition,
                        Modifier.weight(1f)
                    )
                },
                "maxTemp" to {
                    SummaryCard(stringResource(R.string.recording_summary_max_temp),
                        "%.0f%s".format(maxTemp, tempUnitLabel),
                        if (maxTempRaw > 60) MaterialTheme.appColors.statusDanger else MaterialTheme.appColors.metricTemp, Modifier.weight(1f))
                },
                "maxPwm" to {
                    SummaryCard(
                        stringResource(R.string.recording_summary_max_pwm),
                        if (batteryStats.maxPwm.isNaN()) "--" else "%.0f%%".format(batteryStats.maxPwm),
                        if (!batteryStats.maxPwm.isNaN() && batteryStats.maxPwm > 80) MaterialTheme.appColors.statusDanger
                        else MaterialTheme.appColors.metricTemp,
                        Modifier.weight(1f)
                    )
                },
                "maxCurrent" to {
                    SummaryCard(
                        stringResource(R.string.recording_summary_max_current),
                        if (batteryStats.maxCurrent.isNaN()) "--" else "%.1f A".format(batteryStats.maxCurrent),
                        MaterialTheme.appColors.metricVoltage,
                        Modifier.weight(1f)
                    )
                },
                "maxPower" to {
                    SummaryCard(
                        stringResource(R.string.recording_summary_max_power),
                        if (batteryStats.maxPower.isNaN()) "--" else "%.0f W".format(batteryStats.maxPower),
                        MaterialTheme.appColors.metricPosition,
                        Modifier.weight(1f)
                    )
                },
            )

            // Effective tile order via the shared helper (see applyOrder): the
            // rider's saved order, with any newly added tile appearing at the end.
            val tileKeysDefault = allTiles.map { it.first }
            val effectiveTileOrder = applyOrder(tileKeysDefault, savedTileOrder)
            val tilesByKey = allTiles.associateBy { it.first }
            val orderedTiles = effectiveTileOrder.mapNotNull { tilesByKey[it] }

            // Render the shown tiles in the rider's order, in rows of 3, padding a
            // short final row with spacers so every tile keeps the same width.
            val summaryCards: @Composable ColumnScope.() -> Unit = {
                val visibleTiles = orderedTiles.filter { it.first !in hiddenTiles }
                visibleTiles.chunked(3).forEachIndexed { rowIndex, rowTiles ->
                    if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowTiles.forEach { (_, tile) -> tile() }
                        repeat(3 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // Charts, as a keyed list. current / pwm are only present when the
            // trip actually has that data, so those charts never render empty,
            // regardless of the saved order.
            val allCharts: List<Pair<String, @Composable ColumnScope.() -> Unit>> = buildList {
                add("speed" to {
                    ChartCard(stringResource(R.string.recording_chart_speed, speedUnitLabel),
                        dataPoints.map { com.eried.eucplanet.util.Units.speed(it.speed, speedUnit) },
                        MaterialTheme.appColors.metricBattery, unitLabel = speedUnitLabel, minSpan = speedMinSpan,
                        overlays = speedOverlays, axisMax = maxSpeed, peak = speedPeak,
                        scrubIndex = scrubIndex, onScrub = onScrub)
                })
                add("battery" to {
                    ChartCard(stringResource(R.string.recording_chart_battery), dataPoints.map { it.battery.toFloat() },
                        MaterialTheme.appColors.metricVoltage, unitLabel = "%", minSpan = GraphScale.SPAN_BATTERY,
                        scrubIndex = scrubIndex, onScrub = onScrub)
                })
                add("temp" to {
                    ChartCard(stringResource(R.string.recording_chart_temp, tempUnitLabel),
                        dataPoints.map { com.eried.eucplanet.util.Units.temperature(it.temperature, tempUnit) },
                        MaterialTheme.appColors.metricTemp, unitLabel = tempUnitLabel, minSpan = tempMinSpan,
                        scrubIndex = scrubIndex, onScrub = onScrub)
                })
                add("voltage" to {
                    ChartCard(stringResource(R.string.recording_chart_voltage), dataPoints.map { it.voltage },
                        MaterialTheme.appColors.statusDanger, unitLabel = "V", minSpan = GraphScale.SPAN_VOLTAGE,
                        scrubIndex = scrubIndex, onScrub = onScrub)
                })
                if (dataPoints.any { !it.current.isNaN() }) {
                    add("current" to {
                        ChartCard(stringResource(R.string.recording_chart_current),
                            dataPoints.map { it.current },
                            MaterialTheme.appColors.metricVoltage, unitLabel = "A", minSpan = GraphScale.SPAN_CURRENT,
                            regenColor = MaterialTheme.appColors.metricBattery,
                            scrubIndex = scrubIndex, onScrub = onScrub)
                    })
                }
                if (dataPoints.any { !it.pwm.isNaN() }) {
                    add("pwm" to {
                        ChartCard(stringResource(R.string.recording_chart_pwm),
                            dataPoints.map { it.pwm },
                            MaterialTheme.appColors.metricTemp, unitLabel = "%", minSpan = GraphScale.SPAN_LOAD,
                            scrubIndex = scrubIndex, onScrub = onScrub)
                    })
                }
                // Opt-in extras. Each renders only when the rider switched it on
                // in the customizer AND the trip actually carries the data, so
                // enabling one never produces an empty card.
                if ("batterySmooth" in extraCharts) {
                    add("batterySmooth" to {
                        ChartCard(stringResource(R.string.recording_chart_battery_smooth),
                            Smoothing.movingAverage(dataPoints.map { it.battery.toFloat() }, smoothWindow),
                            MaterialTheme.appColors.metricVoltage, unitLabel = "%", minSpan = GraphScale.SPAN_BATTERY,
                            scrubIndex = scrubIndex, onScrub = onScrub)
                    })
                }
                if ("speedSmooth" in extraCharts) {
                    add("speedSmooth" to {
                        ChartCard(stringResource(R.string.recording_chart_speed_smooth, speedUnitLabel),
                            Smoothing.movingAverage(
                                dataPoints.map { com.eried.eucplanet.util.Units.speed(it.speed, speedUnit) },
                                smoothWindow
                            ),
                            MaterialTheme.appColors.metricBattery, unitLabel = speedUnitLabel, minSpan = speedMinSpan,
                            scrubIndex = scrubIndex, onScrub = onScrub)
                    })
                }
                if ("currentSmooth" in extraCharts && dataPoints.any { !it.current.isNaN() }) {
                    add("currentSmooth" to {
                        ChartCard(stringResource(R.string.recording_chart_current_smooth),
                            Smoothing.movingAverage(dataPoints.map { it.current }, smoothWindow),
                            MaterialTheme.appColors.metricVoltage, unitLabel = "A", minSpan = GraphScale.SPAN_CURRENT,
                            regenColor = MaterialTheme.appColors.metricBattery,
                            scrubIndex = scrubIndex, onScrub = onScrub)
                    })
                }
                if ("pwmSmooth" in extraCharts && dataPoints.any { !it.pwm.isNaN() }) {
                    add("pwmSmooth" to {
                        ChartCard(stringResource(R.string.recording_chart_pwm_smooth),
                            Smoothing.movingAverage(dataPoints.map { it.pwm }, smoothWindow),
                            MaterialTheme.appColors.metricTemp, unitLabel = "%", minSpan = GraphScale.SPAN_LOAD,
                            scrubIndex = scrubIndex, onScrub = onScrub)
                    })
                }
                if ("power" in extraCharts && dataPoints.any { !it.current.isNaN() }) {
                    add("power" to {
                        ChartCard(stringResource(R.string.recording_chart_power),
                            // Derived, the CSV has no power column. NaN current
                            // stays NaN so the line breaks rather than reading 0 W.
                            dataPoints.map { if (it.current.isNaN()) Float.NaN else it.voltage * it.current },
                            MaterialTheme.appColors.statusDanger, unitLabel = "W", minSpan = 100f,
                            regenColor = MaterialTheme.appColors.metricBattery,
                            scrubIndex = scrubIndex, onScrub = onScrub)
                    })
                }
                if ("altitude" in extraCharts && dataPoints.any { it.altitude != 0f }) {
                    add("altitude" to {
                        ChartCard(stringResource(R.string.recording_chart_altitude),
                            dataPoints.map { it.altitude },
                            MaterialTheme.appColors.metricPosition, unitLabel = "m", minSpan = 20f,
                            scrubIndex = scrubIndex, onScrub = onScrub)
                    })
                }
            }

            // Effective chart order via the same shared helper (see applyOrder):
            // covers all six keys so the customizer list is stable even for a trip
            // that has no current / pwm data.
            // Every graph is listed, so the customizer shows the smoothed ones
            // sitting under the series they smooth. Whether each is switched on
            // is a separate question, answered by hiddenCharts / extraCharts.
            val effectiveChartOrder = applyOrder(CHART_KEYS_DEFAULT, savedChartOrder)

            // Scrub-synced charts, in the rider's order. A chart drops out when
            // its key is hidden, when it isn't in allCharts (absent current / pwm
            // data), preserving the old data-presence gating. TripDetailsSection
            // stays pinned at the very end, outside the reorder, and hides on the
            // "extra" key.
            val chartsByKey = allCharts.associateBy { it.first }
            val orderedCharts = effectiveChartOrder
                .filter { it !in hiddenCharts }
                .mapNotNull { chartsByKey[it] }
            val chartsContent: @Composable ColumnScope.() -> Unit = {
                orderedCharts.forEachIndexed { i, (_, chart) ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    chart()
                }
                // Extra details always renders when enabled, even with no wheel
                // events (an imported CSV that carries no wheel identity): the
                // section then just says so, rather than silently vanishing.
                if ("extra" !in hiddenCharts) {
                    Spacer(Modifier.height(12.dp))
                    TripDetailsSection(extraEvents)
                }
            }

            // Display names for the customizer sheet, reusing the same tile and
            // chart strings the cards already use so nothing is duplicated.
            val tileLabels: Map<String, String> = mapOf(
                "distance" to stringResource(R.string.recording_summary_distance),
                "duration" to stringResource(R.string.recording_summary_duration),
                "points" to stringResource(R.string.recording_summary_points),
                "topSpeed" to stringResource(R.string.recording_summary_top_speed),
                "avgSpeed" to stringResource(R.string.recording_summary_avg_speed),
                "avgMoving" to stringResource(R.string.recording_summary_avg_moving),
                // Generic label for the sheet only: the real tile still shows the
                // per-trip "Battery (-X%)"; the customizer must stay value-free.
                "battery" to stringResource(R.string.metric_chip_battery),
                "voltage" to stringResource(R.string.recording_summary_voltage),
                "maxTemp" to stringResource(R.string.recording_summary_max_temp),
                "maxPwm" to stringResource(R.string.recording_summary_max_pwm),
                "maxCurrent" to stringResource(R.string.recording_summary_max_current),
                "maxPower" to stringResource(R.string.recording_summary_max_power),
            )
            val chartLabels: Map<String, String> = mapOf(
                "speed" to stringResource(R.string.recording_chart_speed, speedUnitLabel),
                "battery" to stringResource(R.string.recording_chart_battery),
                "temp" to stringResource(R.string.recording_chart_temp, tempUnitLabel),
                "voltage" to stringResource(R.string.recording_chart_voltage),
                "current" to stringResource(R.string.recording_chart_current),
                "pwm" to stringResource(R.string.recording_chart_pwm),
                "batterySmooth" to stringResource(R.string.recording_chart_battery_smooth),
                "speedSmooth" to stringResource(R.string.recording_chart_speed_smooth, speedUnitLabel),
                "currentSmooth" to stringResource(R.string.recording_chart_current_smooth),
                "pwmSmooth" to stringResource(R.string.recording_chart_pwm_smooth),
                "power" to stringResource(R.string.recording_chart_power),
                "altitude" to stringResource(R.string.recording_chart_altitude),
            )

            if (showCustomize) {
                CustomizeSheet(
                    hiddenTiles = hiddenTiles,
                    tileOrder = effectiveTileOrder,
                    tileLabels = tileLabels,
                    hiddenCharts = hiddenCharts,
                    chartOrder = effectiveChartOrder,
                    chartLabels = chartLabels,
                    extraCharts = extraCharts,
                    onToggleTile = { key, hidden -> viewModel.setTileHidden(key, hidden) },
                    onToggleChart = { key, hidden -> viewModel.setChartHidden(key, hidden) },
                    onToggleExtraChart = { key, on -> viewModel.setExtraChart(key, on) },
                    onReorderTiles = { viewModel.setTileOrder(it) },
                    onReorderCharts = { viewModel.setChartOrder(it) },
                    canReset = hiddenTiles.isNotEmpty() || hiddenCharts.isNotEmpty() ||
                        extraCharts.isNotEmpty() ||
                        savedTileOrder.isNotEmpty() || savedChartOrder.isNotEmpty(),
                    onReset = { viewModel.resetTripLayout() },
                    onDismiss = { showCustomize = false },
                )
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

/**
 * Final key order for a customizable list (shared by the stat tiles and the
 * graphs so the two can never drift). Iterates [savedOrder], keeping only keys
 * that still exist in [defaultKeys] (a removed/renamed key is dropped), then
 * appends every current key NOT already listed, in default declaration order.
 *
 * Consequences, by design:
 *  - An empty [savedOrder] means "all keys in default order".
 *  - A key added in a future app version isn't in any saved order yet, so it
 *    appears automatically at the end. Visibility is a separate hidden-set (a
 *    key absent from the hidden set is shown), so the new key defaults to shown.
 */
private fun applyOrder(defaultKeys: List<String>, savedOrder: List<String>): List<String> {
    val known = defaultKeys.toSet()
    val saved = savedOrder.filter { it in known }
    return saved + defaultKeys.filter { it !in saved }
}

/**
 * Tappable section header for the Customize sheet: the section title on the left
 * and a chevron on the right that reflects (and toggles) the expanded state.
 */
@Composable
private fun CustomizeSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.appColors.textSecondary,
        )
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.appColors.textSecondary,
        )
    }
}

/**
 * Trip Details "Customize" bottom sheet. Two collapsible reorderable sections,
 * stat tiles and graphs: each row has a drag handle (left) to set order and a
 * switch (right) to show or hide it. "Extra details" is its own single-toggle
 * section below. Edits persist immediately through the ViewModel, so the live
 * screen updates live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomizeSheet(
    hiddenTiles: Set<String>,
    tileOrder: List<String>,
    tileLabels: Map<String, String>,
    hiddenCharts: Set<String>,
    chartOrder: List<String>,
    chartLabels: Map<String, String>,
    extraCharts: Set<String>,
    onToggleTile: (String, Boolean) -> Unit,
    onToggleChart: (String, Boolean) -> Unit,
    onToggleExtraChart: (String, Boolean) -> Unit,
    onReorderTiles: (List<String>) -> Unit,
    onReorderCharts: (List<String>) -> Unit,
    canReset: Boolean,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    // Sections default expanded; each header's chevron toggles its own list.
    var tilesExpanded by remember { mutableStateOf(true) }
    var chartsExpanded by remember { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.trip_customize),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.appColors.textPrimary,
            )

            Spacer(Modifier.height(8.dp))
            CustomizeSectionHeader(
                title = stringResource(R.string.trip_customize_tiles),
                expanded = tilesExpanded,
                onToggle = { tilesExpanded = !tilesExpanded },
            )
            if (tilesExpanded) {
                ReorderableColumn(
                    list = tileOrder,
                    onSettle = { from, to ->
                        onReorderTiles(tileOrder.toMutableList().apply { add(to, removeAt(from)) })
                    },
                    onMove = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    modifier = Modifier.fillMaxWidth(),
                ) { _, tileKey, _ ->
                    key(tileKey) {
                        // Each tile row: drag handle (left), name, show/hide switch.
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = stringResource(R.string.action_reorder),
                                tint = MaterialTheme.appColors.textSecondary,
                                modifier = Modifier.draggableHandle().size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                tileLabels[tileKey] ?: tileKey,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.appColors.textPrimary,
                            )
                            Switch(
                                checked = tileKey !in hiddenTiles,
                                onCheckedChange = { onToggleTile(tileKey, !it) },
                                colors = themedSwitchColors(),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            CustomizeSectionHeader(
                title = stringResource(R.string.trip_customize_graphs),
                expanded = chartsExpanded,
                onToggle = { chartsExpanded = !chartsExpanded },
            )
            if (chartsExpanded) {
                ReorderableColumn(
                    list = chartOrder,
                    onSettle = { from, to ->
                        onReorderCharts(chartOrder.toMutableList().apply { add(to, removeAt(from)) })
                    },
                    onMove = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    modifier = Modifier.fillMaxWidth(),
                ) { _, chartKey, _ ->
                    key(chartKey) {
                        // Each graph row: drag handle (left), name, show/hide switch.
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = stringResource(R.string.action_reorder),
                                tint = MaterialTheme.appColors.textSecondary,
                                modifier = Modifier.draggableHandle().size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                chartLabels[chartKey] ?: chartKey,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.appColors.textPrimary,
                            )
                            // One list, two stores. The graphs that ship on are
                            // tracked by what the rider HID; the ones that ship
                            // off by what they switched ON. The switch reads and
                            // writes the right one, so the distinction never
                            // surfaces here.
                            val isExtra = chartKey in EXTRA_CHART_KEYS
                            Switch(
                                checked = if (isExtra) chartKey in extraCharts
                                          else chartKey !in hiddenCharts,
                                onCheckedChange = {
                                    if (isExtra) onToggleExtraChart(chartKey, it)
                                    else onToggleChart(chartKey, !it)
                                },
                                colors = themedSwitchColors(),
                            )
                        }
                    }
                }
            }

            // "Extra details" is its own section: a single label + switch, so it
            // isn't collapsible (nothing to expand). It stays wired to the chart
            // hidden set under the "extra" key and renders pinned last on the trip
            // screen. The label appears exactly once, styled like a section title.
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.recording_details_section),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.appColors.textSecondary,
                )
                Switch(
                    checked = "extra" !in hiddenCharts,
                    onCheckedChange = { onToggleChart("extra", !it) },
                    colors = themedSwitchColors(),
                )
            }

            // Restore the whole layout (all tiles and graphs shown, default
            // order). Left-aligned with the Restore icon and the app's plain
            // "Reset" verb, matching the metric-detail reset footer. Greyed out
            // when nothing differs from default.
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                TextButton(onClick = onReset, enabled = canReset, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_reset))
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

/**
 * "Extra details" section listing every Extra-column event of the trip as
 * text: the wheel identity blocks (highlighted on their first row, which
 * matches a map marker), disconnects, and whatever future events the
 * recorder adds.
 */
@Composable
private fun TripDetailsSection(events: List<TripExtraEvent>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.recording_details_section),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                if (events.isEmpty()) {
                    // Imported trips (and any recording that never captured a wheel
                    // identity) have nothing to list, so state that plainly.
                    Text(
                        stringResource(R.string.recording_details_none),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                // The first wheel identity is the ride start (green, matching the
                // map's green start marker). A genuinely different wheel later is
                // a change (purple, matching its purple map circle). The same
                // wheel simply reconnecting is muted - it is not a wheel change,
                // it only gets a dot on the map.
                var firstWheelSeen = false
                events.forEach { e ->
                    val isFirstWheel = e.isWheelStart && !firstWheelSeen
                    if (e.isWheelStart) firstWheelSeen = true
                    Row {
                        Text(
                            e.time,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            e.text,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = when {
                                isFirstWheel -> MaterialTheme.appColors.statusGood
                                e.isWheelChange -> MaterialTheme.appColors.wheelChange
                                e.isWheelStart -> MaterialTheme.appColors.textSecondary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

// One Extra-column event from the trip CSV, with the GPS fix of its row so
// wheel changes can be pinned on the map. isWheelStart marks the first row
// of an identity block (recording start or a mid-ride wheel change).
data class TripExtraEvent(
    val time: String,
    val text: String,
    val lat: Double,
    val lon: Double,
    // Start of an identity block (recording start, a reconnect, or a real wheel
    // swap). isWheelChange narrows that to a genuinely DIFFERENT wheel; a
    // same-wheel reconnect is isWheelStart && !isWheelChange.
    val isWheelStart: Boolean,
    val isWheelChange: Boolean,
    val index: Int,
)

// A mid-ride wheel change ready for the map: [gpsIndex] is the position of
// the change row within the GPS-bearing points (= the coords array the map
// polyline is built from), so the trace can switch colour exactly there.
data class WheelSwitchMarker(
    val gpsIndex: Int,
    val lat: Double,
    val lon: Double,
    val label: String,
    // true = a genuinely different wheel took over here (trace colour switches
    // + purple circle); false = the same wheel reconnected (unbroken trace, a
    // small dot only).
    val isChange: Boolean,
)

// Walks the rows' Extra cells and marks identity-block starts: a block
// begins when wheel.name / wheel.mac appears with no open block or repeats
// a field the open block already has (the recorder re-emits the whole
// block on every reconnect). wheel.disconnected closes the block.
//
// isWheelChange separates a genuinely DIFFERENT wheel from the SAME wheel just
// reconnecting. Because the recorder re-emits the whole identity block on every
// reconnect, a block start alone does not mean the wheel changed - we compare
// the identity VALUES. name is the primary per-device key (it carries the
// device suffix, e.g. Adventure-E0000298); mac backs it up. Same identity = a
// reconnect (one trace colour, dot only); different identity = a real swap.
private fun extractExtraEvents(points: List<TripDataPoint>): List<TripExtraEvent> {
    val out = ArrayList<TripExtraEvent>()
    var block: MutableSet<String>? = null
    // Identity VALUES of the open block and the one before it, so a block start
    // can be classed as reconnect (same wheel) vs change (different wheel).
    var openName: String? = null
    var openMac: String? = null
    var prevName: String? = null
    var prevMac: String? = null
    for ((i, p) in points.withIndex()) {
        val text = p.extra.trim()
        if (text.isEmpty()) continue
        val eq = text.indexOf('=')
        val key = if (eq > 0) text.substring(0, eq).trim() else text
        val value = if (eq > 0) text.substring(eq + 1).trim() else ""
        var isStart = false
        var isChange = false
        if (key == "wheel.name" || key == "wheel.mac") {
            val field = key.removePrefix("wheel.")
            if (block == null || block.contains(field)) {
                // A new identity block begins. Roll the just-closed block's
                // identity into prev*, then seed the new one from this row.
                isStart = true
                prevName = openName
                prevMac = openMac
                openName = null
                openMac = null
                block = hashSetOf(field)
                if (field == "name") openName = value else openMac = value
                // Genuine change only when we HAD a previous wheel and this
                // identity differs from it. The first block ever is the ride
                // start, not a change. Compare on the field that opened the
                // block (the recorder emits name first, so that is usually the
                // name - a reliable per-device key).
                isChange = when (field) {
                    "name" -> prevName != null && !prevName.equals(value, ignoreCase = true)
                    else -> prevMac != null && !prevMac.equals(value, ignoreCase = true)
                }
            } else {
                block.add(field)
                if (field == "name") openName = value else openMac = value
            }
        } else if (key == "wheel.disconnected") {
            block = null
        } else if (key.startsWith("wheel.") && block != null) {
            block.add(key.removePrefix("wheel."))
        }
        out.add(TripExtraEvent(timePartOf(p.date), text, p.latitude, p.longitude, isStart, isChange, i))
    }
    return out
}

/** "dd.MM.yyyy HH:mm:ss.SSS" or ISO "...T HH:mm:ss..." -> "HH:mm:ss". */
private fun timePartOf(date: String): String {
    val sep = date.indexOfFirst { it == ' ' || it == 'T' }
    if (sep < 0) return date
    val t = date.substring(sep + 1)
    return if (t.length >= 8) t.substring(0, 8) else t
}

/**
 * Every Trip Details graph, in default display order.
 *
 * Each smoothed variant sits directly under the raw series it smooths, so the
 * pair reads together in the customizer and on the screen. The raw series are
 * honest but hard to read: battery sags under load and springs back, current is
 * close to noise at the ~1 Hz sample rate, and PWM peaks are momentary.
 */
private val CHART_KEYS_DEFAULT = listOf(
    "speed", "speedSmooth",
    "battery", "batterySmooth",
    "temp",
    "voltage",
    "current", "currentSmooth",
    "pwm", "pwmSmooth",
    "power",
    "altitude",
)

/**
 * The graphs that start switched OFF.
 *
 * They need a separate settings key from the rest, because the hidden-charts CSV
 * records only what was HIDDEN: a key absent from it shows, so listing new keys
 * there would have made six graphs appear unannounced for every rider on
 * upgrade. The rider sees one list either way, the difference is only which
 * store the switch writes to.
 */
private val EXTRA_CHART_KEYS = setOf(
    "speedSmooth", "batterySmooth", "currentSmooth", "pwmSmooth", "power", "altitude",
)

// The rider's Trip-details map-style pick (LIGHT / DARK / SAT). Process-scoped so it
// applies instantly across trips this session without waiting on the settings flow;
// the pick is ALSO persisted (tripMapType) so it survives a restart. Null before the
// first pick, when the persisted value (or the theme default) seeds the map instead.
private var tripMapTypeSession: String? = null

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RouteMapView(
    points: List<TripDataPoint>,
    // The whole ride, drawn faded underneath when a trim is applied so the
    // section still reads in the context of the full trip. Empty = no trim.
    fadedPoints: List<TripDataPoint> = emptyList(),
    // Wheel-identity markers from the stretches the trim cut away, drawn faint
    // and without popups. Empty = no trim.
    fadedSwitches: List<WheelSwitchMarker> = emptyList(),
    // Whether the ride's real start / end survived the trim. Each endpoint marker
    // is ghosted only when its own end was the one cut away.
    startIncluded: Boolean = true,
    endIncluded: Boolean = true,
    isLive: Boolean = false,
    liveLat: Double? = null,
    liveLon: Double? = null,
    // When a chart is being scrubbed, the GPS position of that sample (or null to
    // hide the marker). Drives a dot on the map synced with the chart cursor.
    scrubLat: Double? = null,
    scrubLon: Double? = null,
    // Mid-ride wheel changes (yellow circle + yellow trace onward) and the
    // start marker's popup text (the recording's first wheel identity,
    // empty = no popup).
    wheelSwitches: List<WheelSwitchMarker> = emptyList(),
    startLabel: String = "",
    endLabel: String = "",
    // Persisted base map pick (blank = none yet); a change is written back through
    // onPersistMapType so the style survives an app restart, not just the session.
    savedMapType: String = "",
    onPersistMapType: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // rememberSaveable so a rotation (which recreates the composition) keeps the
    // map fullscreen instead of dropping back to the trip details.
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // Map style (light / dark / satellite) is shared between the inline and the
    // fullscreen map so opening fullscreen keeps the style the rider picked,
    // rather than resetting to light.
    // Priority: this-session pick > persisted pick > theme default. The theme
    // default reads the active background luminance (a dark theme, including a
    // custom dark one, gets the dark map; a light one gets the white map).
    val themeMapDefault = if (MaterialTheme.appColors.appBackground.luminance() < 0.5f) "DARK" else "LIGHT"
    var mapType by rememberSaveable {
        mutableStateOf(tripMapTypeSession ?: savedMapType.ifBlank { themeMapDefault })
    }
    // The persisted value is served through an Eagerly-started flow, so it is
    // normally present by first composition; guard the rare case where it arrives
    // after. Only adopt it while the rider has not picked this session.
    LaunchedEffect(savedMapType) {
        if (tripMapTypeSession == null && savedMapType.isNotBlank() && savedMapType != mapType) {
            mapType = savedMapType
        }
    }
    val onPick: (String) -> Unit = { mapType = it; tripMapTypeSession = it; onPersistMapType(it) }

    MapSurface(
        points = points, fadedPoints = fadedPoints, fadedSwitches = fadedSwitches,
        startIncluded = startIncluded, endIncluded = endIncluded,
        isLive = isLive, liveLat = liveLat, liveLon = liveLon,
        scrubLat = scrubLat, scrubLon = scrubLon,
        wheelSwitches = wheelSwitches, startLabel = startLabel, endLabel = endLabel,
        fullscreen = false, onToggleFullscreen = { fullscreen = true },
        mapType = mapType, onMapTypeChange = onPick,
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
                points = points, fadedPoints = fadedPoints, fadedSwitches = fadedSwitches,
        startIncluded = startIncluded, endIncluded = endIncluded,
        isLive = isLive, liveLat = liveLat, liveLon = liveLon,
                scrubLat = scrubLat, scrubLon = scrubLon,
                wheelSwitches = wheelSwitches, startLabel = startLabel, endLabel = endLabel,
                fullscreen = true, onToggleFullscreen = { fullscreen = false },
                mapType = mapType, onMapTypeChange = onPick,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun MapSurface(
    points: List<TripDataPoint>,
    fadedPoints: List<TripDataPoint>,
    fadedSwitches: List<WheelSwitchMarker>,
    startIncluded: Boolean,
    endIncluded: Boolean,
    isLive: Boolean,
    liveLat: Double?,
    liveLon: Double?,
    scrubLat: Double?,
    scrubLon: Double?,
    wheelSwitches: List<WheelSwitchMarker>,
    startLabel: String,
    endLabel: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    mapType: String,
    onMapTypeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coordsJson = remember(points) {
        points.joinToString(",") { "[${it.latitude},${it.longitude}]" }
    }
    // Empty unless a trim is applied, which makes the faded layer a no-op and
    // leaves the untrimmed map rendering exactly as it did before.
    val fadedCoordsJson = remember(fadedPoints) {
        fadedPoints.joinToString(",") { "[${it.latitude},${it.longitude}]" }
    }
    // Wheel-change markers + start popup, JSON-encoded so rider-controlled
    // BLE names can never break out of the script block.
    val switchesJson = remember(wheelSwitches) {
        val arr = org.json.JSONArray()
        for (s in wheelSwitches) {
            arr.put(org.json.JSONObject()
                .put("idx", s.gpsIndex)
                .put("lat", s.lat).put("lon", s.lon)
                .put("label", s.label)
                .put("change", s.isChange))
        }
        arr.toString()
    }
    // Same encoding as switchesJson. No idx: these never cut a trace colour.
    val fadedSwitchesJson = remember(fadedSwitches) {
        val arr = org.json.JSONArray()
        for (s in fadedSwitches) {
            arr.put(org.json.JSONObject()
                .put("lat", s.lat).put("lon", s.lon)
                .put("change", s.isChange))
        }
        arr.toString()
    }
    val startLabelJs = remember(startLabel) { org.json.JSONObject.quote(startLabel) }
    val endLabelJs = remember(endLabel) { org.json.JSONObject.quote(endLabel) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val mapTypes = listOf("LIGHT", "DARK", "TOPO", "SAT")
    // The HTML actually showing in the WebView.
    //
    // A plain holder rather than Compose state on purpose: it is written from
    // inside AndroidView's update block, and making it state would schedule a
    // recomposition from within one.
    val loadedHtml = remember { arrayOfNulls<String>(1) }
    // Rebuilt whenever the trace changes (a trim, for instance) or we enter or
    // leave live mode. Bake the CURRENT style into the initial HTML so a
    // freshly-opened surface (e.g. fullscreen) starts on the shared style rather
    // than flashing light first; style cycles afterwards go through JS.
    val html = remember(
        coordsJson, fadedCoordsJson, isLive, switchesJson, fadedSwitchesJson,
        startIncluded, endIncluded, startLabelJs, endLabelJs
    ) {
        buildMapHtml(
            coordsJson, fadedCoordsJson, switchesJson, fadedSwitchesJson,
            startIncluded, endIncluded, startLabelJs, endLabelJs, isLive, mapType
        )
    }

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
                    loadedHtml[0] = html
                    webView = this
                }
            },
            // factory runs once, so loading the page only there left the map
            // frozen on whatever trace it was first built with. Applying a trim
            // rebuilt the HTML and then threw it away. Reload whenever the
            // document actually changed, which also refits the view to the new
            // trace.
            update = { wv ->
                webView = wv
                if (loadedHtml[0] != html) {
                    loadedHtml[0] = html
                    wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            },
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

private fun buildMapHtml(coordsJson: String, fadedCoordsJson: String, switchesJson: String, fadedSwitchesJson: String, startIncluded: Boolean, endIncluded: Boolean, startLabelJs: String, endLabelJs: String, isLive: Boolean, initialType: String): String = """
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
  /* Wheel-event badges: small squares, so they read differently from the round
     start / end / live markers and stay smaller than them. A same-wheel
     reconnect is grey; a genuinely different wheel is purple (matching the
     trace colour switch; wheel-change = the wheelChange token's default). */
  .wheel-badge{ border:1.5px solid #fff;box-sizing:border-box;border-radius:2px; }
  .wheel-change{ width:13px;height:13px;background:#AB47BC; }
  .wheel-reconnect{ width:10px;height:10px;background:#9E9E9E; }
  /* Identity badges outside a trimmed section: same shape, ghosted. */
  .faded-badge{ opacity:0.45; }
</style>
</head><body>
<div id="map"></div>
<script>
  var coords=[$coordsJson];
  var fadedCoords=[$fadedCoordsJson];
  var map=L.map('map',{zoomControl:false,attributionControl:false});
  var baseLayer=null;
  // {r} asks the provider for its @2x tile on a high-density screen. Without it
  // a phone upscales a 256 px tile threefold or more, which is most of why the
  // map read as soft and short on detail. Esri's tiles carry no {r}, so it
  // expands to "" there and the URL is unchanged.
  var tileUrls={
    LIGHT:'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png',
    DARK:'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
    TOPO:'https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}',
    SAT:'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'
  };
  window.setMapType=function(t){
    if(baseLayer) map.removeLayer(baseLayer);
    // maxNativeZoom stops requesting tiles the provider does not have, while
    // maxZoom lets the rider keep zooming on upscaled ones rather than hitting
    // a wall at street level.
    var native = (t === 'LIGHT' || t === 'DARK') ? 20 : 19;
    baseLayer=L.tileLayer(tileUrls[t]||tileUrls.LIGHT,
      {maxNativeZoom:native, maxZoom:21, subdomains:'abcd'}).addTo(map);
    baseLayer.bringToBack();
  };
  window.setMapType('$initialType');

  var hasRoute = coords.length >= 2;
  var start=null, end=null, overlap=null;

  function render(){
    if (hasRoute){
      // The rest of the ride, drawn very faint underneath so a trimmed view
      // still shows where the section sits in the whole trip. Empty when
      // untrimmed, which makes this a no-op and leaves that map unchanged.
      // Everything here is non-interactive: the solid layer owns every popup,
      // so a tap near a ghost never opens the wrong thing.
      if (fadedCoords.length >= 2){
        L.polyline(fadedCoords,
          {color:'#4FC3F7',weight:4,opacity:0.38,interactive:false}).addTo(map);
      }
      // Split the trace ONLY at genuine wheel changes (s.change): the first
      // wheel keeps the blue trace and a different wheel's stretch is drawn
      // purple. A same-wheel reconnect does NOT cut the trace - it stays one
      // colour, with just a dot marking where it dropped and resumed.
      var cuts = switches.filter(function(s){return s.change;})
        .map(function(s){return s.idx;})
        .filter(function(i){return i>0 && i<coords.length;})
        .sort(function(a,b){return a-b;});
      var prev = 0;
      for (var k=0;k<=cuts.length;k++){
        var stop = (k<cuts.length)?cuts[k]:coords.length-1;
        if (stop>prev) L.polyline(coords.slice(prev,stop+1),
          {color:k===0?'#4FC3F7':'#AB47BC',weight:4,interactive:false}).addTo(map);
        prev = stop;
      }
      map.fitBounds(L.latLngBounds(coords).pad(0.2));
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

    // Start and end always mark where the WHOLE ride began and finished, never
    // the ends of a trimmed section: those are an artefact of the view, and
    // putting the ride's start dot in the middle of a road reads as a lie.
    //
    // Each is dimmed only when it falls OUTSIDE the selection. Trimming from the
    // very beginning keeps the real start inside what is being looked at, so it
    // stays solid; it is the end, off past the edge, that ghosts.
    var track = (fadedCoords.length >= 2) ? fadedCoords : coords;
    var opA = startIncluded ? 1 : 0.45;
    var opB = endIncluded ? 1 : 0.45;
    var a = track[0], b = track[track.length-1];
    var pa = map.latLngToContainerPoint(a);
    var pb = map.latLngToContainerPoint(b);
    var dist = pa.distanceTo(pb);
    var r = 7; // circleMarker radius in px
    // Overlap is more than 50% of a marker's width: dist < 2*r*(1-0.5) = r.
    if (dist < r){
      // One combined marker, so it can only be solid when BOTH ends are in.
      overlap = L.marker(a,{opacity:Math.min(opA,opB),icon:L.divIcon({className:'half-marker',iconSize:[18,18],iconAnchor:[9,9]})}).addTo(map);
    } else {
      start = L.circleMarker(a,{radius:r,color:'#000',weight:2,opacity:opA,fillColor:'#66BB6A',fillOpacity:opA}).addTo(map);
      end   = L.circleMarker(b,{radius:r,color:'#000',weight:2,opacity:opB,fillColor:'#EF5350',fillOpacity:opB}).addTo(map);
    }
    // Start marker: the ride-start "Connected" popup. End marker: "Ride end".
    // When start and end overlap (loop rides) the single marker shows both.
    if (start && startLabel) start.bindPopup(startLabel);
    if (end && endLabel) end.bindPopup(endLabel);
    if (overlap){
      var combined = [startLabel, endLabel].filter(Boolean).join('<br>');
      if (combined) overlap.bindPopup(combined);
    }
  }

  var switches=$switchesJson;
  var fadedSwitches=$fadedSwitchesJson;
  var startIncluded=$startIncluded;
  var endIncluded=$endIncluded;
  var startLabel=$startLabelJs;
  var endLabel=$endLabelJs;

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
  // Wheel-identity badges from the stretches the trim cut away. Same shapes as
  // the live ones so they read as the same thing, just ghosted, and with no
  // popup: they are context, and the trimmed section owns the interaction.
  // Added before the solid badges so those stack on top where they coincide.
  fadedSwitches.forEach(function(s){
    var cls = s.change ? 'wheel-badge wheel-change' : 'wheel-badge wheel-reconnect';
    var sz = s.change ? 13 : 10;
    var icon = L.divIcon({
      className:'faded-badge', html:'<div class="'+cls+'"></div>',
      iconSize:[sz,sz], iconAnchor:[sz/2,sz/2]
    });
    L.marker([s.lat,s.lon],{icon:icon,interactive:false}).addTo(map);
  });
  // A small square for each identity block after the ride start, each with its
  // own popup (time + wheel). A genuine wheel change is a purple square where
  // the trace colour also switches; a same-wheel reconnect is a smaller grey
  // square on the unbroken trace, flagging where it dropped and resumed. Square
  // so they don't read as another position dot, and both smaller than the
  // start/end markers. Added after render() so they stack above the trace and
  // keep their tap target.
  switches.forEach(function(s){
    var cls = s.change ? 'wheel-badge wheel-change' : 'wheel-badge wheel-reconnect';
    var sz = s.change ? 13 : 10;
    var icon = L.divIcon({
      className:'', html:'<div class="'+cls+'"></div>',
      iconSize:[sz,sz], iconAnchor:[sz/2,sz/2]
    });
    L.marker([s.lat,s.lon],{icon:icon}).addTo(map).bindPopup(s.label);
  });
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

/**
 * Top-bar funnel. Filled and tinted while a trim is applied, outlined and in
 * the bar's normal colour on the full trip, so the state reads by shape as well
 * as by colour. Shared by the portrait and landscape bars so the two stay in
 * sync.
 */
@Composable
private fun TrimAction(trimmed: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (trimmed) Icons.Filled.FilterAlt else Icons.Outlined.FilterAlt,
            contentDescription = stringResource(R.string.trip_trim),
            tint = if (trimmed) MaterialTheme.appColors.primary else LocalContentColor.current,
        )
    }
}
