package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.eried.eucplanet.ui.recording.EucViewerHost
import com.eried.eucplanet.ui.recording.EucViewerPayload
import com.eried.eucplanet.ui.recording.TripMapPage
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The iPhone's side of View online: the EUC Viewer in a WKWebView, as the
 * trip map is drawn. WKWebView cannot answer a request to the viewer's own
 * https origin, as Android's WebView does for the trip path, so the trip goes
 * in as base64 in pieces small enough for one script each, and the page reads
 * them back from `window.__eucTrip`.
 */
internal object IosEucViewerHost : EucViewerHost {
    /** Characters of base64 per injected script. */
    private const val CHUNK = 256 * 1024

    @OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
    @Composable
    override fun ViewerWebView(
        url: String,
        payload: () -> EucViewerPayload?,
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
                NSURL.URLWithString(url)?.let { web.loadRequest(NSURLRequest.requestWithURL(it)) }
                onPage(TripMapPage { js -> web.evaluateJavaScript(js, completionHandler = null) })
                web
            },
            modifier = modifier,
            // The viewer pans and zooms its own map.
            properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun tripSource(page: TripMapPage, payload: EucViewerPayload): String {
        val b64 = Base64.encode(payload.bytes)
        page.evaluate("window.__eucTrip=[];")
        // Scripts run in the order they are sent, before the loader below.
        for (piece in b64.chunked(CHUNK)) page.evaluate("window.__eucTrip.push('$piece');")
        return "var b=window.__eucTrip.join(''); window.__eucTrip=null; deliver(b);"
    }
}
