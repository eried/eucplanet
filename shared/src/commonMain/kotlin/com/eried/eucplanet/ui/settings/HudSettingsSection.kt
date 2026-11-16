package com.eried.eucplanet.ui.settings

// The companion HUD's settings rows, on both phones: finding the HUD, the
// link switch, its screens, its map and its joystick. What only one phone
// has (sending the HUD its app, the setup hint) comes through SettingsHost.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.data.model.HudDiscoveryMode
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.ui.theme.themedSliderColors
import com.eried.eucplanet.ui.theme.themedSwitchColors
import com.eried.eucplanet.ui.theme.themedTextButtonColors
import com.eried.eucplanet.ui.theme.themedTonalButtonColors
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableColumn

@Composable
private fun HudInstallHint(pairedHudVersion: String?, hudEverConnected: Boolean) {
    // Hide the hint as soon as a HUD has paired -- AND keep it hidden
    // for the rest of the session even if the WebSocket drops. Without
    // the sticky flag, the hint flashed back on every reconnect and
    // told a rider who has the HUD installed to "Get it at ...", which
    // reads as broken UX.
    if (pairedHudVersion != null || hudEverConnected) return

    val updateUrl = stringResource(Res.string.hud_update_url)
    val raw = stringResource(Res.string.hud_install_hint)
        .replace("<b>", "").replace("</b>", "")
    val linkColor = MaterialTheme.colorScheme.primary
    // Locate the URL run by text, not via HtmlCompat bold-span detection, which wasn't
    // reliably tagging the run, so no clickable link was ever added, and attach
    // a LinkAnnotation.Url. The https:// prefix is required or ACTION_VIEW has no
    // scheme to launch.
    val annotated = remember(raw, updateUrl, linkColor) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(raw)
            val i = raw.indexOf(updateUrl)
            if (i >= 0) {
                addLink(
                    androidx.compose.ui.text.LinkAnnotation.Url(
                        url = "https://$updateUrl",
                        styles = androidx.compose.ui.text.TextLinkStyles(
                            style = androidx.compose.ui.text.SpanStyle(
                                color = linkColor,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                        )
                    ),
                    start = i,
                    end = i + updateUrl.length
                )
            }
        }
    }
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The card above the link controls that says how to put the HUD and the
 * phone on one network ([SettingsHost.HudSetupHint]).
 */
