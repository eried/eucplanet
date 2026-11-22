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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import com.eried.eucplanet.ui.common.HintText
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.eried.eucplanet.platform.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.service.hud.engo.EngoGlasses
import com.eried.eucplanet.service.hud.engo.EngoFound
import com.eried.eucplanet.ui.common.SearchableText
import com.eried.eucplanet.ui.common.rememberScanStarter
import com.eried.eucplanet.ui.theme.appColors

/**
 * Pairing the ENGO glasses, the way the radar and the external GPS pair:
 * Start scan, pick the pair, and from then on a card with its name and link
 * state, Disconnect or Reconnect, and Forget device. The remembered pair is
 * the only one the app links to.
 *
 * [options] is drawn below, paired or not: the options are the app's, not one
 * pair's, so they can be set before any glasses are paired.
 */
@Composable
internal fun EngoPairing(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    options: @Composable () -> Unit,
) {
    val engo = settings.engoHud
    val host = LocalSettingsHost.current
    val adapter = remember(host) { host.engoGlasses() }
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
            com.eried.eucplanet.ui.common.HintText(stringResource(Res.string.engo_scan_hint))
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
                    label = stringResource(Res.string.radar_stop_scan),
                    onClick = { adapter.stopScan() },
                    containerColor = MaterialTheme.appColors.statusDanger,
                )
            } else {
                LeftAlignedScanButton(
                    label = stringResource(Res.string.radar_pair_button),
                    onClick = { startScan { adapter.startScan() } },
                )
            }
        } else {
            val linked = state == EngoGlasses.State.CONNECTED
            PairedGlassesCard(
                name = engo.pairedName ?: address,
                stateLabel = when {
                    linked -> stringResource(Res.string.radar_state_connected)
                    engo.enabled -> stringResource(Res.string.radar_state_connecting)
                    else -> stringResource(Res.string.radar_state_disconnected)
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
                            if (engo.enabled) Res.string.radar_disconnect else Res.string.radar_reconnect
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
                    SearchableText(stringResource(Res.string.radar_unpair))
                }
            }
        }
        options()
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
                SearchableText(stringResource(Res.string.radar_pair_action))
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

/**
 * ENGO 2 / 3 glasses: a Bluetooth output of its own, with its own two
 * pages. Paired like the radar ([EngoPairing]). Customize is there with or
 * without a pair: the settings belong to the app, not to one pair of glasses,
 * and the preview is drawn on the phone, so a rider can set the pages up first.
 */
