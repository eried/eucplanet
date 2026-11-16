package com.eried.eucplanet.ui.settings

// The settings rows for the Android-only surfaces: sending the HUD its app,
// the hotspot hint, the phone HUD window and Android Auto. The shared
// SettingsScreen reaches them through SettingsHost; the HUD section itself is
// shared (HudSettingsSection.kt).

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.data.model.WidgetActionType
import com.eried.eucplanet.data.model.WidgetMetricType
import com.eried.eucplanet.data.model.pickerLabel
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.common.InfoHint
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedTextButtonColors
import com.eried.eucplanet.ui.theme.themedTonalButtonColors


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

/**
 * The HUD app card: slides in when a HUD that takes the app links, and out
 * when the link drops, the way the address row below it opens and closes.
 */
@Composable
internal fun HudAppSection(viewModel: SettingsViewModel, linked: Boolean) {
    val hudSinkForCard: com.eried.eucplanet.service.hud.HudCommandSink = org.koin.compose.koinInject()
    val hudTakesApp by hudSinkForCard.hudTakesApp.collectAsState()
    // The section's column animates its rows this way.
    AnimatedVisibility(
        visible = linked && hudTakesApp,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        HudAppCard(viewModel)
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
    val sender: com.eried.eucplanet.service.hud.HudAppSender = org.koin.compose.koinInject()
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
    // question there, and a snackbar says where to look.
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
internal fun HudHotspotHint(linked: Boolean) {
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
    // Nothing until the first probe lands, so the card doesn't flash.
    if (!probed) return
    HudSetupCard(
        stringResource(
            // Confirmed on, or "we cannot tell". There is deliberately
            // no "off" copy: nothing available to an ordinary app can
            // prove a hotspot is off, so the card never claims it.
            if (hotspotOn == true) Res.string.hud_hotspot_on_hint
            else Res.string.hud_hotspot_unknown_hint
        )
    )
}

/**
 * Two-stage hotspot detection. Returns true when a stage proves the hotspot
 * is on, and null when neither could tell. It never returns false.
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
    // false.
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
 * Android Auto's stat boxes and buttons, picked like the home screen widget's
 * slots but saved apart: the car and the home screen are different jobs.
 * Three buttons, and all three are the rider's.
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
        HintText(stringResource(Res.string.android_auto_hint))
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
        HintText(stringResource(Res.string.car_alarm_notifications_hint))
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

        HintText(stringResource(Res.string.phone_hud_desc))

        if (!canDraw) {
            // The switch stays off and un-flippable until the permission is
            // there, rather than turning on and quietly doing nothing.
            HintText(stringResource(Res.string.phone_hud_permission_desc))
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
                // No caption under this one. The switch says what it does.
                SwitchSetting(
                    stringResource(Res.string.phone_hud_only_away),
                    settings.phoneHudOnlyWhenAway,
                ) { viewModel.updatePhoneHudOnlyWhenAway(it) }
            }
        }
    }
}