@Composable
fun HudSetupCard(text: String) {
    // Always informational, never alarming. surfaceVariant + the outlined
    // Info icon matches the InfoHint pattern used elsewhere in the app, so
    // this reads as a regular "here's some context" card, not a red error.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun HudIntegrationSection(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(stringResource(Res.string.section_hud_companion))

        // Phone surface is intentionally limited to the install hint:
        // version mismatches are reported on the HUD side only. The
        // HUD is the device the rider is actually looking at while
        // riding, so duplicating the banner here just made the phone
        // app shout when the rider could already see the answer on
        // the helmet. Hides itself once a HUD has paired.
        val pairedHudVersion by viewModel.hudVersion.collectAsState()
        val hudEverConnected by viewModel.hudEverConnected.collectAsState()
        HudInstallHint(
            pairedHudVersion = pairedHudVersion,
            hudEverConnected = hudEverConnected
        )
        // Sending the HUD its app is the Android phone's: an iPhone cannot
        // install one. The card slides in when a HUD that takes the app
        // links, and out when the link drops.
        val host = LocalSettingsHost.current
        if (SettingsRow.HUD_APP_SEND !in host.hiddenRows) host.HudAppSettings(viewModel, linked = pairedHudVersion != null)

        // Hotspot hint sits ABOVE the link controls -- it's the usual
        // setup step. Optional: some riders put the HUD on their home
        // wifi or a separate router, in which case the hotspot doesn't
        // matter. The hint copy makes that explicit so a rider whose
        // hotspot is intentionally off doesn't think anything is wrong.
        // Nothing to set up while a HUD is linked: the hint returns when
        // the link drops, which is when it is useful again.
        // Each phone says it its own way: Android reads its hotspot, the
        // iPhone names Personal Hotspot and the local network permission.
        host.HudSetupHint(linked = pairedHudVersion != null)

        // How to find the HUD is setup, like the hotspot hint: with a HUD
        // linked it is found, and these rows are locked anyway. They fold
        // away and come back when the link drops.
        AnimatedVisibility(visible = pairedHudVersion == null) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Discovery mode comes FIRST -- it decides whether the rider needs to
        // know the IP at all. Auto finds the HUD on its own; Fixed uses only
        // the address below; Both tries auto then falls back to it. Locked
        // while the link is enabled so a mid-session flip can't drop it.
        SegmentedChoice(
            label = stringResource(Res.string.hud_discovery_mode),
            options = listOf(
                HudDiscoveryMode.AUTO to stringResource(Res.string.hud_discovery_auto),
                HudDiscoveryMode.FIXED to stringResource(Res.string.hud_discovery_fixed),
                HudDiscoveryMode.BOTH to stringResource(Res.string.hud_discovery_both),
            ),
            current = settings.hudDiscoveryMode,
            onChange = { viewModel.updateHudDiscoveryMode(it) },
            enabled = !settings.hudServerEnabled,
        )
        HintText(stringResource(Res.string.hud_discovery_desc))

        // IP + port live side by side as one logical input: the rider
        // reads the IP off the HUD's screen, port is almost always the
        // default. They're disabled while the link is active so an
        // accidental keystroke can't drop a live connection. We HIDE the
        // whole row in AUTO mode, since that mode never uses the IP; Fixed
        // and Hybrid both show it.
        val fieldsEnabled = !settings.hudServerEnabled
        // Local edit buffers, seeded from settings ONCE at first
        // composition and never re-keyed. The DataStore-backed write
        // back from updateHudIp / updateHudServerPort is asynchronous;
        // re-keying these on settings.* would race the IME and scramble
        // mid-edit keystrokes (testers reported "192" appearing as "921").
        var ipText by remember { mutableStateOf(settings.hudIp) }
        var portText by remember { mutableStateOf(settings.hudServerPort.toString()) }
        AnimatedVisibility(visible = settings.hudDiscoveryMode != HudDiscoveryMode.AUTO) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // When the link is on and the field is empty, the client falls
            // back to mDNS auto-discovery. We surface that in the LABEL
            // (which is always visible) instead of the placeholder (which
            // only shows when the field is focused) so the rider sees the
            // autodetect state without having to tap into the field first.
            val ipLabel = if (settings.hudServerEnabled && ipText.isBlank())
                stringResource(Res.string.hud_ip_label_autodetect)
            else stringResource(Res.string.hud_ip_label)
            OutlinedTextField(
                value = ipText,
                onValueChange = { new ->
                    if (new.length <= 15 && new.all { it.isDigit() || it == '.' }) {
                        ipText = new
                        viewModel.updateHudIp(new)
                    }
                },
                label = { Text(ipLabel) },
                // Every keystroke is saved, so a half-typed address survives
                // as a real setting. Flagging it while it is incomplete is the
                // only warning the rider gets: with auto-find ON this whole
                // row is hidden, and a stale value sits there unseen.
                isError = ipText.isNotBlank() &&
                    !com.eried.eucplanet.hud.protocol.HudDiscovery.isValidIpv4(ipText),
                // A red border alone does not say what is wrong, and this field
                // is where a half-typed address became a saved setting.
                supportingText = if (ipText.isNotBlank() &&
                    !com.eried.eucplanet.hud.protocol.HudDiscovery.isValidIpv4(ipText)
                ) {
                    { Text(stringResource(Res.string.hud_ip_incomplete)) }
                } else null,
                // What a HUD on this phone's hotspot is typically given
                // (SettingsHost.hudAddressExample).
                placeholder = { Text(host.hudAddressExample) },
                singleLine = true,
                enabled = fieldsEnabled,
                // KeyboardType.Phone for digits + dots.
                //
                // The phone's own keyboard flags for an address
                // (SettingsHost.addressImeOptions).
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                    autoCorrectEnabled = false,
                    platformImeOptions = host.addressImeOptions,
                ),
                modifier = Modifier.weight(2f),
                colors = themedFieldColors(),
                shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { new ->
                    if (new.length <= 5 && new.all { it.isDigit() }) {
                        portText = new
                        // Only commit to the ViewModel if the typed value
                        // is inside the legal port range. Mid-edit values
                        // like "2" would otherwise be coerced to 1024 and
                        // re-render the field, eating the cursor.
                        new.toIntOrNull()?.takeIf { it in 1024..65535 }
                            ?.let { viewModel.updateHudServerPort(it) }
                    }
                },
                label = { Text(stringResource(Res.string.hud_server_port)) },
                singleLine = true,
                enabled = fieldsEnabled,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    autoCorrectEnabled = false,
                    platformImeOptions = host.addressImeOptions,
                ),
                modifier = Modifier.weight(1f),
                colors = themedFieldColors(),
                shape = RoundedCornerShape(12.dp),
            )
        }
        }  // end AnimatedVisibility for the IP/port row
        }
        }  // end AnimatedVisibility for the find-the-HUD setup

        // Toggle goes UNDER the IP/port -- the rider configures the
        // address first and then flips the switch to dial out. Flipping
        // it also locks the fields above so the live connection can't
        // be edited out from under itself. No description below the
        // label: the rider already knows what they're enabling by this
        // point (they typed in an IP just above).
        SwitchSetting(
            label = stringResource(Res.string.hud_server_enabled),
            checked = settings.hudServerEnabled,
            onCheckedChange = { viewModel.updateHudServerEnabled(it) }
        )

        // Discovery activity (which channel found the HUD, probes, dial
        // attempts, transient reconnects after a WiFi switch, etc.) is
        // piped to the Service Mode log as NOTE entries. The rider only
        // sees the resulting address and source in the HUD app card.

        // Three top-level collapsibles under the Integration card.
        // HUD screens first because the reorder list inside is the
        // primary repeat-visit destination; Map options second;
        // Joystick actions last (set-once bindings).
        HudScreensCard(settings = settings, viewModel = viewModel)
        HudMapOptionsCard(settings = settings, viewModel = viewModel)
        HudJoystickCard(settings = settings, viewModel = viewModel)
    }
}