@Composable
internal fun EngoGlassesBlock(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    val engo = settings.engoHud
    SectionHeader(stringResource(Res.string.engo_hud_enabled))
    EngoPairing(settings = settings, viewModel = viewModel) {
        AdvancedCollapsable(
            titleRes = Res.string.engo_pages_title,
            stateKey = "engo_pages"
        ) {
            val layouts = com.eried.eucplanet.service.hud.engo.EngoLayouts
            val kind = layouts.kindOf(engo.layout)
            // What the preview shows: the page at rest (zeros), on a ride, or during a route.
            var previewMode by rememberSaveable { mutableStateOf("RIDING") }
            // The element being set up: none until a number on the preview is tapped, and
            // none again as soon as the preview has no place for it (another layout, the
            // route preview, the turns switch), so a hidden element is never edited.
            var pickedRaw by rememberSaveable { mutableIntStateOf(layouts.NONE) }
            val selected = layouts.keepSelection(pickedRaw, kind, previewMode == "ROUTE", engo.navTakeover)
            LaunchedEffect(selected) { pickedRaw = selected }
            val defaults = com.eried.eucplanet.data.model.DEFAULT_ENGO_ELEMENTS
            val previewLabels = mapOf(
                "ZEROS" to stringResource(Res.string.engo_preview_zeros),
                "RIDING" to stringResource(Res.string.engo_preview_riding),
                "ROUTE" to stringResource(Res.string.engo_preview_navigating),
            )
            // The font first: it applies to every layout, so it is set before picking one.
            SegmentedChoice(
                label = stringResource(Res.string.engo_font),
                options = listOf(
                    "PIXEL" to stringResource(Res.string.engo_font_pixel),
                    "DIGITAL" to stringResource(Res.string.engo_font_digital),
                    "BOLD" to stringResource(Res.string.engo_font_bold),
                ),
                current = layouts.fontOf(engo.font).name,
                onChange = { viewModel.updateEngoFont(it) },
            )
            if (layouts.fontOf(engo.font) == com.eried.eucplanet.service.hud.engo.EngoFontStyle.DIGITAL) {
                SwitchSetting(
                    label = stringResource(Res.string.engo_digital_ghost),
                    checked = engo.digitalGhost,
                    onCheckedChange = { viewModel.updateEngoDigitalGhost(it) }
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Layout | Preview side by side, like the dashboard's View | Stats length.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WidgetSlotDropdown(
                        label = stringResource(Res.string.engo_layout),
                        currentLabel = engoLayoutLabel(kind),
                        options = com.eried.eucplanet.service.hud.engo.EngoLayoutKind.entries.map { it.name to engoLayoutLabel(it) },
                        onSelect = { viewModel.updateEngoLayout(it) },
                        includeNone = false,
                        modifier = Modifier.weight(1f),
                    )
                    WidgetSlotDropdown(
                        label = stringResource(Res.string.engo_preview_mode),
                        currentLabel = previewLabels.getValue(previewMode),
                        options = previewLabels.toList(),
                        onSelect = { previewMode = it },
                        includeNone = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                EngoPagePreview(
                    settings = settings,
                    navigating = previewMode == "ROUTE",
                    zeros = previewMode == "ZEROS",
                    selected = selected,
                    // Tapping the picked number again puts it down.
                    onSelect = { pickedRaw = if (it == selected) layouts.NONE else it },
                )
                // The picked element's options, framed together so they read as one
                // element's settings. Outlined, not filled: the fields are already the
                // darker surface and would vanish into a filled card.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.appColors.outline, RoundedCornerShape(12.dp)),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (selected == layouts.NONE) {
                            HintText(
                                stringResource(Res.string.engo_element_none),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        } else {
                            EngoElementEditor(
                                kind = kind,
                                index = selected,
                                element = engo.elements.getOrNull(selected) ?: defaults[selected],
                                dashboardWindowSec = settings.dashboardRollingWindowSeconds,
                                onChange = { viewModel.updateEngoElement(selected, it) },
                            )
                        }
                    }
                }
                val switchApplies = layouts.turnsSwitchApplies(kind)
                SwitchSetting(
                    label = stringResource(Res.string.engo_nav_takeover),
                    checked = !switchApplies || engo.navTakeover,
                    enabled = switchApplies,
                    onCheckedChange = {
                        viewModel.updateEngoNavTakeover(it)
                        // Turning turns on shows the page they just asked for.
                        if (it) previewMode = "ROUTE"
                    }
                )
                if (!switchApplies) HintText(stringResource(Res.string.engo_turn_always))
            }
        }
    }
}

@Composable
private fun engoLayoutLabel(kind: com.eried.eucplanet.service.hud.engo.EngoLayoutKind): String = when (kind) {
    com.eried.eucplanet.service.hud.engo.EngoLayoutKind.COCKPIT -> stringResource(Res.string.engo_layout_cockpit)
    com.eried.eucplanet.service.hud.engo.EngoLayoutKind.BIG -> stringResource(Res.string.engo_layout_big)
    com.eried.eucplanet.service.hud.engo.EngoLayoutKind.QUAD -> stringResource(Res.string.engo_layout_quad)
    com.eried.eucplanet.service.hud.engo.EngoLayoutKind.TURN -> stringResource(Res.string.engo_layout_turn)
    com.eried.eucplanet.service.hud.engo.EngoLayoutKind.MINIMAL -> stringResource(Res.string.engo_layout_minimal)
}

@Composable
private fun engoStyleLabel(style: com.eried.eucplanet.service.hud.engo.EngoStyle): String = when (style) {
    com.eried.eucplanet.service.hud.engo.EngoStyle.NUMBER -> stringResource(Res.string.engo_style_number)
    com.eried.eucplanet.service.hud.engo.EngoStyle.BLOCKS -> stringResource(Res.string.engo_style_blocks)
    com.eried.eucplanet.service.hud.engo.EngoStyle.SMOOTH -> stringResource(Res.string.engo_style_smooth)
    com.eried.eucplanet.service.hud.engo.EngoStyle.GRAPH -> stringResource(Res.string.engo_style_graph)
}

