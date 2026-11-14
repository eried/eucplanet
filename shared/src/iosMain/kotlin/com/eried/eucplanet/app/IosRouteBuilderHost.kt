package com.eried.eucplanet.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.eried.eucplanet.platform.HttpClient
import com.eried.eucplanet.platform.HttpRequest
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.ui.dashboard.QrCodeCanvas
import com.eried.eucplanet.ui.navigator.RouteBuilderHost
import com.eried.eucplanet.ui.navigator.RouteMapBridge
import com.eried.eucplanet.ui.navigator.RouteMapPage
import com.eried.eucplanet.ui.recording.TripMapHtml
import com.eried.eucplanet.util.QrEncoder
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.CoreGraphics.CGRectMake
import platform.CoreLocation.CLLocationManager
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIPasteboard
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/**
 * The route builder, its share windows and the navigator view on the iPhone.
 * Android's is AndroidRouteBuilderHost; this one draws the same shared map
 * page in a WKWebView with Leaflet inlined, as the trip map does
 * ([IosTripDetailHost]), and answers the page's `AndroidNav` calls through a
 * script message handler. There are no offline areas on iOS: the page gets
 * TripMapHtml.NO_OFFLINE_MAP_JS and the offline dialog is never opened.
 */
internal object IosRouteBuilderHost : RouteBuilderHost {
    private const val TAG = "RouteBuilder"

    override val offlineMapJs: String = TripMapHtml.NO_OFFLINE_MAP_JS

    // Android's QR_MAX_PX: past it a QR carries nothing more.
    override val qrMaxPx: Int = 1024

    /**
     * The page's own `<link>` and `<script>` tags for the bundled Leaflet,
     * swapped for the files themselves: Android loads them from
     * file:///android_asset/, which WKWebView has no counterpart for.
     */
    @OptIn(ExperimentalResourceApi::class)
    private fun inlineLeaflet(html: String): String = runCatching {
        runBlocking {
            val css = Res.readBytes("files/leaflet.css").decodeToString()
            val js = Res.readBytes("files/leaflet.js").decodeToString()
            val rotate = Res.readBytes("files/leaflet-rotate.js").decodeToString()
            html.replace("<link rel=\"stylesheet\" href=\"leaflet.css\"/>", "<style>\n$css\n</style>")
                .replace("<script src=\"leaflet.js\"></script>", "<script>\n$js\n</script>")
                .replace("<script src=\"leaflet-rotate.js\"></script>", "<script>\n$rotate\n</script>")
        }
    }.getOrElse {
        Log.w(TAG, "No bundled Leaflet (${it.message})")
        html
    }