/** HUD joystick long-press bindings: one action picker per direction.
 *  Reuses the same [ActionDropdown] (VOLUME_KEY action vocabulary) as the
 *  Volume keys section so the rider sees a consistent eyes-free action set;
 *  each picker persists through its own ViewModel setter. */
@Composable
private fun HudJoystickCard(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    AdvancedCollapsable(
        title = stringResource(Res.string.hud_joystick_title),
        stateKey = "hud_joystick"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HintText(stringResource(Res.string.hud_joystick_desc))
            // Up | Down on one row, Left | Right on the next (half each).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionDropdown(stringResource(Res.string.hud_joystick_up), settings.hudActionUp, onValueChange = { viewModel.updateHudActionUp(it) }, modifier = Modifier.weight(1f))
                ActionDropdown(stringResource(Res.string.hud_joystick_down), settings.hudActionDown, onValueChange = { viewModel.updateHudActionDown(it) }, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionDropdown(stringResource(Res.string.hud_joystick_left), settings.hudActionLeft, onValueChange = { viewModel.updateHudActionLeft(it) }, modifier = Modifier.weight(1f))
                ActionDropdown(stringResource(Res.string.hud_joystick_right), settings.hudActionRight, onValueChange = { viewModel.updateHudActionRight(it) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Map appearance: tile style + contrast + brightness sliders. Uses the
 *  shared AdvancedCollapsable for chrome + auto-scroll-on-expand. */
@Composable
private fun HudMapOptionsCard(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    AdvancedCollapsable(
        title = stringResource(Res.string.hud_map_options_title),
        stateKey = "hud_map_options"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HudMapStylePicker(settings = settings, viewModel = viewModel)
            HudMapContrastSlider(settings = settings, viewModel = viewModel)
            HudMapBrightnessSlider(settings = settings, viewModel = viewModel)
        }
    }
}

/** Carousel personalization: which screens appear + custom overlay choice.
 *  Uses the shared AdvancedCollapsable for chrome + auto-scroll-on-expand. */
@Composable
private fun HudScreensCard(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    AdvancedCollapsable(
        title = stringResource(Res.string.hud_screens_title),
        stateKey = "hud_screens"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HudScreenList(settings = settings, viewModel = viewModel)
            HudOverlayPicker(settings = settings, viewModel = viewModel)
        }
    }
}

@Composable
private fun HudMapContrastSlider(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.hud_map_contrast_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${settings.hudMapContrastPct}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.Slider(
            value = settings.hudMapContrastPct.toFloat(),
            onValueChange = { viewModel.updateHudMapContrast(it.toInt()) },
            valueRange = 50f..200f,
            steps = 29, // 5% increments across the 150-wide range
            colors = themedSliderColors()
        )
    }
}

@Composable
private fun HudMapBrightnessSlider(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.hud_map_brightness_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            // Signed display so the rider sees +20 / -20 rather than just
            // "20" -- it's a +/- offset, not a magnitude.
            val v = settings.hudMapBrightnessPct
            Text(
                if (v > 0) "+$v" else v.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.Slider(
            value = settings.hudMapBrightnessPct.toFloat(),
            onValueChange = { viewModel.updateHudMapBrightness(it.toInt()) },
            valueRange = -100f..100f,
            steps = 39, // 5-unit increments across the 200-wide range
            colors = themedSliderColors()
        )
    }
}

@Composable
private fun HudScreenList(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    val known = viewModel.knownHudScreens
    val defaultEnabled = viewModel.defaultEnabledHudScreens
    // Row order comes from hudScreensOrder; enabled state from
    // hudScreensEnabled. The two are independent in storage so
    // toggling a Switch off doesn't move the row -- it stays in
    // place and just goes unchecked.
    val orderedAll = remember(settings.hudScreensOrder, known) {
        val parsed = settings.hudScreensOrder.split(",")
            .map { it.trim() }
            .filter { it in known }
            .distinct()
        parsed + known.filter { it !in parsed }
    }
    val enabledSet = remember(settings.hudScreensEnabled, defaultEnabled) {
        val parsed = settings.hudScreensEnabled.split(",")
            .map { it.trim() }
            .filter { it in known }
            .toSet()
        if (parsed.isEmpty() && settings.hudScreensEnabled.isBlank()) {
            defaultEnabled.toSet()
        } else parsed
    }
    val haptic = LocalHapticFeedback.current
    val isOnlyOneEnabled = enabledSet.size <= 1

    sh.calvin.reorderable.ReorderableColumn(
        list = orderedAll,
        onSettle = { from, to -> viewModel.moveHudScreen(from, to) },
        onMove = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
        modifier = Modifier.fillMaxWidth()
    ) { _, id, _ ->
        key(id) {
            val checked = id in enabledSet
            // Prevent the rider from unchecking the LAST enabled
            // screen -- they need at least one screen on the carousel
            // or the HUD has no content to show.
            val canUncheck = !(isOnlyOneEnabled && checked)
            // Drag handle on the left, label in the weighted middle,
            // Material3 Switch on the right. No vertical padding -- the
            // ReorderableColumn handles spacing between rows.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = stringResource(Res.string.action_reorder),
                    modifier = Modifier
                        .draggableHandle()
                        .size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = hudScreenDisplayName(id),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = { wantChecked ->
                        if (wantChecked || canUncheck) {
                            viewModel.setHudScreenEnabled(id, wantChecked)
                        }
                    },
                    enabled = wantCheckedCanFlip(checked, canUncheck),
                    colors = themedSwitchColors()
                )
            }
        }
    }
}

