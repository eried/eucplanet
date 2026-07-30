package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.Units

/**
 * TPMS sensors section (Settings, under External GPS). Auto-detects a wheel that
 * relays tire pressure: shows the live value when a sensor is reporting, nothing
 * when it isn't. Pairing a direct BLE sensor lands once its profile is captured.
 */
@Composable
fun TpmsSection(viewModel: TpmsViewModel = hiltViewModel()) {
    val kpa by viewModel.tirePressureKpa.collectAsState()
    val unit by viewModel.pressureUnit.collectAsState()
    val hasData = kpa > 0f

    HintText(stringResource(R.string.tpms_caption))

    if (hasData) {
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.appColors.surfaceVariant,
                contentColor = MaterialTheme.appColors.textPrimary,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tpms_wheel_sensor),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.appColors.textPrimary,
                    )
                    Text(
                        stringResource(R.string.tpms_wheel_sensor_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.textSecondary,
                    )
                }
                Text(
                    formatPressure(kpa, unit),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.appColors.primary,
                )
            }
        }
    } else {
        Spacer(Modifier.height(4.dp))
        HintText(stringResource(R.string.tpms_no_data), small = true)
    }

    // Pairing a direct BLE sensor is gated on capturing that sensor's profile.
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.tpms_pair_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.appColors.textPrimary,
    )
    HintText(stringResource(R.string.tpms_pair_coming), small = true)
}

private fun formatPressure(kpa: Float, unit: String): String =
    if (unit == "psi") "%.1f psi".format(Units.pressure(kpa, "psi"))
    else "%.2f bar".format(Units.pressure(kpa, "bar"))