/**
 * A graph's length once picked: minutes and seconds ("2:30"), short enough for the
 * picker's small field and read the same in every language. The graph stores its
 * share, so the time follows the dashboard's stats length; a share the dashboard
 * made too short shows the one the graph really uses.
 */
fun engoWindowClock(windowDiv: Int, dashboardWindowSec: Int): String {
    val ms = com.eried.eucplanet.service.hud.engo.EngoPageBuilder.windowMs(windowDiv, dashboardWindowSec)
    val sec = (ms / 1000f).roundToInt()
    return "${sec / 60}:" + (sec % 60).toString().padStart(2, '0')
}

/** A graph length in the open list: its share by name, then how long that is now ("Half · 2:30"). */
@Composable
private fun engoWindowOption(windowDiv: Int, dashboardWindowSec: Int): String {
    val name = when (com.eried.eucplanet.service.hud.engo.EngoPageBuilder.shareOf(windowDiv, dashboardWindowSec)) {
        1 -> stringResource(Res.string.engo_window_full)
        2 -> stringResource(Res.string.engo_window_half)
        4 -> stringResource(Res.string.engo_window_quarter)
        8 -> stringResource(Res.string.engo_window_eighth)
        16 -> stringResource(Res.string.engo_window_sixteenth)
        // A number, the same in every language: "Thirty-second · 0:09" would read as 30 s.
        else -> "1/32"
    }
    return name + " · " + engoWindowClock(windowDiv, dashboardWindowSec)
}

/**
 * The rider's glasses page drawn as the glasses would draw it: their own
 * layout, elements, statistics, styles, font and units, on sample numbers.
 * The picture comes from the very commands the glasses are sent, so what is
 * seen here is what the glasses get. Each element's place carries its number
 * (on the phone only); tapping a place picks that element to set up.
 */