    @OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
    @Composable
    override fun MapView(
        html: String,
        backgroundArgb: Int,
        bridge: RouteMapBridge,
        onPage: (RouteMapPage) -> Unit,
        onPageFinished: () -> Unit,
        modifier: Modifier,
    ) {
        val finished = rememberUpdatedState(onPageFinished)
        // WKWebView holds its navigation delegate weakly: the composition keeps it.
        val delegate = remember { MapNavigationDelegate { finished.value() } }
        val handler = remember(bridge) { NavScriptHandler(bridge) }
        val page = remember(html) { inlineLeaflet(html) }
        UIKitView(
            factory = {
                val content = WKUserContentController()
                content.addUserScript(
                    WKUserScript(
                        source = BRIDGE_JS,
                        injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                        forMainFrameOnly = true,
                    )
                )
                content.addScriptMessageHandler(handler, name = "AndroidNav")
                content.addScriptMessageHandler(handler, name = "EucLog")
                val config = WKWebViewConfiguration()
                config.userContentController = content
                val web = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config)
                web.navigationDelegate = delegate
                // Leaflet pans, zooms and rotates the map itself; the page must not scroll.
                web.scrollView.scrollEnabled = false
                web.scrollView.bounces = false
                web.opaque = false
                // The rider's map style, so the first frame before the tiles does not clash with it.
                web.backgroundColor = argbColor(backgroundArgb)
                web.loadHTMLString(page, baseURL = null)
                onPage(RouteMapPage { script, callback ->
                    web.evaluateJavaScript(script) { result, _ -> callback?.invoke(result?.toString()) }
                })
                web
            },
            modifier = modifier,
            // A drag that starts on the map moves the map, never the screen under it.
            properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
            onRelease = { web ->
                // The content controller holds its handler, which holds the view
                // model's callbacks: let go of both, or the screen leaks.
                web.configuration.userContentController.removeScriptMessageHandlerForName("AndroidNav")
                web.configuration.userContentController.removeScriptMessageHandlerForName("EucLog")
                web.stopLoading()
                web.navigationDelegate = null
            },
        )
    }

    @Composable
    override fun BackHandler(onBack: () -> Unit) = IosBackHandler(onBack = onBack)

    /** The idle timer off while [on], on top of the Keep screen on setting (IosDisplayPrefs). */
    @Composable
    override fun KeepScreenOn(on: Boolean) {
        DisposableEffect(on) {
            if (on) IosDisplayPrefs.holdScreenOn(true)
            onDispose { if (on) IosDisplayPrefs.holdScreenOn(false) }
        }
    }

    /** CoreLocation asks once, while in use; LocationBridge reports the answer. */
    @Composable
    override fun RequestLocationPermission() {
        LaunchedEffect(Unit) {
            if (IosLocation.access.value == LocationAccess.NOT_ASKED) IosLocation.start()
        }
    }

    @Composable
    override fun rememberGpxSaver(onPicked: (file: String) -> Unit): (suggestedName: String) -> Unit {
        val callback = rememberUpdatedState(onPicked)
        return remember { { name -> callback.value(IosGpxFiles.exportPath(name)) } }
    }

    @Composable
    override fun rememberGpxOpener(onPicked: (file: String) -> Unit): () -> Unit {
        val callback = rememberUpdatedState(onPicked)
        return remember { { IosGpxFiles.open { callback.value(it) } } }
    }

    @Composable
    override fun rememberMarkerPhotoPicker(onPicked: (dataUrl: String) -> Unit): () -> Unit {
        val callback = rememberUpdatedState(onPicked)
        return remember { { IosAvatarPicker.openMarker { callback.value(it) } } }
    }

    override fun openUrl(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any>(), null)
    }

    /** A charger's photo, fetched once and cropped to fill [modifier]; nothing while it loads or when it fails. */
    @Composable
    override fun RemoteImage(url: String, modifier: Modifier) {
        val bitmap by produceState<ImageBitmap?>(null, url) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val reply = HttpClient(10_000L, 15_000L).execute(HttpRequest(url, HttpRequest.Body.None, emptyList()))
                    if (!reply.isSuccessful) null
                    else org.jetbrains.skia.Image.makeFromEncoded(reply.body).toComposeImageBitmap()
                }.getOrNull()
            }
        }
        val b = bitmap
        if (b == null) Box(modifier) else Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }

    // No offline areas on the iPhone; the route builder never offers them.
    @Composable
    override fun OfflineDataDialog(routeSquares: List<String>, onDismiss: () -> Unit) {
        LaunchedEffect(Unit) { onDismiss() }
    }

    /** The shared encoder, module for module what Android's ZXing draws. */
    @Composable
    override fun QrCode(content: String, sizePx: Int, modifier: Modifier, mediumEcc: Boolean) =
        QrCodeCanvas(content, sizePx, modifier, if (mediumEcc) QrEncoder.Ecc.M else QrEncoder.Ecc.Q)

    @Composable
    override fun <T : Any> QrScanner(
        parse: (String) -> T?,
        onFound: (T) -> Unit,
        invalidText: String,
        modifier: Modifier,
        misfit: (String) -> String?,
    ) = IosQrScanner(parse, onFound, invalidText, modifier, misfit)

    /** iOS shows no notice of its own for a copy, so the screen's snackbar says it. */
    override fun copyText(text: String, onCopied: () -> Unit) {
        UIPasteboard.generalPasteboard.string = text
        onCopied()
    }

    override fun shareText(text: String, subject: String) = IosShareSheet.share(listOf(text), subject)

    /** Safari, not this app: the iPhone has no app link for the share page, so a plain open leaves the app. */
    override fun openShareLink(url: String, toolbarArgb: Int, navigationBarArgb: Int) = openUrl(url)

    override fun shareRouteQrImage(
        link: String, text: String, title: String, distance: String, footer: String, subject: String,
    ) {
        val card = IosRouteQrCard.render(link, title, distance, footer)
        IosShareSheet.share(listOfNotNull(card, text), subject)
    }

    private fun argbColor(argb: Int): UIColor = UIColor(
        red = ((argb shr 16) and 0xFF) / 255.0,
        green = ((argb shr 8) and 0xFF) / 255.0,
        blue = (argb and 0xFF) / 255.0,
        alpha = ((argb ushr 24) and 0xFF) / 255.0,
    )

    /**
     * The `AndroidNav` object the page calls, as a stand-in posting each call
     * to the message handler as JSON, and the page's console to the log.
     */
    private val BRIDGE_JS = """
        (function() {
          function post(name, m, a) {
            try { window.webkit.messageHandlers[name].postMessage(JSON.stringify({ m: m, a: a })); } catch (e) {}
          }
          var calls = ['onOfflineMap', 'onBearingChanged', 'onMapClick', 'onRouteLineClick',
            'onMarkerDragged', 'onMarkerDragStart', 'onSelfTap', 'onMarkerTapped',
            'onMapViewChanged', 'onMapBounds', 'onTilesLoaded', 'onPoiTapped'];
          var nav = {};
          calls.forEach(function(c) { nav[c] = function() { post('AndroidNav', c, Array.prototype.slice.call(arguments)); }; });
          window.AndroidNav = nav;
          ['log', 'warn', 'error'].forEach(function(level) {
            var original = console[level];
            console[level] = function() {
              post('EucLog', level, Array.prototype.slice.call(arguments).map(String));
              if (original) original.apply(console, arguments);
            };
          });
          window.addEventListener('error', function(e) {
            post('EucLog', 'error', [String(e.message) + ' @' + e.lineno]);
          });
        })();
    """.trimIndent()
}

