package com.eried.eucplanet.ui.recording

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
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
    var tripToShare by remember { mutableStateOf<TripRecord?>(null) }
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

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importTrips(uri) { }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.recording_clear_all_title)) },
            text = { Text(stringResource(R.string.recording_clear_all_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllTrips { showClearDialog = false }
                }) { Text(stringResource(R.string.action_delete_all), color = MaterialTheme.appColors.statusDanger) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    tripToShare?.let { trip ->
        TripActionDialog(
            onShareFile = { viewModel.shareTrip(trip) },
            onViewOnline = { onViewOnline?.invoke(trip.id) },
            onReplay = { onReplayTrip?.invoke(trip.id) },
            onDismiss = { tripToShare = null }
        )
    }

    if (tripToDelete != null) {
        AlertDialog(
            onDismissRequest = { tripToDelete = null },
            title = { Text(stringResource(R.string.recording_delete_trip_title)) },
            text = { Text(stringResource(R.string.recording_delete_trip_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTrip(tripToDelete!!)
                    tripToDelete = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.appColors.statusDanger) }
            },
            dismissButton = {
                TextButton(onClick = { tripToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
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
                            liveDistanceKm = if (isRecordingTrip) liveTripKm else null,
                            distanceUnit = distanceUnit,
                            onView = { onViewTrip?.invoke(trip) },
                            onShare = { tripToShare = trip },
                            onDelete = { tripToDelete = trip },
                            onRetryOnline = { viewModel.retryOnlineUploads() }
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
    liveDistanceKm: Float?,
    distanceUnit: String,
    onView: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetryOnline: () -> Unit = {}
) {
    val distanceUnitLabel = com.eried.eucplanet.util.Units.distanceUnit(distanceUnit)
    val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
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

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isRecording) MaterialTheme.appColors.statusDanger.copy(alpha = 0.15f)
                             else MaterialTheme.colorScheme.surfaceVariant
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
                    dateFormat.format(Date(trip.startTime)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isRecording) MaterialTheme.appColors.statusDanger else MaterialTheme.colorScheme.onSurface
                )
                if (isRecording) {
                    val elapsed = (now - trip.startTime) / 1000
                    val minutes = elapsed / 60
                    val seconds = elapsed % 60
                    val km = liveDistanceKm ?: 0f
                    Text(
                        "%.1f %s | %d:%02d".format(
                            com.eried.eucplanet.util.Units.distance(km, distanceUnit),
                            distanceUnitLabel, minutes, seconds
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.appColors.statusDanger
                    )
                } else {
                    val duration = ((trip.endTime ?: trip.startTime) - trip.startTime) / 1000
                    val minutes = duration / 60
                    val seconds = duration % 60
                    Text(
                        "%.1f %s | %d:%02d".format(
                            com.eried.eucplanet.util.Units.distance(trip.distanceKm, distanceUnit),
                            distanceUnitLabel, minutes, seconds
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
                trip.eucstatsStatus != 0 -> OnlineStatusIcon(trip, onRetryOnline)   // shared / uploading / failed
                trip.uploadStatus == 1 -> PendingStatusIcon()                       // folder backup in flight
                trip.uploadStatus == 2 -> UploadStatusIcon(trip)                    // saved locally only
            }
            // View (eye), always available
            IconButton(onClick = onView) {
                Icon(Icons.Default.Visibility, contentDescription = stringResource(R.string.action_view))
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
        Icon(Icons.Default.Pending, contentDescription = msg, tint = MaterialTheme.appColors.statusWarn,
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun UploadStatusIcon(trip: TripRecord) {
    val fmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }

    val uploadedAtText = trip.uploadedAt?.let { fmt.format(Date(it)) }
    val msg = uploadedAtText?.let {
        stringResource(R.string.cloud_uploaded_on, it)
    } ?: stringResource(R.string.cloud_not_uploaded)

    val snackbar = LocalSnackbar.current
    val scope = LocalSnackbarScope.current
    IconButton(onClick = { showSnackbarLocal(snackbar, scope, msg) }) {
        Icon(Icons.Default.CheckCircle, contentDescription = msg, tint = MaterialTheme.appColors.statusGood,
            modifier = Modifier.size(20.dp))
    }
}

/** eucstats online status: green cloud = shared, warn cloud = under review / uploading,
 *  red cloud-off = failed (tap to retry). Old/imported trips have eucstatsStatus 0 and
 *  never reach here, so they keep the local disk tick. */
@Composable
private fun OnlineStatusIcon(trip: TripRecord, onRetry: () -> Unit) {
    val snackbar = LocalSnackbar.current
    val scope = LocalSnackbarScope.current
    val flagged = trip.eucstatsStatus == 2 && trip.eucstatsValidation == "flagged"
    val isRetry = trip.eucstatsStatus == 3
    val icon = when {
        isRetry -> Icons.Default.CloudOff
        trip.eucstatsStatus == 2 && !flagged -> Icons.Default.CloudDone
        flagged -> Icons.Default.Cloud
        else -> Icons.Default.CloudQueue   // 1 = pending / uploading
    }
    val tint = when {
        isRetry -> MaterialTheme.appColors.statusDanger
        trip.eucstatsStatus == 2 && !flagged -> MaterialTheme.appColors.statusGood
        else -> MaterialTheme.appColors.statusWarn
    }
    val msg = when {
        isRetry -> stringResource(R.string.online_status_failed)
        trip.eucstatsStatus == 2 && !flagged -> stringResource(R.string.online_status_shared)
        flagged -> stringResource(R.string.online_status_flagged)
        else -> stringResource(R.string.online_status_pending)
    }
    IconButton(onClick = { if (isRetry) onRetry() else showSnackbarLocal(snackbar, scope, msg) }) {
        Icon(icon, contentDescription = msg, tint = tint, modifier = Modifier.size(20.dp))
    }
}
