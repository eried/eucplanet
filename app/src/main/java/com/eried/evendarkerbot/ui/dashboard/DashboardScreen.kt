package com.eried.evendarkerbot.ui.dashboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.evendarkerbot.R
import com.eried.evendarkerbot.ble.ConnectionState
import com.eried.evendarkerbot.ui.theme.AccentBlue
import com.eried.evendarkerbot.ui.theme.AccentGreen
import com.eried.evendarkerbot.ui.theme.AccentOrange
import com.eried.evendarkerbot.ui.theme.AccentRed
import com.eried.evendarkerbot.ui.theme.AccentYellow
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToRecording: () -> Unit,
    onNavigateToMetric: (String) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val wheelData by viewModel.wheelData.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
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
                    IconButton(onClick = onNavigateToSettings) {
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
            SpeedGauge(
                speed = wheelData.speed.absoluteValue,
                maxSpeed = gaugeMax,
                imperial = imperial,
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .aspectRatio(1.25f)
                    .clickable { onNavigateToMetric("SPEED") }
            )

            Spacer(Modifier.height(16.dp))

            // Stats grid — 3 rows of 2
            val battColor = when {
                wheelData.batteryPercent < 20 -> AccentRed
                wheelData.batteryPercent < 40 -> AccentOrange
                else -> AccentGreen
            }
            val tempColor = when {
                wheelData.maxTemperature > 60 -> AccentRed
                wheelData.maxTemperature > 45 -> AccentOrange
                else -> AccentGreen
            }
            val loadColor = when {
                pwm >= 80 -> AccentRed
                pwm >= 60 -> AccentOrange
                else -> AccentGreen
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(stringResource(R.string.stat_battery), "${wheelData.batteryPercent}%", battColor, history.battery, Modifier.weight(1f),
                    onClick = { onNavigateToMetric("BATTERY") })
                val tempValue = com.eried.evendarkerbot.util.Units.temperature(wheelData.maxTemperature, imperial)
                val tempUnit = com.eried.evendarkerbot.util.Units.tempUnit(imperial)
                StatCard(stringResource(R.string.stat_temp), "%.0f%s".format(tempValue, tempUnit), tempColor, history.temperature, Modifier.weight(1f),
                    onClick = { onNavigateToMetric("TEMPERATURE") })
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(stringResource(R.string.stat_voltage), "%.1fV".format(wheelData.voltage), AccentBlue, history.voltage, Modifier.weight(1f),
                    onClick = { onNavigateToMetric("VOLTAGE") })
                StatCard(stringResource(R.string.stat_amps), "%.1fA".format(wheelData.current),
                    if (wheelData.current > 20) AccentOrange else AccentBlue, history.current, Modifier.weight(1f),
                    onClick = { onNavigateToMetric("CURRENT") })
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(stringResource(R.string.stat_load), "%.0f%%".format(pwm), loadColor, history.load, Modifier.weight(1f),
                    onClick = { onNavigateToMetric("LOAD") })
                val tripValue = com.eried.evendarkerbot.util.Units.distance(wheelData.tripDistance, imperial)
                val distUnit = com.eried.evendarkerbot.util.Units.distanceUnit(imperial)
                StatCard(stringResource(R.string.stat_trip), "%.1f %s".format(tripValue, distUnit), AccentBlue, emptyList(), Modifier.weight(1f))
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
                ActionButton(Icons.Default.FlashOn, stringResource(R.string.action_light),
                    active = wheelData.lightOn,
                    enabled = connectionState == ConnectionState.CONNECTED,
                    onClick = { viewModel.onLightToggle() },
                    modifier = Modifier.weight(1f))
                ActionButton(Icons.Default.RecordVoiceOver, stringResource(R.string.action_voice),
                    onClick = { viewModel.onVoiceAnnounce() },
                    modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(Icons.Default.Shield,
                    if (safetyActive) stringResource(R.string.action_legal_on) else stringResource(R.string.action_legal_mode),
                    active = safetyActive, activeColor = AccentOrange,
                    enabled = connectionState == ConnectionState.CONNECTED,
                    onClick = { viewModel.onSafetySpeedToggle() },
                    modifier = Modifier.weight(1f))
                ActionButton(
                    if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    if (locked) stringResource(R.string.action_locked) else stringResource(R.string.action_lock_wheel),
                    active = locked, activeColor = AccentRed,
                    enabled = connectionState == ConnectionState.CONNECTED,
                    onClick = { viewModel.onLockToggle() },
                    modifier = Modifier.weight(1f))
                ActionButton(Icons.Default.FiberManualRecord,
                    if (tripCount > 0) stringResource(R.string.action_recorder_trips, tripCount)
                    else stringResource(R.string.action_recorder),
                    active = recording, activeColor = AccentRed,
                    onClick = { onNavigateToRecording() },
                    modifier = Modifier.weight(1f))
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
                val crashes = remember { com.eried.evendarkerbot.util.CrashHandler.listCrashes(context) }
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = { Text("EUC Planet") },
                    text = {
                        Column {
                            Text("Version $versionName", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Custom control app for the InMotion V14 electric unicycle — BLE dashboard, voice announcements, trip recording with GPS, configurable alarms, Flic 2 buttons, volume-key shortcuts, auto-lighting and adaptive volume.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Built with Jetpack Compose (Material 3), AndroidX (AppCompat, Lifecycle, Navigation, Room), Hilt, Kotlin Coroutines, Google Play Services Location and the Flic 2 SDK.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Made by Erwin Ried — eucplanet.ried.no",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (crashes.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.about_crash_logs),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                crashes.forEach { file ->
                                    Text(
                                        file.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { shareCrashFile(context, file) }
                                            .padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAboutDialog = false }) {
                            Text("OK")
                        }
                    }
                )
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
    modifier: Modifier = Modifier
) {
    val speedColor = when {
        speed > maxSpeed * 0.85f -> AccentRed
        speed > maxSpeed * 0.65f -> AccentOrange
        speed > maxSpeed * 0.4f -> AccentYellow
        else -> AccentGreen
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    val displaySpeed = com.eried.evendarkerbot.util.Units.speed(speed, imperial)
    val displayMax = com.eried.evendarkerbot.util.Units.speed(maxSpeed, imperial)
    val maxInt = displayMax.toInt()
    val step = (maxInt / 3f).toInt().coerceAtLeast(5)
    val scaleLabels = listOf(0, step, step * 2, maxInt)
    val unitLabel = com.eried.evendarkerbot.util.Units.speedUnit(imperial)

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

        // Scale labels outside ticks
        val labelRadius = arcRadius + arcThickness + size.minDimension * 0.04f
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

        // Speed number — dead center of the arc circle
        val speedText = "%.0f".format(displaySpeed)
        val speedMeasured = textMeasurer.measure(
            speedText,
            style = TextStyle(
                fontSize = (size.minDimension * 0.22f).sp,
                fontWeight = FontWeight.Bold,
                color = speedColor
            )
        )
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
    }
}

// --- Stat card with sparkline ---

@Composable
private fun StatCard(
    label: String,
    value: String,
    color: Color,
    sparkData: List<Float> = emptyList(),
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val odoValue = com.eried.evendarkerbot.util.Units.distance(odoKm, imperial)
            val odoUnit = com.eried.evendarkerbot.util.Units.distanceUnit(imperial)
            Text(stringResource(R.string.stat_odo), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
            Text("%.0f %s".format(odoValue, odoUnit), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val infoText = listOfNotNull(modelName, firmwareVersion?.let { "v$it" })
                .joinToString(" · ")
            if (infoText.isNotBlank()) {
                Text(infoText, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = "v$versionName",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.clickable(onClick = onVersionClick)
            )
        }
    }
}

// --- Helpers ---

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

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColor: Color = AccentBlue,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val disabledAlpha = 0.35f
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) activeColor.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (active) activeColor
            else MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = disabledAlpha),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
        ),
        contentPadding = PaddingValues(4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, textAlign = TextAlign.Center, lineHeight = 13.sp)
        }
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
