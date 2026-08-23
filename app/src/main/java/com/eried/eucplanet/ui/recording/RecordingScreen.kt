package com.eried.eucplanet.ui.recording

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.foundation.layout.imePadding
import com.eried.eucplanet.ui.common.LocalSnackbar
import com.eried.eucplanet.ui.common.LocalSnackbarScope
import com.eried.eucplanet.ui.common.showSnackbar as showSnackbarLocal
import kotlinx.coroutines.launch
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.common.InfoHint
import com.eried.eucplanet.ui.theme.appColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onBack: () -> Unit,
    onViewTrip: ((TripRecord) -> Unit)? = null,
    onOpenBackupSettings: (() -> Unit)? = null,
    onViewOnline: ((Long) -> Unit)? = null,
    onReplayTrip: ((Long) -> Unit)? = null,
    viewModel: RecordingViewModel = hiltViewModel()
) {
    val recording by viewModel.recording.collectAsState()
    val pendingTripId by viewModel.pendingTripId.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val liveTripKm by viewModel.liveTripDistanceKm.collectAsState()
    val folderConfigured by viewModel.folderConfigured.collectAsState()
    val dropboxLinked by viewModel.dropboxLinked.collectAsState()
    val distanceUnit by viewModel.distanceUnit.collectAsState()
    val distanceUnitLabel = com.eried.eucplanet.util.Units.distanceUnit(distanceUnit)
    val gpsFix by viewModel.gpsFix.collectAsState()
    val locationGranted by viewModel.locationPermissionGranted.collectAsState()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (viewModel.refreshLocationPermission()) viewModel.startGpsPreview()
    }

    var showClearDialog by remember { mutableStateOf(false) }
    var showManageMenu by remember { mutableStateOf(false) }
    var tripToDelete by remember { mutableStateOf<TripRecord?>(null) }
    // Trip whose tools sheet is open, and which tool it drilled into.
    var tripForTools by remember { mutableStateOf<TripRecord?>(null) }
    var renameToolTrip by remember { mutableStateOf<TripRecord?>(null) }
    var wheelToolTrip by remember { mutableStateOf<TripRecord?>(null) }
    var splitToolTrip by remember { mutableStateOf<TripRecord?>(null) }
    var combineToolTrip by remember { mutableStateOf<TripRecord?>(null) }
    var tripToShare by remember { mutableStateOf<TripRecord?>(null) }
    val highlightedTripIds by viewModel.highlightedTripIds.collectAsState()
    val canArchiveTrips by viewModel.canArchiveTrips.collectAsState()
    val listState = rememberLazyListState()

    // Shared snackbar host so status icons / ViewModel auto-stop toasts use
    // the same surface as the rest of the app instead of the native Android
    // Toast (which clips behind the keyboard and breaks our Snackbar
    // convention, see ui/common/SnackbarLocals.kt).
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.toasts.collect { msg ->
            snackbarScope.launch { snackbar.showSnackbar(msg) }
        }
    }

    // Block back navigation while importing
    BackHandler(enabled = importing) { /* swallow */ }

    // Keep GPS warm while on the recording screen so the user can see fix status
    DisposableEffect(Unit) {
        viewModel.refreshLocationPermission()
        viewModel.startGpsPreview()
        onDispose { viewModel.stopGpsPreview() }
    }

    // Auto-scroll to first item (most recent trip) when recording state changes
    LaunchedEffect(recording, trips.size) {
        if (trips.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    // Bring a freshly made trip into view. A tint nobody can see is no help,
    // and a split or combine lands its results in date order, which is often
    // well down a long list.
    LaunchedEffect(highlightedTripIds, trips) {
        if (highlightedTripIds.isEmpty()) return@LaunchedEffect
        val first = trips.indexOfFirst { it.id in highlightedTripIds }
        if (first >= 0) listState.animateScrollToItem(first)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importTrips(uri) { }
        }
    }

    if (showClearDialog) {
        var archiveAll by remember(showClearDialog) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            shape = RoundedCornerShape(12.dp),
            title = { Text(stringResource(R.string.recording_clear_all_title)) },
            text = {
                Column {
                    // The warning has to match the box below it: with
                    // archiving on, nothing is destroyed and "cannot be
                    // undone" would be a lie.
                    Text(
                        stringResource(
                            if (archiveAll) R.string.recording_clear_all_body_archive
                            else R.string.recording_clear_all_body
                        )
                    )
                    if (canArchiveTrips) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.appColors.divider)
                    Spacer(Modifier.height(4.dp))
                    ArchiveChoiceRow(
                        checked = archiveAll,
                        title = stringResource(R.string.recording_delete_archive),
                        desc = stringResource(R.string.recording_delete_archive_desc),
                        onCheckedChange = { archiveAll = it },
                    )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllTrips(archiveAll) { showClearDialog = false }
                }, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_delete_all), color = MaterialTheme.appColors.statusDanger) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    tripToShare?.let { trip ->
        val dropboxLinked by viewModel.dropboxLinked.collectAsState()
        TripActionDialog(
            onShareFile = { viewModel.shareTrip(trip) },
            onViewOnline = { onViewOnline?.invoke(trip.id) },
            onReplay = { onReplayTrip?.invoke(trip.id) },
            onDismiss = { tripToShare = null },
            dropboxLinked = dropboxLinked,
            onShareViaDropbox = { viewModel.shareViaDropbox(trip) },
            onInspectOnline = { viewModel.inspectOnline(trip) },
        )
    }

    tripForTools?.let { trip ->
        TripToolsDialog(
            trip = trip,
            onDismiss = { tripForTools = null },
            onRename = { renameToolTrip = trip },
            onChangeWheel = { wheelToolTrip = trip },
            onSplit = { splitToolTrip = trip },
            onCombine = { combineToolTrip = trip },
        )
    }

    renameToolTrip?.let { trip ->
        RenameTripDialog(
            currentName = trip.customName,
            onConfirm = { name ->
                viewModel.renameTrip(trip, name)
                renameToolTrip = null
            },
            onDismiss = { renameToolTrip = null },
        )
    }

    wheelToolTrip?.let { trip ->
        // Loaded on open rather than held in state: the picker is rare and the
        // list is tiny, so there is nothing to gain from keeping it warm.
        var known by remember(trip.id) {
            mutableStateOf<List<com.eried.eucplanet.data.repository.WheelChoice>?>(null)
        }
        LaunchedEffect(trip.id) { known = viewModel.knownWheels() }
        ChangeWheelDialog(
            knownWheels = known.orEmpty(),
            currentWheel = com.eried.eucplanet.data.repository.WheelChoice.fromJson(trip.wheelMetaJson),
            onConfirm = { wheel ->
                viewModel.changeTripWheel(trip, wheel)
                wheelToolTrip = null
            },
            onDismiss = { wheelToolTrip = null },
        )
    }

    splitToolTrip?.let { trip ->
        var cuts by remember(trip.id) {
            mutableStateOf<List<com.eried.eucplanet.data.repository.TripSplitDetector.Cut>?>(null)
        }
        LaunchedEffect(trip.id) { cuts = viewModel.detectSplitPoints(trip) }
        // Only opens once the scan has run, so the rider never sees an empty
        // list that is about to fill in.
        cuts?.let { found ->
            SplitTripDialog(
                cuts = found,
                formatElapsed = { com.eried.eucplanet.util.Units.humanDuration(it / 1000) },
                tripStartMs = trip.startTime,
                canArchive = canArchiveTrips,
                onConfirm = { chosen, archiveSource ->
                    viewModel.splitTrip(trip, chosen, archiveSource)
                    splitToolTrip = null
                },
                onDismiss = { splitToolTrip = null },
            )
        }
    }

    combineToolTrip?.let { trip ->
        val dateFmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Locale.getDefault()) }
        CombineTripsDialog(
            anchor = trip,
            // Finished trips only: a recording still being written has no end and
            // its CSV is still open.
            trips = trips.filter { it.endTime != null || it.id == trip.id },
            // Date and time alone are ambiguous: the label is minute-precision,
            // so two rides in the same minute, or two on the same commute, read
            // identically in a dropdown. The duration tells them apart.
            label = { t ->
                val secs = ((t.endTime ?: t.startTime) - t.startTime) / 1000
                val dur = com.eried.eucplanet.util.Units.humanDuration(secs)
                "${dateFmt.format(Date(t.startTime))}  ·  $dur"
            },
            wheelOf = { t ->
                t.wheelMetaJson
                    ?.let { runCatching { org.json.JSONObject(it).optString("ble_name") }.getOrNull() }
            },
            canArchive = canArchiveTrips,
            onConfirm = { chosen, archiveSources ->
                viewModel.combineRange(chosen, archiveSources)
                combineToolTrip = null
            },
            onDismiss = { combineToolTrip = null },
        )
    }

    if (tripToDelete != null) {
        var archiveBackups by remember(tripToDelete) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { tripToDelete = null },
            shape = RoundedCornerShape(12.dp),
            title = { Text(stringResource(R.string.recording_delete_trip_title)) },
            text = {
                Column {
                    // Same rule as delete-all: the question has to match the
                    // box under it, or it reads as if the backup goes too.
                    Text(
                        stringResource(
                            if (canArchiveTrips && archiveBackups)
                                R.string.recording_delete_trip_body_archive
                            else R.string.recording_delete_trip_body
                        )
                    )
                    if (canArchiveTrips) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.appColors.divider)
                    Spacer(Modifier.height(4.dp))
                    ArchiveChoiceRow(
                        checked = archiveBackups,
                        title = stringResource(R.string.recording_delete_archive),
                        desc = stringResource(R.string.recording_delete_archive_desc),
                        onCheckedChange = { archiveBackups = it },
                    )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTrip(tripToDelete!!, archiveBackups)
                    tripToDelete = null
                }, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_delete), color = MaterialTheme.appColors.statusDanger) }
            },
            dismissButton = {
                TextButton(onClick = { tripToDelete = null }, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    CompositionLocalProvider(
        LocalSnackbar provides snackbar,
        LocalSnackbarScope provides snackbarScope
    ) {
    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar, modifier = Modifier.imePadding()) {
                androidx.compose.material3.Snackbar(
                    it,
                    containerColor = MaterialTheme.appColors.snackbarBackground,
                    contentColor = MaterialTheme.appColors.snackbarText,
                    actionContentColor = MaterialTheme.appColors.snackbarAction
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recording_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !importing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showManageMenu = true }, enabled = !importing) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_manage))
                    }
                    DropdownMenu(
                        expanded = showManageMenu,
                        onDismissRequest = { showManageMenu = false },
                        shape = RoundedCornerShape(12.dp),
                        containerColor = MaterialTheme.appColors.menuBackground
                    ) {
                        if (onOpenBackupSettings != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_trip_backup_options)) },
                                onClick = {
                                    showManageMenu = false
                                    onOpenBackupSettings()
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.appColors.divider)
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_import)) },
                            onClick = {
                                showManageMenu = false
                                importLauncher.launch(arrayOf(
                                    "application/octet-stream",
                                    "application/zip",
                                    "text/csv",
                                    "text/comma-separated-values",
                                    "*/*"
                                ))
                            }
                        )
                        if (trips.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_export_all)) },
                                onClick = {
                                    showManageMenu = false
                                    viewModel.exportAllAsZip()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recording_clear_all_menu), color = MaterialTheme.appColors.statusDanger) },
                                onClick = {
                                    showManageMenu = false
                                    showClearDialog = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (!importing) {
                FloatingActionButton(
                    onClick = { viewModel.toggleRecording() },
                    containerColor = MaterialTheme.appColors.statusDanger
                ) {
                    Icon(
                        if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = if (recording) stringResource(R.string.action_stop) else stringResource(R.string.action_record),
                        tint = MaterialTheme.colorScheme.background
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // GPS status indicator (or permission prompt)
            if (!locationGranted) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.LocationOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.recording_location_permission_needed),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        Text(
                            stringResource(R.string.recording_location_permission_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = {
                            locationPermissionLauncher.launch(arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            ))
                        }) {
                            Text(stringResource(R.string.action_grant_permission))
                        }
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    Icon(
                        if (gpsFix) Icons.Default.LocationOn else Icons.Default.LocationOff,
                        contentDescription = null,
                        tint = if (gpsFix) MaterialTheme.appColors.statusGood else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(
                            if (gpsFix) R.string.recording_gps_locked
                            else R.string.recording_gps_waiting
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (gpsFix) MaterialTheme.appColors.statusGood else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Import progress bar
            if (importing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }

            // General stats card
            if (trips.isNotEmpty()) {
                val totalKm = trips.sumOf { it.distanceKm.toDouble() }.toFloat()
                val totalTimeSec = trips.sumOf {
                    ((it.endTime ?: it.startTime) - it.startTime) / 1000
                }
                val totalHours = totalTimeSec / 3600
                val totalMins = (totalTimeSec % 3600) / 60

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatItem(stringResource(R.string.recording_stat_trips), "${trips.size}")
                        StatItem(
                            stringResource(R.string.recording_stat_distance),
                            "%.1f %s".format(com.eried.eucplanet.util.Units.distance(totalKm, distanceUnit), distanceUnitLabel)
                        )
                        StatItem(stringResource(R.string.recording_stat_time), "${totalHours}h ${totalMins}m")
                    }
                }
            }

            if (trips.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                InfoHint(text = stringResource(R.string.recording_empty))
            } else {
                // The currently recording trip has endTime == null
                val recordingTripId = if (recording) trips.firstOrNull { it.endTime == null }?.id else null

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    items(trips, key = { it.id }) { trip ->
                        val isRecordingTrip = trip.id == recordingTripId
                        val isPendingTrip = trip.id == pendingTripId
                        TripCard(
                            trip = trip,
                            isRecording = isRecordingTrip,
                            isPending = isPendingTrip,
                            isJustMade = trip.id in highlightedTripIds,
                            liveDistanceKm = if (isRecordingTrip) liveTripKm else null,
                            distanceUnit = distanceUnit,
                            onView = { onViewTrip?.invoke(trip) },
                            onTools = { tripForTools = trip },
                            onShare = { tripToShare = trip },
                            onDelete = { tripToDelete = trip },
                            onRetryOnline = { viewModel.retryOnlineUploads() },
                            onRecheckOnline = { viewModel.recheckHeldTrip(it) },
                            folderConfigured = folderConfigured,
                            dropboxLinked = dropboxLinked,
                            onRetryBackup = { viewModel.retryBackup(it) },
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TripCard(
    trip: TripRecord,
    isRecording: Boolean,
    isPending: Boolean,
    /** Just produced by a split or a combine, so the list can point at it. */
    isJustMade: Boolean = false,
    liveDistanceKm: Float?,
    distanceUnit: String,
    onView: () -> Unit,
    onTools: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetryOnline: () -> Unit = {},
    /** Re-ask the server for a held trip's verdict (it can change after upload). */
    onRecheckOnline: (TripRecord) -> Unit = {},
    /** Which backups the rider actually has, so the row only reports on those. */
    folderConfigured: Boolean = false,
    dropboxLinked: Boolean = false,
    onRetryBackup: (TripRecord) -> Unit = {},
) {
    val distanceUnitLabel = com.eried.eucplanet.util.Units.distanceUnit(distanceUnit)
    val dateFormat = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Locale.getDefault())
    val disabledColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    // Live ticking elapsed time for the recording trip
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (isRecording) {
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    // Fades out rather than snapping off, so a rider glancing back still sees
    // it settling and knows which row it was.
    val justMadeTint by animateColorAsState(
        targetValue = if (isJustMade) MaterialTheme.appColors.primary.copy(alpha = 0.22f)
            else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 600),
        label = "justMadeTint",
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isRecording) MaterialTheme.appColors.statusDanger.copy(alpha = 0.15f)
                             else justMadeTint
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(onClick = onView)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecording) {
                Icon(Icons.Default.FiberManualRecord, null,
                    tint = MaterialTheme.appColors.statusDanger, modifier = Modifier
                        .size(16.dp)
                        .padding(end = 4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Rider's custom name when set, otherwise the ride's date.
                    trip.customName?.takeIf { it.isNotBlank() }
                        ?: dateFormat.format(Date(trip.startTime)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isRecording) MaterialTheme.appColors.statusDanger else MaterialTheme.colorScheme.onSurface
                )
                if (isRecording) {
                    val elapsed = (now - trip.startTime) / 1000
                    val km = liveDistanceKm ?: 0f
                    Text(
                        "%.1f %s | %s".format(
                            com.eried.eucplanet.util.Units.distance(km, distanceUnit),
                            distanceUnitLabel, com.eried.eucplanet.util.Units.humanDuration(elapsed)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.appColors.statusDanger
                    )
                } else {
                    val duration = ((trip.endTime ?: trip.startTime) - trip.startTime) / 1000
                    Text(
                        "%.1f %s | %s".format(
                            com.eried.eucplanet.util.Units.distance(trip.distanceKm, distanceUnit),
                            distanceUnitLabel, com.eried.eucplanet.util.Units.humanDuration(duration)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Status icon slot, same position as the green upload-success tick.
            // Orange "pending" icon covers two phases: the discard-grace window
            // (isPending=true) and the brief in-flight period after grace while the
            // sync worker is uploading (uploadStatus=1). Without this, there is a
            // visible gap between the orange icon disappearing and the green tick
            // appearing once the upload completes. Recording trips show neither.
            // Single combined status. Online is the headline (it supersedes the folder
            // tick / a lagging folder backup); only the discard-grace window outranks it.
            when {
                isRecording -> {}                                                   // no status while recording
                isPending -> PendingStatusIcon()                                    // discard-grace window
                else -> TripStatusIcon(
                    trip, folderConfigured, dropboxLinked,
                    onRetryBackup = { onRetryBackup(trip) },
                    onRetryOnline = onRetryOnline,
                    onRecheckOnline = { onRecheckOnline(trip) },
                )
            }
            // Tools. This slot used to hold a "view" eye, which did exactly what
            // tapping the row already does, so it was a second door to the same
            // place. Tools (change wheel, split, combine) has nowhere else to go.
            IconButton(onClick = onTools, enabled = !isRecording) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = stringResource(R.string.trip_tools_title),
                    tint = if (isRecording) disabledColor
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Share, uses the local CSV which exists from the moment recording stops,
            // independent of backup state. Always available except while actively
            // recording (CSV writer is open).
            IconButton(onClick = onShare, enabled = !isRecording) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share),
                    tint = if (isRecording) disabledColor
                           else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Delete, same confirmation dialog whether the trip is in the discard
            // grace window or already finalized. TripRepository.deleteTrip cancels
            // the pending grace if the deleted trip is the pending one, so the dialog
            // path is the only place that asks the user to confirm.
            IconButton(onClick = onDelete, enabled = !isRecording) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete),
                    tint = if (isRecording) disabledColor
                           else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PendingStatusIcon() {
    val msg = stringResource(R.string.discard_trip_pending_label)
    val snackbar = LocalSnackbar.current
    val scope = LocalSnackbarScope.current
    IconButton(onClick = { showSnackbarLocal(snackbar, scope, msg) }) {
        // Three plain dots. The filled Pending circle read as one more cloud
        // state; Erwin picked this from the full candidate lineup instead.
        Icon(Icons.Default.MoreHoriz, contentDescription = msg, tint = MaterialTheme.appColors.statusWarn,
            modifier = Modifier.size(20.dp))
    }
}

/**
 * One icon for everything that happens to a trip after it is saved: the
 * rider's own backups (folder, Dropbox) and the public leaderboard.
 *
 * One, deliberately. The first version gave backups their own icon next to
 * the leaderboard's, and the row grew a second cloud - a control for every
 * destination instead of an answer to the rider's actual question, which is
 * "is this trip taken care of?". Red if anything failed (tap fixes it),
 * orange if anything is still moving (tap nudges it), green when everything
 * this trip is meant to reach has it. Tapping green says where it stands,
 * backup time and leaderboard verdict together, in one message.
 *
 * Failures outrank progress, and the tap acts on the worst thing showing.
 */
@Composable
private fun TripStatusIcon(
    trip: TripRecord,
    folderConfigured: Boolean,
    dropboxLinked: Boolean,
    onRetryBackup: () -> Unit,
    onRetryOnline: () -> Unit,
    onRecheckOnline: () -> Unit,
) {
    val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Locale.getDefault()) }

    // The rider's own backups. These, and only these, pick the icon's color:
    // the question the cloud answers is "is this ride safe", and a ride is
    // safe when a backup holds it. The leaderboard has its own opinions - a
    // months-old "held for review" among them - and letting those tint the
    // icon painted whole pages of properly backed-up trips orange.
    val backupFailed = (folderConfigured && trip.uploadStatus == 3) ||
        (dropboxLinked && trip.dropboxStatus == 3)
    // Only an active upload counts as waiting. uploadStatus 4 is a trip that
    // CAME from Dropbox: a backup already holds it by definition, and the
    // folder mirror catching up quietly is not something to warn about.
    val backupWaiting = (folderConfigured && trip.uploadStatus == 1) ||
        (dropboxLinked && trip.dropboxStatus == 1)
    val backupAt = (trip.dropboxUploadedAt ?: trip.uploadedAt)?.let { fmt.format(Date(it)) }
    val backupHeld = trip.uploadStatus == 2 || trip.uploadStatus == 4 ||
        trip.dropboxStatus == 2 || backupAt != null

    // The leaderboard: message and tap behaviour only, never the color.
    val settled = trip.eucstatsStatus == 2
    val flagged = settled && trip.eucstatsValidation == "flagged"
    val rejected = settled && trip.eucstatsValidation == "rejected"
    val onlineDone = settled && !flagged && !rejected

    // Nothing configured, nothing sent, nothing to say.
    if (!folderConfigured && !dropboxLinked && trip.eucstatsStatus == 0 && !backupHeld) return

    // Green is a promise - a backup HOLDS this trip - so it is only shown
    // when one does. The first cut fell through to green whenever nothing was
    // failing or in flight, which put a green cloud on trips whose own tap
    // message said "Not backed up yet". A trip in that state gets a muted
    // cloud instead, and tapping it starts the backup.
    // The leaderboard's two FINAL bad endings warrant a color of their own:
    // a failed upload (the tap retries it) and a rejection. Yellow, not red -
    // the ride itself is safe in a backup, something just wants attention.
    // Interim pipeline states still color nothing.
    val onlineProblem = trip.eucstatsStatus == 3 || rejected
    val icon = when {
        backupFailed -> Icons.Default.CloudOff
        backupWaiting -> Icons.Default.CloudQueue
        onlineProblem -> Icons.Default.Cloud
        backupHeld -> Icons.Default.CloudDone
        else -> Icons.Default.Cloud
    }
    val tint = when {
        backupFailed -> MaterialTheme.appColors.statusDanger
        backupWaiting || onlineProblem -> MaterialTheme.appColors.statusWarn
        backupHeld -> MaterialTheme.appColors.statusGood
        else -> MaterialTheme.appColors.textSecondary
    }
    // The whole story in one message: each part only speaks when it has
    // something to say, so a trip with no leaderboard life reads as before.
    val parts = mutableListOf<String>()
    parts += when {
        backupFailed -> stringResource(R.string.backup_status_failed)
        backupWaiting -> stringResource(R.string.backup_status_pending)
        backupAt != null -> stringResource(R.string.cloud_uploaded_on, backupAt)
        else -> stringResource(R.string.cloud_not_uploaded)
    }
    // Final leaderboard states only. "Uploading" and "held for automated
    // check" are the pipeline's business, not the rider's: a hold months old
    // reads as a problem when it is just a verdict nobody re-asked for.
    // Tapping a held trip still re-asks silently, so stale holds clear
    // themselves without ever being announced.
    when {
        rejected -> parts += stringResource(R.string.online_status_rejected)
        trip.eucstatsStatus == 3 -> parts += stringResource(R.string.online_status_failed)
        onlineDone -> parts += stringResource(R.string.online_status_shared)
    }
    val msg = parts.joinToString(separator = "\n")

    val snackbar = LocalSnackbar.current
    val scope = LocalSnackbarScope.current
    IconButton(onClick = {
        when {
            // A backup problem is the rider's to fix, so the tap acts on it.
            // "Not backed up yet" is one of those problems: the tap sends it.
            backupFailed || backupWaiting -> onRetryBackup()
            !backupHeld && (folderConfigured || dropboxLinked) -> onRetryBackup()
            // A failed leaderboard upload of an original ride can be retried.
            trip.eucstatsStatus == 3 -> onRetryOnline()
            // One tap, one toast. The recheck that used to ride along here
            // posted two more toasts of its own ("Checking with the
            // leaderboard...", then the verdict), so tapping a held trip
            // produced a three-message sequence about a pipeline the rider
            // never asked after. A stale hold can stay stale; it colors
            // nothing and says nothing.
            else -> showSnackbarLocal(snackbar, scope, msg)
        }
    }) {
        Icon(icon, contentDescription = msg, tint = tint, modifier = Modifier.size(20.dp))
    }
}

