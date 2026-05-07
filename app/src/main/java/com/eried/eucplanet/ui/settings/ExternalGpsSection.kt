package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentRed

/**
 * Section in the Integration tab for pairing/unpairing an external BLE GPS box.
 * Two faces:
 *  * Not paired: caption + a single "Pair external GPS" button that switches to
 *    the scan card. Picking a result pairs and triggers connect.
 *  * Paired: status card with name, connection state, live speed when connected,
 *    plus reconnect / disconnect / forget.
 */
@Composable
fun ExternalGpsSection(viewModel: ExternalGpsViewModel = hiltViewModel()) {
    val pairedAddress by viewModel.pairedAddress.collectAsState()
    val pairedName by viewModel.pairedName.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val scanResults by viewModel.scanResults.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val sample by viewModel.currentSample.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.section_external_gps))
        HintText(stringResource(R.string.external_gps_caption), small = true)

        if (pairedAddress == null) {
            // Not paired: scan card with start/stop and a list of discovered devices.
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
                liveSpeedKmh = sample?.speedKmh,
                onReconnect = { viewModel.reconnect() },
                onDisconnect = { viewModel.disconnect() },
                onUnpair = { viewModel.unpair() }
            )
        }
    }
}

@Composable
private fun UnpairedExternalGpsCard(
    scanning: Boolean,
    results: List<com.eried.eucplanet.ble.gps.ExternalGpsScanResult>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPick: (com.eried.eucplanet.ble.gps.ExternalGpsScanResult) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!scanning && results.isEmpty()) {
                Button(
                    onClick = onStartScan,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.external_gps_pair_button)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(
                            stringResource(R.string.external_gps_scanning),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                results.forEach { result ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                if (scanning && results.isEmpty()) {
                    HintText(stringResource(R.string.external_gps_no_results), small = true)
                }
                Spacer(Modifier.height(4.dp))
                if (scanning) {
                    Button(
                        onClick = onStopScan,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                    ) { Text(stringResource(R.string.external_gps_stop_scan)) }
                }
            }
        }
    }
}

@Composable
private fun PairedExternalGpsCard(
    deviceName: String,
    connectionState: ConnectionState,
    liveSpeedKmh: Float?,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit
) {
    val stateLabel = when (connectionState) {
        ConnectionState.CONNECTED -> stringResource(R.string.external_gps_state_connected)
        ConnectionState.CONNECTING, ConnectionState.INITIALIZING, ConnectionState.SCANNING ->
            stringResource(R.string.external_gps_state_connecting)
        ConnectionState.DISCONNECTED -> stringResource(R.string.external_gps_state_disconnected)
    }
    val stateColor = when (connectionState) {
        ConnectionState.CONNECTED -> AccentGreen
        ConnectionState.CONNECTING, ConnectionState.INITIALIZING, ConnectionState.SCANNING ->
            MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.DISCONNECTED -> AccentRed
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
            Text(stateLabel, style = MaterialTheme.typography.bodyMedium, color = stateColor)
            if (connectionState == ConnectionState.CONNECTED && liveSpeedKmh != null) {
                Text(
                    stringResource(R.string.external_gps_live_speed, liveSpeedKmh),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connectionState == ConnectionState.CONNECTED) {
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.external_gps_disconnect))
                    }
                } else {
                    Button(onClick = onReconnect, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.external_gps_reconnect))
                    }
                }
                TextButton(onClick = onUnpair) {
                    Text(stringResource(R.string.external_gps_unpair), color = AccentRed)
                }
            }
        }
    }
}
