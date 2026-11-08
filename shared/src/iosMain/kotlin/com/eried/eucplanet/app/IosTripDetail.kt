package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.ui.recording.TripDetailHost
import com.eried.eucplanet.ui.recording.TripMapHtml
import com.eried.eucplanet.ui.recording.TripMapPage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/**
 * The iPhone's side of the trip viewer: the route map in a WKWebView, loading
 * the same page as Android's WebView with the bundled Leaflet inlined, so the
 * map draws without the CDN (the tiles still need the network). There is no
 * downloaded map on iOS yet, so the page gets TripMapHtml.NO_OFFLINE_MAP_JS.
 */
internal object IosTripDetailHost : TripDetailHost {
    private const val TAG = "TripMap"

    override val isDebugBuild: Boolean = false

    override val offlineMapJs: String = TripMapHtml.NO_OFFLINE_MAP_JS

    /** leaflet.css and leaflet.js from app/src/main/assets, copied into the iOS resources at build time. */
    @OptIn(ExperimentalResourceApi::class)
    override val leafletHead: String by lazy {
        runCatching {
            runBlocking {
                val css = Res.readBytes("files/leaflet.css").decodeToString()
                val js = Res.readBytes("files/leaflet.js").decodeToString()
                "<style>\n$css\n</style>\n<script>\n$js\n</script>"
            }
        }.getOrElse {
            Log.w(TAG, "No bundled Leaflet (${it.message}), using the CDN")
            TripMapHtml.LEAFLET_CDN_HEAD
        }
    }

    @OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
    @Composable
    override fun MapWebView(
        html: String,
        onPage: (TripMapPage) -> Unit,
        onLoaded: () -> Unit,
        modifier: Modifier,
    ) {
        val loaded = rememberUpdatedState(onLoaded)
        // WKWebView holds its navigation delegate weakly: the composition keeps it.
        val delegate = remember { MapNavigationDelegate { loaded.value() } }
        UIKitView(
            factory = {
                val web = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = WKWebViewConfiguration())
                web.navigationDelegate = delegate
                // Leaflet pans and zooms the map itself; the page must not scroll or bounce.
                web.scrollView.scrollEnabled = false
                web.scrollView.bounces = false
                web.opaque = false
                // The page's own background (#0b0f19), as Android's WebView has under it.
                web.backgroundColor = UIColor(red = 11.0 / 255, green = 15.0 / 255, blue = 25.0 / 255, alpha = 1.0)
                web.loadHTMLString(html, baseURL = null)
                onPage(TripMapPage { js -> web.evaluateJavaScript(js, completionHandler = null) })
                web
            },
            modifier = modifier,
            // A drag that starts on the map pans the map, never the screen
            // under it, as Android's WebView asks of its scroll parent.
            properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
        )
    }

    /** iOS shows the fullscreen dialog over everything already; nothing to set. */
    @Composable
    override fun FullscreenMapWindow() = Unit
}

private class MapNavigationDelegate(private val onLoaded: () -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onLoaded()
    }
}
