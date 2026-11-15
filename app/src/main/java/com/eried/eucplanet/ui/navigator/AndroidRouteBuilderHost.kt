package com.eried.eucplanet.ui.navigator

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.dashboard.QR_MAX_PX
import com.eried.eucplanet.ui.dashboard.QrCodeImage
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch

/** The Android [RouteBuilderHost], over the Activity the route builder sits in. */
@Composable
fun rememberAndroidRouteBuilderHost(): RouteBuilderHost {
    val context = LocalContext.current
    return remember(context) { AndroidRouteBuilderHost(context) }
}

private class AndroidRouteBuilderHost(private val context: Context) : RouteBuilderHost {

    override val offlineMapJs: String get() = com.eried.eucplanet.nav.offline.map.OfflineMapWeb.JS

    override val qrMaxPx: Int = QR_MAX_PX

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun MapView(
        html: String,
        backgroundArgb: Int,
        bridge: RouteMapBridge,
        onPage: (RouteMapPage) -> Unit,
        onPageFinished: () -> Unit,
        modifier: Modifier,
    ) {
        val finished by rememberUpdatedState(onPageFinished)
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // The bundled leaflet.js / leaflet.css load from
                    // file:///android_asset/, without file access enabled
                    // they silently fail on API 30+ and the map is blank.
                    settings.allowFileAccess = true
                    // Matches the rider's chosen map style, so the first frame
                    // painted before tiles arrive does not clash with it.
                    setBackgroundColor(backgroundArgb)
                    addJavascriptInterface(NavJsBridge(bridge), "AndroidNav")
                    val offlineMap = com.eried.eucplanet.nav.offline.map.OfflineMapWeb.renderer()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            finished()
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ) = com.eried.eucplanet.nav.offline.map.OfflineMapWeb.intercept(offlineMap, request)
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(
                            consoleMessage: android.webkit.ConsoleMessage
                        ): Boolean {
                            android.util.Log.i(
                                "RouteBuilderJS",
                                "${consoleMessage.messageLevel()} " +
                                    "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} " +
                                    consoleMessage.message()
                            )
                            return true
                        }
                    }
                    loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                    val web = this
                    onPage(RouteMapPage { script, callback ->
                        if (callback == null) web.evaluateJavascript(script, null)
                        else web.evaluateJavascript(script) { callback(it) }
                    })
                }
            },
            onRelease = { wv ->
                // The WebView outlives the composition unless explicitly
                // torn down; its JS bridge captures the ViewModel, so
                // skipping this leaks the screen on every exit.
                wv.removeJavascriptInterface("AndroidNav")
                wv.loadUrl("about:blank")
                wv.destroy()
            }
        )
    }

    @Composable
    override fun BackHandler(onBack: () -> Unit) =
        androidx.activity.compose.BackHandler(onBack = onBack)

    @Composable
    override fun KeepScreenOn(on: Boolean) {
        // Sets only the view flag, which the window's own keep-on flag (the
        // dashboard setting) is OR-ed with, so that setting still wins.
        val hostView = LocalView.current
        DisposableEffect(on) {
            hostView.keepScreenOn = on
            onDispose { hostView.keepScreenOn = false }
        }
    }

    @Composable
    override fun RequestLocationPermission() {
        val locationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { /* the location flow re-checks on its own; nothing to do here */ }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                locationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    @Composable
    override fun rememberGpxSaver(onPicked: (file: String) -> Unit): (suggestedName: String) -> Unit {
        val saveLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/gpx+xml")
        ) { uri -> uri?.let { onPicked(it.toString()) } }
        return { name -> saveLauncher.launch(name) }
    }

    @Composable
    override fun rememberGpxOpener(onPicked: (file: String) -> Unit): () -> Unit {
        val loadLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { onPicked(it.toString()) } }
        return {
            // No "*/*", that wildcard showed every file. Android has no
            // registered MIME for ".gpx", so such files resolve to
            // octet-stream; that entry has to stay or real GPX files would
            // be hidden.
            loadLauncher.launch(
                arrayOf(
                    "application/gpx+xml", "application/xml",
                    "text/xml", "application/octet-stream"
                )
            )
        }
    }

    @Composable
    override fun rememberMarkerPhotoPicker(onPicked: (dataUrl: String) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        // When a freshly-picked image is decoded, it lands here and the crop
        // dialog opens. Cleared on cancel or after the crop result is saved.
        var pendingMarkerSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        // The decode is downsampled to a sane preview size -- a 12 megapixel
        // gallery photo is multi-megabyte of pixel data and dragging it inside
        // the crop dialog re-uploads that texture to the GPU every frame,
        // hanging the UI thread. ~1024 px on the long edge is plenty for the
        // 64 px output anyway.
        val markerPhotoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val bmp = runCatching {
                    // Full-res phone photos (50+ MP, the crash log once showed a
                    // 191 MB bitmap) OOM or exceed RecordingCanvas's 100 MB
                    // per-bitmap limit and take the activity down; the helper
                    // brings the long edge to ~1024 px before we ever draw it.
                    decodeDownsampledBitmap(context, uri)
                }.getOrNull()
                if (bmp != null) pendingMarkerSource = bmp
            }
        }
        // On Apply, the 64x64 circular crop is encoded to base64 and handed on.
        pendingMarkerSource?.let { src ->
            UserMarkerCropDialog(
                source = src,
                onCancel = { pendingMarkerSource = null },
                onApply = { cropped ->
                    onPicked(cropped.toBase64DataUrl())
                    pendingMarkerSource = null
                }
            )
        }
        return {
            markerPhotoPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
    }

    override fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    @Composable
    override fun RemoteImage(url: String, modifier: Modifier) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }

    @Composable
    override fun OfflineDataDialog(routeSquares: List<String>, onDismiss: () -> Unit) {
        com.eried.eucplanet.ui.settings.offline.AndroidOfflineDataDialog(
            onDismiss = onDismiss,
            routeSquares = routeSquares,
        )
    }

    @Composable
    override fun QrCode(content: String, sizePx: Int, modifier: Modifier, mediumEcc: Boolean) {
        if (mediumEcc) QrCodeImage(content = content, sizePx = sizePx, modifier = modifier, ecc = ErrorCorrectionLevel.M)
        else QrCodeImage(content = content, sizePx = sizePx, modifier = modifier)
    }

    @Composable
    override fun <T : Any> QrScanner(
        parse: (String) -> T?,
        onFound: (T) -> Unit,
        invalidText: String,
        modifier: Modifier,
        misfit: (String) -> String?,
    ) = QrScannerArea(parse, onFound, invalidText, modifier, misfit)

    /**
     * Android 13 and up pops its own clipboard confirmation for every write, so a
     * snackbar on top of it says "Copied" twice for one tap. Below that there is
     * no system feedback at all and the snackbar is the only sign anything
     * happened, so it is posted there and only there.
     */
    override fun copyText(text: String, onCopied: () -> Unit) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("EUC Planet", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onCopied()
    }

    override fun shareText(text: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching { context.startActivity(Intent.createChooser(send, subject)) }
    }

    override fun openShareLink(url: String, toolbarArgb: Int, navigationBarArgb: Int) =
        openShareLink(context, url, toolbarArgb, navigationBarArgb)

    override fun shareRouteQrImage(
        link: String, text: String, title: String, distance: String, footer: String, subject: String,
    ) {
        runCatching { RouteQrCard.share(context, link, text, title, distance, footer, subject) }
    }
}

