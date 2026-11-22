package com.eried.eucplanet.ui.settings.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.platform.pluralStringResource
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.action_cancel
import com.eried.eucplanet.resources.action_delete
import com.eried.eucplanet.resources.action_retry
import com.eried.eucplanet.resources.offline_data_all_current
import com.eried.eucplanet.resources.offline_data_along_route
import com.eried.eucplanet.resources.offline_data_around_me
import com.eried.eucplanet.resources.offline_data_around_me_wide
import com.eried.eucplanet.resources.offline_data_check_now
import com.eried.eucplanet.resources.offline_data_credit
import com.eried.eucplanet.resources.offline_data_delete_q
import com.eried.eucplanet.resources.offline_data_download
import com.eried.eucplanet.resources.offline_data_download_q
import com.eried.eucplanet.resources.offline_data_list_error
import com.eried.eucplanet.resources.offline_data_mobile_warn
import com.eried.eucplanet.resources.offline_data_near_all_here
import com.eried.eucplanet.resources.offline_data_near_q
import com.eried.eucplanet.resources.offline_data_no_location
import com.eried.eucplanet.resources.offline_data_no_route
import com.eried.eucplanet.resources.offline_data_route_all_here
import com.eried.eucplanet.resources.offline_data_route_q
import com.eried.eucplanet.resources.offline_data_select
import com.eried.eucplanet.resources.offline_data_space
import com.eried.eucplanet.resources.offline_data_stop
import com.eried.eucplanet.resources.offline_data_stop_q
import com.eried.eucplanet.resources.offline_data_tap_hint
import com.eried.eucplanet.resources.offline_data_title
import com.eried.eucplanet.resources.offline_data_update
import com.eried.eucplanet.resources.offline_data_update_all
import com.eried.eucplanet.resources.offline_data_updates_found
import com.eried.eucplanet.resources.offline_data_wifi_updates
import com.eried.eucplanet.ui.common.appMenuItemColors
import com.eried.eucplanet.ui.theme.appColors

/**
 * What the offline areas screen asks of its platform: the phone's way of
 * writing a file size, and the web view the squares map draws in. Android's
 * is AndroidOfflineDataHost (Formatter, WebView), the iPhone's
 * IosOfflineDataHost (NSByteCountFormatter, WKWebView).
 */
interface OfflineDataHost {
    /** [bytes] as the phone writes a short file size ("1.2 GB"). */
    fun formatSize(bytes: Long): String

    /**
     * The squares map: [html] (OfflineGridHtml.page) in a web view on
     * [backgroundArgb], its `AndroidOffline.onTile(name)` calls to [onTile].
     * [onPage] gets a way to run script on the page; [onPageFinished] says
     * when it has loaded.
     */
    @Composable
    fun GridMap(
        html: String,
        backgroundArgb: Int,
        onTile: (String) -> Unit,
        onPage: (OfflineGridPage) -> Unit,
        onPageFinished: () -> Unit,
        modifier: Modifier,
    )
}

/** Runs script on the squares map's page. */
fun interface OfflineGridPage {
    fun run(script: String)
}

/** A choice waiting for the rider: download, update, stop or delete [row]. */
private data class Pending(val row: TileRow, val mobileWarn: Boolean = false)

/**
 * Offline areas: a map of 5 degree squares, each a map and routing. Tap one
 * to download, stop or delete it. The squares around the rider or along the
 * planned route come from one small menu, after a question with their size.
 * [routeSquares] (from Start, before a ride) asks about those squares as
 * soon as the server list is in, so the sizes are right.
 */
