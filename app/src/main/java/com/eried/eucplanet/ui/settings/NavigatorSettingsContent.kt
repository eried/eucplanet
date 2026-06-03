package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.ui.common.HintText
import kotlin.math.roundToInt
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.ui.theme.themedSwitchColors
import com.eried.eucplanet.ui.theme.themedSliderColors

/**
 * The "Navigator" settings section: voice guidance, the default travel mode,
 * arrival / off-route thresholds and the (advanced) routing endpoints.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigatorSettingsContent(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settingsState by viewModel.settings.collectAsState()
    val settings = settingsState ?: return

    // Tolerances are stored in metres; show them in the rider's chosen unit.
    fun distLabel(meters: Int): String =
        if (settings.imperialUnits) "${(meters * 3.28084).roundToInt()} ft"
        else "$meters m"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Full path vs Next segment ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.nav_setting_full_path),
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = settings.navSolveFullPath,
                onCheckedChange = { viewModel.updateNavSolveFullPath(it) },
                colors = themedSwitchColors(),
            )
        }
        HintText(stringResource(R.string.nav_setting_full_path_desc), small = true)

        // --- Arrival radius ---
        SliderRow(
            label = stringResource(R.string.nav_setting_arrival_radius),
            valueText = distLabel(settings.navArrivalRadiusM),
            value = settings.navArrivalRadiusM.toFloat(),
            range = 5f..100f,
            steps = 18,
            onChange = { viewModel.updateNavArrivalRadius((it / 5f).roundToInt() * 5) }
        )
        HintText(stringResource(R.string.nav_setting_arrival_radius_desc), small = true)

        // --- Off-route tolerance ---
        SliderRow(
            label = stringResource(R.string.nav_setting_offroute),
            valueText = distLabel(settings.navOffRouteToleranceM),
            value = settings.navOffRouteToleranceM.toFloat(),
            range = 15f..150f,
            steps = 26,
            onChange = { viewModel.updateNavOffRouteTolerance((it / 5f).roundToInt() * 5) }
        )
        HintText(stringResource(R.string.nav_setting_offroute_desc), small = true)

        // --- Routing endpoints (advanced) ---
        Text(
            stringResource(R.string.nav_setting_endpoints),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        HintText(stringResource(R.string.nav_setting_endpoints_hint), small = true)

        // The endpoint fields edit a local copy and persist only on focus loss,
        // so a half-typed URL isn't written to DataStore on every keystroke.
        var geocoder by rememberSaveable(settings.navGeocoderUrl) {
            mutableStateOf(settings.navGeocoderUrl)
        }
        OutlinedTextField(
            value = geocoder,
            onValueChange = { geocoder = it },
            label = { Text(stringResource(R.string.nav_setting_geocoder_url)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { fs ->
                    if (!fs.isFocused && geocoder != settings.navGeocoderUrl) {
                        viewModel.updateNavGeocoderUrl(geocoder.trim())
                    }
                },
            colors = themedFieldColors(),
        )

        var router by rememberSaveable(settings.navRouterUrl) {
            mutableStateOf(settings.navRouterUrl)
        }
        OutlinedTextField(
            value = router,
            onValueChange = { router = it },
            label = { Text(stringResource(R.string.nav_setting_router_url)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { fs ->
                    if (!fs.isFocused && router != settings.navRouterUrl) {
                        viewModel.updateNavRouterUrl(router.trim())
                    }
                },
            colors = themedFieldColors(),
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = themedSliderColors(),
        )
    }
}
