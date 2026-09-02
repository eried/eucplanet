package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.eried.eucplanet.ui.common.rememberScanStarter
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
    // Scans ask to turn Bluetooth on rather than reporting that it is off.
    val startScan = rememberScanStarter()
    val seen by viewModel.seen.collectAsState()

    val wheelHasSensor = wheelKpa > 0f
    val hasPaired = paired != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Your sensor first. With one paired there is nothing to explain, so
        // nothing is explained: the row IS the answer.
        if (hasPaired) {
            TpmsSensorRow(
                // Named for the sensor rather than for what it is. "Tire
                // sensor" is the category, and with one row it says nothing a
                // rider did not already know; the family plus the front of the
                // address is what they recognise their own cap by.
                title = stringResource(
                    R.string.tpms_paired_sensor_fmt,
                    stringResource(R.string.tpms_family_ly),
                    shortAddress(paired),
                ),
                // The address is only worth the line while there is a reading
                // to go with it. With none, the rider's question is why the
                // number is missing, and the address does not answer it.
                subtitle = if (pairedKpa == null) stringResource(R.string.tpms_not_decoded)
                    else paired.orEmpty(),
                reading = pairedKpa?.let { formatPressure(it, unit) },
                onRemove = { viewModel.forgetPaired() },
            )
        }

        // The wheel's own, only when the wheel actually reports one.
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

        // Nothing set up: the one sentence worth printing is that nothing is
        // set up, with the button that fixes it directly under. The caption
        // that used to sit here described the screen instead of being it.
        if (!hasPaired && !wheelHasSensor) {
            InfoHint(stringResource(R.string.tpms_none_yet))
        }

        // Character for character what the radar and external GPS sections
        // do, because they are the two scans that live in a settings section
        // and this is the third. The spinner sits above the button there, not
        // beside it; beside it was Flic's full screen, and mine.
        if (scanning) {
            CircularProgressIndicator(modifier = Modifier.padding(vertical = 4.dp))
            LeftAlignedScanButton(
                label = stringResource(R.string.tpms_scan_stop),
                onClick = { viewModel.toggleScan() },
                containerColor = MaterialTheme.appColors.statusDanger,
            )
        } else {
            LeftAlignedScanButton(
                label = stringResource(R.string.tpms_scan),
                onClick = { startScan { viewModel.toggleScan() } },
            )
        }

    }
}

/**
 * The front of a MAC, which is how a rider recognises their own sensor.
 *
 * The first three octets: this family burns the same 11:11:11 into the tail of
 * every unit it ships, so the usual trick of showing the last few characters
 * would give every sensor the same name.
 */
private fun shortAddress(address: String?): String =
    address?.split(":")?.take(3)?.joinToString("")?.uppercase().orEmpty()

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
                    // Wraps. One line was fine while this held an address and
                    // nothing else; the moment it had to explain a missing
                    // reading it cut the sentence off mid word, leaving the
                    // rider with the half that raises the question and none of
                    // the half that answers it.
                    maxLines = 3,
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
                            imageVector = Icons.Default.Delete,
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
