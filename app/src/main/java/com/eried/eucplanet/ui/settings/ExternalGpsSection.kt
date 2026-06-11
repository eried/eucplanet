package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.ui.theme.themedSwitchColors

/**
 * Section in the Integration tab for pairing/unpairing an external BLE GPS box.
 * Two faces:
 *  * Not paired: caption + a single "Pair external GPS" button that switches to
 *    the scan card. Picking a result pairs and triggers connect.
 *  * Paired: status card with name, connection state, live speed when connected,
 *    plus reconnect / forget, axis remap dropdowns and autodetect wizard.
 */
@Composable
fun ExternalGpsSection(
    viewModel: ExternalGpsViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onOpenTpms: () -> Unit = {},
) {
    val pairedAddress by viewModel.pairedAddress.collectAsState()
    val pairedName by viewModel.pairedName.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val scanResults by viewModel.scanResults.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val sample by viewModel.currentSample.collectAsState()
    val settings by settingsViewModel.settings.collectAsState()
    val autoDetectPhase by viewModel.autoDetect.collectAsState()
    val bluetoothOff by viewModel.bluetoothOff.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BringIntoViewSection(expanded = true, spacing = 8.dp) {
        // Quick entry into the TPMS screen. Sensors are bound there
        // because the rider may have many (one per wheel) and the list
        // would otherwise dominate this section.
        TextButton(
            onClick = onOpenTpms,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.appColors.textPrimary,
            ),
        ) {
            Text(
                stringResource(R.string.dash_tpms_settings),
                modifier = Modifier.weight(1f),
            )
            Text(">", color = MaterialTheme.appColors.hint)
        }

        // Show speed on the dashboard, a general display option, kept first
        // because it has nothing to do with the external box below it.
        ToggleRow(
            label = stringResource(R.string.gps_show_on_dashboard),
            checked = settings?.gpsShowOnDashboard == true,
            onCheckedChange = { settingsViewModel.updateGpsShowOnDashboard(it) }
        )
        HintText(stringResource(R.string.gps_show_on_dashboard_desc), small = true)

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.external_gps_device_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        if (pairedAddress == null) {
            HintText(stringResource(R.string.external_gps_caption), small = true)
            if (bluetoothOff) {
                HintText(stringResource(R.string.scan_bluetooth_off_title), small = true)
            }
            UnpairedExternalGpsCard(
                scanning = scanning,
                results = scanResults,
                onStartScan = { viewModel.startScan() },
                onStopScan = { viewModel.stopScan() },
                onPick = { viewModel.pair(it) }
            )
        } else {
            PairedExternalGpsCard(
                deviceName = pairedName ?: pairedAddress!!,
                connectionState = connectionState,
                liveSpeedKmh = sample?.speedKmh
            )

            // Disconnect / Reconnect sits on the left half, Forget on the
            // right half of the same row. Keeps the half-width visual rhythm
            // of LeftAlignedScanButton without leaving an empty gap.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (connectionState == ConnectionState.CONNECTED) {
                    Button(
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.external_gps_disconnect))
                    }
                } else {
                    Button(
                        onClick = { viewModel.reconnect() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.external_gps_reconnect))
                    }
                }
                Button(
                    onClick = { viewModel.unpair() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.appColors.statusDanger)
                ) {
                    Text(stringResource(R.string.external_gps_unpair))
                }
            }

            // --- Axis remap ---
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.external_gps_axes_title),
                style = MaterialTheme.typography.titleSmall
            )
            HintText(stringResource(R.string.external_gps_axes_desc), small = true)

            val axisOptions = listOf(
                "X" to "X",
                "-X" to "-X",
                "Y" to "Y",
                "-Y" to "-Y",
                "Z" to "Z",
                "-Z" to "-Z"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    InlineAxisDropdown(
                        label = stringResource(R.string.external_gps_map_x),
                        current = settings?.raceboxMapX ?: "X",
                        options = axisOptions,
                        onSelect = { settingsViewModel.updateRaceboxMapX(it) }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    InlineAxisDropdown(
                        label = stringResource(R.string.external_gps_map_y),
                        current = settings?.raceboxMapY ?: "Y",
                        options = axisOptions,
                        onSelect = { settingsViewModel.updateRaceboxMapY(it) }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    InlineAxisDropdown(
                        label = stringResource(R.string.external_gps_map_z),
                        current = settings?.raceboxMapZ ?: "Z",
                        options = axisOptions,
                        onSelect = { settingsViewModel.updateRaceboxMapZ(it) }
                    )
                }
            }

            LeftAlignedScanButton(
                label = stringResource(R.string.external_gps_autodetect),
                onClick = {
                    viewModel.startAutoDetect { mapX, mapY, mapZ ->
                        settingsViewModel.setRaceboxAxisMap(mapX, mapY, mapZ)
                    }
                }
            )
        }

        Spacer(Modifier.height(4.dp))
        ToggleRow(
            label = stringResource(R.string.gps_prioritize_external),
            checked = settings?.gpsPrioritizeExternal == true,
            onCheckedChange = { settingsViewModel.updateGpsPrioritizeExternal(it) }
        )
        HintText(stringResource(R.string.gps_prioritize_external_desc), small = true)
        }   // end BringIntoViewSection
    }

    if (autoDetectPhase !is ExternalGpsViewModel.AutoDetectPhase.Idle) {
        AutoDetectDialog(
            phase = autoDetectPhase,
            onCancel = { viewModel.cancelAutoDetect() },
            onNext = { viewModel.nextAutoDetectStep() },
            onDismiss = { viewModel.dismissAutoDetect() }
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            colors = themedSwitchColors(),)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InlineAxisDropdown(
    label: String,
    current: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == current }?.second ?: current
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AutoDetectDialog(
    phase: ExternalGpsViewModel.AutoDetectPhase,
    onCancel: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit
) {
    // Wizard state machine: each motion phase has a paired Instruct + Capture
    // dialog. Instruct shows the user what to do and waits for Next; Capture
    // runs the accelerometer poll and only shows Cancel (and not even that
    // on the final vertical capture, since by the time it completes the
    // result has already been applied).
    val instruction: String
    val showSpinner: Boolean
    val showNext: Boolean
    val showCancel: Boolean
    val isDone: Boolean
    when (phase) {
        is ExternalGpsViewModel.AutoDetectPhase.Prep -> {
            instruction = stringResource(R.string.external_gps_autodetect_prep)
            showSpinner = false
            showNext = true
            showCancel = true
            isDone = false
        }
        is ExternalGpsViewModel.AutoDetectPhase.ForwardInstruct -> {
            instruction = stringResource(R.string.external_gps_autodetect_forward)
            showSpinner = false
            showNext = true
            showCancel = true
            isDone = false
        }
        is ExternalGpsViewModel.AutoDetectPhase.ForwardCapture -> {
            instruction = stringResource(R.string.external_gps_autodetect_forward_capture)
            showSpinner = true
            showNext = false
            showCancel = true
            isDone = false
        }
        is ExternalGpsViewModel.AutoDetectPhase.LateralInstruct -> {
            instruction = stringResource(R.string.external_gps_autodetect_lateral)
            showSpinner = false
            showNext = true
            showCancel = true
            isDone = false
        }
        is ExternalGpsViewModel.AutoDetectPhase.LateralCapture -> {
            instruction = stringResource(R.string.external_gps_autodetect_lateral_capture)
            showSpinner = true
            showNext = false
            showCancel = true
            isDone = false
        }
        is ExternalGpsViewModel.AutoDetectPhase.VerticalInstruct -> {
            instruction = stringResource(R.string.external_gps_autodetect_vertical)
            showSpinner = false
            showNext = true
            showCancel = true
            isDone = false
        }
        is ExternalGpsViewModel.AutoDetectPhase.VerticalCapture -> {
            instruction = stringResource(R.string.external_gps_autodetect_vertical_capture)
            showSpinner = true
            showNext = false
            showCancel = false  // results land at the end of this phase
            isDone = false
        }
        is ExternalGpsViewModel.AutoDetectPhase.Done -> {
            instruction = stringResource(R.string.external_gps_autodetect_done)
            showSpinner = false
            showNext = false
            showCancel = false
            isDone = true
        }
        else -> {
            instruction = ""
            showSpinner = false
            showNext = false
            showCancel = true
            isDone = false
        }
    }
    AlertDialog(
        onDismissRequest = {
            if (isDone) onDismiss() else if (showCancel) onCancel()
        },
        title = { Text(stringResource(R.string.external_gps_autodetect_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(instruction, style = MaterialTheme.typography.bodyLarge)
                if (showSpinner) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        },
        // confirmButton: Next during instruct screens, Done on the final screen
        // (matches the finish-button word other wizards use; saying "OK" on a
        // success-summary screen reads weaker than affirming the completion).
        confirmButton = {
            when {
                isDone -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done))
                }
                showNext -> TextButton(onClick = onNext) {
                    Text(stringResource(R.string.external_gps_autodetect_next))
                }
                else -> {}
            }
        },
        // dismissButton: Cancel everywhere it's allowed (skipped on the final
        // capture and on Done).
        dismissButton = {
            if (showCancel) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.external_gps_autodetect_abort), color = MaterialTheme.appColors.statusDanger)
                }
            }
        }
    )
}

