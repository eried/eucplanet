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
import com.eried.eucplanet.tpms.LyTpmsDecoder
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
    val sensors by viewModel.sensors.collectAsState()
    val activeAddress by viewModel.activeAddress.collectAsState()
    val wheelIsActive by viewModel.wheelIsActive.collectAsState()
    val tempUnit by viewModel.tempUnit.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    // Scans ask to turn Bluetooth on rather than reporting that it is off.
    val startScan = rememberScanStarter()

    val wheelHasSensor = wheelKpa > 0f
    val hasPaired = sensors.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Your sensor first. With one paired there is nothing to explain, so
        // nothing is explained: the row IS the answer.
        // One row per cap. A rider has more than one wheel and each of them
        // has its own sensor, which is why a single slot was wrong: the second
        // cap was dropped before it could even be listed.
        sensors.forEach { sensor ->
            TpmsSensorRow(
                // Named for the platform, not for one shop's badge. The
                // same Wicarlink code decodes every one of these caps
                // whichever app they were sold with - LY TPMS, ITPMS and the
                // unbranded ones - so the family name is the honest label and
                // the aliases are listed in docs/protocols/wicarlink-tpms.md.
                //
                // The id beside it is the sensor's own, decoded from the
                // packet: the same characters the rider already sees in the
                // app the cap came with, rather than a slice of a MAC.
                title = stringResource(
                    R.string.tpms_paired_sensor_fmt,
                    stringResource(R.string.tpms_family_ly),
                    shortAddress(sensor.address),
                ),
                // What the sensor is telling us. A cap that has not spoken
                // since the app started has nothing to say yet, and says so
                // rather than blaming the decoder: these transmit when the
                // pressure moves and stay quiet on a settled tyre.
                // A cap that says it is losing air says it here, ahead of
                // everything else: that is the sentence the whole feature
                // exists for. Inflating earns its place too, as feedback while
                // a rider stands there with a pump. The other three states are
                // the cap waking up and mean nothing to anyone.
                subtitle = if (sensor.kpa == null) {
                    stringResource(R.string.tpms_waiting)
                } else {
                    // Everything the cap says, on the one line it has: battery,
                    // air temperature, and what it thinks is happening. The
                    // state used to replace the other two, which threw away
                    // facts to make room for a word.
                    listOfNotNull(
                        sensor.volts?.let { "%.2f V".format(it) },
                        sensor.tempC?.let { formatTemp(it, tempUnit) },
                        when (sensor.state) {
                            LyTpmsDecoder.State.LEAKAGE -> stringResource(R.string.tpms_state_leaking)
                            LyTpmsDecoder.State.INFLATION -> stringResource(R.string.tpms_state_inflating)
                            // The other three are the cap waking up and say
                            // nothing about the tyre, so they say nothing here.
                            else -> null
                        },
                    ).joinToString("  ·  ")
                },
                reading = sensor.kpa?.let { formatPressure(it, unit) },
                active = sensor.address == activeAddress,
                onRemove = { viewModel.forget(sensor.address) },
            )
        }

        // The wheel's own, only when the wheel actually reports one.
        if (wheelHasSensor) {
            TpmsSensorRow(
                title = stringResource(R.string.tpms_wheel_sensor),
                // "Replaced" when it has been: a cap that is paired but
                // has not spoken yet has replaced nothing, and the wheel is
                // still the sensor answering for the tyre.
                subtitle = stringResource(
                    if (hasPaired && !wheelIsActive) R.string.tpms_wheel_sensor_replaced
                    else R.string.tpms_wheel_sensor_desc
                ),
                reading = formatPressure(wheelKpa, unit),
                active = wheelIsActive,
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
 * The sensor id the vendor app shows for this cap.
 *
 * The first three octets of the address, which for this family is the same
 * string the packet carries as its id: the cap sends its id as three bytes
 * printed backwards, and those are the reversed MAC tail. Showing the LAST few
 * characters instead - the usual trick - would name every sensor identically,
 * because this family burns the same 11:11:11 into every unit it ships.
 *
 * Same characters the rider already sees in the app the cap was sold with, so
 * a sensor added in both places is recognisable in both.
 */
/** The sensor's own air temperature, in the rider's unit. */
@Composable
private fun formatTemp(c: Float, unit: String): String =
    "%.0f%s".format(com.eried.eucplanet.util.Units.temperature(c, unit),
        com.eried.eucplanet.util.Units.tempUnit(unit))

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
    /** True for the sensor currently speaking for the tyre. */
    active: Boolean = false,
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
                // The Watch section's badge, verbatim: the same dot, the
                // same accent, and the same Live / Idle wording. A dot on its
                // own was a colour the rider had to learn; this is the
                // vocabulary the app already uses for "this device is the one
                // talking right now".
                //
                // It also explains a frozen number. These caps report when the
                // pressure moves, so a sensor that has gone quiet keeps its
                // last reading on screen, and Idle is what says that reading
                // is old rather than wrong.
                Icon(
                    imageVector = Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.appColors.connectionActive
                        else LocalContentColor.current.copy(alpha = 0.5f),
                    modifier = Modifier.size(10.dp),
                )
                // Only the quiet state gets a word. A green dot beside a live
                // reading already says live; writing it as well is the label
                // twice. Idle is the one that needs saying, because a number
                // that has stopped moving looks exactly like one that has not.
                if (!active) {
                    Text(
                        stringResource(R.string.status_idle),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                    )
                }
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
private fun formatPressure(kpa: Float, unit: String): String =
    Units.formatPressure(kpa, unit)
