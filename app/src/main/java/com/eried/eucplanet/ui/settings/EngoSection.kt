package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.service.hud.engo.EngoAdapter
import com.eried.eucplanet.service.hud.engo.EngoFound
import com.eried.eucplanet.ui.common.SearchableText
import com.eried.eucplanet.ui.common.rememberScanStarter
import com.eried.eucplanet.ui.theme.appColors
import org.koin.compose.koinInject

/**
 * Pairing the ENGO glasses, the way the radar and the external GPS pair:
 * Start scan, pick the pair, and from then on a card with its name and link
 * state, Disconnect or Reconnect, and Forget device. The remembered pair is
 * the only one the app links to.
 *
 * [paired] is drawn below the card while a pair is remembered: the glasses'
 * own options.
 */
@Composable
internal fun EngoPairing(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    paired: @Composable () -> Unit,
) {
    val engo = settings.engoHud
    val adapter = koinInject<EngoAdapter>()
    val scanning by adapter.scanning.collectAsState()
    val found by adapter.found.collectAsState()
    val state by adapter.state.collectAsState()
    // Scans ask to turn Bluetooth on rather than reporting that it is off.
    val startScan = rememberScanStarter()
    // A scan left running when the rider walks away from the screen stops.
    DisposableEffect(Unit) { onDispose { adapter.stopScan() } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val address = engo.pairedAddress
        if (address == null) {
            com.eried.eucplanet.ui.common.HintText(stringResource(R.string.engo_scan_hint))
            found.forEach { glasses ->
                FoundGlassesCard(glasses) {
                    adapter.stopScan()
                    viewModel.updateEngoPaired(glasses.address, glasses.name)
                }
            }
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.appColors.primary,
                )
                LeftAlignedScanButton(
                    label = stringResource(R.string.radar_stop_scan),
                    onClick = { adapter.stopScan() },
                    containerColor = MaterialTheme.appColors.statusDanger,
                )
            } else {
                LeftAlignedScanButton(
                    label = stringResource(R.string.radar_pair_button),
                    onClick = { startScan { adapter.startScan() } },
                )
            }
        } else {
            val linked = state == EngoAdapter.State.CONNECTED
            PairedGlassesCard(
                name = engo.pairedName ?: address,
                stateLabel = when {
                    linked -> stringResource(R.string.radar_state_connected)
                    engo.enabled -> stringResource(R.string.radar_state_connecting)
                    else -> stringResource(R.string.radar_state_disconnected)
                },
                stateColor = when {
                    linked -> MaterialTheme.appColors.statusGood
                    engo.enabled -> MaterialTheme.appColors.textSecondary
                    else -> MaterialTheme.appColors.statusDanger
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.updateEngoHudEnabled(!engo.enabled) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    SearchableText(
                        stringResource(
                            if (engo.enabled) R.string.radar_disconnect else R.string.radar_reconnect
                        )
                    )
                }
                Button(
                    onClick = { viewModel.updateEngoForget() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.appColors.statusDanger,
                        contentColor = MaterialTheme.appColors.onPrimary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    SearchableText(stringResource(R.string.radar_unpair))
                }
            }
            paired()
        }
    }
}

@Composable
private fun FoundGlassesCard(glasses: EngoFound, onPair: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.appColors.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    glasses.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.appColors.textPrimary,
                )
                Text(
                    "${glasses.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                )
            }
            Button(onClick = onPair, shape = RoundedCornerShape(12.dp)) {
                SearchableText(stringResource(R.string.radar_pair_action))
            }
        }
    }
}

@Composable
private fun PairedGlassesCard(
    name: String,
    stateLabel: String,
    stateColor: androidx.compose.ui.graphics.Color,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.appColors.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.appColors.textPrimary,
            )
            Text(stateLabel, style = MaterialTheme.typography.bodyMedium, color = stateColor)
        }
    }
}