/**
 * JavaScript to native bridge for the Leaflet map. `@JavascriptInterface` methods
 * are invoked on the WebView's private JS thread, so every callback is hopped
 * onto the main thread before it touches the [RouteBuilderViewModel], two quick
 * callbacks racing a non-atomic waypoint update could otherwise drop a pin.
 */
private class NavJsBridge(private val bridge: RouteMapBridge) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onOfflineMap(on: Boolean) {
        main.post { bridge.offlineMap(on) }
    }

    @JavascriptInterface
    fun onBearingChanged(deg: Double) {
        // Drives the reset-to-north button's visibility and needle angle.
        main.post { bridge.bearingChanged(deg.toFloat()) }
    }

    @JavascriptInterface
    fun onMapClick(lat: Double, lng: Double) {
        main.post { bridge.mapClick(lat, lng) }
    }

    @JavascriptInterface
    fun onRouteLineClick(lat: Double, lng: Double) {
        main.post { bridge.routeLineClick(lat, lng) }
    }

    @JavascriptInterface
    fun onMarkerDragged(index: Int, lat: Double, lng: Double) {
        main.post {
            bridge.markerDragEnd()
            bridge.markerDragged(index, lat, lng)
        }
    }

    @JavascriptInterface
    fun onMarkerDragStart() {
        main.post { bridge.markerDragStart() }
    }

    @JavascriptInterface
    fun onSelfTap(x: Int, y: Int) {
        main.post { bridge.selfTap(x, y) }
    }

    @JavascriptInterface
    fun onMarkerTapped(index: Int, x: Int, y: Int) {
        main.post { bridge.markerTapped(index, x, y) }
    }

    @JavascriptInterface
    fun onMapViewChanged(lat: Double, lng: Double, zoom: Float) {
        main.post { bridge.mapViewChanged(lat, lng, zoom) }
    }

    @JavascriptInterface
    fun onMapBounds(south: Double, west: Double, north: Double, east: Double) {
        main.post { bridge.mapBoundsChanged(south, west, north, east) }
    }

    @JavascriptInterface
    fun onTilesLoaded() {
        main.post { bridge.tilesLoaded() }
    }

    @JavascriptInterface
    fun onPoiTapped(id: String) {
        val parsed = id.toLongOrNull() ?: return
        main.post { bridge.poiTapped(parsed) }
    }
}

