package com.eried.eucplanet.ui.settings

// The settings rows for the Android-only surfaces: the companion HUD, the
// phone HUD window and Android Auto. The shared SettingsScreen reaches them
// through SettingsHost.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.SystemUpdate
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.data.model.HudDiscoveryMode
import com.eried.eucplanet.data.model.WidgetActionType
import com.eried.eucplanet.data.model.WidgetMetricType
import com.eried.eucplanet.data.model.pickerLabel
import com.eried.eucplanet.platform.getString
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.common.InfoHint
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
    // Mirror the welcome tutorial's link handling (OutroLinkBullet): locate the
    // URL run by text, not via HtmlCompat bold-span detection, which wasn't
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
 * The HUD's own app, looked after from the phone. Shows while a HUD that can
 * take it is linked: which version it runs, a button to send the one this
 * phone app carries (it asks first when the HUD already runs that build), and one to remove
 * it, behind a confirm. Nothing is sent without the tap, and the HUD still
 * asks its rider before it installs or removes anything.
 */
/**
 * The HUD app on the HUD and the one this phone carries, as two rows. The
 * names sit in one column and the versions in the next, so the versions
 * start at the same place and are compared at a glance.
 */
@Composable
private fun HudAppVersions(installed: String, available: String?, color: Color, connected: String? = null) {
    val style = MaterialTheme.typography.bodySmall
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(stringResource(Res.string.hud_app_installed), style = style, color = color, maxLines = 1)
            if (available != null) {
                Text(stringResource(Res.string.hud_app_available), style = style, color = color, maxLines = 1)
            }
            if (connected != null) {
                Text(stringResource(Res.string.hud_app_connected), style = style, color = color, maxLines = 1)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(installed, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (available != null) {
                Text(available, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (connected != null) {
                Text(connected, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HudAppCard(viewModel: SettingsViewModel) {
    // The app card is Android's alone (the HUD link lives in :app), so it
    // reads the link straight from Koin instead of through the shared model.
    val hudServer: com.eried.eucplanet.service.hud.HudServer = org.koin.compose.koinInject()
    val hudSink: com.eried.eucplanet.service.hud.HudCommandSink = org.koin.compose.koinInject()
    val hudVersion by viewModel.hudVersion.collectAsState()
    val hudCode by hudSink.hudVersionCode.collectAsState()
    val takesApp by hudSink.hudTakesApp.collectAsState()
    val sender = hudServer.appSender
    val state by sender.state.collectAsState()
    // The section slides this card in and out with the link. While it
    // slides out the HUD is already gone, so the last version it reported
    // is kept to draw until the card has closed.
    val lastVersion = remember { arrayOfNulls<String>(1) }
    if (hudVersion != null) lastVersion[0] = hudVersion
    val version = hudVersion ?: lastVersion[0] ?: return
    val linked = hudVersion != null && takesApp
    // Where the phone reached the HUD and how it got the address, in the
    // words of the Discovery mode choice. That choice folds away while a
    // HUD is linked, so this row is where the rider reads the address off,
    // for instance to type it under Fixed later.
    val peer by hudServer.peer.collectAsState()
    val source by hudServer.connectionSource.collectAsState()
    val how = when (source) {
        com.eried.eucplanet.service.hud.HudServer.ConnectionSource.UDP_BEACON,
        com.eried.eucplanet.service.hud.HudServer.ConnectionSource.MDNS,
        com.eried.eucplanet.service.hud.HudServer.ConnectionSource.SUBNET_PROBE ->
            stringResource(Res.string.hud_discovery_auto)
        com.eried.eucplanet.service.hud.HudServer.ConnectionSource.MANUAL ->
            stringResource(Res.string.hud_discovery_fixed)
        else -> null
    }
    val connected = peer?.let { if (how == null) it else "$it ($how)" }
    val hudBuild by hudSink.hudBuild.collectAsState()
    // An older HUD or another build is sent on the tap. The very same build
    // can go too, after a question. Only a newer HUD is left alone.
    val offer = com.eried.eucplanet.hud.protocol.HudAppPush.offer(
        hudCode, hudBuild, sender.carriedVersionCode, sender.carriedBuild
    )
    val older = offer == com.eried.eucplanet.hud.protocol.HudAppPush.Offer.SEND
    val sendable = offer != com.eried.eucplanet.hud.protocol.HudAppPush.Offer.NONE
    var confirmResend by remember { mutableStateOf(false) }
    // Two builds of one version read the same by number, so the build is
    // shown beside it whenever it is known.
    fun label(name: String, build: String) = if (build.isEmpty()) name else "$name ($build)"
    val sending = state is com.eried.eucplanet.service.hud.HudAppSender.State.Sending
    // After a send or a remove request the next step is on the HUD. Both
    // buttons rest a few seconds so a second tap cannot stack a second
    // question there, and a toast says where to look.
    var resting by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val snackbar = com.eried.eucplanet.ui.common.LocalSnackbar.current
    val snackbarScope = com.eried.eucplanet.ui.common.LocalSnackbarScope.current
    val sentText = stringResource(Res.string.hud_app_sent)
    val failedText = stringResource(Res.string.hud_app_failed)
    LaunchedEffect(state) {
        when (state) {
            com.eried.eucplanet.service.hud.HudAppSender.State.Sent -> {
                com.eried.eucplanet.ui.common.showSnackbar(snackbar, snackbarScope, sentText)
                resting = true
                kotlinx.coroutines.delay(5_000L)
                resting = false
                sender.reset()
            }
            com.eried.eucplanet.service.hud.HudAppSender.State.Failed -> {
                com.eried.eucplanet.ui.common.showSnackbar(snackbar, snackbarScope, failedText)
                sender.reset()
            }
            else -> Unit
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.appColors.surface,
        contentColor = MaterialTheme.appColors.textPrimary
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.hud_app_title),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Always both, also when they match: the rider reads
                    // that the two are the same instead of taking it on trust.
                    HudAppVersions(
                        installed = label(version, hudBuild),
                        available = if (sender.carriedVersionCode > 0) {
                            label(sender.carriedVersionName, sender.carriedBuild)
                        } else null,
                        color = LocalContentColor.current.copy(alpha = 0.72f),
                        connected = connected
                    )
                }
            }
            (state as? com.eried.eucplanet.service.hud.HudAppSender.State.Sending)?.let { st ->
                LinearProgressIndicator(
                    progress = { st.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Text(
                    stringResource(Res.string.hud_app_sending, (st.fraction * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // Always the same two halves. Send only goes dead for a HUD that
            // runs a newer app than this phone carries.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { if (older) sender.send() else confirmResend = true },
                    enabled = linked && sendable && !sending && !resting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(Res.string.hud_app_send), maxLines = 1) }
                Button(
                    onClick = { confirmRemove = true },
                    enabled = linked && !sending && !resting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.appColors.statusDanger,
                        contentColor = MaterialTheme.appColors.onPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(Res.string.hud_app_remove), maxLines = 1) }
            }
        }
    }
    if (confirmResend) {
        AlertDialog(
            onDismissRequest = { confirmResend = false },
            shape = RoundedCornerShape(12.dp),
            title = { Text(stringResource(Res.string.hud_app_resend_title)) },
            text = {
                // The question, then both versions to compare.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.hud_app_resend_body))
                    HudAppVersions(
                        installed = label(version, hudBuild),
                        available = label(sender.carriedVersionName, sender.carriedBuild),
                        color = LocalContentColor.current
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmResend = false; sender.send() },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(Res.string.hud_app_send)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmResend = false },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            shape = RoundedCornerShape(12.dp),
            title = { Text(stringResource(Res.string.hud_app_remove_title)) },
            text = { Text(stringResource(Res.string.hud_app_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmRemove = false; sender.remove() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        stringResource(Res.string.hud_app_remove),
                        color = MaterialTheme.appColors.statusDanger
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmRemove = false },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }
}


@Composable
private fun HudHotspotHint(linked: Boolean) {
    if (linked) return
    val ctx = LocalContext.current
    var probed by remember { mutableStateOf(false) }
    var hotspotOn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            hotspotOn = detectHotspotEnabled(ctx)
            probed = true
            kotlinx.coroutines.delay(5_000L)
        }
    }
    // Always informational, never alarming. surfaceVariant + the outlined
    // Info icon matches the InfoHint pattern used elsewhere in the app, so
    // this reads as a regular "here's some context" card, not a red error.
    // Nothing until the first probe lands, so the card doesn't flash.
    if (!probed) return
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
                text = stringResource(
                    // Confirmed on, or "we cannot tell". There is deliberately
                    // no "off" copy: nothing available to an ordinary app can
                    // prove a hotspot is off, so the card never claims it.
                    if (hotspotOn == true) Res.string.hud_hotspot_on_hint
                    else Res.string.hud_hotspot_unknown_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Two-stage hotspot detection. Returns true or false only when a stage
 * actually answered, and null when neither could tell.
 *
 * The null matters. This used to return false when nothing answered, so the
 * card stated "Phone hotspot is off" to riders whose hotspot was plainly on,
 * and a tester had to argue with it. Neither stage is dependable on a modern
 * phone: [android.net.wifi.WifiManager] `isWifiApEnabled` is a blocklisted
 * hidden API for apps targeting API 30 and up (we target 36), and the
 * interface-name sniff below only recognises the names we happened to know.
 * A miss on both is "we cannot tell", which is not the same fact as "off",
 * and only one of those is safe to print.
 */
private fun detectHotspotEnabled(ctx: android.content.Context): Boolean? {
    // Stage 1: reflection into the legacy isWifiApEnabled API.
    val viaReflection: Boolean? = runCatching {
        val wifi = ctx.applicationContext
            .getSystemService(android.content.Context.WIFI_SERVICE)
            as android.net.wifi.WifiManager
        val m = wifi.javaClass.getMethod("isWifiApEnabled")
        m.invoke(wifi) as? Boolean
    }.getOrNull()
    // Only a `true` here means anything. Querying AP state is a privileged
    // operation, so for an ordinary app this answers false whether the hotspot
    // is off OR we simply are not allowed to know, and those are not the same
    // fact. Verified on an emulator: it returns false with no hidden-API denial
    // logged at all. Trusting that false is what told a tester his hotspot was
    // off while he was looking at it running.
    if (viaReflection == true) return true

    // Stage 2: look for a SoftAP-style network interface. When the rider
    // toggles hotspot on, the kernel brings up an interface named "ap0",
    // "softap0", "wlan1" (Samsung) or "swlan0" (some MIUI).
    //
    // Finding one proves the hotspot is up. NOT finding one proves nothing:
    // the list is a guess at OEM naming, and an OEM we have not met names it
    // something else. So a hit returns true and a miss returns null, never
    // false. Only stage 1 can report a trustworthy "off".
    val sawSoftAp = runCatching {
        val ifs = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        ifs.any { iface ->
            val name = iface.name.orEmpty().lowercase()
            iface.isUp && !iface.isLoopback && (
                name.startsWith("ap") || name.startsWith("softap") ||
                name.startsWith("swlan") || name == "wlan1"
            )
        }
    }.getOrDefault(false)
    return if (sawSoftAp) true else null
}

/**
 * HUD companion settings, surfaced as a section at the bottom of the
 * Integration tab next to Flic 2 buttons and Volume keys.
 *
 * Intentionally minimal in v0.1, one master switch plus the port and a
 * mirror-navigation toggle. Anything else is wired to the same shared
 * settings the phone already exposes (units, accent, gauge thresholds), so
 * a single source of truth governs phone + watch + HUD.
 *
 * The phone-side server is gated on [com.eried.eucplanet.data.model.AppSettings.hudServerEnabled]
 * and on the [com.eried.eucplanet.service.WheelService] being alive; it
 * binds a listening socket only while the rider has the app actively open.
 */
/**
 * Android Auto's stat boxes and buttons, picked like the home screen widget's
 * slots but saved apart: the car and the home screen are different jobs.
 * Three buttons, and all three are the rider's. Navigate used to lead the
 * strip and took a slot; it went when this stopped being a navigation app.
 */
@Composable
internal fun AndroidAutoSection(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel,
) {
    val aa = com.eried.eucplanet.data.model.AndroidAutoSettings
    val metricSlots = aa.metricSlots(settings.androidAuto.metrics)
    val actionSlots = aa.actionSlots(settings.androidAuto.actions)
    val metricOptions = WidgetMetricType.entries.map { it.key to stringResource(it.pickerLabel) }
    val actionOptions = WidgetActionType.entries.map { it.key to stringResource(it.pickerLabel) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(stringResource(Res.string.section_android_auto))
        HintText(stringResource(Res.string.android_auto_hint), small = true)
        metricSlots.indices.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { i ->
                    WidgetSlotDropdown(
                        label = stringResource(Res.string.widget_metric_slot, i + 1),
                        currentLabel = WidgetMetricType.byKey(metricSlots[i])
                            ?.let { stringResource(it.pickerLabel) } ?: "",
                        options = metricOptions,
                        onSelect = { viewModel.updateAndroidAutoMetricSlot(i, it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        actionSlots.indices.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { i ->
                    WidgetSlotDropdown(
                        label = stringResource(Res.string.widget_action_slot, i + 1),
                        currentLabel = WidgetActionType.byKey(actionSlots[i])
                            ?.let { stringResource(it.pickerLabel) } ?: "",
                        options = actionOptions,
                        onSelect = { viewModel.updateAndroidAutoActionSlot(i, it) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        SwitchSetting(
            stringResource(Res.string.car_alarm_notifications),
            settings.androidAuto.alarmNotifications,
        ) { viewModel.updateAndroidAutoAlarmNotifications(it) }
        HintText(stringResource(Res.string.car_alarm_notifications_hint), small = true)
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
        // Slides in when a HUD that takes the app links, and out when the
        // link drops, the way the address row below opens and closes.
        val hudSinkForCard: com.eried.eucplanet.service.hud.HudCommandSink = org.koin.compose.koinInject()
        val hudTakesApp by hudSinkForCard.hudTakesApp.collectAsState()
        AnimatedVisibility(visible = pairedHudVersion != null && hudTakesApp) {
            HudAppCard(viewModel)
        }

        // Hotspot hint sits ABOVE the link controls -- it's the usual
        // setup step. Optional: some riders put the HUD on their home
        // wifi or a separate router, in which case the hotspot doesn't
        // matter. The hint copy makes that explicit so a rider whose
        // hotspot is intentionally off doesn't think anything is wrong.
        // Nothing to set up while a HUD is linked: the hint returns when
        // the link drops, which is when it is useful again.
        HudHotspotHint(linked = pairedHudVersion != null)

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
        HintText(stringResource(Res.string.hud_discovery_desc), small = true)

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
                // 192.168.43.42 looks like a typical Android-hotspot
                // client IP (43.0/24 is the legacy AOSP softAP subnet,
                // .42 is obviously placeholder-ish so it doesn't get
                // mistaken for an actual fixed address).
                placeholder = { Text("192.168.43.42") },
                singleLine = true,
                enabled = fieldsEnabled,
                // KeyboardType.Phone for digits + dots.
                //
                // platformImeOptions passes Android IME flags Compose
                // doesn't expose directly:
                //  - flagNoPersonalizedLearning: don't store typed IPs
                //  - flagNoExtractUi: suppress Gboard's candidate strip,
                //    which on dark theme rendered as the "black bar"
                //    below the field testers reported.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                    autoCorrectEnabled = false,
                    platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions(
                        "flagNoPersonalizedLearning|flagNoExtractUi"
                    )
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
                    platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions(
                        "flagNoPersonalizedLearning|flagNoExtractUi"
                    )
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
        // piped to the Service Mode log as NOTE entries. Riders don't see
        // any of it unless they explicitly open the diagnostics dialog.
        // The connection-source StateFlow on HudServer stays around because
        // the watchdog and tests still consult it; only the rider-facing
        // surface is gone.

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
            HintText(stringResource(Res.string.hud_joystick_desc), small = true)
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
    val ctx = LocalContext.current
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
                        text = hudScreenDisplayName(ctx, id),
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

/** Disable the Checkbox visually when it's the last enabled screen AND
 *  already checked -- prevents the rider from tapping the only screen
 *  they have off, while letting them tick disabled screens back on. */
private fun wantCheckedCanFlip(currentlyChecked: Boolean, canUncheck: Boolean): Boolean {
    if (!currentlyChecked) return true
    return canUncheck
}

/** Map stable HUD screen id to its localized display name. Falls back
 *  to the id itself for unknown ids so a future-shipped HUD that
 *  reports a brand-new screen still surfaces SOMETHING the rider can
 *  see and reorder. */
private fun hudScreenDisplayName(ctx: android.content.Context, id: String): String = when (id) {
    "Dashboard" -> ctx.getString(Res.string.hud_screen_name_dashboard)
    "Camera" -> ctx.getString(Res.string.hud_screen_name_camera)
    "Telemetry" -> ctx.getString(Res.string.hud_screen_name_telemetry)
    "Custom" -> ctx.getString(Res.string.hud_screen_name_custom)
    "CustomCam" -> ctx.getString(Res.string.hud_screen_name_custom_cam)
    "MapNav" -> ctx.getString(Res.string.hud_screen_name_map_nav)
    "Map" -> ctx.getString(Res.string.hud_screen_name_map)
    "Nav" -> ctx.getString(Res.string.hud_screen_name_nav)
    "Power" -> ctx.getString(Res.string.hud_screen_name_power)
    "TripStats" -> ctx.getString(Res.string.hud_screen_name_trip_stats)
    "Compass" -> ctx.getString(Res.string.hud_screen_name_compass)
    "Safety" -> ctx.getString(Res.string.hud_screen_name_safety)
    "BigClock" -> ctx.getString(Res.string.hud_screen_name_big_clock)
    else -> id
}

/**
 * Map-tile style picker. The chosen code goes into AppSettings.hudMapStyle,
 * ships over the wire on the next 5 Hz frame, and the HUD's HudTileCache
 * swaps URL templates + clears its bitmap LRU so the change is visible
 * within a few seconds.
 *
 * Five Carto raster styles cover the readability range riders care about:
 * Voyager (default, neutral parchment), Dark + Dark-no-labels (night riding
 * / OLED-style cockpits), Light (high contrast on bright prisms), and
 * Positron (low-key low-saturation). All five are free with CDN attribution
 * (handled in the HUD's MapScreen footer).
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

/**
 * Phone HUD: the same Overlay Studio presets the companion HUD shows,
 * drawn in a window over other apps instead of shipped to a screen.
 */
@Composable
internal fun PhoneHudSection(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel,
) {
    AdvancedCollapsable(
        title = stringResource(Res.string.phone_hud_section),
        stateKey = "surface-phone-hud",
    ) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // Re-read on every resume: the rider grants this in system settings
        // and comes back, so a value captured once would be stale exactly
        // when it matters.
        var canDraw by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(ctx)) }
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    canDraw = android.provider.Settings.canDrawOverlays(ctx)
                }
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }

        HintText(stringResource(Res.string.phone_hud_desc), small = true)

        if (!canDraw) {
            // The switch stays off and un-flippable until the permission is
            // there, rather than turning on and quietly doing nothing.
            HintText(stringResource(Res.string.phone_hud_permission_desc), small = true)
            com.eried.eucplanet.ui.common.FixButton(
                text = stringResource(Res.string.phone_hud_grant),
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${ctx.packageName}"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
        } else {
            SwitchSetting(
                stringResource(Res.string.phone_hud_enable),
                settings.phoneHudEnabled,
            ) { viewModel.updatePhoneHudEnabled(it) }

            if (settings.phoneHudEnabled) {
                // Same picker the HUD's custom overlay uses: the Studio's
                // own LoadPresetSheet, so a rider who has chosen a preset
                // for the HUD finds the identical browser here rather than
                // a second, poorer way of doing it.
                var lists by remember {
                    mutableStateOf<SettingsViewModel.HudOverlayLists?>(null)
                }
                var presetSheetOpen by remember { mutableStateOf(false) }
                LaunchedEffect(settings.phoneHudOverlayName) {
                    lists = viewModel.loadHudOverlayLists()
                }

                if (settings.phoneHudOverlayName.isNotBlank()) {
                    Text(
                        stringResource(
                            Res.string.hud_overlay_current_preset,
                            settings.phoneHudOverlayName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.appColors.textPrimary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { presetSheetOpen = true },
                        colors = themedTonalButtonColors(),
                        shape = RoundedCornerShape(12.dp),
                    ) { com.eried.eucplanet.ui.common.SearchableText(stringResource(Res.string.hud_overlay_select_preset)) }
                    if (settings.phoneHudOverlayName.isNotBlank()) {
                        TextButton(
                            onClick = { viewModel.pickPhoneHudOverlay("") },
                            colors = themedTextButtonColors(),
                            shape = RoundedCornerShape(12.dp),
                        ) { com.eried.eucplanet.ui.common.SearchableText(stringResource(Res.string.hud_custom_overlay_none)) }
                    }
                }

                if (presetSheetOpen) {
                    val l = lists
                    if (l != null) {
                        // Dialog, not inline: LoadPresetSheet scrolls
                        // internally and needs a bounded height, which the
                        // settings column (itself scrolling) cannot give it.
                        // The HUD section documents the same crash.
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = { presetSheetOpen = false },
                            properties = androidx.compose.ui.window.DialogProperties(
                                usePlatformDefaultWidth = false
                            ),
                        ) {
                            com.eried.eucplanet.ui.studio.LoadPresetSheet(
                                folderAvailable = l.folderAvailable,
                                presets = l.savedPresets,
                                bundledPresets = l.bundledPortrait,
                                // Landscape layouts are offered too. The
                                // window never rotates, so a preset drawn
                                // exactly as authored is the honest result
                                // rather than geometry we try to correct.
                                bundledLandscapePresets = l.bundledLandscape,
                                onLoad = {
                                    viewModel.pickPhoneHudOverlay(it)
                                    presetSheetOpen = false
                                },
                                onLoadBundled = {
                                    viewModel.pickPhoneHudOverlay(it)
                                    presetSheetOpen = false
                                },
                                onDelete = { /* deleting belongs in the Studio */ },
                                onOpenFolderSettings = { presetSheetOpen = false },
                                onDismiss = { presetSheetOpen = false },
                            )
                        }
                    }
                }
                // No caption under this one. The switch says what it does,
                // and the portrait-only limit is enforced by the picker
                // simply not offering landscape layouts, so explaining it
                // here was words for something the rider cannot hit.
                SwitchSetting(
                    stringResource(Res.string.phone_hud_only_away),
                    settings.phoneHudOnlyWhenAway,
                ) { viewModel.updatePhoneHudOnlyWhenAway(it) }
            }
        }
    }
}
