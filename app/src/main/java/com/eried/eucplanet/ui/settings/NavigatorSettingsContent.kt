package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
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

        // --- Avoidances (collapsible; all OFF by default) ---
        var avoidExpanded by rememberSaveable { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { avoidExpanded = !avoidExpanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.nav_setting_avoid),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                if (avoidExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (avoidExpanded) {
            HintText(stringResource(R.string.nav_setting_avoid_hint), small = true)
            SwitchRow(
                label = stringResource(R.string.nav_setting_avoid_highways),
                checked = settings.navAvoidHighways,
                onChange = { viewModel.updateNavAvoidHighways(it) }
            )
            SwitchRow(
                label = stringResource(R.string.nav_setting_avoid_tolls),
                checked = settings.navAvoidTolls,
                onChange = { viewModel.updateNavAvoidTolls(it) }
            )
            SwitchRow(
                label = stringResource(R.string.nav_setting_avoid_ferries),
                checked = settings.navAvoidFerries,
                onChange = { viewModel.updateNavAvoidFerries(it) }
            )
            SwitchRow(
                label = stringResource(R.string.nav_setting_avoid_unpaved),
                checked = settings.navAvoidUnpaved,
                onChange = { viewModel.updateNavAvoidUnpaved(it) }
            )
        }

        // --- Routing services ---
        // Address search + routing always work (basic navigation), so those URLs
        // stay editable. Advanced map features add the on-map charger / places
        // layers; only those extra source fields are gated behind it.
        val endpointsEnabled = settings.navAdvancedMap
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

        // Advanced map features gate the on-map overlays + their source fields
        // below (chargers / places / community), but NOT the basic URLs above.
        SwitchRow(
            label = stringResource(R.string.nav_setting_advanced_map),
            checked = settings.navAdvancedMap,
            onChange = { viewModel.updateNavAdvancedMap(it) }
        )
        HintText(stringResource(R.string.nav_setting_advanced_map_desc), small = true)

        var overpass by rememberSaveable(settings.navOverpassUrl) {
            mutableStateOf(settings.navOverpassUrl)
        }
        OutlinedTextField(
            value = overpass,
            onValueChange = { overpass = it },
            enabled = endpointsEnabled,
            label = { Text(stringResource(R.string.nav_setting_overpass_url)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { fs ->
                    if (!fs.isFocused && overpass != settings.navOverpassUrl) {
                        viewModel.updateNavOverpassUrl(overpass.trim())
                    }
                },
            colors = themedFieldColors(),
        )

        // Title, then the description + clickable link directly under it, then
        // the key field.
        Text(
            stringResource(R.string.nav_setting_ocm_subtitle),
            style = MaterialTheme.typography.titleSmall,
            color = if (endpointsEnabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Description with the openchargemap.org link inline at the end.
        val hint = stringResource(R.string.nav_setting_ocm_key_hint)
        val urlText = stringResource(R.string.nav_setting_ocm_key_link)
        val annotated = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                append("$hint ")
            }
            withLink(LinkAnnotation.Url("https://openchargemap.org/site/profile/applications")) {
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline
                    )
                ) { append(urlText) }
            }
        }
        Text(annotated, style = MaterialTheme.typography.bodySmall)
        var ocmKey by rememberSaveable(settings.navOcmApiKey) {
            mutableStateOf(settings.navOcmApiKey)
        }
        OutlinedTextField(
            value = ocmKey,
            onValueChange = { ocmKey = it },
            enabled = endpointsEnabled,
            label = { Text(stringResource(R.string.nav_setting_ocm_key)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { fs ->
                    if (!fs.isFocused && ocmKey != settings.navOcmApiKey) {
                        viewModel.updateNavOcmApiKey(ocmKey.trim())
                    }
                },
            colors = themedFieldColors(),
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = themedSwitchColors(),
        )
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