/**
 * The generic "open a web page" probe used both to find the phone's browser
 * and to verify a candidate really is one.
 *
 * Needs the VIEW + BROWSABLE + scheme https entry in the manifest's
 * `<queries>` block: without it, both [PackageManager.resolveActivity] and
 * [PackageManager.queryIntentActivities] go blind to every browser package
 * on API 30+, and [resolveBrowserPackage] always returns null.
 */
private fun browserProbe(): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
        .addCategory(Intent.CATEGORY_BROWSABLE)

/**
 * The package that owns http/https VIEW links on this phone, or null if none
 * can be told apart from this app itself.
 *
 * [PackageManager.resolveActivity] answers the way a launcher would ("who
 * opens this by default"), but its answer is not trusted on its own: some
 * builds report a resolver or chooser activity instead of a real browser
 * package, and in principle any app that merely claims the https scheme
 * (without being a browser) could be the default. So the candidate is
 * accepted only if it also turns up in [PackageManager.queryIntentActivities]
 * for the very same probe, meaning it genuinely registered as a handler for
 * a generic web page rather than a name that happened to come back from a
 * resolver. When there is no default, or the default fails that check, the
 * first entry of queryIntentActivities that is not this app is used instead.
 * A device where neither path survives returns null, and the caller falls
 * back to a Custom Tab.
 */
private fun resolveBrowserPackage(context: Context): String? {
    val pm = context.packageManager
    val probe = browserProbe()
    val ownPackage = context.packageName
    val candidates = runCatching {
        pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
    }.getOrDefault(emptyList())

    val default = runCatching {
        pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()
    if (default != null && default != ownPackage &&
        candidates.any { it.activityInfo?.packageName == default }
    ) {
        return default
    }

    return candidates.firstOrNull { it.activityInfo?.packageName != ownPackage }
        ?.activityInfo?.packageName
}

/**
 * Hands [uri] to the phone's real browser as an explicit ACTION_VIEW, so it
 * opens in the browser's own task rather than inside this app's. See
 * [resolveBrowserPackage] for how the browser is named.
 *
 * Never throws: returns false, without launching anything, when no browser
 * package can be told apart, or when the explicit launch itself fails (the
 * expected case is an ActivityNotFoundException, the package answers the
 * browser question but not this exact URL). Either way the caller is free to
 * fall back to a Custom Tab.
 */
private fun openInBrowser(context: Context, uri: Uri): Boolean {
    val browserPackage = resolveBrowserPackage(context) ?: return false
    val view = Intent(Intent.ACTION_VIEW, uri)
        .setPackage(browserPackage)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(view) }.isSuccess
}

/**
 * Hands the share link to the phone's real browser, so Open shows the web
 * viewer as an ordinary tab the rider leaves the app for and comes back from.
 *
 * A plain ACTION_VIEW never leaves the app: the same URL is claimed as a
 * verified App Link (see the /share intent filter in the manifest), so Android
 * routes it back into MainActivity, which parses it, sees the room the rider is
 * already in, and dismisses. Nothing appears to happen.
 *
 * So the browser is named explicitly: [openInBrowser] resolves who owns
 * "open a web page" on this phone (see [resolveBrowserPackage]) and addresses
 * that package directly with the real link. An explicit intent is not App
 * Link traffic, and it lands in the browser's own task, which is what makes
 * it a real Chrome tab: the task switcher lists Chrome, and coming back lands
 * on the map where the rider left it. A Custom Tab renders the page inside
 * this app's own task instead, which is exactly what the rider asked not to
 * happen.
 *
 * Addressing the browser with ACTION_MAIN instead (makeMainSelectorActivity)
 * does raise Chrome, but Chrome answers a MAIN launch with its own home page
 * and drops the data URI, so the rider lands on a blank tab. Verified on the
 * emulator, which is why the link travels as a VIEW.
 *
 * A device where no browser package can be told apart falls back to a Custom
 * Tab, then to a browsable view inside a chooser, so Open still does
 * something rather than silently handing the link back to this app.
 */
private fun openShareLink(context: Context, url: String, toolbarArgb: Int, navigationBarArgb: Int) {
    val uri = Uri.parse(url)
    if (openInBrowser(context, uri)) return
    val browser = runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull()
    if (browser != null) {
        val opened = runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setDefaultColorSchemeParams(
                    CustomTabColorSchemeParams.Builder()
                        .setToolbarColor(toolbarArgb)
                        .setNavigationBarColor(navigationBarArgb)
                        .build()
                )
                .build()
                .apply { intent.setPackage(browser) }
                .launchUrl(context, uri)
        }.isSuccess
        if (opened) return
    }
    runCatching {
        val view = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(
            Intent.createChooser(view, context.getString(R.string.share_open))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
