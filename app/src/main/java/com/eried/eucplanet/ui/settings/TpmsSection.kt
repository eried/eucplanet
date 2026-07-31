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
    val kpa by viewModel.tirePressureKpa.collectAsState()
    val unit by viewModel.pressureUnit.collectAsState()
    val hasData = kpa > 0f

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HintText(stringResource(R.string.tpms_caption), small = true)

        TpmsSensorRow(
            title = stringResource(R.string.tpms_wheel_sensor),
            subtitle = stringResource(
                if (hasData) R.string.tpms_wheel_sensor_desc else R.string.tpms_idle
            ),
            reading = if (hasData) formatPressure(kpa, unit) else null,
        )

        // Pair another sensor: an added sensor replaces the wheel's built-in
        // one. Direct BLE pairing is stubbed, so the scan button is disabled.
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.tpms_pair_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.appColors.textPrimary,
        )
        HintText(stringResource(R.string.tpms_pair_replaces), small = true)
        LeftAlignedScanButton(
            label = stringResource(R.string.tpms_scan),
            onClick = { },
            enabled = false,
        )
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
            }
        }
    }
}

private fun formatPressure(kpa: Float, unit: String): String =
    if (unit == "psi") "%.1f psi".format(Units.pressure(kpa, "psi"))
    else "%.2f bar".format(Units.pressure(kpa, "bar"))
