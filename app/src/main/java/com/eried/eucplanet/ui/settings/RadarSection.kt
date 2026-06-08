package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.radar.RadarScanResult
import com.eried.eucplanet.data.model.RadarFrame
import com.eried.eucplanet.data.model.ThreatLevel
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedSegmentedColors
import com.eried.eucplanet.ui.theme.themedSwitchColors

/**
 * Sub-section of the Integration settings tab for pairing a rear-view radar
 * (Garmin Varia). Lives under [FlicTab] alongside Flic buttons and volume
 * keys. Twin of [ExternalGpsSection]: not-paired card → scan; paired card →
 * status + live frame debug view + Disconnect/Reconnect/Forget.
 */
@Composable
fun RadarSection(
    viewModel: RadarViewModel = hiltViewModel()
) {
    val pairedAddress by viewModel.pairedAddress.collectAsState()
    val pairedName by viewModel.pairedName.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val scanResults by viewModel.scanResults.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val frame by viewModel.currentFrame.collectAsState()
    val bluetoothOff by viewModel.bluetoothOff.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pairedAddress == null) {
            HintText(stringResource(R.string.radar_caption), small = true)
            if (bluetoothOff) {
                HintText(stringResource(R.string.scan_bluetooth_off_title), small = true)
            }
            UnpairedRadarCard(
                scanning = scanning,
                results = scanResults,
                onStartScan = { viewModel.startScan() },
                onStopScan = { viewModel.stopScan() },
                onPick = { viewModel.pair(it) }
            )
        } else {
            PairedRadarCard(
                deviceName = pairedName ?: pairedAddress!!,
                connectionState = connectionState,
                frame = frame
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (connectionState == ConnectionState.CONNECTED) {
                    Button(
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.radar_disconnect))
                    }
                } else {
                    Button(
                        onClick = { viewModel.reconnect() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.radar_reconnect))
                    }
                }
                Button(
                    onClick = { viewModel.unpair() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.appColors.statusDanger)
                ) {
                    Text(stringResource(R.string.radar_unpair))
                }
            }

            RadarOverlaySettings(viewModel)
        }
    }
}

@Composable
private fun RadarOverlaySettings(viewModel: RadarViewModel) {
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val settings by settingsViewModel.settings.collectAsState()
    val s = settings ?: return

    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(R.string.radar_show_overlay),
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(
            checked = s.radarShowOverlay,
            onCheckedChange = { viewModel.updateShowOverlay(it) },
            colors = themedSwitchColors(),
        )
    }
    HintText(stringResource(R.string.radar_show_overlay_desc), small = true)

    if (s.radarShowOverlay) {
        val sideOptions = listOf(
            "LEFT" to stringResource(R.string.radar_overlay_left),
            "BOTH" to stringResource(R.string.radar_overlay_both),
            "RIGHT" to stringResource(R.string.radar_overlay_right)
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
        ) {
            sideOptions.forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = key == s.radarOverlaySide,
                    onClick = { viewModel.updateOverlaySide(key) },
                    shape = SegmentedButtonDefaults.itemShape(i, sideOptions.size),
                    colors = themedSegmentedColors(),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun UnpairedRadarCard(
    scanning: Boolean,
    results: List<RadarScanResult>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPick: (RadarScanResult) -> Unit
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
                            "${result.vendor.displayName}  ·  ${result.device.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { onPick(result) }) {
                        Text(stringResource(R.string.radar_pair_action))
                    }
                }
            }
        }
        if (scanning) {
            CircularProgressIndicator(modifier = Modifier.padding(vertical = 4.dp))
            LeftAlignedScanButton(
                label = stringResource(R.string.radar_stop_scan),
                onClick = onStopScan,
                containerColor = MaterialTheme.appColors.statusDanger
            )
        } else {
            LeftAlignedScanButton(
                label = stringResource(R.string.radar_pair_button),
                onClick = onStartScan
            )
        }
    }
}

@Composable
private fun PairedRadarCard(
    deviceName: String,
    connectionState: ConnectionState,
    frame: RadarFrame?
) {
    val stateLabel = when (connectionState) {
        ConnectionState.CONNECTED -> stringResource(R.string.radar_state_connected)
        ConnectionState.CONNECTING, ConnectionState.INITIALIZING, ConnectionState.SCANNING ->
            stringResource(R.string.radar_state_connecting)
        ConnectionState.DISCONNECTED -> stringResource(R.string.radar_state_disconnected)
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
                deviceName,
                style = MaterialTheme.typography.titleMedium
            )
            val battery = frame?.batteryPercent
            val combined = buildString {
                append(stateLabel)
                if (connectionState == ConnectionState.CONNECTED && battery != null) {
                    append("  ·  ")
                    append(stringResource(R.string.radar_battery_fmt, battery))
                }
            }
            Text(combined, style = MaterialTheme.typography.bodyMedium, color = stateColor)

            if (connectionState == ConnectionState.CONNECTED && frame != null) {
                if (frame.threats.isEmpty()) {
                    Text(
                        stringResource(R.string.radar_clear),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.appColors.statusGood
                    )
                } else {
                    // Debug-friendly per-target dump so the user can confirm
                    // the parser works against their actual hardware before
                    // we commit to the overlay UI. Fixed-height scroll area
                    // so the card stays a constant size while threat count
                    // bounces around in demo / busy-road mode.
                    Text(
                        stringResource(R.string.radar_threats_count, frame.threats.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 132.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        frame.threats.sortedBy { it.distanceM }.forEach { t ->
                            val color = when (t.threatLevel) {
                                ThreatLevel.FAST_APPROACH -> MaterialTheme.appColors.statusDanger
                                ThreatLevel.APPROACHING -> MaterialTheme.appColors.statusWarn
                                ThreatLevel.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                "#${t.id}  ·  ${t.distanceM} m  ·  ${t.approachSpeedKmh} km/h",
                                style = MaterialTheme.typography.bodySmall,
                                color = color
                            )
                        }
                    }
                }
            }
        }
    }
}