@Composable
fun OfflineDataDialog(
    onDismiss: () -> Unit,
    viewModel: OfflineDataViewModel,
    host: OfflineDataHost,
    offlineMapJs: String,
    routeSquares: List<String>? = null,
) {
    val colors = MaterialTheme.appColors
    val state by viewModel.state.collectAsState()
    val wifiAuto by viewModel.wifiAutoUpdate.collectAsState()
    val mapType by produceState<String?>(null) { value = viewModel.mapType() }
    val hasRoute = remember { viewModel.hasRoute() }
    var pageReady by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<OfflineGridPage?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var bulk by remember { mutableStateOf<Bulk?>(null) }
    var selectOpen by remember { mutableStateOf(false) }
    // The screen's snackbar would sit behind this full-screen dialog, so the
    // dialog has its own, styled the same.
    val snack = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    fun toast(message: String) {
        snackScope.launch {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(message)
        }
    }
    val noRoute = stringResource(Res.string.offline_data_no_route)
    val allHere = stringResource(Res.string.offline_data_route_all_here)
    val nearAllHere = stringResource(Res.string.offline_data_near_all_here)
    val noLocation = stringResource(Res.string.offline_data_no_location)
    // What Check for updates found, put into words here because a plural
    // is read in composition; cleared once shown so the same count shows again.
    var checked by remember { mutableStateOf<Int?>(null) }
    checked?.let { n ->
        val message = if (n == 0) stringResource(Res.string.offline_data_all_current)
        else pluralStringResource(Res.plurals.offline_data_updates_found, n, n)
        LaunchedEffect(n) {
            toast(message)
            checked = null
        }
    }

    fun size(b: Long) = host.formatSize(b)

    var routeAsked by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) {
        if (routeSquares == null || routeAsked || state.loading) return@LaunchedEffect
        routeAsked = true
        val missing = viewModel.missingOf(routeSquares)
        if (missing.isEmpty()) toast(allHere) else bulk = Bulk(missing, route = true)
    }

    fun ask(row: TileRow) {
        pending = Pending(row, row.status == TileStatus.AVAILABLE && OfflineDataState.warnSize(row.bytes, viewModel.onWifi()))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.appBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.offline_data_title), style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                    Text(
                        stringResource(Res.string.offline_data_space, size(state.usedBytes), size(state.freeBytes)),
                        style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
                    )
                }
                com.eried.eucplanet.ui.common.CloseIconButton(onClick = onDismiss)
            }
            if (state.listError) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.offline_data_list_error),
                        style = MaterialTheme.typography.bodySmall, color = colors.statusWarn, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::refresh) {
                        Text(stringResource(Res.string.action_retry), color = colors.textButton)
                    }
                }
            }
            Text(
                stringResource(Res.string.offline_data_tap_hint),
                style = MaterialTheme.typography.bodySmall, color = colors.hint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val type = mapType
                if (type != null) {
                    val html = remember(type) {
                        val start = viewModel.startPoint()
                        OfflineGridHtml.page(
                            type, hex(colors.appBackground),
                            start?.lat ?: 54.0, start?.lng ?: 12.0, if (start != null) 6 else 3,
                            offlineMapJs,
                        )
                    }
                    host.GridMap(
                        html = html,
                        backgroundArgb = colors.appBackground.toArgb(),
                        onTile = { name -> viewModel.state.value.tiles[name]?.let(::ask) },
                        onPage = { page = it },
                        onPageFinished = { pageReady = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Small chips on the map instead of a row of wide buttons.
                // Select picks several squares at once; My route greys out
                // until a route is planned.
                Column(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box {
                        MapChip(Icons.Default.SelectAll, stringResource(Res.string.offline_data_select)) { selectOpen = true }
                        DropdownMenu(
                            expanded = selectOpen,
                            onDismissRequest = { selectOpen = false },
                            containerColor = colors.menuBackground,
                        ) {
                            val itemColors = appMenuItemColors()
                            fun near(km: Double) {
                                selectOpen = false
                                val missing = viewModel.nearTilesMissing(km)
                                when {
                                    missing == null -> toast(noLocation)
                                    missing.isEmpty() -> toast(nearAllHere)
                                    else -> bulk = Bulk(missing, route = false)
                                }
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.offline_data_around_me)) },
                                leadingIcon = { Icon(Icons.Default.MyLocation, contentDescription = null) },
                                colors = itemColors,
                                onClick = { near(NEAR_KM) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.offline_data_around_me_wide)) },
                                leadingIcon = { Icon(Icons.Default.ZoomOutMap, contentDescription = null) },
                                colors = itemColors,
                                onClick = { near(WIDE_KM) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.offline_data_along_route)) },
                                leadingIcon = { Icon(Icons.Default.Route, contentDescription = null) },
                                enabled = hasRoute,
                                colors = itemColors,
                                onClick = {
                                    selectOpen = false
                                    val missing = viewModel.routeTilesMissing()
                                    when {
                                        missing == null -> toast(noRoute)
                                        missing.isEmpty() -> toast(allHere)
                                        else -> bulk = Bulk(missing, route = true)
                                    }
                                },
                            )
                        }
                    }
                    if (state.outdatedCount > 0) {
                        MapChip(Icons.Default.Refresh, stringResource(Res.string.offline_data_update_all, state.outdatedCount), viewModel::updateAll)
                    }
                }
            }
            // Squares drawn again whenever their state changes.
            LaunchedEffect(pageReady, state.tiles) {
                val wv = page ?: return@LaunchedEffect
                if (!pageReady) return@LaunchedEffect
                wv.run("setColors(${squareColors(colors.statusGood, colors.statusWarn, colors.primary, colors.outline)})")
                val json = OfflineGridHtml.tilesJson(state.tiles.values) { r ->
                    when (r.status) {
                        TileStatus.DOWNLOADING -> "${(r.progress * 100).toInt()}%"
                        TileStatus.DOWNLOADED, TileStatus.UPDATE -> size(r.bytes)
                        TileStatus.AVAILABLE -> null
                    }
                }
                wv.run("setTiles($json)")
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.offline_data_wifi_updates),
                    color = colors.textPrimary, modifier = Modifier.weight(1f),
                )
                // Checks now instead of waiting for the week; the same still
                // icon as the weather panel's refresh, dimmed while it asks.
                // Only with something downloaded: there is nothing to update
                // otherwise.
                if (state.tiles.values.any { it.status == TileStatus.DOWNLOADED || it.status == TileStatus.UPDATE }) {
                    IconButton(
                        onClick = {
                            // null: the list error line already says it.
                            viewModel.checkNow { outdated -> checked = outdated }
                        },
                        enabled = !state.loading,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(Res.string.offline_data_check_now),
                            tint = colors.textSecondary.copy(alpha = if (state.loading) 0.35f else 1f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Switch(
                    checked = wifiAuto,
                    onCheckedChange = viewModel::setWifiAutoUpdate,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.switchOn, uncheckedTrackColor = colors.switchOff),
                )
            }
            Text(
                stringResource(Res.string.offline_data_credit),
                style = MaterialTheme.typography.labelSmall, color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SnackbarHost(snack, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            Snackbar(
                it,
                containerColor = colors.snackbarBackground,
                contentColor = colors.snackbarText,
                actionContentColor = colors.snackbarAction,
            )
        }
        }
    }

    pending?.let { p ->
        val row = p.row
        val (question, confirm) = when (row.status) {
            TileStatus.AVAILABLE -> if (p.mobileWarn) {
                stringResource(Res.string.offline_data_mobile_warn, size(row.bytes))
            } else {
                stringResource(Res.string.offline_data_download_q, size(row.bytes))
            } to stringResource(Res.string.offline_data_download)
            TileStatus.DOWNLOADING -> stringResource(Res.string.offline_data_stop_q) to stringResource(Res.string.offline_data_stop)
            TileStatus.DOWNLOADED, TileStatus.UPDATE ->
                stringResource(Res.string.offline_data_delete_q, size(row.bytes)) to stringResource(Res.string.action_delete)
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = colors.dialog,
            text = { Text(question, color = colors.textPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    when (row.status) {
                        TileStatus.AVAILABLE -> viewModel.download(listOf(row.name))
                        TileStatus.DOWNLOADING -> viewModel.cancel(row.name)
                        TileStatus.DOWNLOADED, TileStatus.UPDATE -> viewModel.delete(row.name)
                    }
                    pending = null
                }) {
                    Text(confirm, color = if (row.status == TileStatus.AVAILABLE) colors.textButton else colors.statusDanger)
                }
            },
            dismissButton = {
                if (row.status == TileStatus.UPDATE) {
                    TextButton(onClick = { viewModel.download(listOf(row.name)); pending = null }) {
                        Text(stringResource(Res.string.offline_data_update), color = colors.textButton)
                    }
                } else {
                    TextButton(onClick = { pending = null }) {
                        Text(stringResource(Res.string.action_cancel), color = colors.textButton)
                    }
                }
            },
        )
    }

    bulk?.let { b ->
        val names = b.names
        val bytes = remember(names) { viewModel.fetchBytes(names) }
        val text = if (OfflineDataState.warnSize(bytes, viewModel.onWifi())) {
            stringResource(Res.string.offline_data_mobile_warn, size(bytes))
        } else {
            stringResource(if (b.route) Res.string.offline_data_route_q else Res.string.offline_data_near_q, size(bytes))
        }
        AlertDialog(
            onDismissRequest = { bulk = null },
            containerColor = colors.dialog,
            text = { Text(text, color = colors.textPrimary) },
            confirmButton = {
                TextButton(onClick = { viewModel.download(names); bulk = null }) {
                    Text(stringResource(Res.string.offline_data_download), color = colors.textButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { bulk = null }) {
                    Text(stringResource(Res.string.action_cancel), color = colors.textButton)
                }
            },
        )
    }
}