/**
 * The page's `AndroidNav` calls, delivered to [bridge] as Android's NavJsBridge
 * delivers them. WebKit calls this on the main thread, one message at a time,
 * so two quick taps cannot race a waypoint update.
 */
private class NavScriptHandler(private val bridge: RouteMapBridge) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val json = runCatching { JSONObject(didReceiveScriptMessage.body as? String ?: return) }.getOrNull() ?: return
        val a = json.optJSONArray("a") ?: return
        val m = json.optString("m")
        if (didReceiveScriptMessage.name == "EucLog") {
            Log.i("RouteBuilderJS", "$m ${(0 until a.length()).joinToString(" ") { a.optString(it) }}")
            return
        }
        fun d(i: Int) = a.optDouble(i, 0.0)
        fun n(i: Int) = d(i).toInt()
        runCatching {
            when (m) {
                "onOfflineMap" -> bridge.offlineMap(a.optBoolean(0))
                "onBearingChanged" -> bridge.bearingChanged(d(0).toFloat())
                "onMapClick" -> bridge.mapClick(d(0), d(1))
                "onRouteLineClick" -> bridge.routeLineClick(d(0), d(1))
                "onMarkerDragged" -> {
                    bridge.markerDragEnd()
                    bridge.markerDragged(n(0), d(1), d(2))
                }
                "onMarkerDragStart" -> bridge.markerDragStart()
                "onSelfTap" -> bridge.selfTap(n(0), n(1))
                "onMarkerTapped" -> bridge.markerTapped(n(0), n(1), n(2))
                "onMapViewChanged" -> bridge.mapViewChanged(d(0), d(1), d(2).toFloat())
                "onMapBounds" -> bridge.mapBoundsChanged(d(0), d(1), d(2), d(3))
                "onTilesLoaded" -> bridge.tilesLoaded()
                "onPoiTapped" -> a.optString(0).toLongOrNull()?.let(bridge.poiTapped)
            }
        }.onFailure { Log.w("RouteBuilderJS", "$m failed: $it") }
    }
}
