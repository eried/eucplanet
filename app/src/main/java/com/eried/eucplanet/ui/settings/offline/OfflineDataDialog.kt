package com.eried.eucplanet.ui.settings.offline

import android.annotation.SuppressLint
import android.text.format.Formatter
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.appColors
import org.json.JSONObject
import org.koin.androidx.compose.koinViewModel

/** A choice waiting for the rider: download, update, stop or delete [row]. */
private data class Pending(val row: TileRow, val mobileWarn: Boolean = false)

/**
 * Offline routing areas: a map of 5 degree squares. Tap one to download,
 * stop or delete it. The planned route's squares come in one tap.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfflineDataDialog(onDismiss: () -> Unit, viewModel: OfflineDataViewModel = koinViewModel()) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.appColors
    val state by viewModel.state.collectAsState()
    val wifiAuto by viewModel.wifiAutoUpdate.collectAsState()
    val mapType by produceState<String?>(null) { value = viewModel.mapType() }
    var pageReady by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var bulk by remember { mutableStateOf<List<String>?>(null) }
    // A snackbar would sit behind this full-screen dialog: notes show inline.
    var notice by remember { mutableStateOf<String?>(null) }
    val noRoute = stringResource(R.string.offline_data_no_route)
    val allHere = stringResource(R.string.offline_data_route_all_here)

    fun size(b: Long) = Formatter.formatShortFileSize(ctx, b)

    fun ask(row: TileRow) {
        pending = Pending(row, row.status == TileStatus.AVAILABLE && OfflineDataState.warnSize(row.bytes, viewModel.onWifi()))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
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
                    Text(stringResource(R.string.offline_data_title), style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                    Text(
                        stringResource(R.string.offline_data_space, size(state.usedBytes), size(state.freeBytes)),
                        style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = colors.textPrimary)
                }
            }
            if (state.listError) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.offline_data_list_error),
                        style = MaterialTheme.typography.bodySmall, color = colors.statusWarn, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.action_retry), color = colors.textButton)
                    }
                }
            }
            Text(
                stringResource(R.string.offline_data_tap_hint),
                style = MaterialTheme.typography.bodySmall, color = colors.hint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val type = mapType
                if (type != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            WebView(c).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = true
                                setBackgroundColor(colors.appBackground.toArgb())
                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun onTile(name: String) {
                                        post { viewModel.state.value.tiles[name]?.let(::ask) }
                                    }
                                }, "AndroidOffline")
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) { pageReady = true }
                                }
                                val start = viewModel.startPoint()
                                loadDataWithBaseURL(
                                    "file:///android_asset/",
                                    OfflineGridHtml.page(
                                        type, hex(colors.appBackground),
                                        start?.lat ?: 54.0, start?.lng ?: 12.0, if (start != null) 6 else 3,
                                    ),
                                    "text/html", "UTF-8", null,
                                )
                                webView = this
                            }
                        },
                        onRelease = { wv ->
                            wv.removeJavascriptInterface("AndroidOffline")
                            wv.loadUrl("about:blank")
                            wv.destroy()
                        },
                    )
                }
            }
            // Squares drawn again whenever their state changes.
            LaunchedEffect(pageReady, state.tiles) {
                val wv = webView ?: return@LaunchedEffect
                if (!pageReady) return@LaunchedEffect
                wv.evaluateJavascript("setColors(${squareColors(colors.statusGood, colors.statusWarn, colors.primary, colors.outline)})", null)
                val json = OfflineGridHtml.tilesJson(state.tiles.values) { r ->
                    when (r.status) {
                        TileStatus.DOWNLOADING -> "${(r.progress * 100).toInt()}%"
                        TileStatus.DOWNLOADED, TileStatus.UPDATE -> size(r.bytes)
                        TileStatus.AVAILABLE -> null
                    }
                }
                wv.evaluateJavascript("setTiles($json)", null)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                    onClick = {
                        val missing = viewModel.routeTilesMissing()
                        when {
                            missing == null -> notice = noRoute
                            missing.isEmpty() -> notice = allHere
                            else -> bulk = missing
                        }
                    },
                ) { Text(stringResource(R.string.offline_data_along_route), maxLines = 2) }
                if (state.outdatedCount > 0) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                        onClick = viewModel::updateAll,
                    ) { Text(stringResource(R.string.offline_data_update_all, state.outdatedCount), maxLines = 2) }
                }
            }
            notice?.let { n ->
                LaunchedEffect(n) {
                    kotlinx.coroutines.delay(4_000)
                    notice = null
                }
                Text(
                    n, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.offline_data_wifi_updates),
                    color = colors.textPrimary, modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = wifiAuto,
                    onCheckedChange = viewModel::setWifiAutoUpdate,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.switchOn, uncheckedTrackColor = colors.switchOff),
                )
            }
            Text(
                stringResource(R.string.offline_data_credit),
                style = MaterialTheme.typography.labelSmall, color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    pending?.let { p ->
        val row = p.row
        val (question, confirm) = when (row.status) {
            TileStatus.AVAILABLE -> if (p.mobileWarn) {
                stringResource(R.string.offline_data_mobile_warn, size(row.bytes))
            } else {
                stringResource(R.string.offline_data_download_q, size(row.bytes))
            } to stringResource(R.string.offline_data_download)
            TileStatus.DOWNLOADING -> stringResource(R.string.offline_data_stop_q) to stringResource(R.string.offline_data_stop)
            TileStatus.DOWNLOADED, TileStatus.UPDATE ->
                stringResource(R.string.offline_data_delete_q, size(row.bytes)) to stringResource(R.string.action_delete)
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
                        Text(stringResource(R.string.offline_data_update), color = colors.textButton)
                    }
                } else {
                    TextButton(onClick = { pending = null }) {
                        Text(stringResource(R.string.action_cancel), color = colors.textButton)
                    }
                }
            },
        )
    }

    bulk?.let { names ->
        val bytes = names.sumOf { state.tiles[it]?.bytes ?: 0L }
        val text = if (OfflineDataState.warnSize(bytes, viewModel.onWifi())) {
            stringResource(R.string.offline_data_mobile_warn, size(bytes))
        } else {
            stringResource(R.string.offline_data_route_q, names.size, size(bytes))
        }
        AlertDialog(
            onDismissRequest = { bulk = null },
            containerColor = colors.dialog,
            text = { Text(text, color = colors.textPrimary) },
            confirmButton = {
                TextButton(onClick = { viewModel.download(names); bulk = null }) {
                    Text(stringResource(R.string.offline_data_download), color = colors.textButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { bulk = null }) {
                    Text(stringResource(R.string.action_cancel), color = colors.textButton)
                }
            },
        )
    }
}

private fun hex(c: Color): String = String.format("#%06X", 0xFFFFFF and c.toArgb())

/** Square colours per state, from the theme. */
private fun squareColors(good: Color, warn: Color, active: Color, idle: Color): String = JSONObject().apply {
    put("DOWNLOADED", JSONObject().put("stroke", hex(good)).put("fill", hex(good)).put("op", 0.35))
    put("UPDATE", JSONObject().put("stroke", hex(warn)).put("fill", hex(warn)).put("op", 0.35))
    put("DOWNLOADING", JSONObject().put("stroke", hex(active)).put("fill", hex(active)).put("op", 0.3))
    put("AVAILABLE", JSONObject().put("stroke", hex(idle)).put("fill", hex(idle)).put("op", 0.04))
}.toString()