@Composable
private fun UnpairedExternalGpsCard(
    scanning: Boolean,
    results: List<com.eried.eucplanet.ble.gps.ExternalGpsScanResult>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPick: (com.eried.eucplanet.ble.gps.ExternalGpsScanResult) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        results.forEach { result ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(result.device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${result.source.displayName}  ·  ${result.device.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { onPick(result) }) {
                        Text(stringResource(R.string.external_gps_pair_action))
                    }
                }
            }
        }
        if (scanning) {
            CircularProgressIndicator(modifier = Modifier.padding(vertical = 4.dp))
            LeftAlignedScanButton(
                label = stringResource(R.string.external_gps_stop_scan),
                onClick = onStopScan,
                containerColor = MaterialTheme.appColors.statusDanger
            )
        } else {
            LeftAlignedScanButton(
                label = stringResource(R.string.external_gps_pair_button),
                onClick = onStartScan
            )
        }
    }
}

@Composable
private fun PairedExternalGpsCard(
    deviceName: String,
    connectionState: ConnectionState,
    liveSpeedKmh: Float?
) {
    val stateLabel = when (connectionState) {
        ConnectionState.CONNECTED -> stringResource(R.string.external_gps_state_connected)
        ConnectionState.CONNECTING, ConnectionState.INITIALIZING, ConnectionState.SCANNING ->
            stringResource(R.string.external_gps_state_connecting)
        ConnectionState.DISCONNECTED -> stringResource(R.string.external_gps_state_disconnected)
    }
    val stateColor = when (connectionState) {
        ConnectionState.CONNECTED -> MaterialTheme.appColors.statusGood
        ConnectionState.CONNECTING, ConnectionState.INITIALIZING, ConnectionState.SCANNING ->
            MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.DISCONNECTED -> MaterialTheme.appColors.statusDanger
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.external_gps_paired_with, deviceName),
                style = MaterialTheme.typography.titleMedium
            )
            // Combine the connection-state label and the live speed onto a
            // single line so the card stays compact: "Connected, 28.7 km/h".
            val combinedLine = if (connectionState == ConnectionState.CONNECTED && liveSpeedKmh != null) {
                stateLabel + "  ·  " + "%.1f km/h".format(liveSpeedKmh)
            } else {
                stateLabel
            }
            Text(combinedLine, style = MaterialTheme.typography.bodyMedium, color = stateColor)
        }
    }
}

/**
 * Section-action button styled to match the "Synchronize all" button in the
 * cloud trips section: a half-width Button sitting on the left half of a
 * full-width Row, with an empty Spacer on the right. Shared across the
 * Integration tab (Flic / External GPS Start scan + Stop scan), the Alarms
 * tab (New alarm) and any other "kick off a thing" action in the
 * settings, so they all read as the same kind of action at a glance.
 *
 * The half-width Row pattern is preferred over a fixed-dp Button because
 * it scales with the screen, looks proportional on small phones AND
 * tablets without needing a separate width per breakpoint.
 */
@Composable
fun LeftAlignedScanButton(
    label: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier
) {
    val colors = if (containerColor != null)
        ButtonDefaults.buttonColors(containerColor = containerColor)
    else ButtonDefaults.buttonColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.weight(1f),
            colors = colors
        ) {
            if (leadingIcon != null) {
                androidx.compose.material3.Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(text = label)
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