/** Disables the Switch when it is the last enabled screen, so the rider
 *  cannot turn off the only screen left. Disabled screens can always be
 *  turned back on. */
private fun wantCheckedCanFlip(currentlyChecked: Boolean, canUncheck: Boolean): Boolean {
    if (!currentlyChecked) return true
    return canUncheck
}

/** Map stable HUD screen id to its localized display name. Falls back
 *  to the id itself for unknown ids so a future-shipped HUD that
 *  reports a brand-new screen still surfaces SOMETHING the rider can
 *  see and reorder. */
@Composable
private fun hudScreenDisplayName(id: String): String = when (id) {
    "Dashboard" -> stringResource(Res.string.hud_screen_name_dashboard)
    "Camera" -> stringResource(Res.string.hud_screen_name_camera)
    "Telemetry" -> stringResource(Res.string.hud_screen_name_telemetry)
    "Custom" -> stringResource(Res.string.hud_screen_name_custom)
    "CustomCam" -> stringResource(Res.string.hud_screen_name_custom_cam)
    "MapNav" -> stringResource(Res.string.hud_screen_name_map_nav)
    "Map" -> stringResource(Res.string.hud_screen_name_map)
    "Nav" -> stringResource(Res.string.hud_screen_name_nav)
    "Power" -> stringResource(Res.string.hud_screen_name_power)
    "TripStats" -> stringResource(Res.string.hud_screen_name_trip_stats)
    "Compass" -> stringResource(Res.string.hud_screen_name_compass)
    "Safety" -> stringResource(Res.string.hud_screen_name_safety)
    "BigClock" -> stringResource(Res.string.hud_screen_name_big_clock)
    else -> id
}

