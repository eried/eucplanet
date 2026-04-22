package com.eried.eucplanet.ui.dashboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.core.content.FileProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentOrange
import com.eried.eucplanet.ui.theme.AccentRed
import com.eried.eucplanet.ui.theme.AccentYellow
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToSettings: (Int?) -> Unit,
    onNavigateToRecording: () -> Unit,
    onNavigateToFlic: () -> Unit = {},
    onNavigateToTripDetail: (Long) -> Unit = {},
    onNavigateToMetric: (String) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    DisposableEffect(Unit) {
        Log.i("EucDash", "DashboardScreen ENTER")
        onDispose { Log.i("EucDash", "DashboardScreen DISPOSE") }
    }
    val wheelData by viewModel.wheelData.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    SideEffect { Log.d("EucDash", "recompose conn=$connectionState speed=${wheelData.speed}") }
    val safetyActive by viewModel.safetySpeedActive.collectAsState()
    val locked by viewModel.locked.collectAsState()
    val recording by viewModel.recording.collectAsState()
    val tripCount by viewModel.tripCount.collectAsState()
    val tiltbackSpeed by viewModel.tiltbackSpeed.collectAsState()
    val safetyTiltbackSpeed by viewModel.safetyTiltbackSpeed.collectAsState()
    val realHistory by viewModel.history.collectAsState()
    // Show demo sparklines when disconnected so the feature is visible
    val history = if (realHistory.battery.size >= 2) realHistory else MetricHistory(
        battery = listOf(85f, 84f, 83f, 84f, 82f, 80f, 79f, 78f, 77f, 78f, 76f, 75f, 74f, 73f, 72f),
        temperature = listOf(32f, 33f, 34f, 35f, 36f, 37f, 38f, 37f, 36f, 38f, 39f, 40f, 41f, 40f, 39f),
        voltage = listOf(98f, 97.5f, 97f, 96.5f, 97f, 96f, 95.5f, 95f, 96f, 95f, 94.5f, 94f, 93.5f, 94f, 93f),
        current = listOf(2f, 5f, 12f, 8f, 3f, 15f, 20f, 10f, 4f, 8f, 18f, 6f, 3f, 7f, 11f),
        load = listOf(5f, 12f, 25f, 18f, 8f, 35f, 50f, 30f, 10f, 20f, 45f, 15f, 8f, 22f, 28f),
        speed = emptyList()
    )
    val modelName by viewModel.modelName.collectAsState()
    val firmwareVersion by viewModel.firmwareVersion.collectAsState()
    val imperial by viewModel.imperialUnits.collectAsState()
    val accentKey by viewModel.accentKey.collectAsState()
    val showGaugeColorBand by viewModel.showGaugeColorBand.collectAsState()
    val gaugeOrangePct by viewModel.gaugeOrangePct.collectAsState()
    val gaugeRedPct by viewModel.gaugeRedPct.collectAsState()
    val currentMode by viewModel.currentDisplayMode.collectAsState()
    val hasFlic by viewModel.hasFlicConfigured.collectAsState()
    val flicFlashAt by viewModel.flicFlashAt.collectAsState()
    val latestTripId by viewModel.latestTripId.collectAsState()
    val currentTripId by viewModel.currentTripId.collectAsState()
    var showQuitDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity

    BackHandler { showQuitDialog = true }

    if (showDisconnectDialog) {
        val wheelLabel = modelName ?: stringResource(R.string.wheel_generic)
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.disconnect)) },
            text = { Text(stringResource(R.string.disconnect_from, wheelLabel)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    viewModel.disconnect()
                }) {
                    Text(stringResource(R.string.disconnect), color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showQuitDialog) {
        AlertDialog(
            onDismissRequest = { showQuitDialog = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.stopEverything()
                    activity?.finish()
                }) {
                    Text(stringResource(R.string.exit_stop_all), color = AccentRed)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showQuitDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(onClick = { activity?.finish() }) {
                        Text(stringResource(R.string.exit_background))
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnectionDot(connectionState)
                        Spacer(Modifier.width(8.dp))
                        val connectedLabel = stringResource(R.string.connection_connected)
                        val connectingLabel = stringResource(R.string.connection_connecting)
                        val initLabel = stringResource(R.string.connection_initializing)
                        val scanningLabel = stringResource(R.string.connection_scanning)
                        val disconnectedLabel = stringResource(R.string.connection_disconnected)
                        Text(
                            text = when (connectionState) {
                                ConnectionState.CONNECTED -> modelName ?: connectedLabel
                                ConnectionState.CONNECTING -> connectingLabel
                                ConnectionState.INITIALIZING -> initLabel
                                ConnectionState.SCANNING -> scanningLabel
                                ConnectionState.DISCONNECTED -> disconnectedLabel
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    FlicIndicator(
                        hasFlic = hasFlic,
                        flashAt = flicFlashAt,
                        onClick = onNavigateToFlic
                    )
                    IconButton(onClick = {
                        if (connectionState == ConnectionState.CONNECTED) {
                            showDisconnectDialog = true
                        } else {
                            onNavigateToScan()
                        }
                    }) {
                        Icon(
                            if (connectionState == ConnectionState.DISCONNECTED)
                                Icons.AutoMirrored.Filled.BluetoothSearching
                            else Icons.Default.Bluetooth,
                            contentDescription = stringResource(R.string.connection)
                        )
                    }
                    IconButton(onClick = { onNavigateToSettings(null) }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
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
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val effectiveTiltback = if (safetyActive) safetyTiltbackSpeed else tiltbackSpeed
            val gaugeMax = ((effectiveTiltback / 10f).toInt() + 1) * 10f
            val pwm = wheelData.pwm.absoluteValue

            // Speed gauge — wide arc dial (tap opens history)
            val useAccent = !com.eried.eucplanet.ui.theme.isDefaultAccent(accentKey)
            val primary = MaterialTheme.colorScheme.primary
            val safeColor = if (useAccent) primary else AccentGreen
            SpeedGauge(
                speed = wheelData.speed.absoluteValue,
                maxSpeed = gaugeMax,
                imperial = imperial,
                overrideColor = if (useAccent) primary else null,
                showColorBand = showGaugeColorBand,
                orangeThresholdPct = gaugeOrangePct,
                redThresholdPct = gaugeRedPct,
                safeBandColor = if (useAccent) primary else AccentBlue,
                pcMode = if (connectionState == ConnectionState.CONNECTED) wheelData.pcMode else -1,
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .aspectRatio(1.25f)
                    .clickable { onNavigateToMetric("SPEED") }
            )

            Spacer(Modifier.height(16.dp))

            // Stats grid — 3 rows of 2. Alert tiers only apply when connected (disconnected values are 0).
            val live = connectionState == ConnectionState.CONNECTED
            val battColor = when {
                live && wheelData.batteryPercent < 20 -> AccentRed
                live && wheelData.batteryPercent < 40 -> AccentOrange
                else -> safeColor
            }
            val tempColor = when {
                live && wheelData.maxTemperature > 60 -> AccentRed
                live && wheelData.maxTemperature > 45 -> AccentOrange
                else -> safeColor
            }
            val loadColor = when {
                live && pwm >= 80 -> AccentRed
                live && pwm >= 60 -> AccentOrange
                else -> safeColor
            }

            val placeholder = "—"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(stringResource(R.string.stat_battery),
                    if (live) "${wheelData.batteryPercent}%" else placeholder,
                    battColor, history.battery, Modifier.weight(1f),
                    onClick = { onNavigateToMetric("BATTERY") })
                val tempValue = com.eried.eucplanet.util.Units.temperature(wheelData.maxTemperature, imperial)
                val tempUnit = com.eried.eucplanet.util.Units.tempUnit(imperial)
                StatCard(stringResource(R.string.stat_temp),
                    if (live) "%.0f%s".format(tempValue, tempUnit) else placeholder,
                    tempColor, history.temperature, Modifier.weight(1f),
                    onClick = { onNavigateToMetric("TEMPERATURE") })
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(stringResource(R.string.stat_voltage),
                    if (live) "%.1fV".format(wheelData.voltage) else placeholder,
                    primary, history.voltage, Modifier.weight(1f),
                    onClick = { onNavigateToMetric("VOLTAGE") })

                val showWatts = currentMode == "WATTS"
                val ampsLabel = stringResource(R.string.stat_amps)
                val wattsLabel = stringResource(R.string.stat_watts)
                val currentValue = if (showWatts) wheelData.voltage * wheelData.current else wheelData.current
                val currentText = when {
                    !live -> placeholder
                    showWatts -> "%.0fW".format(currentValue)
                    else -> "%.1fA".format(currentValue)
                }
                StatCard(
                    if (showWatts) wattsLabel else ampsLabel,
                    currentText,
                    if (live && wheelData.current > 20) AccentOrange else primary,
                    history.current,
                    Modifier.weight(1f),
                    onClick = { onNavigateToMetric("CURRENT") },
                    onLongClick = { viewModel.toggleCurrentDisplayMode() }
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(stringResource(R.string.stat_load),
                    if (live) "%.0f%%".format(pwm) else placeholder,
                    loadColor, history.load, Modifier.weight(1f),
                    onClick = { onNavigateToMetric("LOAD") })
                val tripValue = com.eried.eucplanet.util.Units.distance(wheelData.tripDistance, imperial)
                val distUnit = com.eried.eucplanet.util.Units.distanceUnit(imperial)
                StatCard(stringResource(R.string.stat_trip),
                    if (live) "%.1f %s".format(tripValue, distUnit) else placeholder,
                    primary, emptyList(), Modifier.weight(1f))
            }

            Spacer(Modifier.height(14.dp))

            // Action buttons — pill-shaped, 2 rows of 3
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(Icons.Default.Campaign, stringResource(R.string.action_horn),
                    enabled = connectionState == ConnectionState.CONNECTED,
                    onClick = { viewModel.onHornPress() },
                    modifier = Modifier.weight(1f))
                ActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.FlashOn,
                    label = stringResource(R.string.action_light),
                    active = wheelData.lightOn,
                    enabled = connectionState == ConnectionState.CONNECTED,
                    onClick = { viewModel.onLightToggle() },
                    menu = { dismiss ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_auto_lights)) },
                            onClick = { dismiss(); onNavigateToSettings(6) }
                        )
                    }
                )
                ActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.RecordVoiceOver,
                    label = stringResource(R.string.action_voice),
                    onClick = { viewModel.onVoiceAnnounce() },
                    menu = { dismiss ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_voice_settings)) },
                            onClick = { dismiss(); onNavigateToSettings(3) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_alarms)) },
                            onClick = { dismiss(); onNavigateToSettings(5) }
                        )
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Shield,
                    label = if (safetyActive) stringResource(R.string.action_legal_on) else stringResource(R.string.action_legal_mode),
                    active = safetyActive,
                    activeColor = if (useAccent) primary else AccentOrange,
                    enabled = connectionState == ConnectionState.CONNECTED,
                    onClick = { viewModel.onSafetySpeedToggle() },
                    menu = { dismiss ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_legal_settings)) },
                            onClick = { dismiss(); onNavigateToSettings(2) }
                        )
                    }
                )
                ActionButton(
                    if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    if (locked) stringResource(R.string.action_locked) else stringResource(R.string.action_lock_wheel),
                    active = locked, activeColor = if (useAccent) primary else AccentRed,
                    enabled = connectionState == ConnectionState.CONNECTED,
                    onClick = { viewModel.onLockToggle() },
                    modifier = Modifier.weight(1f))
                ActionTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.FiberManualRecord,
                    label = if (tripCount > 0) stringResource(R.string.action_recorder_trips, tripCount)
                        else stringResource(R.string.action_recorder),
                    active = recording,
                    activeColor = if (useAccent) primary else AccentRed,
                    onClick = {
                        val targetTripId = currentTripId ?: latestTripId
                        if (targetTripId != null) onNavigateToTripDetail(targetTripId)
                        else onNavigateToRecording()
                    },
                    menu = { dismiss ->
                        val targetTripId = currentTripId ?: latestTripId
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (recording) stringResource(R.string.menu_view_current_trip)
                                    else stringResource(R.string.menu_view_last_trip)
                                )
                            },
                            enabled = targetTripId != null,
                            onClick = {
                                dismiss()
                                targetTripId?.let { onNavigateToTripDetail(it) }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (recording) stringResource(R.string.menu_stop_recording)
                                    else stringResource(R.string.menu_start_recording)
                                )
                            },
                            onClick = {
                                dismiss()
                                if (recording) viewModel.stopRecording() else viewModel.startRecording()
                            }
                        )
                    }
                )
            }

            Spacer(Modifier.weight(1f, fill = true))

            // Bottom info row: ODO + wheel model + firmware + version (tap for About)
            var showAboutDialog by remember { mutableStateOf(false) }
            val context = LocalContext.current
            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "?" }
            }
            WheelInfoBox(
                odoKm = wheelData.totalDistance,
                imperial = imperial,
                modelName = modelName,
                firmwareVersion = firmwareVersion,
                versionName = versionName,
                onVersionClick = { showAboutDialog = true }
            )

            if (showAboutDialog) {
                val crashes = remember { com.eried.eucplanet.util.CrashHandler.listCrashes(context) }
                val licenseText = remember {
                    try {
                        context.resources.openRawResource(R.raw.license)
                            .bufferedReader().use { it.readText() }
                    } catch (_: Exception) { "" }
                }
                Dialog(
                    onDismissRequest = { showAboutDialog = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .heightIn(max = 680.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(colorResource(R.color.ic_launcher_background))
                                    .align(Alignment.CenterHorizontally)
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ic_launcher_foreground),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "EUC Planet",
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            Text(
                                "v$versionName · ${com.eried.eucplanet.BuildConfig.BUILD_STAMP}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            Spacer(Modifier.height(16.dp))

                            Text(
                                "Custom control app for the InMotion V14 electric unicycle: BLE dashboard, voice announcements, trip recording with GPS, configurable alarms, Flic 2 buttons, volume-key shortcuts, auto-lighting and adaptive volume.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Made by Erwin Ried — eucplanet.ried.no",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { openUrl(context, "https://eucplanet.ried.no") }
                            )

                            Spacer(Modifier.height(12.dp))

                            var aboutTab by remember { mutableStateOf(0) }
                            TabRow(
                                selectedTabIndex = aboutTab,
                                containerColor = MaterialTheme.colorScheme.surface
                            ) {
                                Tab(
                                    selected = aboutTab == 0,
                                    onClick = { aboutTab = 0 },
                                    text = { Text(stringResource(R.string.about_license)) }
                                )
                                Tab(
                                    selected = aboutTab == 1,
                                    onClick = { aboutTab = 1 },
                                    text = {
                                        Text(
                                            if (crashes.isEmpty())
                                                stringResource(R.string.about_crash_logs)
                                            else
                                                "${stringResource(R.string.about_crash_logs)} (${crashes.size})"
                                        )
                                    }
                                )
                            }

                            Box(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
                                when (aboutTab) {
                                    0 -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            Text(
                                                if (licenseText.isNotBlank()) licenseText else "—",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rememberScrollState())
                                                    .padding(10.dp)
                                            )
                                        }
                                    }
                                    1 -> {
                                        if (crashes.isEmpty()) {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Text(
                                                    stringResource(R.string.about_crash_logs_empty),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        } else {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rememberScrollState())
                                            ) {
                                                crashes.forEach { file ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { shareCrashFile(context, file) }
                                                            .padding(vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.BugReport,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                        Text(
                                                            file.name,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showAboutDialog = false }) {
                                    Text(stringResource(R.string.action_ok))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Speed gauge: thick arc dial, no needle, centered speed ---

@Composable
private fun SpeedGauge(
    speed: Float,
    maxSpeed: Float,
    imperial: Boolean,
    overrideColor: Color? = null,
    showColorBand: Boolean = false,
    orangeThresholdPct: Int = 65,
    redThresholdPct: Int = 85,
    safeBandColor: Color = AccentBlue,
    pcMode: Int = -1,
    modifier: Modifier = Modifier
) {
    val speedColor = overrideColor ?: when {
        speed > maxSpeed * 0.85f -> AccentRed
        speed > maxSpeed * 0.65f -> AccentOrange
        speed > maxSpeed * 0.4f -> AccentYellow
        else -> AccentGreen
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    val displaySpeed = com.eried.eucplanet.util.Units.speed(speed, imperial)
    val displayMax = com.eried.eucplanet.util.Units.speed(maxSpeed, imperial)
    val maxInt = displayMax.toInt()
    val step = (maxInt / 3f).toInt().coerceAtLeast(5)
    val scaleLabels = listOf(0, step, step * 2, maxInt)
    val unitLabel = com.eried.eucplanet.util.Units.speedUnit(imperial)

    Canvas(modifier = modifier) {
        val dim = size.minDimension
        val arcThickness = dim * 0.07f
        val arcRadius = dim / 2f - arcThickness - dim * 0.06f
        val center = Offset(size.width / 2f, size.height * 0.52f)

        val startAngle = 140f
        val sweepTotal = 260f
        val speedFraction = (speed / maxSpeed).coerceIn(0f, 1f)
        val speedSweep = sweepTotal * speedFraction

        // Background arc
        drawArc(
            color = trackColor,
            startAngle = startAngle, sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = Size(arcRadius * 2, arcRadius * 2),
            style = Stroke(width = arcThickness, cap = StrokeCap.Round)
        )

        // Thin color band behind the arc: safe (accent/blue) > orange > red.
        if (showColorBand) {
            val bandThickness = arcThickness * 0.35f
            val bandRadius = arcRadius + arcThickness * 0.55f + bandThickness * 0.5f
            val bandAlpha = 0.65f
            // Stored values are 25..100 and already correspond to the arc's visible portion.
            val orangeFrac = (orangeThresholdPct / 100f).coerceIn(0.25f, 0.95f)
            val redFrac = (redThresholdPct / 100f).coerceIn(orangeFrac + 0.01f, 1f)
            val orangeStart = startAngle + sweepTotal * orangeFrac
            val orangeSweep = sweepTotal * (redFrac - orangeFrac)
            val redStart = startAngle + sweepTotal * redFrac
            val redSweep = sweepTotal * (1f - redFrac)
            val bandTopLeft = Offset(center.x - bandRadius, center.y - bandRadius)
            val bandSize = Size(bandRadius * 2, bandRadius * 2)
            // Safe zone: entire arc from 0 up to the orange handle.
            drawArc(color = safeBandColor.copy(alpha = bandAlpha),
                startAngle = startAngle, sweepAngle = sweepTotal * orangeFrac,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
            drawArc(color = AccentOrange.copy(alpha = bandAlpha),
                startAngle = orangeStart, sweepAngle = orangeSweep,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
            drawArc(color = AccentRed.copy(alpha = bandAlpha),
                startAngle = redStart, sweepAngle = redSweep,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
        }

        // Speed arc
        if (speedSweep > 0.5f) {
            drawArc(
                color = speedColor,
                startAngle = startAngle, sweepAngle = speedSweep,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = arcThickness, cap = StrokeCap.Round)
            )
        }

        // Tick marks on outside of arc
        val tickOuter = arcRadius + arcThickness * 0.7f
        val tickInner = arcRadius + arcThickness * 0.1f
        for (i in 0..24) {
            val angle = startAngle + (sweepTotal * i / 24f)
            val rad = Math.toRadians(angle.toDouble())
            val isMajor = i % 8 == 0
            val isMinor = i % 4 == 0
            if (!isMajor && !isMinor) continue
            drawLine(
                color = if (isMajor) dimColor else dimColor.copy(alpha = 0.35f),
                start = Offset(center.x + tickInner * cos(rad).toFloat(), center.y + tickInner * sin(rad).toFloat()),
                end = Offset(center.x + tickOuter * cos(rad).toFloat(), center.y + tickOuter * sin(rad).toFloat()),
                strokeWidth = if (isMajor) 2.5f else 1.2f
            )
        }

        // Scale labels outside ticks — pushed outward so digits aren't hugging the dial.
        val labelRadius = arcRadius + arcThickness + size.minDimension * 0.08f
        for ((idx, label) in scaleLabels.withIndex()) {
            val angle = startAngle + (sweepTotal * idx / (scaleLabels.size - 1).toFloat())
            val rad = Math.toRadians(angle.toDouble())
            val measured = textMeasurer.measure(
                "$label",
                style = TextStyle(fontSize = (size.minDimension * 0.05f).sp, color = dimColor)
            )
            drawText(
                measured,
                topLeft = Offset(
                    center.x + labelRadius * cos(rad).toFloat() - measured.size.width / 2f,
                    center.y + labelRadius * sin(rad).toFloat() - measured.size.height / 2f
                )
            )
        }

        // Speed number — dead center of the arc circle.
        // Dynamically clamp text width so digits keep a visible horizontal margin from the arc.
        val speedText = "%.0f".format(displaySpeed)
        val baseFactor = if (speedText.length >= 3) 0.17f else 0.2f
        val innerRadius = arcRadius - arcThickness * 0.5f
        val maxTextHalfWidth = innerRadius * 0.72f  // leaves ~28% clearance each side
        var speedFontFactor = baseFactor
        var speedMeasured = textMeasurer.measure(
            speedText,
            style = TextStyle(
                fontSize = (size.minDimension * speedFontFactor).sp,
                fontWeight = FontWeight.Bold,
                color = speedColor
            )
        )
        if (speedMeasured.size.width / 2f > maxTextHalfWidth) {
            val scale = maxTextHalfWidth / (speedMeasured.size.width / 2f)
            speedFontFactor *= scale
            speedMeasured = textMeasurer.measure(
                speedText,
                style = TextStyle(
                    fontSize = (size.minDimension * speedFontFactor).sp,
                    fontWeight = FontWeight.Bold,
                    color = speedColor
                )
            )
        }
        drawText(
            speedMeasured,
            topLeft = Offset(
                center.x - speedMeasured.size.width / 2f,
                center.y - speedMeasured.size.height / 2f - size.minDimension * 0.03f
            )
        )

        // speed unit below speed number
        val unitMeasured = textMeasurer.measure(
            unitLabel,
            style = TextStyle(fontSize = (size.minDimension * 0.06f).sp, color = dimColor)
        )
        drawText(
            unitMeasured,
            topLeft = Offset(
                center.x - unitMeasured.size.width / 2f,
                center.y + speedMeasured.size.height / 2f - size.minDimension * 0.01f
            )
        )

        // P/D indicator below the unit label, only when we have telemetry.
        val gearLabel = when (pcMode) {
            1 -> "D"
            3 -> "P"
            else -> null
        }
        if (gearLabel != null) {
            val gearColor = if (gearLabel == "P") dimColor else speedColor
            val gearMeasured = textMeasurer.measure(
                gearLabel,
                style = TextStyle(
                    fontSize = (size.minDimension * 0.08f).sp,
                    fontWeight = FontWeight.Bold,
                    color = gearColor
                )
            )
            drawText(
                gearMeasured,
                topLeft = Offset(
                    center.x - gearMeasured.size.width / 2f,
                    center.y + speedMeasured.size.height / 2f + unitMeasured.size.height
                )
            )
        }
    }
}

// --- Stat card with sparkline ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatCard(
    label: String,
    value: String,
    color: Color,
    sparkData: List<Float> = emptyList(),
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val clickModifier = when {
        onClick != null || onLongClick != null -> Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick
        )
        else -> Modifier
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(clickModifier)
    ) {
        // Sparkline background
        if (sparkData.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .matchParentSize()
            ) {
                val min = sparkData.min()
                val max = sparkData.max()
                val range = (max - min).coerceAtLeast(0.1f)
                val padding = size.height * 0.15f
                val drawHeight = size.height - padding * 2
                val stepX = size.width / (sparkData.size - 1).toFloat()

                val path = androidx.compose.ui.graphics.Path()
                sparkData.forEachIndexed { idx, v ->
                    val x = idx * stepX
                    val y = padding + drawHeight - ((v - min) / range) * drawHeight
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                // Filled area under curve
                val fillPath = androidx.compose.ui.graphics.Path()
                fillPath.addPath(path)
                fillPath.lineTo(size.width, size.height)
                fillPath.lineTo(0f, size.height)
                fillPath.close()
                drawPath(fillPath, color = color.copy(alpha = 0.08f))
                // Line on top
                drawPath(path, color = color.copy(alpha = 0.4f), style = Stroke(width = 2.5f))
            }
        }

        // Text content on top
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp)
            Spacer(Modifier.height(2.dp))
            Text(value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1)
        }
    }
}

// --- Bottom info box: ODO + model + firmware ---

@Composable
private fun WheelInfoBox(
    odoKm: Float,
    imperial: Boolean,
    modelName: String?,
    firmwareVersion: String?,
    versionName: String,
    onVersionClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Left: ODO
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val odoValue = com.eried.eucplanet.util.Units.distance(odoKm, imperial)
            val odoUnit = com.eried.eucplanet.util.Units.distanceUnit(imperial)
            Text(stringResource(R.string.stat_odo), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
            Text("%.0f %s".format(odoValue, odoUnit), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
        }

        // Center: model · firmware
        val infoText = listOfNotNull(modelName, firmwareVersion?.let { "v$it" })
            .joinToString(" · ")
        if (infoText.isNotBlank()) {
            Text(
                infoText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Right: app version
        Text(
            text = "v$versionName",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .clickable(onClick = onVersionClick)
        )
    }
}

// --- Helpers ---

@Composable
private fun FlicIndicator(
    hasFlic: Boolean,
    flashAt: Long,
    onClick: () -> Unit
) {
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(flashAt) {
        if (flashAt == 0L) return@LaunchedEffect
        flashAlpha.snapTo(1f)
        flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 1200))
    }
    // Always visible. Filled circle when paired, hollow ring when not. Flash turns it green on action.
    val baseAlpha = if (hasFlic) 1f else 0.55f
    val alpha = (baseAlpha + flashAlpha.value * (1f - baseAlpha)).coerceAtMost(1f)
    val tint = if (flashAlpha.value > 0f) AccentGreen
               else MaterialTheme.colorScheme.onSurface
    val icon = if (hasFlic || flashAlpha.value > 0f) Icons.Default.RadioButtonChecked
               else Icons.Default.RadioButtonUnchecked
    IconButton(
        onClick = onClick,
        modifier = Modifier.alpha(alpha)
    ) {
        Icon(
            icon,
            contentDescription = stringResource(R.string.flic_button),
            tint = tint
        )
    }
}

@Composable
private fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> AccentGreen
        ConnectionState.CONNECTING, ConnectionState.INITIALIZING, ConnectionState.SCANNING -> AccentYellow
        ConnectionState.DISCONNECTED -> AccentRed
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColor: Color = AccentBlue,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val disabledAlpha = 0.35f
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = disabledAlpha)
        active -> activeColor.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
        active -> activeColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = content,
                maxLines = 2, textAlign = TextAlign.Center, lineHeight = 13.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    active: Boolean = false,
    activeColor: Color = AccentBlue,
    enabled: Boolean = true
) {
    Box(modifier = modifier) {
        var menuOpen by remember { mutableStateOf(false) }
        ActionButton(
            icon = icon,
            label = label,
            active = active,
            activeColor = activeColor,
            enabled = enabled,
            onClick = onClick,
            onLongClick = { menuOpen = true },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            menu { menuOpen = false }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun shareCrashFile(context: Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "EUC Planet crash: ${file.name}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.about_share_crash))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
