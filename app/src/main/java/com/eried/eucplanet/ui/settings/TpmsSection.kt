package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.common.InfoHint
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.Units

/**
 * TPMS sensors, shown inside the Integration section. The wheel-relayed sensor
 * is rendered as a Watch-style "device" row (icon, name, status subtitle, live
 * dot + reading) so it reads in line with the rest of the app. Direct BLE
 * pairing is stubbed - the scan button stays disabled until a sensor profile
 * is captured.
 */
@Composable
fun TpmsSection(viewModel: TpmsViewModel = hiltViewModel()) {
    val wheelKpa by viewModel.tirePressureKpa.collectAsState()
    val unit by viewModel.pressureUnit.collectAsState()
    val paired by viewModel.paired.collectAsState()
    val pairedKpa by viewModel.pairedKpa.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val seen by viewModel.seen.collectAsState()

    val wheelHasSensor = wheelKpa > 0f
    val hasPaired = paired != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HintText(stringResource(R.string.tpms_caption), small = true)

        // Your sensors first, because they are the answer to the question the
        // screen is asking. A paired sensor outranks the wheel's own, which is
        // the rule the whole feature follows.
        if (hasPaired) {
            TpmsSensorRow(
                title = stringResource(R.string.tpms_paired_sensor),
                subtitle = paired.orEmpty(),
                reading = pairedKpa?.let { formatPressure(it, unit) },
                onRemove = { viewModel.forgetPaired() },
            )
        }

        // The wheel's own sensor, only when there is one. Saying "no sensor
        // reporting yet" on a wheel that has never had one is an answer to a
        // question nobody asked, and it sat above a paired sensor that WAS
        // reporting, which read as a contradiction.
        if (wheelHasSensor) {
            TpmsSensorRow(
                title = stringResource(R.string.tpms_wheel_sensor),
                subtitle = stringResource(
                    if (hasPaired) R.string.tpms_wheel_sensor_replaced
                    else R.string.tpms_wheel_sensor_desc
                ),
                reading = formatPressure(wheelKpa, unit),
            )
        }

        if (!hasPaired && !wheelHasSensor && !scanning) {
            // The app's info hint, icon and all, rather than a bare line of
            // text: this is the state a rider lands on with nothing set up,
            // and it is the one place the section explains itself.
            InfoHint(stringResource(R.string.tpms_none_yet))
        }

        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                if (hasPaired) R.string.tpms_pair_replace_title else R.string.tpms_pair_title
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.appColors.textPrimary,
        )
        HintText(stringResource(R.string.tpms_pair_replaces), small = true)
        LeftAlignedScanButton(
            label = stringResource(
                if (scanning) R.string.tpms_scan_stop else R.string.tpms_scan
            ),
            onClick = { viewModel.toggleScan() },
            enabled = true,
            // Red while scanning, like every other stop-scan in the app
            // (external GPS, radar, Flic). A scan is a radio the rider has to
            // remember to switch off, so the button that stops it is marked.
            containerColor = if (scanning) MaterialTheme.appColors.statusDanger else null,
        )

        // Only while scanning, and only ones that are not already yours. A
        // sensor that decodes is adopted on sight, so anything still listed
        // here is either a new model being worked out or not a sensor at all.
        if (scanning) {
            val others = seen.filter { it.looksLikeSensor && it.address != paired }
            if (others.isEmpty()) {
                HintText(stringResource(R.string.tpms_scan_listening), small = true)
            }
            others.take(4).forEach { adv ->
                TpmsSensorRow(
                    title = adv.name?.takeIf { it.isNotBlank() } ?: adv.address,
                    subtitle = adv.manufacturer.entries.joinToString(" ") {
                        "0x%04X %s".format(it.key, it.value)
                    }.ifBlank { adv.service.values.joinToString(" ") },
                    reading = adv.kpa?.let { formatPressure(it, unit) } ?: "${adv.rssi} dBm",
                )
            }
        }
    }
}

/**
 * One sensor rendered like a Watch device card: leading icon, name over a
 * status subtitle, and a live/idle dot on the right (with the reading when a
 * sensor is reporting). Mirrors DeviceCard so both read as the same kind of row.
 */
@Composable
private fun TpmsSensorRow(
    title: String,
    subtitle: String,
    reading: String?,
    onRemove: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.appColors.surface,
        contentColor = MaterialTheme.appColors.textPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    tint = if (reading != null) MaterialTheme.appColors.connectionActive
                        else LocalContentColor.current.copy(alpha = 0.5f),
                    modifier = Modifier.size(10.dp),
                )
                if (reading != null) {
                    Text(
                        reading,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.appColors.primary,
                    )
                }
                // Only the rider's own sensors can be removed; the wheel's is
                // the wheel's, and a scan result is not yours to forget.
                if (onRemove != null) {
                    androidx.compose.material3.IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.tpms_forget),
                            tint = MaterialTheme.appColors.statusDanger,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A pressure in the rider's unit.
 *
 * kPa had no branch and fell through to bar, so choosing it showed a bar
 * number labelled bar while the rider had asked for kPa. Decimals differ
 * because the units do: 77.4 psi and 5.34 bar carry about the same precision,
 * and kPa is already fine as a whole number.
 */
private fun formatPressure(kpa: Float, unit: String): String = when (unit) {
    "psi" -> "%.1f psi".format(Units.pressure(kpa, "psi"))
    "kpa", "kPa" -> "%.0f kPa".format(kpa)
    else -> "%.2f bar".format(Units.pressure(kpa, "bar"))
}
