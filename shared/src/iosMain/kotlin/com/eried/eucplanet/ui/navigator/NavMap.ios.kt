@file:OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class, BetaInteropApi::class)

package com.eried.eucplanet.ui.navigator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import platform.CoreGraphics.CGRectMake
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

// Injected before the page runs so the Leaflet HTML's AndroidNav.* calls resolve;
// each forwards a small JSON envelope to the "nav" message handler.
private const val SHIM_JS = """
window.AndroidNav = {
  onMapClick: function(lat,lng){ __navPost({f:'mapClick',a:[lat,lng]}); },
  onRouteLineClick: function(lat,lng){ __navPost({f:'routeLineClick',a:[lat,lng]}); },
  onMarkerDragged: function(i,lat,lng){ __navPost({f:'markerDragged',a:[i,lat,lng]}); },
  onMarkerDragStart: function(){ __navPost({f:'markerDragStart',a:[]}); },
  onSelfTap: function(x,y){ __navPost({f:'selfTap',a:[x,y]}); },
  onMarkerTapped: function(i,x,y){ __navPost({f:'markerTapped',a:[i,x,y]}); },
  onMapViewChanged: function(lat,lng,z){ __navPost({f:'mapViewChanged',a:[lat,lng,z]}); },
  onTilesLoaded: function(){ __navPost({f:'tilesLoaded',a:[]}); }
};
function __navPost(o){ try { window.webkit.messageHandlers.nav.postMessage(JSON.stringify(o)); } catch(e){} }
"""

private val NAV_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

private class NavMsgHandler(private val cb: NavMapCallbacks) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val body = didReceiveScriptMessage.body as? String ?: return
        val o = try { NAV_JSON.parseToJsonElement(body).jsonObject } catch (_: Exception) { return }
        val f = (o["f"] as? JsonPrimitive)?.contentOrNull ?: return
        val a = o["a"] as? JsonArray ?: JsonArray(emptyList())
        fun d(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun n(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.intOrNull ?: 0
        when (f) {
            "mapClick" -> cb.onMapClick(d(0), d(1))
            "routeLineClick" -> cb.onRouteLineClick(d(0), d(1))
            "markerDragged" -> cb.onMarkerDragged(n(0), d(1), d(2))
            "markerDragStart" -> cb.onMarkerDragStart()
            "selfTap" -> cb.onSelfTap(n(0), n(1))
            "markerTapped" -> cb.onMarkerTapped(n(0), n(1), n(2))
            "mapViewChanged" -> cb.onMapViewChanged(d(0), d(1), d(2).toFloat())
            "tilesLoaded" -> cb.onTilesLoaded()
        }
    }
}

@Composable
actual fun NavMapView(
    mapType: String,
    controller: NavMapController,
    callbacks: NavMapCallbacks,
    modifier: Modifier,
) {
    val web = remember {
        val cfg = WKWebViewConfiguration()
        cfg.userContentController.addUserScript(
            WKUserScript(SHIM_JS, WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, forMainFrameOnly = true)
        )
        cfg.userContentController.addScriptMessageHandler(NavMsgHandler(callbacks), name = "nav")
        WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = cfg).apply {
            setOpaque(false)
            loadHTMLString(routeBuilderHtmlFor(mapType), baseURL = null)
        }
    }
    // Route the controller's native→JS calls into this web view.
    remember(controller, web) {
        controller.sink = { code -> web.evaluateJavaScript(code, null) }
        true
    }
    UIKitView(factory = { web }, modifier = modifier, update = {})
}
