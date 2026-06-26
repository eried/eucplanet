package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

        // --- Arrival radius + off-route tolerance (half-and-half on one row) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NumberUpDown(
                value = settings.navArrivalRadiusM,
                onValueChange = { viewModel.updateNavArrivalRadius(it) },
                range = 5..100,
                step = 5,
                suffix = "m",
                label = stringResource(R.string.nav_setting_arrival_radius),
                modifier = Modifier.weight(1f),
            )
            NumberUpDown(
                value = settings.navOffRouteToleranceM,
                onValueChange = { viewModel.updateNavOffRouteTolerance(it) },
                range = 15..150,
                step = 5,
                suffix = "m",
                label = stringResource(R.string.nav_setting_offroute),
                modifier = Modifier.weight(1f),
            )
        }
        // Both field hints combined on one line.
        HintText(
            stringResource(R.string.nav_setting_arrival_radius_desc) + "  •  " +
                stringResource(R.string.nav_setting_offroute_desc),
            small = true
        )

        // --- Advanced map features (on-map charger / places overlays + their
        // source endpoints). The basic routing URLs further down are never
        // gated and always apply. ---
        val endpointsEnabled = settings.navAdvancedMap
        SwitchRow(
            label = stringResource(R.string.nav_setting_advanced_map),
            checked = settings.navAdvancedMap,
            onChange = { viewModel.updateNavAdvancedMap(it) }
        )

        // The "show chargers and places" description + the charger/places source
        // and community fields only matter when the overlays are on, so hide
        // them all when advanced map is off.
        if (endpointsEnabled) {
            HintText(stringResource(R.string.nav_setting_advanced_map_desc), small = true)
            EndpointField(
                label = stringResource(R.string.nav_setting_overpass_url),
                value = settings.navOverpassUrl,
                enabled = true,
                onCommit = { viewModel.updateNavOverpassUrl(it) },
                presets = OVERPASS_PRESETS,
            )

            // Charger community (Open Charge Map): title, then the description
            // with the openchargemap.org link inline, then the key field.
            Text(
                stringResource(R.string.nav_setting_ocm_subtitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
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
            EndpointField(
                label = stringResource(R.string.nav_setting_ocm_key),
                value = settings.navOcmApiKey,
                enabled = true,
                onCommit = { viewModel.updateNavOcmApiKey(it) },
            )
        }

        // --- Routing services (endpoint URLs), kept below the map features.
        // Address search + routing always work (basic navigation), so these URLs
        // stay editable regardless of advanced map features. They persist on
        // focus loss AND on disposal (so a value typed then navigated-away-from
        // isn't lost). ---
        Text(
            stringResource(R.string.nav_setting_endpoints),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        HintText(stringResource(R.string.nav_setting_endpoints_hint), small = true)
        EndpointField(
            label = stringResource(R.string.nav_setting_geocoder_url),
            value = settings.navGeocoderUrl,
            enabled = true,
            onCommit = { viewModel.updateNavGeocoderUrl(it) },
        )
        EndpointField(
            label = stringResource(R.string.nav_setting_router_url),
            value = settings.navRouterUrl,
            enabled = true,
            onCommit = { viewModel.updateNavRouterUrl(it) },
        )

        // --- Avoidances (collapsible; all off by default) ---
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

        Spacer(Modifier.height(8.dp))
    }
}

/** Known public Overpass instances; the default first. Same protocol, so they
 *  are interchangeable for the chargers & places query. */
private val OVERPASS_PRESETS = listOf(
    "OpenStreetMap (default)" to "https://overpass-api.de/api/interpreter",
    "OpenStreetMap France" to "https://overpass.openstreetmap.fr/api/interpreter",
    "Kumi Systems" to "https://overpass.kumi.systems/api/interpreter",
    "Swiss OSM" to "https://overpass.osm.ch/api/interpreter",
    "private.coffee" to "https://overpass.private.coffee/api/interpreter",
)

/**
 * A URL / key field that commits its value on focus loss AND on disposal (so a
 * value typed then navigated-away-from or section-collapsed is never lost), with
 * an optional dropdown of known [presets] for services that have interchangeable
 * public instances.
 */
@Composable
private fun EndpointField(
    label: String,
    value: String,
    enabled: Boolean,
    onCommit: (String) -> Unit,
    presets: List<Pair<String, String>> = emptyList(),
) {
    var text by rememberSaveable(value) { mutableStateOf(value) }
    var expanded by remember { mutableStateOf(false) }
    val latestText by rememberUpdatedState(text)
    val persisted by rememberUpdatedState(value)
    fun commit() {
        val trimmed = latestText.trim()
        if (trimmed != persisted) onCommit(trimmed)
    }
    DisposableEffect(Unit) { onDispose { commit() } }

    // No presets -> no trailing icon at all (a conditionally-empty trailing
    // composable still reserves space and leaves a visible notch on the right).
    val dropdownIcon: (@Composable () -> Unit)? = if (presets.isEmpty()) null else {
        {
            IconButton(onClick = { expanded = true }, enabled = enabled) {
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = dropdownIcon,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { fs -> if (!fs.isFocused) commit() },
            colors = themedFieldColors(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            presets.forEach { (name, url) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        text = url
                        expanded = false
                        if (url != value) onCommit(url)
                    }
                )
            }
        }
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