/** Squares picked together, waiting for the rider's yes. */
private class Bulk(val names: List<String>, val route: Boolean)

/** "Around me" and its wider version: how far out squares are picked. */
private const val NEAR_KM = 50.0
private const val WIDE_KM = 200.0

/** A small pill over the map. */
@Composable
private fun MapChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.appColors
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = colors.navPopupPanel,
        contentColor = colors.navPopupInk,
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = colors.navPopupInk, modifier = Modifier.padding(end = 6.dp).size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = colors.navPopupInk)
        }
    }
}

private fun hex(c: Color): String = "#%06X".format(0xFFFFFF and c.toArgb())

/** Square colours per state, from the theme. */
private fun squareColors(good: Color, warn: Color, active: Color, idle: Color): String = JSONObject().apply {
    put("DOWNLOADED", JSONObject().put("stroke", hex(good)).put("fill", hex(good)).put("op", 0.35))
    put("UPDATE", JSONObject().put("stroke", hex(warn)).put("fill", hex(warn)).put("op", 0.35))
    put("DOWNLOADING", JSONObject().put("stroke", hex(active)).put("fill", hex(active)).put("op", 0.3))
    put("AVAILABLE", JSONObject().put("stroke", hex(idle)).put("fill", hex(idle)).put("op", 0.04))
}.toString()