@Composable
private fun EngoPagePreview(
    settings: com.eried.eucplanet.data.model.AppSettings,
    navigating: Boolean,
    zeros: Boolean,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val engo = settings.engoHud
    val units = com.eried.eucplanet.service.hud.engo.EngoMetrics.units(settings)
    val pxW = com.eried.eucplanet.service.hud.engo.EngoRaster.W
    val pxH = com.eried.eucplanet.service.hud.engo.EngoRaster.H
    val bitmap = remember(engo, units, navigating, zeros, settings.gaugeOrangeThresholdPct, settings.gaugeRedThresholdPct,
        settings.dashboardRollingWindowSeconds) {
        val page = com.eried.eucplanet.service.hud.engo.EngoDemoSource.page(
            engo = engo,
            units = units,
            orangePct = settings.gaugeOrangeThresholdPct,
            redPct = settings.gaugeRedThresholdPct,
            navigating = navigating,
            nowMs = 1_000_000L,
            dashboardWindowSec = settings.dashboardRollingWindowSeconds,
            zeros = zeros,
        )
        val caps = com.eried.eucplanet.service.hud.engo.EngoCaps(colorRYG = true)
        val px = com.eried.eucplanet.service.hud.engo.EngoRaster.render(
            com.eried.eucplanet.service.hud.engo.EngoLayout.render(page, caps), color = true,
        )
        com.eried.eucplanet.platform.argbImageBitmap(px, pxW, pxH)
    }
    val layouts = com.eried.eucplanet.service.hud.engo.EngoLayouts
    val spec = layouts.resolve(layouts.kindOf(engo.layout), navigating, engo.navTakeover)
    val accent = MaterialTheme.appColors.primary
    val onAccent = MaterialTheme.appColors.onPrimary
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(pxW / pxH.toFloat())
            .clip(RoundedCornerShape(12.dp)),
    ) {
        val scale = maxWidth / pxW
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = stringResource(Res.string.engo_preview_label),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        for (spot in spec.spots) {
            val b = spot.bounds
            val isSelected = spot.element == selected
            // The badge sits where the place's title is not: on the left of a big number
            // or a right-aligned title, on the right of a title that starts on the left.
            val g = spot.geom
            val badgeLeft = g is com.eried.eucplanet.service.hud.engo.EngoLayouts.Geom.Hero ||
                (g is com.eried.eucplanet.service.hud.engo.EngoLayouts.Geom.Corner && !g.centred)
            Box(
                modifier = Modifier
                    .offset(x = scale * (b[0] - 3), y = scale * (b[1] - 3))
                    .size(width = scale * (b[2] - b[0] + 7), height = scale * (b[3] - b[1] + 7))
                    .then(if (isSelected) Modifier.border(1.5.dp, accent, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { onSelect(spot.element) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(if (badgeLeft) Alignment.TopStart else Alignment.TopEnd)
                        .size(18.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (isSelected) accent else accent.copy(alpha = 0.55f)),
                ) {
                    Text(
                        "${spot.element + 1}",
                        color = onAccent,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * One element of the glasses page: which metric (or none, past element 1),
 * which statistic of it, how it is drawn where the place has room for a bar or
 * a graph, and how far back a graph looks.
 */
@Composable
private fun EngoElementEditor(
    kind: com.eried.eucplanet.service.hud.engo.EngoLayoutKind,
    index: Int,
    element: com.eried.eucplanet.data.model.EngoElement,
    dashboardWindowSec: Int,
    onChange: (com.eried.eucplanet.data.model.EngoElement) -> Unit,
) {
    val metrics = com.eried.eucplanet.service.hud.engo.EngoMetrics
    val layouts = com.eried.eucplanet.service.hud.engo.EngoLayouts
    val known = metrics.known(element.key)
    val supportsStats = known &&
        com.eried.eucplanet.data.model.MetricCatalog.byKey(element.key)?.supportsStats == true
    val stat = if (supportsStats) element.stat else "CURRENT"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WidgetSlotDropdown(
            label = stringResource(Res.string.engo_element_metric),
            currentLabel = if (known) metricChipLabel(element.key) else "",
            options = metrics.KEYS.map { it to metricChipLabel(it) },
            onSelect = { onChange(element.copy(key = if (it == "NONE") "" else it)) },
            includeNone = index > 0,
            modifier = Modifier.weight(1f),
        )
        WidgetSlotDropdown(
            label = stringResource(Res.string.engo_slot_stat),
            currentLabel = engoStatLabel(stat),
            options = metrics.STATS.map { it to engoStatLabel(it) },
            onSelect = { onChange(element.copy(stat = it)) },
            includeNone = false,
            enabled = supportsStats,
            modifier = Modifier.weight(1f),
        )
    }
    val styles = layouts.spotOf(kind, index)?.styles.orEmpty()
    if (styles.size > 1 && known) {
        val style = layouts.styleOf(element.style).takeIf { it in styles }
            ?: com.eried.eucplanet.service.hud.engo.EngoStyle.NUMBER
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetSlotDropdown(
                label = stringResource(Res.string.engo_element_style),
                currentLabel = engoStyleLabel(style),
                options = styles.map { it.name to engoStyleLabel(it) },
                onSelect = { onChange(element.copy(style = it)) },
                includeNone = false,
                modifier = Modifier.weight(1f),
            )
            if (style == com.eried.eucplanet.service.hud.engo.EngoStyle.GRAPH) {
                WidgetSlotDropdown(
                    label = stringResource(Res.string.engo_graph_window),
                    currentLabel = engoWindowClock(element.windowDiv, dashboardWindowSec),
                    options = com.eried.eucplanet.service.hud.engo.EngoPageBuilder.offeredDivs(dashboardWindowSec)
                        .map { "$it" to engoWindowOption(it, dashboardWindowSec) },
                    onSelect = { onChange(element.copy(windowDiv = it.toInt())) },
                    includeNone = false,
                    modifier = Modifier.weight(1f),
                )
            } else Spacer(Modifier.weight(1f))
        }
    }
    if (layouts.onlyDuringRoute(kind, index)) HintText(stringResource(Res.string.engo_element_route_only))
}

@Composable
private fun engoStatLabel(stat: String): String = when (stat) {
    "MAX" -> stringResource(Res.string.dashboard_stat_max)
    "MIN" -> stringResource(Res.string.dashboard_stat_min)
    "AVG" -> stringResource(Res.string.dashboard_stat_avg)
    "SUSTAINED_PEAK" -> stringResource(Res.string.dashboard_stat_sustained_peak)
    else -> stringResource(Res.string.dashboard_stat_current)
}