/**
 * Map-tile style picker. The chosen code goes into AppSettings.hudMapStyle,
 * ships over the wire on the next 5 Hz frame, and the HUD's HudTileCache
 * swaps URL templates + clears its bitmap LRU so the change is visible
 * within a few seconds.
 *
 * Offers the raw style codes: osm, cyclosm, topo, hot, satellite, light,
 * dark. Blank falls back to light.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HudMapStylePicker(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    // Raw internal codes on purpose: the rider asked to see them, not
    // localised friendly text. The ten Carto slugs collapsed into "light"
    // and "dark" when Light/Dark moved to Esri Canvas (the keyless Carto
    // endpoints are being key-gated); a saved legacy slug still resolves
    // to the matching Esri style in the HUD's tile cache.
    val options = listOf(
        // The same providers the app's own maps offer, first because they are
        // the ones riders asked for: plain OSM and the two that actually draw
        // trails. Needs HUD protocol minor 17; an older HUD does not know these
        // codes and the phone already flags "update your HUD".
        "osm",
        "cyclosm",
        "topo",
        "hot",
        "satellite",
        "light",
        "dark",
    )
    val currentCode = settings.hudMapStyle.ifBlank { "light" }
    val currentLabel = currentCode
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(Res.string.hud_map_style_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = themedFieldColors(),
            shape = RoundedCornerShape(12.dp),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            options.forEach { code ->
                DropdownMenuItem(
                    text = { Text(code) },
                    onClick = {
                        viewModel.updateHudMapStyle(code)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun HudOverlayPicker(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    var lists by remember {
        mutableStateOf<SettingsViewModel.HudOverlayLists?>(null)
    }
    var sheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(settings.syncFolderUri, settings.hudCustomOverlayName) {
        lists = viewModel.loadHudOverlayLists()
    }

    // Section block: small SUBSECTION label + 2-line description + a
    // button that opens the existing Studio LoadPresetSheet. Matches the
    // style of other settings sub-blocks: label uppercase primary,
    // bodySmall onSurfaceVariant body text, button is filled tonal.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(Res.string.hud_overlay_subsection).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(Res.string.hud_overlay_subsection_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (settings.hudCustomOverlayName.isNotBlank()) {
            Text(
                text = stringResource(
                    Res.string.hud_overlay_current_preset,
                    settings.hudCustomOverlayName
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.FilledTonalButton(
                onClick = { sheetOpen = true },
                colors = themedTonalButtonColors(),
                shape = RoundedCornerShape(12.dp),
            ) {
                com.eried.eucplanet.ui.common.SearchableText(stringResource(Res.string.hud_overlay_select_preset))
            }
            if (settings.hudCustomOverlayName.isNotBlank()) {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.pickHudOverlay("") },
                    colors = themedTextButtonColors(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    com.eried.eucplanet.ui.common.SearchableText(stringResource(Res.string.hud_custom_overlay_none))
                }
            }
        }
    }

    if (sheetOpen) {
        val l = lists
        // The Studio's LoadPresetSheet internally uses a verticalScroll
        // inside a side panel; that scroll needs a bounded height to
        // measure. Calling it inline inside the settings Column (itself
        // verticalScroll) gives it an infinite-height parent and
        // crashes. Wrap in a Dialog so it lives in its own window with
        // proper constraints.
        if (l != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { sheetOpen = false },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false
                )
            ) {
                com.eried.eucplanet.ui.studio.LoadPresetSheet(
                    folderAvailable = l.folderAvailable,
                    presets = l.savedPresets,
                    bundledPresets = l.bundledPortrait,
                    bundledLandscapePresets = l.bundledLandscape,
                    onLoad = { name ->
                        viewModel.pickHudOverlay(name)
                        sheetOpen = false
                    },
                    onLoadBundled = { name ->
                        viewModel.pickHudOverlay(name)
                        sheetOpen = false
                    },
                    onDelete = { /* delete handled in the Studio, not here */ },
                    onOpenFolderSettings = { sheetOpen = false },
                    onDismiss = { sheetOpen = false }
                )
            }
        }
    }
}
