package com.eried.eucplanet.ui.settings.offline

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.format.Formatter
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.nav.offline.OfflineTileStore
import com.eried.eucplanet.nav.offline.map.OfflineMapWeb
import com.eried.eucplanet.ui.settings.OfflineAreasRow
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** The offline areas screen on Android: the shared dialog over a WebView, the Koin view model and the offline map. */
@Composable
fun AndroidOfflineDataDialog(onDismiss: () -> Unit, routeSquares: List<String>? = null) {
    val ctx = LocalContext.current
    val host = remember(ctx) { AndroidOfflineDataHost(ctx) }
    OfflineDataDialog(
        onDismiss = onDismiss,
        viewModel = koinViewModel(),
        host = host,
        offlineMapJs = OfflineMapWeb.JS,
        routeSquares = routeSquares,
    )
}

/** The Navigation section's "Offline areas" row on Android. */
@Composable
fun AndroidOfflineAreasRow() {
    val ctx = LocalContext.current
    OfflineAreasRow(
        store = koinInject<OfflineTileStore>(),
        host = remember(ctx) { AndroidOfflineDataHost(ctx) },
        dialog = { dismiss -> AndroidOfflineDataDialog(onDismiss = dismiss) },
    )
}

/** Formatter's short sizes and the squares map in a WebView, as the dialog always drew it. */
class AndroidOfflineDataHost(private val context: Context) : OfflineDataHost {
    override fun formatSize(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun GridMap(
        html: String,
        backgroundArgb: Int,
        onTile: (String) -> Unit,
        onPage: (OfflineGridPage) -> Unit,
        onPageFinished: () -> Unit,
        modifier: Modifier,
    ) {
        val tile = rememberUpdatedState(onTile)
        val finished = rememberUpdatedState(onPageFinished)
        AndroidView(
            modifier = modifier,
            factory = { c ->
                WebView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    setBackgroundColor(backgroundArgb)
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onTile(name: String) {
                            post { tile.value(name) }
                        }
                    }, "AndroidOffline")
                    val offlineMap = OfflineMapWeb.renderer()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { finished.value() }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ) = OfflineMapWeb.intercept(offlineMap, request)
                    }
                    loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                    onPage(OfflineGridPage { js -> evaluateJavascript(js, null) })
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

/** TripRepository's last fix and ConnectivityManager's metered flag. */
class AndroidOfflineDataPorts(
    private val context: Context,
    private val trips: TripRepository,
) : OfflineDataPorts {
    override fun lastKnownLocation(): GeoPoint? =
        trips.lastKnownLocation.value?.let { GeoPoint(it.latitude, it.longitude) }

    override fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
