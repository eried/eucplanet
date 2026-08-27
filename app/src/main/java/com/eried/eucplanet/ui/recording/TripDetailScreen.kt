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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import com.eried.eucplanet.ui.navigator.RouteBuilderViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.util.TripCsv
import com.eried.eucplanet.util.GraphScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import kotlin.math.roundToInt
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.common.TrimTimeDialog
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.eried.eucplanet.util.Smoothing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // The exact-times dialog, now reached from the span in the trim bar rather
    // than straight off the funnel.
    var showTrim by remember { mutableStateOf(false) }
    // The funnel toggles the bar. Dragging two handles is how a rider actually
    // wants to find a stretch of ride; typing MM:SS is the fallback for when
    // they already know the moment they want.
    var showTrimBar by remember(trip.id) { mutableStateOf(false) }
    // Deferred: parsing every row's timestamp is the single most expensive thing
    // this screen can do on a long ride, and most trips are never trimmed. It is
    // computed the moment the rider opens the dialog or a trim is live, and not
    // before.
    val needElapsed = showTrim || showTrimBar || trimRange != null
    // Sticky once computed: hiding the bar used to flip needElapsed off,
    // which dropped this table while the bar was still animating away - its
    // numbers visibly snapped to 0:00 mid-exit. Once the rider has paid for
    // the parse, keep it for the life of the screen.
    val elapsedHolder = remember(allPoints) { mutableStateOf<LongArray?>(null) }
    if (needElapsed && elapsedHolder.value == null) {
        elapsedHolder.value = TripTrim.elapsedOffsets(allPoints)
    }
    val elapsedMs = elapsedHolder.value ?: LongArray(0)
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
    // Opt-in extra stat tiles (start->end battery, energy, consumption).
    val extraTiles by viewModel.tripExtraTiles.collectAsState()
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

    // True until the CSV has been read. Distinguishes "still loading" from
    // "genuinely has no data", which otherwise look identical and made an
    // ordinary long ride flash an error message on open.
    var loadingTrip by remember(trip.id) { mutableStateOf(true) }
    // A short trip loads faster than the eye can register, and a skeleton that
    // appears and vanishes in a few frames reads as a glitch. Hold it back
    // briefly: below the threshold the screen simply appears, above it the
    // rider gets the placeholder instead of a stall.
    var showSkeleton by remember(trip.id) { mutableStateOf(false) }
    LaunchedEffect(trip.id) {
        loadingTrip = true
        showSkeleton = false
        val reveal = launch {
            delay(SKELETON_REVEAL_MS)
            if (loadingTrip) showSkeleton = true
        }
        // Off the main thread: LaunchedEffect runs on the composition's
        // dispatcher, which is Main, and readTripData opens the file and parses
        // every row. On a long ride that blocked the UI thread for the whole
        // read, which is what made opening a trip feel slow.
        allPoints = withContext(Dispatchers.IO) { viewModel.readTripData(trip) }
        // Without this, opening a second trip from the same screen instance
        // would carry the previous trip's window across.
        trimRange = null
        loadingTrip = false
        reveal.cancel()
    }

    // Live-recording view. When the open trip is the one currently recording,
    // keep re-reading its still-growing CSV so the charts and the map trace
    // extend as the ride goes, instead of being frozen at the snapshot taken
    // when the screen opened. This never touches loadingTrip/showSkeleton, so a
    // refresh never flashes the placeholder. `> allPoints.size` only swaps in a
    // genuinely fuller read, so a momentarily truncated tail (a row half-written
    // when we read) can't shrink the view.
    LaunchedEffect(trip.id, isLiveTrip) {
        if (isLiveTrip) {
            while (isActive) {
                delay(LIVE_REFRESH_MS)
                val fresh = withContext(Dispatchers.IO) { viewModel.readTripData(trip) }
                if (fresh.size > allPoints.size) allPoints = fresh
            }
        } else if (allPoints.isNotEmpty()) {
            // Recording just ended: pick up the final rows close() flushed after
            // the last live refresh. Guarded so the very first (pre-load) pass
            // for an ordinary completed trip doesn't clobber the initial read.
            allPoints = withContext(Dispatchers.IO) { viewModel.readTripData(trip) }
        }
    }

    val dateFormat = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Locale.getDefault())
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Trip metrics and the header date range (start -> end) are hoisted so the
    // landscape top bar can show the range centred (the landscape body gives its
    // height to the permanent map + the scrollable charts, with no room for it).
    // Two metrics values, deliberately. healTripMetrics WRITES startTime,
    // endTime and distanceKm back onto the trip row, and the trip list reads
    // those stored fields rather than the CSV. Feeding it trimmed numbers would
    // overwrite the ride's real identity with whatever window happened to be
    // showing, with no way back. It gets the full trip, always.
    val metrics = remember(dataPoints) { viewModel.tripMetrics(dataPoints) }
    // Untrimmed, dataPoints IS allPoints, so computing this separately would
    // walk and re-parse the whole file a second time for an identical result.
    val fullMetricsWhenTrimmed = remember(allPoints, trimmed) {
        if (trimmed) viewModel.tripMetrics(allPoints) else null
    }
    val fullMetrics = fullMetricsWhenTrimmed ?: metrics
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
    // Rider's custom name leads the header when set (matching the trip list and
    // eucviewer's inspector); otherwise the start -> end date range stands in.
    val tripTitle = trip.customName?.takeIf { it.isNotBlank() } ?: headerDateTime

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
                            tripTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Visible from the start, disabled until the data
                        // lands: hiding them while loading made the bar reflow
                        // as they popped in. Hidden only for a trip that
                        // finished loading genuinely empty - there they would
                        // never work at all.
                        if (loadingTrip || dataPoints.isNotEmpty()) {
                            val ready = dataPoints.isNotEmpty()
                            IconButton(onClick = { showCustomize = true }, enabled = ready) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trip_customize))
                            }
                            TrimAction(trimmed = trimmed, open = showTrimBar, enabled = ready, onClick = { showTrimBar = !showTrimBar })
                        }
                        IconButton(
                            onClick = { showShareDialog = true },
                            enabled = !isLiveTrip && !loadingTrip,
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
                        // Visible from the start, disabled until the data
                        // lands: hiding them while loading made the bar reflow
                        // as they popped in. Hidden only for a trip that
                        // finished loading genuinely empty - there they would
                        // never work at all.
                        if (loadingTrip || dataPoints.isNotEmpty()) {
                            val ready = dataPoints.isNotEmpty()
                            IconButton(onClick = { showCustomize = true }, enabled = ready) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trip_customize))
                            }
                            TrimAction(trimmed = trimmed, open = showTrimBar, enabled = ready, onClick = { showTrimBar = !showTrimBar })
                        }
                        IconButton(
                            onClick = { showShareDialog = true },
                            enabled = !isLiveTrip && !loadingTrip
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
        if (loadingTrip) {
            // Reading a long ride takes a moment now that it happens off the main
            // thread, and flashing "no data" in the meantime reads as an error
            // when nothing is wrong. Show the shape of the screen instead, so the
            // layout is already there when the numbers land. Below the reveal
            // threshold this is blank for a few frames, which nobody sees.
            if (showSkeleton) {
                // The rider's own layout, which is settings and therefore known
                // before the ride is read. Charts that depend on the trip
                // carrying current or PWM are counted optimistically; being one
                // card out is invisible next to showing the wrong shape.
                val skeletonTiles = applyOrder(TILE_KEYS_DEFAULT, savedTileOrder)
                    .count { it !in hiddenTiles && (it !in EXTRA_TILE_KEYS || it in extraTiles) }
                val skeletonCharts = applyOrder(CHART_KEYS_DEFAULT, savedChartOrder)
                    .count { it !in hiddenCharts && (it !in EXTRA_CHART_KEYS || it in extraCharts) }
                TripDetailSkeleton(
                    tileCount = skeletonTiles,
                    chartCount = skeletonCharts,
                    modifier = Modifier.padding(padding),
                )
            }
        } else if (dataPoints.isEmpty()) {
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
            //
            // Not while trimmed. The stored figure describes the whole ride, so
            // it stayed at the full distance while every other tile followed the
            // window, and a trimmed trip read as if it covered the same ground
            // in half the time.
            val distanceKm = if (!trimmed && trip.distanceKm > 0f) trip.distanceKm
                else metrics.distanceKm
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
            // Energy for the ride, integrated from the recorded voltage and
            // current with the same step the live path uses. Reads off the
            // trimmed points, so trimming a trip re-costs it rather than
            // leaving a whole-ride figure beside windowed ones.
            val rideEnergy = remember(dataPoints) {
                com.eried.eucplanet.data.repository.ChargeEnergy.rideEnergy(
                    dataPoints.mapNotNull { p ->
                        com.eried.eucplanet.util.TripCsv.parseDate(p.date)?.let { t ->
                            Triple(t, p.voltage, p.current)
                        }
                    }
                )
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
            // Shared zoom window across every chart: fractions of the (already
            // trimmed) ride. Two fingers pinch or drag it on any chart,
            // double-tap resets to the full ride. Keyed on the data so a new
            // trim or trip starts zoomed out.
            var chartWindow by remember(dataPoints) { mutableStateOf(0f..1f) }
            val onWindow: (ClosedFloatingPointRange<Float>) -> Unit = { chartWindow = it }

            val gpsPoints = remember(dataPoints) {
                dataPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
            }
            // The whole ride's fixes, for the faded context track behind a trim.
            // Untrimmed these are the same list, so the filter is skipped rather
            // than run twice over every row.
            val fullGpsWhenTrimmed = remember(allPoints, trimmed) {
                if (trimmed) allPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
                else null
            }
            val fullGpsPoints = fullGpsWhenTrimmed ?: gpsPoints
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
            // Same reuse as above: identical to extraEvents when untrimmed.
            val fullExtraWhenTrimmed = remember(allPoints, trimmed) {
                if (trimmed) extractExtraEvents(allPoints) else null
            }
            val fullExtraEvents = fullExtraWhenTrimmed ?: extraEvents
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
            // The trace baked into the map. While this trip is still recording,
            // pin it to the snapshot from when the data first loaded so the map
            // WebView isn't rebuilt on every live refresh - a rebuild reloads
            // the page, resetting the rider's pan/zoom and flashing the tiles.
            // The live path (updateLivePoint) grows the trace smoothly instead.
            // Not recording: track gpsPoints directly, so a completed trip and
            // the final trace the moment recording stops both draw in full, one
            // clean reload.
            var pinnedTrace by remember(trip.id) { mutableStateOf<List<TripDataPoint>?>(null) }
            LaunchedEffect(isLive, gpsPoints) {
                if (isLive) {
                    if (pinnedTrace == null && gpsPoints.isNotEmpty()) pinnedTrace = gpsPoints
                } else {
                    pinnedTrace = null
                }
            }
            val mapPoints = if (isLive) (pinnedTrace ?: gpsPoints) else gpsPoints
            // The scrubbed sample's own GPS fix (from the full dataPoints, which the
            // chart index maps onto), or null if it had none.
            val scrubPoint = scrubIndex?.let { i ->
                dataPoints.getOrNull(i)?.takeIf { it.latitude != 0.0 && it.longitude != 0.0 }
            }
            // Tooltip for that dot: the wall-clock time of the sample and how far
            // into the ride it is. The dot alone says where, not when, which is
            // the question being asked when a rider drags a chart cursor and
            // watches the map. Elapsed is computed from the two timestamps rather
            // than from elapsedMs, which only exists while the trim UI is open,
            // and the parser is resolved once per section instead of per drag.
            val scrubParse = remember(dataPoints) {
                dataPoints.firstNotNullOfOrNull { TripCsv.parserFor(it.date) }
            }
            val scrubStartMs = remember(dataPoints, scrubParse) {
                scrubParse?.let { p -> dataPoints.firstNotNullOfOrNull { p(it.date) } }
            }
            val scrubLabel = scrubPoint?.let { p ->
                val clock = timePartOf(p.date)
                val at = scrubParse?.invoke(p.date)
                if (at != null && scrubStartMs != null) {
                    val elapsed = com.eried.eucplanet.util.Units
                        .humanDuration(((at - scrubStartMs) / 1000).coerceAtLeast(0))
                    "$clock · $elapsed"
                } else clock
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
                    points = mapPoints,
                    fadedPoints = if (trimmed) fullGpsPoints else emptyList(),
                    fadedSwitches = fadedSwitches,
                    startIncluded = startIncluded,
                    endIncluded = endIncluded,
                    isLive = isLive,
                    liveLat = liveLocation?.latitude,
                    liveLon = liveLocation?.longitude,
                    scrubLat = scrubPoint?.latitude,
                    scrubLon = scrubPoint?.longitude,
                    scrubLabel = scrubLabel.orEmpty(),
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
                // Opt-in (EXTRA_TILE_KEYS): the session's real start -> end %, which
                // survives a mid-trip charge on a combined ride where max -> min does
                // not. Title shows total drained.
                "batteryRange" to {
                    SummaryCard(
                        stringResource(R.string.recording_summary_battery_range, batteryStats.batteryDrained),
                        stringResource(R.string.recording_summary_battery_fmt, batteryStats.batteryStart, batteryStats.batteryEnd),
                        if (batteryStats.batteryEnd < 20) MaterialTheme.appColors.statusDanger else MaterialTheme.appColors.statusGood,
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
                "energy" to {
                    SummaryCard(
                        stringResource(R.string.recording_summary_energy),
                        if (rideEnergy.netWh <= 0f) "--" else "%.0f Wh".format(rideEnergy.netWh),
                        MaterialTheme.appColors.metricBattery,
                        Modifier.weight(1f)
                    )
                },
                "consumption" to {
                    SummaryCard(
                        stringResource(R.string.recording_summary_consumption),
                        // Distance can be zero on a trip that never moved, and a
                        // ride with no usable voltage rows has no energy either.
                        if (rideEnergy.netWh <= 0f || distanceKm <= 0.05f) "--"
                        else "%.0f Wh/%s".format(
                            rideEnergy.netWh / distanceKm /
                                com.eried.eucplanet.util.Units.distance(1f, distanceUnit),
                            distanceUnitLabel,
                        ),
                        MaterialTheme.appColors.metricBattery,
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
                "maxTorque" to {
                    SummaryCard(
                        stringResource(R.string.recording_summary_max_torque),
                        if (batteryStats.maxTorque.isNaN()) "--" else "%.1f Nm".format(batteryStats.maxTorque),
                        MaterialTheme.appColors.metricPosition,
                        Modifier.weight(1f)
                    )
                },
                "maxPhaseCurrent" to {
                    SummaryCard(
                        stringResource(R.string.recording_summary_max_phase_current),
                        if (batteryStats.maxPhaseCurrent.isNaN()) "--" else "%.0f A".format(batteryStats.maxPhaseCurrent),
                        MaterialTheme.appColors.metricPosition,
                        Modifier.weight(1f)
                    )
                },
            )

            // Effective tile order via the shared helper (see applyOrder): the
            // rider's saved order, with any newly added tile appearing at the end.
            // Keys come from the shared registry so the loading skeleton and the
            // real tiles can never disagree about what is on screen.
            val effectiveTileOrder = applyOrder(TILE_KEYS_DEFAULT, savedTileOrder)
            // Drift guard: a tile added to allTiles but not to TILE_KEYS_DEFAULT
            // would silently never render, and one removed would leave a gap in
            // the skeleton. Debug-only, since it is a developer mistake.
            if (com.eried.eucplanet.BuildConfig.DEBUG) {
                check(allTiles.map { it.first } == TILE_KEYS_DEFAULT) {
                    "TILE_KEYS_DEFAULT is out of sync with allTiles: " +
                        "${allTiles.map { it.first }} vs $TILE_KEYS_DEFAULT"
                }
            }
            val tilesByKey = allTiles.associateBy { it.first }
            val orderedTiles = effectiveTileOrder.mapNotNull { tilesByKey[it] }

            // The trim strip, defined once and placed by each orientation: under
            // the date line in portrait, at the head of the info column in
            // landscape where the date lives in the top bar instead.
            val trimBar: @Composable ColumnScope.() -> Unit = {
                // Explicit vertical enter/exit. The fully qualified call
                // resolves to the generic AnimatedVisibility overload, whose
                // default is fadeIn + expandIn - a clip growing from a CORNER,
                // so the trim bar appeared to wipe in from the left, sideways,
                // unlike every other reveal in the app. The ColumnScope
                // overload's vertical defaults never applied to a qualified
                // call. Same spec as the settings sections.
                androidx.compose.animation.AnimatedVisibility(
                    visible = showTrimBar && elapsedMs.isNotEmpty(),
                    enter = androidx.compose.animation.expandVertically(
                        animationSpec = androidx.compose.animation.core.tween(180),
                        expandFrom = Alignment.Top,
                    ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)),
                    exit = androidx.compose.animation.shrinkVertically(
                        animationSpec = androidx.compose.animation.core.tween(160),
                        shrinkTowards = Alignment.Top,
                    ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)),
                ) {
                    val full = elapsedMs.lastOrNull() ?: 0L
                    // The strip appears above wherever the rider has scrolled to,
                    // so everything below it shifts down and the control they just
                    // asked for is off-screen. Scroll it into view instead of
                    // leaving them to find it. The delay lets the strip lay out
                    // first, otherwise there are no bounds to bring into view.
                    val trimRequester = remember { BringIntoViewRequester() }
                    LaunchedEffect(Unit) {
                        delay(120)
                        runCatching { trimRequester.bringIntoView() }
                    }
                    Spacer(Modifier.height(4.dp))
                    TripTrimBar(
                        modifier = Modifier.bringIntoViewRequester(trimRequester),
                        durationMs = full,
                        startMs = trimRange?.first ?: 0L,
                        endMs = trimRange?.last ?: full,
                        onRange = { s, e ->
                            // Dragging back to the full span is "no trim", so the
                            // rest of the screen returns to the untrimmed path.
                            trimRange = if (s <= 0L && e >= full) null else s..e
                        },
                        onReset = { trimRange = null },
                        onEditExact = { showTrim = true },
                    )
                }
            }

            // Render the shown tiles in the rider's order, in rows of 3, padding a
            // short final row with spacers so every tile keeps the same width.
            val summaryCards: @Composable ColumnScope.() -> Unit = {
                val visibleTiles = orderedTiles.filter {
                    it.first !in hiddenTiles && (it.first !in EXTRA_TILE_KEYS || it.first in extraTiles)
                }
                visibleTiles.chunked(3).forEachIndexed { rowIndex, rowTiles ->
                    if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                    // Row height comes from its tallest tile, and every tile
                    // fills it (see SummaryCard): cards in one row are always
                    // level, and since each card reserves its two-line label
                    // space, toggling tiles in the customizer cannot nudge row
                    // heights as a wrapped label moves between rows.
                    Row(
                        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                        scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
                })
                add("battery" to {
                    ChartCard(stringResource(R.string.recording_chart_battery), dataPoints.map { it.battery.toFloat() },
                        MaterialTheme.appColors.metricVoltage, unitLabel = "%", minSpan = GraphScale.SPAN_BATTERY,
                        scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
                })
                add("temp" to {
                    ChartCard(stringResource(R.string.recording_chart_temp, tempUnitLabel),
                        dataPoints.map { com.eried.eucplanet.util.Units.temperature(it.temperature, tempUnit) },
                        MaterialTheme.appColors.metricTemp, unitLabel = tempUnitLabel, minSpan = tempMinSpan,
                        scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
                })
                add("voltage" to {
                    ChartCard(stringResource(R.string.recording_chart_voltage), dataPoints.map { it.voltage },
                        MaterialTheme.appColors.statusDanger, unitLabel = "V", minSpan = GraphScale.SPAN_VOLTAGE,
                        scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
                })
                if (dataPoints.any { !it.current.isNaN() }) {
                    add("current" to {
                        ChartCard(stringResource(R.string.recording_chart_current),
                            dataPoints.map { it.current },
                            MaterialTheme.appColors.metricVoltage, unitLabel = "A", minSpan = GraphScale.SPAN_CURRENT,
                            regenColor = MaterialTheme.appColors.metricBattery,
                            scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
                    })
                }
                if (dataPoints.any { !it.pwm.isNaN() }) {
                    add("pwm" to {
                        ChartCard(stringResource(R.string.recording_chart_pwm),
                            dataPoints.map { it.pwm },
                            MaterialTheme.appColors.metricTemp, unitLabel = "%", minSpan = GraphScale.SPAN_LOAD,
                            scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
                    })
                }
                // Torque and phase amps ship OFF, opt-in via the customizer
                // like every other extra graph. The data gate also skips the
                // all-zero columns from families that never report them, so
                // switching one on can never produce an empty card. Bipolar
                // like Current: positive drive, negative regen/brake.
                if ("torque" in extraCharts && dataPoints.any { !it.torque.isNaN() && it.torque != 0f }) {
                    add("torque" to {
                        ChartCard(stringResource(R.string.recording_chart_torque),
                            dataPoints.map { it.torque },
                            MaterialTheme.appColors.metricPosition, unitLabel = "Nm", minSpan = GraphScale.SPAN_TORQUE,
                            regenColor = MaterialTheme.appColors.metricBattery,
                            scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
                    })
                }
                if ("phaseCurrent" in extraCharts && dataPoints.any { !it.phaseCurrent.isNaN() && it.phaseCurrent != 0f }) {
                    add("phaseCurrent" to {
                        ChartCard(stringResource(R.string.recording_chart_phase_current),
                            dataPoints.map { it.phaseCurrent },
                            MaterialTheme.appColors.metricPosition, unitLabel = "A", minSpan = GraphScale.SPAN_PHASE_CURRENT,
                            regenColor = MaterialTheme.appColors.metricBattery,
                            scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
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
                            scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
                    })
                }
                if ("batteryEnvelope" in extraCharts && dataPoints.any { it.battery > 0 }) {
                    add("batteryEnvelope" to {
                        // Derived and deliberately stepped: one latched value
                        // per 30 s, following charge actually spent (coulomb-
                        // warped between the trip's real start and end
                        // battery) instead of load sag. Down riding, flat
                        // stopped, up on a sustained regen descent. Do not
                        // smooth it into a curve - the steps are the point.
                        val env = remember(dataPoints) {
                            val tMs = TripTrim.elapsedOffsets(dataPoints)
                            com.eried.eucplanet.util.BatteryEnvelope.compute(
                                FloatArray(tMs.size) { tMs[it] / 1000f },
                                FloatArray(dataPoints.size) { dataPoints[it].battery.toFloat() },
                                FloatArray(dataPoints.size) { dataPoints[it].current },
                            ).toList()
                        }
                        ChartCard(stringResource(R.string.recording_chart_battery_envelope),
                            env,
                            MaterialTheme.appColors.chartEnvelope, unitLabel = "%", minSpan = GraphScale.SPAN_BATTERY,
                            scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
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
                            scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
                    })
                }
                if ("currentSmooth" in extraCharts && dataPoints.any { !it.current.isNaN() }) {
                    add("currentSmooth" to {
                        ChartCard(stringResource(R.string.recording_chart_current_smooth),
                            Smoothing.movingAverage(dataPoints.map { it.current }, smoothWindow),
                            MaterialTheme.appColors.metricVoltage, unitLabel = "A", minSpan = GraphScale.SPAN_CURRENT,
                            regenColor = MaterialTheme.appColors.metricBattery,
                            scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
                    })
                }
                if ("pwmSmooth" in extraCharts && dataPoints.any { !it.pwm.isNaN() }) {
                    add("pwmSmooth" to {
                        ChartCard(stringResource(R.string.recording_chart_pwm_smooth),
                            Smoothing.movingAverage(dataPoints.map { it.pwm }, smoothWindow),
                            MaterialTheme.appColors.metricTemp, unitLabel = "%", minSpan = GraphScale.SPAN_LOAD,
                            scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
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
                            scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
                    })
                }
                if ("altitude" in extraCharts && dataPoints.any { it.altitude != 0f }) {
                    add("altitude" to {
                        ChartCard(stringResource(R.string.recording_chart_altitude),
                            dataPoints.map { it.altitude },
                            MaterialTheme.appColors.metricPosition, unitLabel = "m", minSpan = 20f,
                            scrubIndex = scrubIndex, onScrub = onScrub, window = chartWindow, onWindow = onWindow)
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
                "batteryRange" to stringResource(R.string.metric_chip_battery_range),
                "voltage" to stringResource(R.string.recording_summary_voltage),
                "maxTemp" to stringResource(R.string.recording_summary_max_temp),
                "maxPwm" to stringResource(R.string.recording_summary_max_pwm),
                "energy" to stringResource(R.string.recording_summary_energy),
                "consumption" to stringResource(R.string.recording_summary_consumption),
                "maxCurrent" to stringResource(R.string.recording_summary_max_current),
                "maxPower" to stringResource(R.string.recording_summary_max_power),
                "maxTorque" to stringResource(R.string.recording_summary_max_torque),
                "maxPhaseCurrent" to stringResource(R.string.recording_summary_max_phase_current),
            )
            val chartLabels: Map<String, String> = mapOf(
                "speed" to stringResource(R.string.recording_chart_speed, speedUnitLabel),
                "battery" to stringResource(R.string.recording_chart_battery),
                "batteryEnvelope" to stringResource(R.string.recording_chart_battery_envelope),
                "temp" to stringResource(R.string.recording_chart_temp, tempUnitLabel),
                "voltage" to stringResource(R.string.recording_chart_voltage),
                "current" to stringResource(R.string.recording_chart_current),
                "pwm" to stringResource(R.string.recording_chart_pwm),
                "torque" to stringResource(R.string.recording_chart_torque),
                "phaseCurrent" to stringResource(R.string.recording_chart_phase_current),
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
                    extraTiles = extraTiles,
                    hiddenCharts = hiddenCharts,
                    chartOrder = effectiveChartOrder,
                    chartLabels = chartLabels,
                    extraCharts = extraCharts,
                    onToggleTile = { key, hidden -> viewModel.setTileHidden(key, hidden) },
                    onToggleExtraTile = { key, on -> viewModel.setExtraTile(key, on) },
                    onToggleChart = { key, hidden -> viewModel.setChartHidden(key, hidden) },
                    onToggleExtraChart = { key, on -> viewModel.setExtraChart(key, on) },
                    onReorderTiles = { viewModel.setTileOrder(it) },
                    onReorderCharts = { viewModel.setChartOrder(it) },
                    canReset = hiddenTiles.isNotEmpty() || hiddenCharts.isNotEmpty() ||
                        extraCharts.isNotEmpty() || extraTiles.isNotEmpty() ||
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
                        // Landscape keeps the date range in the top bar, so the
                        // bar leads the scrolling column instead.
                        trimBar()
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
                        tripTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // Directly under the trip's own header, where the date range
                    // it narrows is already on screen, rather than up with the
                    // app bar.
                    trimBar()
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
    extraTiles: Set<String>,
    hiddenCharts: Set<String>,
    chartOrder: List<String>,
    chartLabels: Map<String, String>,
    extraCharts: Set<String>,
    onToggleTile: (String, Boolean) -> Unit,
    onToggleExtraTile: (String, Boolean) -> Unit,
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
                            // One list, two stores - same split as the graphs:
                            // tiles that ship on are tracked by what was HID,
                            // the opt-in ones by what was switched ON.
                            val isExtra = tileKey in EXTRA_TILE_KEYS
                            Switch(
                                checked = if (isExtra) tileKey in extraTiles
                                          else tileKey !in hiddenTiles,
                                onCheckedChange = {
                                    if (isExtra) onToggleExtraTile(tileKey, it)
                                    else onToggleTile(tileKey, !it)
                                },
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
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // One line, ellipsized. Reserving two label lines kept the cards
            // uniform but grew every tile; labels are written to fit one line
            // (keep copy short), and a locale that overflows ellipsizes
            // rather than growing its row.
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            // A fixed-height slot rather than font styling: the arrow in
            // range values ("91→84%") pulls a taller fallback font, and
            // neither lineHeight nor LineHeightStyle.Trim reliably caps a
            // fallback's line box. The slot is the plain value's natural line
            // (19.sp for 16.sp text) so cards sit at their old height and the
            // arrow's taller line box centers inside it instead of stretching
            // the row. Sized in sp so it grows with the rider's font scale.
            val valueSlot = with(LocalDensity.current) { 19.sp.toDp() }
            Box(Modifier.height(valueSlot), contentAlignment = Alignment.Center) {
                Text(value, fontSize = 16.sp, maxLines = 1,
                    fontWeight = FontWeight.Bold, color = color)
            }
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
internal fun extractExtraEvents(points: List<TripDataPoint>): List<TripExtraEvent> {
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
    "battery", "batterySmooth", "batteryEnvelope",
    "temp",
    "voltage",
    "current", "currentSmooth",
    "pwm", "pwmSmooth",
    "torque", "phaseCurrent",
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
/** How long a trip may take to load before the skeleton is shown. */
private const val SKELETON_REVEAL_MS = 120L
// While the open trip is the one still recording, re-read its (still-open) CSV
// this often so the charts and the map trace grow as new samples land. The CSV
// flushes every 10 rows, so the visible data trails the ride by under a flush;
// the live position dot on the map comes straight from GPS and moves smoothly
// regardless of this interval.
private const val LIVE_REFRESH_MS = 3_000L

/**
 * Stat tile keys, in default display order.
 *
 * Declared here as well as built inline with their content, so the loading
 * skeleton can lay out the rider's actual tiles before any trip data exists.
 * Visibility and order come from settings, not from the ride.
 */
private val TILE_KEYS_DEFAULT = listOf(
    "distance", "duration", "points", "topSpeed", "avgSpeed", "avgMoving",
    "battery", "batteryRange", "voltage", "maxTemp", "maxPwm",
    "energy", "consumption", "maxCurrent", "maxPower",
    "maxTorque", "maxPhaseCurrent",
)

/**
 * Stat tiles that ship OFF, opt-in via the customizer. Same reasoning as
 * [EXTRA_CHART_KEYS]: the tripHiddenTiles store records only what was HIDDEN, so
 * it can't express "new and off until asked for". These live in the inverted
 * tripExtraTiles store instead. `batteryRange` is the combined-trip-friendly
 * start->end readout; `energy` / `consumption` were previously defined but never
 * wired into the default order, so this also makes them reachable at last.
 */
private val EXTRA_TILE_KEYS = setOf(
    "batteryRange", "energy", "consumption", "maxTorque", "maxPhaseCurrent",
)

private val EXTRA_CHART_KEYS = setOf(
    "speedSmooth", "batterySmooth", "currentSmooth", "pwmSmooth", "power", "altitude",
    "torque", "phaseCurrent", "batteryEnvelope",
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
    // Clock time and elapsed for the scrubbed sample, shown as a tooltip on the
    // dot. Empty = no tooltip.
    scrubLabel: String = "",
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
    // byId resolves the ids this screen used to persist (SAT) onto the shared
    // ones, so a rider who last chose satellite still gets satellite.
    var mapType by rememberSaveable {
        mutableStateOf(
            com.eried.eucplanet.hud.protocol.MapLayers
                .byId(tripMapTypeSession ?: savedMapType.ifBlank { themeMapDefault })
                .id
        )
    }
    // The persisted value is served through an Eagerly-started flow, so it is
    // normally present by first composition; guard the rare case where it arrives
    // after. Only adopt it while the rider has not picked this session.
    LaunchedEffect(savedMapType) {
        val resolved = com.eried.eucplanet.hud.protocol.MapLayers.byId(savedMapType).id
        if (tripMapTypeSession == null && savedMapType.isNotBlank() && resolved != mapType) {
            mapType = resolved
        }
    }
    val onPick: (String) -> Unit = { mapType = it; tripMapTypeSession = it; onPersistMapType(it) }

    MapSurface(
        points = points, fadedPoints = fadedPoints, fadedSwitches = fadedSwitches,
        startIncluded = startIncluded, endIncluded = endIncluded,
        isLive = isLive, liveLat = liveLat, liveLon = liveLon,
        scrubLat = scrubLat, scrubLon = scrubLon, scrubLabel = scrubLabel,
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
                scrubLat = scrubLat, scrubLon = scrubLon, scrubLabel = scrubLabel,
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
    scrubLabel: String,
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
    // Same seven as the navigator and eucviewer, same order. No overlays
    // here: a recorded trip has no chargers or places to draw.
    // Straight from the registry: this list once said SAT while the tile table
    // said SATELLITE, so picking satellite silently fell back to plain OSM.
    val mapTypes = com.eried.eucplanet.hud.protocol.MapLayers.ALL.map { it.id }
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
            Box {
                var layerMenu by remember { mutableStateOf(false) }
                MapButton(
                    icon = Icons.Default.Layers,
                    desc = stringResource(R.string.nav_map_style),
                    onClick = { layerMenu = true },
                )
                DropdownMenu(
                    expanded = layerMenu,
                    onDismissRequest = { layerMenu = false },
                    containerColor = MaterialTheme.appColors.menuBackground
                ) {
                    RouteBuilderViewModel.MAP_LAYERS.forEach { layer ->
                        val id = layer.id
                        DropdownMenuItem(
                            leadingIcon = {
                                RadioButton(
                                    selected = id == mapType,
                                    onClick = { onMapTypeChange(id); layerMenu = false }
                                )
                            },
                            text = { Text(stringResource(layer.labelRes)) },
                            onClick = { onMapTypeChange(id); layerMenu = false }
                        )
                    }
                }
            }
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
    LaunchedEffect(scrubLat, scrubLon, scrubLabel, webView) {
        val wv = webView ?: return@LaunchedEffect
        if (scrubLat != null && scrubLon != null) {
            // Quoted: the label is built from file-supplied timestamps, so it is
            // never dropped into the call unescaped.
            val tip = org.json.JSONObject.quote(scrubLabel)
            wv.evaluateJavascript(
                "if(window.updateScrubPoint)updateScrubPoint($scrubLat,$scrubLon,$tip);", null)
        } else {
            wv.evaluateJavascript("if(window.clearScrubPoint)clearScrubPoint();", null)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MapButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
  /* Scrub tooltip: dark and compact so it labels the dot without covering the
     trace it is sitting on. Leaflet's default is a white box with an arrow. */
  .scrub-tip{
    background:rgba(20,22,26,0.92); color:#fff; border:none; border-radius:4px;
    padding:2px 7px; font-size:11px; font-weight:600; white-space:nowrap;
    box-shadow:0 1px 4px rgba(0,0,0,0.5);
  }
  .scrub-tip:before{ border-top-color:rgba(20,22,26,0.92); }
  /* Tile credit: required, but it should read as a footnote rather than a
     label. Leaflet's default is an opaque white box at 11px, which on a card
     this size is the loudest thing on the map. Dim and small, and moved off
     the bottom-right where the layer and fullscreen buttons sit. */
  .leaflet-control-attribution{
    background:transparent!important;
    color:rgba(255,255,255,0.88)!important;
    text-shadow:0 0 2px rgba(0,0,0,0.95),0 0 5px rgba(0,0,0,0.75)!important;
    font-size:8px!important;
    padding:2px 6px!important;
    /* Centred along the bottom edge: the map sits in a rounded card, and the
       middle of that edge is the one spot no corner curve and none of the
       buttons reach. Only the two upper corners are rounded, so it reads as a
       tab off the edge rather than a floating box. */
    position:fixed!important;
    left:50%!important;
    bottom:0!important;
    transform:translateX(-50%)!important;
    margin:0!important;
    /* One line, as wide as it needs: left:50% caps a fixed element's
       shrink-to-fit width at the remaining half of the viewport, so the
       credit word-wrapped whenever it was longer than half the card. */
    white-space:nowrap!important;
    width:max-content!important;
    pointer-events:none;
  }
</style>
</head><body>
<div id="map"></div>
<script>
  var coords=[$coordsJson];
  var fadedCoords=[$fadedCoordsJson];
  var map=L.map('map',{zoomControl:false,attributionControl:true});
  map.attributionControl.setPrefix('');
  map.attributionControl.setPosition('bottomleft');
  var baseLayer=null;
  var refLayer=null;
  // {r} asks the provider for its @2x tile on a high-density screen. Without it
  // a phone upscales a 256 px tile threefold or more, which is most of why the
  // map read as soft and short on detail. Esri's tiles carry no {r}, so it
  // expands to "" there and the URL is unchanged.
  // LIGHT is OpenStreetMap's own rendering, the same source Overlay Studio uses.
  // Carto's light_all was here before and is drawn from an OSM extract they
  // refresh on their own schedule: side by side on the same tile it loses the
  // street names, the POIs and most of the building detail, which is what made
  // this map look dated next to the Studio one.
  var MAP_LAYERS = ${com.eried.eucplanet.ui.navigator.mapLayersJson()};
  window.setMapType=function(t){
    if(baseLayer) map.removeLayer(baseLayer);
    if(refLayer){ map.removeLayer(refLayer); refLayer=null; }
    // One table for every map in the app, credits included: see MapLayers.
    var layer = MAP_LAYERS[t] || MAP_LAYERS['OSM'];
    var opts = {maxZoom:21, maxNativeZoom:layer.maxNative, attribution:layer.attr};
    if (layer.subs) opts.subdomains = layer.subs;
    if (layer.retina) opts.detectRetina = true;
    baseLayer=L.tileLayer(layer.url, opts).addTo(map);
    // Esri Canvas labels ride on a separate reference layer; keep it under
    // the route but over the base. Leaflet collapses the duplicate credit.
    if (layer.ref){ refLayer=L.tileLayer(layer.ref, opts).addTo(map); refLayer.bringToBack(); }
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
    // Grow the trace as the ride goes. The baked coords are pinned to the
    // open-time snapshot while recording (Kotlin stops feeding growth so the
    // page never reloads), so this live line carries every step after it. Seed
    // it from the last baked coord so it joins the existing trace rather than
    // starting detached.
    if (!livePath){
      var seed = coords.length ? [coords[coords.length-1], p] : [p];
      livePath = L.polyline(seed,{color:'#4FC3F7',weight:4,interactive:false}).addTo(map);
    } else {
      livePath.addLatLng(p);
    }
  };

  // Scrub marker API: a dot synced with the chart cursor. Pans into view only
  // if the point is off-screen, so scrubbing doesn't jerk the map around.
  var scrub=null;
  window.updateScrubPoint = function(lat, lon, label){
    var p = [lat, lon];
    if (!scrub){
      scrub = L.circleMarker(p,{radius:7,color:'#fff',weight:2,fillColor:'#FFC107',fillOpacity:1}).addTo(map);
      // Permanent: a finger is already busy dragging the chart, so there is no
      // second one free to hover or tap the dot for it.
      if (label) scrub.bindTooltip(label,{permanent:true,direction:'top',offset:[0,-6],className:'scrub-tip'});
    } else {
      scrub.setLatLng(p);
      if (label){
        if (scrub.getTooltip()) scrub.setTooltipContent(label);
        else scrub.bindTooltip(label,{permanent:true,direction:'top',offset:[0,-6],className:'scrub-tip'});
      } else if (scrub.getTooltip()) scrub.unbindTooltip();
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
    // Shared zoom window (fractions of the ride) and its updater. Two-finger
    // pinch/pan reports through [onWindow]; the default renders the full ride.
    window: ClosedFloatingPointRange<Float> = 0f..1f,
    onWindow: ((ClosedFloatingPointRange<Float>) -> Unit)? = null,
) {
    if (values.isEmpty()) return

    // Zoom windowing: shadow the inputs with the visible slice, so the whole
    // body below (bounds, drawing, scrub) simply works on what is on screen.
    // The y-axis re-fits the slice, which is what makes zooming useful.
    // Indices crossing the boundary are mapped back to the full-ride domain,
    // so the map marker and the other charts keep meaning the same moment.
    val n0 = values.size
    val fullView = (window.start <= 0f && window.endInclusive >= 1f) || n0 < 3
    val winA = if (fullView) 0 else (window.start * (n0 - 1)).roundToInt().coerceIn(0, n0 - 2)
    val winB = if (fullView) n0 - 1 else (window.endInclusive * (n0 - 1)).roundToInt().coerceIn(winA + 1, n0 - 1)
    @Suppress("NAME_SHADOWING") val values =
        if (fullView) values else values.subList(winA, winB + 1)
    @Suppress("NAME_SHADOWING") val overlays =
        if (fullView) overlays
        else overlays.map {
            if (it.values.size == n0) it.copy(values = it.values.subList(winA, winB + 1)) else it
        }
    @Suppress("NAME_SHADOWING") val scrubIndex =
        scrubIndex?.minus(winA)?.takeIf { it in 0..(winB - winA) }
    val onScrubRaw = onScrub
    @Suppress("NAME_SHADOWING") val onScrub: ((Int?) -> Unit)? =
        if (onScrubRaw == null) null else { i -> onScrubRaw(i?.plus(winA)) }
    val curWindow = rememberUpdatedState(window)

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
                // the rider still sees it (e.g. "0.0 - 35.0 (peak 80)").
                val rangeLabel = if (peak != null && peak > dataMax + 0.5f)
                    "%.1f - %.1f (peak %.0f)".format(dataMin, dataMax, peak)
                else
                    "%.1f - %.1f".format(dataMin, dataMax)
                // A zoom badge on the range label while a slice is shown.
                val zoomedLabel = if (fullView) rangeLabel
                else rangeLabel + "  \u00d7" + "%.1f".format(
                    1f / (window.endInclusive - window.start).coerceAtLeast(ChartWindow.MIN_SPAN))
                Text(zoomedLabel, fontSize = 11.sp,
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
                    .pointerInput(Unit) {
                        // Double-tap zooms back out to the full ride.
                        detectTapGestures(onDoubleTap = { onWindow?.invoke(0f..1f) })
                    }
                    .pointerInput(Unit) {
                        // Two fingers zoom and pan the shared window. One finger
                        // stays reserved for the page scroll and the long-press
                        // scrub above, so the three gestures never collide.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var sawMulti = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2 && onWindow != null) {
                                    sawMulti = true
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid()
                                    if (zoom != 1f || pan.x != 0f) {
                                        onWindow.invoke(
                                            ChartWindow.zoomPan(
                                                curWindow.value,
                                                zoom,
                                                (centroid.x / size.width).coerceIn(0f, 1f),
                                                pan.x / size.width,
                                            )
                                        )
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (pressed == 0) {
                                    break
                                } else if (sawMulti) {
                                    // Down to one finger after a pinch: end the
                                    // gesture instead of letting the leftover
                                    // finger scroll the page.
                                    event.changes.forEach { it.consume() }
                                }
                            }
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
    /** First valid battery reading (the session's true start %). Unlike
     *  [batteryMax] this survives a mid-trip charge or regen: for a combined
     *  trip that recharged between segments, the start is the earliest
     *  segment's start, not the highest sample. */
    val batteryStart: Int,
    /** Last valid battery reading before the end-of-trip cliff (true end %). */
    val batteryEnd: Int,
    /** Total percent drained = sum of downward steps across valid samples.
     *  Equals [batteryStart] - [batteryEnd] for a monotonic ride; for a
     *  combined trip with a mid-charge it counts the real energy used
     *  instead of pretending the recharge never happened. */
    val batteryDrained: Int,
    val voltageMax: Float,
    val voltageMin: Float,
    /** Peak PWM / motor load (%) over valid non-NaN points. NaN when the trip has no PWM data. */
    val maxPwm: Float,
    /** Peak signed current (A) over valid non-NaN points. NaN when the trip has no current data. */
    val maxCurrent: Float,
    /** Peak instantaneous power (W = voltage * current) over valid points with non-NaN current. NaN when no current data. */
    val maxPower: Float,
    /** Peak torque (Nm) over valid samples; NaN when the trip has none. */
    val maxTorque: Float,
    /** Peak phase current (A) over valid samples; NaN when the trip has none. */
    val maxPhaseCurrent: Float
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
        return TripBatteryStats(0, 0, 0, 0, 0, 0, 0f, 0f, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
    }

    val endIdx = trimEndIndex(points)
    val ridePoints = if (endIdx in 1 until points.size) points.subList(0, endIdx) else points

    val validBatteries = mutableListOf<Int>()
    val validVoltages = mutableListOf<Float>()
    var lastValidBattery: Int? = null
    // Session start/end and total drained, walked in time order alongside the
    // extremes. start = first valid sample, end = last valid sample (before the
    // cliff), drained = sum of downward steps so a mid-charge counts honestly.
    var firstValidBattery: Int? = null
    var drained = 0
    // Peak PWM / current / power over the same validity mask. Tracked as a
    // running max so a single walk feeds every maximum; NaN samples are skipped.
    var maxPwm = Float.NaN
    var maxCurrent = Float.NaN
    var maxPower = Float.NaN
    var maxTorque = Float.NaN
    var maxPhaseCurrent = Float.NaN

    for (p in ridePoints) {
        val valid = p.battery > 0 &&
            p.voltage > 0f &&
            (lastValidBattery == null || p.battery >= lastValidBattery!! - 10)
        if (valid) {
            if (firstValidBattery == null) firstValidBattery = p.battery
            lastValidBattery?.let { prev -> if (p.battery < prev) drained += prev - p.battery }
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
            // != 0f: families that never report these write zero columns,
            // and "max 0.0 Nm" would read as data where there is none.
            if (!p.torque.isNaN() && p.torque != 0f) {
                maxTorque = if (maxTorque.isNaN()) p.torque else maxOf(maxTorque, p.torque)
            }
            if (!p.phaseCurrent.isNaN() && p.phaseCurrent != 0f) {
                maxPhaseCurrent = if (maxPhaseCurrent.isNaN()) p.phaseCurrent else maxOf(maxPhaseCurrent, p.phaseCurrent)
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
            batteryStart = points.first().battery,
            batteryEnd = points.last().battery,
            batteryDrained = (points.first().battery - points.last().battery).coerceAtLeast(0),
            voltageMax = rawVoltMax,
            voltageMin = rawVoltMin,
            maxPwm = maxPwm,
            maxCurrent = maxCurrent,
            maxPower = maxPower,
            maxTorque = maxTorque,
            maxPhaseCurrent = maxPhaseCurrent
        )
    }

    val batMax = validBatteries.max()
    val batMin = validBatteries.min()
    return TripBatteryStats(
        batteryMax = batMax,
        batteryMin = batMin,
        batteryConsumption = (batMax - batMin).coerceAtLeast(0),
        batteryStart = firstValidBattery ?: batMax,
        batteryEnd = lastValidBattery ?: batMin,
        batteryDrained = drained,
        voltageMax = validVoltages.max(),
        voltageMin = validVoltages.min(),
        maxPwm = maxPwm,
        maxCurrent = maxCurrent,
        maxPower = maxPower,
        maxTorque = maxTorque,
        maxPhaseCurrent = maxPhaseCurrent
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
 * Placeholder shown while a trip's CSV is being read.
 *
 * Mirrors the real layout, a map above stat tiles above chart cards, so the
 * screen does not jump when the data lands. A slow shimmer travels across the
 * blocks to say "working", which a static grey layout does not.
 *
 * Deliberately not the "no data" message: that means the trip is empty, and
 * showing it while a perfectly good long ride loads reads as an error.
 */
@Composable
private fun TripDetailSkeleton(
    /** Visible stat tiles, laid out in rows of three like the real ones. */
    tileCount: Int,
    /** Visible graph cards. */
    chartCount: Int,
    modifier: Modifier = Modifier,
) {
    val shimmer = rememberInfiniteTransition(label = "trip-skeleton")
    // Travels well past 1f so there is a pause between sweeps rather than a
    // relentless strobe.
    val progress by shimmer.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "trip-skeleton-sweep",
    )
    val base = MaterialTheme.appColors.textPrimary.copy(alpha = 0.07f)
    val highlight = MaterialTheme.appColors.textPrimary.copy(alpha = 0.14f)

    @Composable
    fun Block(height: Dp, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
        BoxWithConstraints(modifier.height(height).clip(RoundedCornerShape(corner))) {
            val w = with(LocalDensity.current) { maxWidth.toPx() }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(base, highlight, base),
                            startX = (progress - 0.4f) * w,
                            endX = (progress + 0.4f) * w,
                        )
                    )
            )
        }
    }

    // Same order and metrics as the real portrait body: the date line, the stat
    // tiles in rows of three, the 250dp map, then one card per graph.
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Block(18.dp, Modifier.fillMaxWidth(0.55f), corner = 6.dp)   // date range
        Spacer(Modifier.height(12.dp))

        // Tiles: 10dp corners and the same 8dp gaps as SummaryCard, with a short
        // final row padded by spacers so the widths stay uniform.
        // The height is the real card's, built the same way: 10dp padding top
        // and bottom, an 11sp one-line label (a ~13sp line box), and the 19sp
        // value slot - in sp so the skeleton follows the rider's font scale
        // exactly like the cards, and the screen does not shift when the data
        // lands.
        val tileHeight = 20.dp + with(LocalDensity.current) { 32.sp.toDp() }
        val rows = (tileCount + 2) / 3
        repeat(rows) { row ->
            if (row > 0) Spacer(Modifier.height(8.dp))
            val inRow = minOf(3, tileCount - row * 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(inRow) { Block(tileHeight, Modifier.weight(1f), corner = 10.dp) }
                repeat(3 - inRow) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(12.dp))
        Block(14.dp, Modifier.fillMaxWidth(0.22f), corner = 6.dp)   // "Route" caption
        Spacer(Modifier.height(4.dp))
        Block(250.dp, Modifier.fillMaxWidth())                      // map

        Spacer(Modifier.height(16.dp))
        repeat(chartCount) { i ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Block(150.dp, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Top-bar funnel, carrying two states that are genuinely independent: whether
 * the trim bar is open, and whether a trim is actually applied. A trim survives
 * closing the bar, so one signal cannot stand for both.
 *
 * Open shows as a selected container behind the icon, the standard toggle
 * appearance. Applied shows as a filled glyph plus a tint, so the icon still
 * says "this trip is filtered" on its own once the bar is closed, and says it
 * by shape as well as by colour.
 *
 * Material has no funnel-with-marker glyph, and a Badge dot was tried here: at
 * top-bar size it touches the funnel's edge in the same colour and reads as a
 * lump on the glyph rather than a marker. Filled against outlined is the
 * clearer signal.
 */
@Composable
private fun TrimAction(trimmed: Boolean, open: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val appColors = MaterialTheme.appColors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (open) appColors.primary.copy(alpha = 0.15f) else Color.Transparent,
            contentColor = if (trimmed || open) appColors.primary else LocalContentColor.current,
            // Custom contentColor above replaces the defaults factory's
            // disabled derivation, so grey out explicitly like a stock button.
            disabledContentColor = LocalContentColor.current.copy(alpha = 0.38f),
        ),
    ) {
        Icon(
            if (trimmed) Icons.Filled.FilterAlt else Icons.Outlined.FilterAlt,
            contentDescription = stringResource(R.string.trip_trim),
        )
    }
}
