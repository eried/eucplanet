package com.eried.eucplanet.ui.recording

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
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
import androidx.compose.runtime.LaunchedEffect
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
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onBack: () -> Unit,
    onViewTrip: ((TripRecord) -> Unit)? = null,
    onOpenBackupSettings: (() -> Unit)? = null,
    viewModel: RecordingViewModel = hiltViewModel()
) {
    val recording by viewModel.recording.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val liveTripKm by viewModel.liveTripDistanceKm.collectAsState()
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
    val listState = rememberLazyListState()

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
                }) { Text(stringResource(R.string.action_delete_all), color = AccentRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
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
                }) { Text(stringResource(R.string.action_delete), color = AccentRed) }
            },
            dismissButton = {
                TextButton(onClick = { tripToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Scaffold(
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
                        onDismissRequest = { showManageMenu = false }
                    ) {
                        if (onOpenBackupSettings != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_trip_backup_options)) },
                                onClick = {
                                    showManageMenu = false
                                    onOpenBackupSettings()
                                }
                            )
                            HorizontalDivider()
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
                                text = { Text(stringResource(R.string.recording_clear_all_menu), color = AccentRed) },
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
                    containerColor = AccentRed
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
                        tint = if (gpsFix) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(
                            if (gpsFix) R.string.recording_gps_locked
                            else R.string.recording_gps_waiting
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (gpsFix) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant
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

            if (!recording && !importing && trips.isEmpty()) {
                HintText(
                    stringResource(R.string.recording_auto_record_tip),
                    small = true,
                    modifier = Modifier.padding(vertical = 8.dp)
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
                        StatItem(stringResource(R.string.recording_stat_distance), "%.1f km".format(totalKm))
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
                        TripCard(
                            trip = trip,
                            isRecording = isRecordingTrip,
                            liveDistanceKm = if (isRecordingTrip) liveTripKm else null,
                            onView = { onViewTrip?.invoke(trip) },
                            onShare = { viewModel.shareTrip(trip) },
                            onDelete = { tripToDelete = trip }
                        )
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
    liveDistanceKm: Float?,
    onView: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
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
            containerColor = if (isRecording) AccentRed.copy(alpha = 0.15f)
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
                    tint = AccentRed, modifier = Modifier
                        .size(16.dp)
                        .padding(end = 4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    dateFormat.format(Date(trip.startTime)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isRecording) AccentRed else MaterialTheme.colorScheme.onSurface
                )
                if (isRecording) {
                    val elapsed = (now - trip.startTime) / 1000
                    val minutes = elapsed / 60
                    val seconds = elapsed % 60
                    val km = liveDistanceKm ?: 0f
                    Text(
                        "%.1f km | %d:%02d".format(km, minutes, seconds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentRed
                    )
                } else {
                    val duration = ((trip.endTime ?: trip.startTime) - trip.startTime) / 1000
                    val minutes = duration / 60
                    val seconds = duration % 60
                    Text(
                        "%.1f km | %d:%02d".format(trip.distanceKm, minutes, seconds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Backup indicator — only shown once the trip is actually backed up.
            // Pending/failed/never-configured all render nothing to keep the row clean.
            if (!isRecording && trip.uploadStatus == 2) {
                UploadStatusIcon(trip)
            }
            // View (eye) — always available
            IconButton(onClick = onView) {
                Icon(Icons.Default.Visibility, contentDescription = stringResource(R.string.action_view))
            }
            // Share
            IconButton(onClick = onShare, enabled = !isRecording) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share),
                    tint = if (isRecording) disabledColor
                           else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Delete
            IconButton(onClick = onDelete, enabled = !isRecording) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete),
                    tint = if (isRecording) disabledColor
                           else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UploadStatusIcon(trip: TripRecord) {
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }

    val uploadedAtText = trip.uploadedAt?.let { fmt.format(Date(it)) }
    val msg = uploadedAtText?.let {
        stringResource(R.string.cloud_uploaded_on, it)
    } ?: stringResource(R.string.cloud_not_uploaded)

    IconButton(onClick = { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }) {
        Icon(Icons.Default.CheckCircle, contentDescription = msg, tint = AccentGreen,
            modifier = Modifier.size(20.dp))
    }
}
