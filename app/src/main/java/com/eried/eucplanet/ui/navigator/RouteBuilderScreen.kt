package com.eried.eucplanet.ui.navigator

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.NavMode
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.nav.NavFormat
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableColumn

/**
 * The Route Builder: a full-bleed Leaflet map (WebView) with a top search bar,
 * a hamburger menu (save / load / clear / start / exit) and a bottom panel for
 * the travel mode and the draggable list of stops. Tapping the map drops a pin.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteBuilderScreen(
    onExit: () -> Unit,
    onOpenNavSettings: () -> Unit,
    viewModel: RouteBuilderViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val waypoints by viewModel.waypoints.collectAsState()
    val route by viewModel.route.collectAsState()
    val travelMode by viewModel.travelMode.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val routing by viewModel.routing.collectAsState()
    val mapRender by viewModel.mapRender.collectAsState()
    val userLocation by viewModel.currentLocation.collectAsState()
    val imperial by viewModel.imperialUnits.collectAsState()
    val mapType by viewModel.mapType.collectAsState()
    val homePlace by viewModel.home.collectAsState()
    val workPlace by viewModel.work.collectAsState()
    // While guidance runs the map is read-only: no search, no stop editing.
    val navRunning by viewModel.navRunning.collectAsState()
    // Persisted custom marker photo (base64 data URL or null).
    val markerPhoto by viewModel.userMarkerPhoto.collectAsState()
    // When a freshly-picked image is decoded, it lands here and the crop
    // dialog opens. Cleared on cancel or after the crop result is saved.
    var pendingMarkerSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val routeClean by viewModel.routeClean.collectAsState()
    var selfMenuOpen by remember { mutableStateOf(false) }
    // Screen-space offset where the self menu should anchor. Pushed in from
    // the JS bridge when the rider taps their own marker so the menu opens
    // *at* the marker instead of at the centre of the map.
    var selfMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
    var clearConfirmOpen by remember { mutableStateOf(false) }
    // The picked GPX waiting on a "replace the current route?" confirmation.
    // Held only after the rider has actually chosen a file (asking BEFORE
    // the picker would be confusing — they may cancel out, and the prompt
    // would be for nothing). Null when no pending choice.
    var pendingGpxUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var markerMenuIndex by remember { mutableStateOf(-1) }
    // Screen position of the tapped marker, so its menu opens at the pin.
    var markerMenuOffset by remember { mutableStateOf(DpOffset.Zero) }

    var searchText by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var menuOpen by remember { mutableStateOf(false) }
    var panelExpanded by rememberSaveable { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // The bottom stops panel's measured height in pixels, used to offset
    // recenter / tap-stop centring so the target lands in the centre of
    // the VISIBLE map area rather than behind the panel. Updated via
    // onSizeChanged so it always matches reality (the previous 300 / 80 dp
    // estimate was both too coarse AND ignored the rider's content sizes).
    var panelHeightPx by remember { mutableStateOf(0) }
    // Top app bar measured height in pixels. We can't rely on the Scaffold
    // PaddingValues here because with enableEdgeToEdge() the Scaffold's
    // contentWindowInsets behaviour differs across themes and the value
    // can come back smaller than the actually-rendered bar height. We
    // measure the TopAppBar ourselves so the recenter offset always
    // matches what the rider sees on screen.
    var topBarHeightPx by remember { mutableStateOf(0) }
    // Cover the map until the first GPS fix lands, so the rider never sees it
    // snap from world view to their location. Skip drops the gate immediately.
    var locationGateDone by remember { mutableStateOf(false) }
    LaunchedEffect(userLocation) {
        if (userLocation != null) locationGateDone = true
    }
    var pageReady by remember { mutableStateOf(false) }
    var didInitialCenter by remember { mutableStateOf(false) }

    BackHandler { onExit() }

    // Surface ViewModel messages as snackbars.
    LaunchedEffect(Unit) {
        viewModel.messages.collect { resId ->
            snackbarHost.showSnackbar(context.getString(resId))
        }
    }

    // The builder is useless without a fix — ask for location up front so the
    // "my location" button and live guidance work. Harmless if already granted.
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

    // Push map redraws into the WebView once the page is ready.
    LaunchedEffect(pageReady, mapRender) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        // If the rider has a saved view (they were already looking somewhere
        // and we just came back from a sub-screen like Navigation Parameters),
        // pretend nothing needs to fit — let the saved view re-apply via the
        // LaunchedEffect below. Otherwise honour the requested fit.
        val fit = mapRender.fit && viewModel.savedView.value == null
        wv.evaluateJavascript(
            "nativeRender(${jsString(viewModel.waypointsJson())}," +
                "${jsString(viewModel.geometryJson())},$fit);",
            null
        )
    }

    // Restore the saved map view (centre + zoom) once, the moment the page
    // becomes ready — this is what makes "go to Settings and swipe back"
    // not snap the map to the world view. Keyed ONLY on pageReady so a
    // user pan that updates savedView doesn't re-fire this effect (which
    // would call nativeRecenter on every drag — an infinite move-end
    // recursion).
    //
    // Stored in the VM only, no settings persistence: a fresh app launch
    // starts from the rider's current location like before; only
    // within-session sub-navigation restores.
    LaunchedEffect(pageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val v = viewModel.savedView.value ?: return@LaunchedEffect
        wv.evaluateJavascript("nativeRecenter(${v.lat}, ${v.lng}, ${v.zoom});", null)
    }

    // Keep the saved Home / Work places shown on the map.
    LaunchedEffect(pageReady, homePlace, workPlace) {
        val wv = webView ?: return@LaunchedEffect
        if (pageReady) {
            wv.evaluateJavascript(
                "nativeSetPlaces(${jsString(viewModel.placesJson())});", null
            )
        }
    }

    // Push the custom rider-marker photo (or clear it) into the map JS so
    // the teardrop/circle uses it as soon as the value changes. Keyed on
    // both photo + pageReady so the photo lands as soon as Leaflet is up.
    LaunchedEffect(pageReady, markerPhoto) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val arg = markerPhoto?.let { "'" + it + "'" } ?: "''"
        wv.evaluateJavascript("nativeSetUserPhoto($arg);", null)
    }

    // The crop dialog only mounts once a freshly-picked image has been
    // decoded into a Bitmap. On Apply, the resulting 64×64 circular crop is
    // encoded to base64 and persisted; the saved value flows back through
    // [markerPhoto] above to update the map.
    pendingMarkerSource?.let { src ->
        UserMarkerCropDialog(
            source = src,
            onCancel = { pendingMarkerSource = null },
            onApply = { cropped ->
                viewModel.setUserMarkerPhoto(cropped.toBase64DataUrl())
                pendingMarkerSource = null
            }
        )
    }

    // Keep the "you are here" pin live. Switches between two visuals based
    // on speed: a teardrop pointing in the direction of travel when the
    // rider is moving, a plain round puck when stationary. Hysteresis on
    // the threshold means a single GPS-noise spike doesn't flick the marker
    // shape: we need a SUSTAINED speed over ~2.5 m/s to call the rider
    // "moving", then they need to drop below ~1.0 m/s before going back to
    // "still". Without this, while standing the marker would visibly grow
    // / shrink as the bearing flickered with GPS jitter.
    val movingOnMps = 2.5f
    val movingOffMps = 1.0f
    var lastSentMoving by remember { mutableStateOf(false) }
    LaunchedEffect(pageReady, userLocation) {
        val wv = webView ?: return@LaunchedEffect
        val loc = userLocation ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        wv.evaluateJavascript("nativeSetUser(${loc.latitude},${loc.longitude});", null)
        val sp = if (loc.hasSpeed()) loc.speed else 0f
        val newMoving = if (lastSentMoving) sp >= movingOffMps else sp >= movingOnMps
        if (newMoving && loc.hasBearing()) {
            wv.evaluateJavascript("nativeSetUserHeading(${loc.bearing});", null)
        } else if (!newMoving && lastSentMoving) {
            wv.evaluateJavascript("nativeSetUserStill();", null)
        }
        lastSentMoving = newMoving
    }

    // First load: frame the map on the rider instead of the whole world.
    // Skipped when a saved route is present — that route frames itself.
    LaunchedEffect(pageReady, userLocation, route) {
        val loc = userLocation
        if (pageReady && !didInitialCenter && route == null && loc != null) {
            webView?.evaluateJavascript(
                "nativeRecenter(${loc.latitude},${loc.longitude},15);", null
            )
            didInitialCenter = true
        }
    }

    // Push the rider's theme accent into the map so the route line follows it.
    val accentHex = "#%06X".format(0xFFFFFF and MaterialTheme.colorScheme.primary.toArgb())
    LaunchedEffect(pageReady, accentHex) {
        if (pageReady) {
            webView?.evaluateJavascript("nativeSetAccent('$accentHex');", null)
        }
    }

    // Apply the saved base map style (dark / light / satellite).
    LaunchedEffect(pageReady, mapType) {
        if (pageReady) {
            webView?.evaluateJavascript("nativeSetMapType('$mapType');", null)
        }
    }

    // Mirror the nav-running state into the map. When locked, the JS swaps
    // numbered pins for small dots and the arrival-radius ring becomes the
    // dominant visual (the rider rides INTO the area, not to a pin). Drag
    // and map-click are inert. We re-call nativeRender with the current
    // waypoints + geometry right after so the new pin style applies
    // immediately, without waiting for the next stop change.
    LaunchedEffect(pageReady, navRunning) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        wv.evaluateJavascript(
            "nativeSetNavLocked(${if (navRunning) "true" else "false"});",
            null
        )
        wv.evaluateJavascript(
            "nativeRender(${jsString(viewModel.waypointsJson())}," +
                "${jsString(viewModel.geometryJson())},false);",
            null
        )
    }

    // Push the current travel mode (DRIVING / CYCLING / WALKING / STRAIGHT)
    // into the map so the route line uses the matching colour, and STRAIGHT
    // mode gets the chevron arrows along its (otherwise featureless) line.
    LaunchedEffect(pageReady, travelMode) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        wv.evaluateJavascript("nativeSetTravelMode('${travelMode.name}');", null)
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> uri?.let { viewModel.saveGpx(it) } }

    val loadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // If the current route is an unsaved multi-stop one, confirm before
        // overwriting it — we already have the file, just hold the URI and
        // pop the dialog now. Otherwise load straight away.
        val needConfirm = viewModel.waypoints.value.size > 1 &&
            !viewModel.routeClean.value
        if (needConfirm) {
            pendingGpxUri = uri
        } else {
            viewModel.loadGpx(uri)
        }
    }

    // Photo picker for the custom rider-marker. The picked image is decoded
    // immediately so the crop dialog can show it; the dialog then renders a
    // 64×64 circular crop and the result is base64-encoded into settings.
    val markerPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            if (bmp != null) pendingMarkerSource = bmp
        }
    }

    // Starting navigation — Direct mode has no street-by-street routing, so it
    // launches Treasure Hunt; the routed modes launch turn-by-turn guidance.
    // Shared by the menu item and the bottom button.
    // Set the moment Start is tapped so the button never flips to "Stop
    // navigation" while the builder is still animating away.
    var navStarting by remember { mutableStateOf(false) }
    val startNav: () -> Unit = {
        navStarting = true
        val mode = if (travelMode == TravelMode.STRAIGHT)
            NavMode.TREASURE_HUNT else NavMode.TURN_BY_TURN
        viewModel.startNavigation(mode) { onExit() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                modifier = Modifier.onSizeChanged { sz -> topBarHeightPx = sz.height },
                colors = TopAppBarDefaults.topAppBarColors(
                    // 80 % alpha so the map shows through the top bar (the
                    // map area extends under it via the contentWindowInsets
                    // override below).
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.80f)
                ),
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_menu_exit)
                        )
                    }
                },
                title = {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = {
                            searchText = it
                            viewModel.search(it)
                        },
                        placeholder = { Text(stringResource(R.string.nav_search_hint)) },
                        enabled = !navRunning,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchText = ""
                                    viewModel.clearSearch()
                                }) { Icon(Icons.Default.Close, null) }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp)
                            .onFocusChanged { searchFocused = it.isFocused }
                    )
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.nav_menu))
                        }
                        BuilderMenu(
                            expanded = menuOpen,
                            canStart = userLocation != null && waypoints.isNotEmpty(),
                            onDismiss = { menuOpen = false },
                            onSave = { saveLauncher.launch("route.gpx") },
                            onLoad = {
                                // No "*/*" — that wildcard showed every file.
                                // Android has no registered MIME for ".gpx", so
                                // such files resolve to octet-stream; that entry
                                // has to stay or real GPX files would be hidden.
                                // If the rider's current route is unsaved and
                                // multi-stop, we'll prompt AFTER they pick a
                                // file (so cancelling the picker is silent).
                                loadLauncher.launch(
                                    arrayOf(
                                        "application/gpx+xml", "application/xml",
                                        "text/xml", "application/octet-stream"
                                    )
                                )
                            },
                            onClear = {
                                if (waypoints.size > 1 && !routeClean) {
                                    clearConfirmOpen = true
                                } else viewModel.clear()
                            },
                            onStart = startNav,
                            hasStops = waypoints.isNotEmpty(),
                            hasHome = homePlace != null,
                            hasWork = workPlace != null,
                            onClearHome = viewModel::clearHome,
                            onClearWork = viewModel::clearWork,
                            onNavSettings = onOpenNavSettings,
                            onCustomizeMarker = {
                                markerPhotoPicker.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            onResetMarker = { viewModel.setUserMarkerPhoto(null) },
                            hasCustomMarker = markerPhoto != null,
                            navRunning = navRunning
                        )
                    }
                }
            )
        }
    ) { padding ->
        // Net pixel offset applied when recentering: the rider's latlng
        // should land at the centre of the VISIBLE map area, which is
        // (panel_height - top_bar_height) / 2 px ABOVE the WebView's
        // geometric centre when panel > bar, BELOW when bar > panel.
        // Passing this delta (positive = shift map south so rider appears
        // north of centre) instead of just the panel height is what makes
        // the rider end up in the visible middle rather than against the
        // top bar.
        //
        // Until the panel + top bar have been laid out once we fall back to
        // sensible estimates so a recenter pressed on the very first frame
        // still lands the rider plausibly rather than overshooting one way
        // or the other. After the first measurement, the values are replaced
        // by the real measurements automatically. Recomputed on every
        // recomposition because both sources are State, so the click handler
        // always reads the latest value.
        val effectivePanelPx =
            if (panelHeightPx > 0) panelHeightPx
            else with(density) { 300.dp.toPx().toInt() }
        val effectiveTopBarPx =
            if (topBarHeightPx > 0) topBarHeightPx
            else with(density) { 88.dp.toPx().toInt() }
        // Map's coordinate space is CSS pixels (Leaflet projects at the
        // WebView's CSS size, not its device-pixel size). We measured the
        // panel + bar in DEVICE pixels via onSizeChanged, so divide by the
        // density factor to convert before handing the offset to JS --
        // otherwise the rider ends up DPR-x shifted (typical phones have
        // DPR=2.6..3.5, hence the "rider parks against the top bar"
        // symptom: a 66 dp shift becomes 198 device-px on screen).
        val recenterOffsetPx =
            (effectivePanelPx - effectiveTopBarPx) / 2f / density.density
        // The map fills the WHOLE screen, including the strip behind the
        // top bar -- the bar is now 80 % translucent so the map shows
        // through it. We still apply the BOTTOM padding so the bottom
        // panel and the FABs don't clash with the system nav bar.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            // --- Map ---
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // The bundled leaflet.js / leaflet.css load from
                        // file:///android_asset/ — without file access enabled
                        // they silently fail on API 30+ and the map is blank.
                        settings.allowFileAccess = true
                        setBackgroundColor(android.graphics.Color.parseColor("#0b0f19"))
                        addJavascriptInterface(
                            NavJsBridge(
                                mapClick = { lat, lng ->
                                    if (!viewModel.navRunning.value) {
                                        viewModel.addWaypoint(lat, lng)
                                    }
                                },
                                markerDragged = { i, lat, lng ->
                                    if (!viewModel.navRunning.value) {
                                        viewModel.moveWaypoint(i, lat, lng)
                                    }
                                },
                                selfTap = { x, y ->
                                    selfMenuOffset = DpOffset(x.dp, y.dp)
                                    selfMenuOpen = true
                                },
                                mapViewChanged = { lat, lng, zoom ->
                                    viewModel.setSavedView(lat, lng, zoom)
                                },
                                markerTapped = { idx, x, y ->
                                    // While navigation is running the only sensible
                                    // action on a stop is to recenter the map onto
                                    // it — Save as Home / Work would mid-trip change
                                    // the saved place which the rider isn't asking
                                    // for. Skip the menu entirely and pan straight
                                    // to the pin.
                                    if (viewModel.navRunning.value) {
                                        waypoints.getOrNull(idx)?.let { wp ->
                                            webView?.evaluateJavascript(
                                                "nativeCenterOn(${wp.lat},${wp.lng},$recenterOffsetPx);", null
                                            )
                                        }
                                    } else {
                                        markerMenuIndex = idx
                                        markerMenuOffset = DpOffset(x.dp, y.dp)
                                    }
                                }
                            ),
                            "AndroidNav"
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageReady = true
                            }
                        }
                        loadDataWithBaseURL(
                            "file:///android_asset/", ROUTE_BUILDER_HTML,
                            "text/html", "UTF-8", null
                        )
                        webView = this
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

            // --- Search results overlay ---
            if (!navRunning && (searchFocused || searching || searchResults.isNotEmpty())) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        // Outer Box no longer applies the top inset (the map
                        // fills the area behind the translucent app bar), so
                        // restore it HERE -- otherwise the dropdown lands at
                        // y = 0 / behind the search field.
                        .padding(top = padding.calculateTopPadding())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        // Saved Home / Work presets — tap to drop as the next
                        // stop. Whichever was added last is hidden (just used,
                        // re-suggesting it is noise); adding any plain stop
                        // brings it back.
                        val lastPresetKind by viewModel.lastAddedPresetKind.collectAsState()
                        val presets = listOfNotNull(
                            homePlace?.takeIf { lastPresetKind != "HOME" }?.let { "Home" to it },
                            workPlace?.takeIf { lastPresetKind != "WORK" }?.let { "Work" to it }
                        )
                        presets.forEach { (label, place) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        searchText = ""
                                        focusManager.clearFocus()
                                        viewModel.addPreset(
                                            place, if (label == "Home") "HOME" else "WORK"
                                        )
                                        // Pan to the freshly-added preset so the
                                        // rider can see WHERE it dropped without
                                        // hunting -- the map is full-screen
                                        // behind the search results, so the new
                                        // pin can easily be off-screen.
                                        webView?.evaluateJavascript(
                                            "nativeCenterOn(${place.lat},${place.lng},$recenterOffsetPx);",
                                            null
                                        )
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (label == "Home") Icons.Default.Home
                                    else Icons.Default.Work,
                                    null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(label, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    place.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        }
                        if (searching) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.nav_searching))
                            }
                        }
                        searchResults.forEachIndexed { i, result ->
                            if (i > 0) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                            TextButton(
                                onClick = {
                                    searchText = ""
                                    focusManager.clearFocus()
                                    viewModel.pickSearchResult(result)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.Place, null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    result.name,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            // --- Bottom controls: the locate-me FAB sits just above the panel ---
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                FloatingActionButton(
                    onClick = { viewModel.cycleMapType() },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 16.dp, bottom = 12.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Icon(Icons.Default.Layers, stringResource(R.string.nav_map_style))
                }
                FloatingActionButton(
                    onClick = {
                        val l = viewModel.recenterOnUser()
                        if (l != null) {
                            // Tell the JS how much of the map's bottom is
                            // occluded by the stops panel so the rider's pin
                            // ends up in the middle of the VISIBLE map area,
                            // not buried under the dock. Expanded panel ≈
                            // 300 dp; collapsed (just the header row) ≈ 80 dp.
                            webView?.evaluateJavascript(
                                "nativeRecenter(${l.latitude},${l.longitude},16,$recenterOffsetPx);",
                                null
                            )
                        } else {
                            scope.launch {
                                snackbarHost.showSnackbar(
                                    context.getString(R.string.nav_no_location)
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 16.dp, bottom = 12.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Icon(Icons.Default.MyLocation, stringResource(R.string.nav_my_location))
                }

                BottomPanel(
                    expanded = panelExpanded,
                    onToggle = { panelExpanded = !panelExpanded },
                    travelMode = travelMode,
                    onModeChange = viewModel::setTravelMode,
                    waypoints = waypoints,
                    home = homePlace,
                    work = workPlace,
                    routeDistanceM = route?.totalDistanceM,
                    routing = routing,
                    imperial = imperial,
                    onRemove = viewModel::removeWaypoint,
                    onReorder = viewModel::reorderWaypoints,
                    onCenterPin = { idx ->
                        waypoints.getOrNull(idx)?.let { wp ->
                            webView?.evaluateJavascript(
                                "nativeCenterOn(${wp.lat},${wp.lng},$recenterOffsetPx);", null
                            )
                        }
                    },
                    onSaveHome = viewModel::saveWaypointAsHome,
                    onSaveWork = viewModel::saveWaypointAsWork,
                    onStartNavigation = startNav,
                    onStopNavigation = viewModel::stopNavigation,
                    canStartNavigation = userLocation != null && waypoints.isNotEmpty(),
                    navRunning = navRunning && !navStarting,
                    modifier = Modifier.onSizeChanged { sz -> panelHeightPx = sz.height }
                )
            }

            // Locating gate — hides the world-view-to-location jump.
            if (!locationGateDone) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.nav_locating))
                            Spacer(Modifier.height(18.dp))
                            TextButton(onClick = { locationGateDone = true }) {
                                Text(stringResource(R.string.nav_skip_locating))
                            }
                        }
                    }
                }
            }

            // Rider-position menu — anchored at the rider's marker via
            // selfMenuOffset (the JS bridge pushes the marker's screen-space
            // position on tap). TopStart alignment lets the offset be the
            // absolute pixel position the menu opens at.
            Box(modifier = Modifier.align(Alignment.TopStart)) {
                DropdownMenu(
                    expanded = selfMenuOpen,
                    onDismissRequest = { selfMenuOpen = false },
                    offset = selfMenuOffset
                ) {
                    if (homePlace == null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nav_save_home)) },
                            onClick = { selfMenuOpen = false; viewModel.saveSelfAsHome() }
                        )
                    }
                    if (workPlace == null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nav_save_work)) },
                            onClick = { selfMenuOpen = false; viewModel.saveSelfAsWork() }
                        )
                    }
                    // "Add stop here" is hidden while navigation is running:
                    // dropping a new stop mid-trip would re-solve the route
                    // around it, which is destructive (same reason map taps
                    // and pin drags are gated in the JS layer).
                    if (waypoints.isNotEmpty() && !navRunning) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nav_self_add_stop)) },
                            onClick = {
                                selfMenuOpen = false
                                userLocation?.let {
                                    viewModel.addWaypoint(it.latitude, it.longitude)
                                }
                            }
                        )
                    }
                    // Marker-customization cluster — separated from the
                    // place-saving / add-stop items above because the actions
                    // affect a different thing (the rider's avatar, not the
                    // route). Short labels because the context (your-marker
                    // menu) already implies "your marker". The divider only
                    // renders when something ELSE is also in the menu — a
                    // lone divider on top of Customize looks pointless.
                    val hasAddStop = waypoints.isNotEmpty() && !navRunning
                    val hasOtherItems = homePlace == null || workPlace == null || hasAddStop
                    if (hasOtherItems) HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.nav_customize_marker_short)) },
                        onClick = {
                            selfMenuOpen = false
                            markerPhotoPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    )
                    if (markerPhoto != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nav_remove_marker_photo_short)) },
                            onClick = {
                                selfMenuOpen = false
                                viewModel.setUserMarkerPhoto(null)
                            }
                        )
                    }
                }
            }

            // Stop-marker menu — save that stop as a Home / Work preset.
            // Anchored at the tapped pin via markerMenuOffset. Each "Save as"
            // option only shows when that slot is empty; once Home (or Work)
            // is saved the option vanishes from this menu and reappears only
            // after the rider forgets it via the main menu.
            Box(modifier = Modifier.align(Alignment.TopStart)) {
                DropdownMenu(
                    expanded = markerMenuIndex >= 0,
                    onDismissRequest = { markerMenuIndex = -1 },
                    offset = markerMenuOffset
                ) {
                    if (homePlace == null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nav_save_home)) },
                            onClick = {
                                val i = markerMenuIndex; markerMenuIndex = -1
                                if (i >= 0) viewModel.saveWaypointAsHome(i)
                            }
                        )
                    }
                    if (workPlace == null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nav_save_work)) },
                            onClick = {
                                val i = markerMenuIndex; markerMenuIndex = -1
                                if (i >= 0) viewModel.saveWaypointAsWork(i)
                            }
                        )
                    }
                }
            }

            // Confirm before discarding an edited multi-stop route.
            if (clearConfirmOpen) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { clearConfirmOpen = false },
                    title = { Text(stringResource(R.string.nav_menu_clear)) },
                    text = { Text(stringResource(R.string.nav_clear_confirm)) },
                    confirmButton = {
                        TextButton(onClick = {
                            clearConfirmOpen = false; viewModel.clear()
                        }) { Text(stringResource(R.string.nav_menu_clear)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { clearConfirmOpen = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }

            // Confirm before replacing an unsaved multi-stop route with the
            // GPX the rider just picked. We already have the URI in hand —
            // confirming "Replace?" only makes sense AFTER a file is chosen,
            // because cancelling the picker should be a no-op.
            val pendingUri = pendingGpxUri
            if (pendingUri != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { pendingGpxUri = null },
                    title = { Text(stringResource(R.string.nav_menu_load)) },
                    text = { Text(stringResource(R.string.nav_load_confirm)) },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingGpxUri = null
                            viewModel.loadGpx(pendingUri)
                        }) { Text(stringResource(R.string.nav_menu_load_short)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingGpxUri = null }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun BuilderMenu(
    expanded: Boolean,
    canStart: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onClear: () -> Unit,
    onStart: () -> Unit,
    hasStops: Boolean,
    hasHome: Boolean,
    hasWork: Boolean,
    onClearHome: () -> Unit,
    onClearWork: () -> Unit,
    onNavSettings: () -> Unit,
    onCustomizeMarker: () -> Unit,
    onResetMarker: () -> Unit,
    hasCustomMarker: Boolean,
    navRunning: Boolean
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // "Start navigation" is omitted here — it is already a primary button.
        // Save is allowed even while nav is running so the rider can capture
        // the remaining stops as a GPX checkpoint (the trim from
        // syncBuilderRoute means the saved file only contains stops still
        // ahead). Load / Clear stay hidden during nav — both would replace
        // the running route, which is destructive.
        if (hasStops) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_menu_save)) },
                onClick = { onDismiss(); onSave() }
            )
        }
        if (!navRunning) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_menu_load)) },
                onClick = { onDismiss(); onLoad() }
            )
        }
        if (hasStops && !navRunning) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_menu_clear)) },
                onClick = { onDismiss(); onClear() }
            )
        }
        // Marker-customization group, bounded by separators above and below
        // so the rider's-avatar actions read as a distinct cluster from the
        // route / settings items.
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.nav_customize_marker)) },
            onClick = { onDismiss(); onCustomizeMarker() }
        )
        if (hasCustomMarker) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_remove_marker_photo)) },
                onClick = { onDismiss(); onResetMarker() }
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.nav_setting_params)) },
            onClick = { onDismiss(); onNavSettings() }
        )
        // Forget Home / Work sit last — they are rare, destructive actions.
        if (hasHome || hasWork) {
            HorizontalDivider()
        }
        if (hasHome) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_clear_home)) },
                onClick = { onDismiss(); onClearHome() }
            )
        }
        if (hasWork) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_clear_work)) },
                onClick = { onDismiss(); onClearWork() }
            )
        }
    }
}

/**
 * The list label for a pin: its positional role — Nth stop / Destination (the
 * rider is the implicit start) — with the solved address appended in
 * parentheses once routing has filled it in.
 */
@Composable
private fun waypointLabel(
    index: Int,
    total: Int,
    name: String
): String {
    val role = if (index == total - 1)
        stringResource(R.string.nav_role_destination)
    else stringArrayResource(R.array.nav_stop_ordinals)
        .getOrElse(index) { stringResource(R.string.nav_role_destination) }
    return if (name.isBlank()) role else "$role ($name)"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BottomPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    travelMode: TravelMode,
    onModeChange: (TravelMode) -> Unit,
    waypoints: List<com.eried.eucplanet.data.model.Waypoint>,
    home: com.eried.eucplanet.data.model.Waypoint?,
    work: com.eried.eucplanet.data.model.Waypoint?,
    routeDistanceM: Double?,
    routing: Boolean,
    imperial: Boolean,
    onRemove: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onCenterPin: (Int) -> Unit,
    onSaveHome: (Int) -> Unit,
    onSaveWork: (Int) -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    canStartNavigation: Boolean,
    navRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        // Square corners (no rounded top) so the panel reads as a clean
        // dock at the bottom of the map. 80 % alpha lets the map (and the
        // route line that often runs right up against the bottom edge)
        // peek through, so the rider sees more context.
        shape = RoundedCornerShape(0.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
        tonalElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            // Header: title + count/distance + COMPACT start/stop + collapse chevron.
            // The compact start/stop sits inline so a rider with the panel
            // collapsed (i.e. the big Start button is hidden) can still kick
            // off / cancel navigation without expanding -- the action they
            // actually need most often is right there.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.nav_waypoints),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "(${waypoints.size})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                if (routing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (routeDistanceM != null && routeDistanceM > 0) {
                    Text(
                        NavFormat.distance(context, routeDistanceM, imperial),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.weight(1f))
                // Compact start / stop. Hidden when expanded (the big button
                // at the bottom of the expanded panel does the same job) and
                // when there is nothing to start (no waypoints, no fix yet).
                // Uses the SAME filled-Button style as the big one in the
                // expanded panel so both Start / Stop controls look like
                // the same action -- a translucent / black-looking
                // TextButton next to a coloured filled Button was reading
                // as two different things.
                if (!expanded && (navRunning || canStartNavigation)) {
                    val label = stringResource(
                        if (navRunning) R.string.nav_stop_short
                        else R.string.nav_start_short
                    )
                    Button(
                        onClick = {
                            if (navRunning) onStopNavigation() else onStartNavigation()
                        },
                        contentPadding = androidx.compose.foundation.layout
                            .PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) { Text(label) }
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown
                        else Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(
                            if (expanded) R.string.nav_minimize else R.string.nav_expand
                        )
                    )
                }
            }

            // Expand / collapse is animated -- a hard show/hide on the
            // travel-mode chips + waypoint list felt jarring, especially with
            // the new 80 % translucent panel where you can SEE the map content
            // jump as the rectangle resizes. AnimatedVisibility with the
            // default expand/shrink + fade gives the right "drawer slides
            // open" feel.
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.expandVertically(
                    expandFrom = Alignment.Top
                ) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically(
                    shrinkTowards = Alignment.Top
                ) + androidx.compose.animation.fadeOut()
            ) {
              Column {
                Spacer(Modifier.height(8.dp))
                // Travel mode selector.
                val modes = listOf(
                    Triple(
                        TravelMode.STRAIGHT,
                        Icons.Default.Timeline,
                        R.string.nav_mode_straight
                    ),
                    Triple(
                        TravelMode.CYCLING,
                        Icons.Default.DirectionsBike,
                        R.string.nav_mode_cycling
                    ),
                    Triple(
                        TravelMode.WALKING,
                        Icons.Default.DirectionsWalk,
                        R.string.nav_mode_walking
                    ),
                    Triple(
                        TravelMode.DRIVING,
                        Icons.Default.DirectionsCar,
                        R.string.nav_mode_driving
                    )
                )
                // Travel mode (icons) + a Start button, combined into one row.
                // The mode row dims to 40% alpha while nav is running so the
                // "selected mode pill" doesn't read as enabled. Functional
                // disable is also on each SegmentedButton.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .weight(1f)
                            .then(if (navRunning) Modifier.alpha(0.4f) else Modifier)
                    ) {
                        modes.forEachIndexed { index, (mode, icon, labelRes) ->
                            // Icon tint matches the route line colour for that
                            // mode so the chip visually previews what the line
                            // on the map will look like. Cool→warm activity
                            // gradient — see routeColorFor() in MapHtml.kt for
                            // the full reasoning.
                            //   Walk     sky blue   #03A9F4
                            //   Bike     green      #4CAF50
                            //   Drive    orange     #FB8C00 (not red)
                            //   Straight magenta    #E91E63
                            val modeColor = when (mode) {
                                TravelMode.WALKING  -> Color(0xFF03A9F4)
                                TravelMode.CYCLING  -> Color(0xFFFB8C00)
                                TravelMode.DRIVING  -> Color(0xFFE53935)
                                TravelMode.STRAIGHT -> Color(0xFF43A047)
                            }
                            SegmentedButton(
                                selected = travelMode == mode,
                                onClick = { onModeChange(mode) },
                                enabled = !navRunning,
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                icon = {}
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = stringResource(labelRes),
                                    tint = modeColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = if (navRunning) onStopNavigation else onStartNavigation,
                        enabled = navRunning || canStartNavigation
                    ) {
                        Text(
                            stringResource(
                                if (navRunning) R.string.nav_stop_short
                                else R.string.nav_start_short
                            )
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Highwater "minimum height" so the list does NOT shrink as
                // the rider deletes stops -- a shrinking list causes the rows
                // below (and the Start button) to creep up, and a second /
                // third quick Delete tap easily lands on the Start button by
                // accident. We measure the column at its TALLEST so far
                // (across edits AND across waypoints.isEmpty() flips) and
                // pin a min-height to that value. The lock resets when the
                // rider collapses the panel (signal that they're done
                // editing). Measured in PX (px-int -> dp via density) so the
                // size matches reality regardless of row content / wrapping,
                // not the previous fixed 48 dp-per-row estimate.
                val density = androidx.compose.ui.platform.LocalDensity.current
                var stableMinH by remember { mutableStateOf(0) }
                if (!expanded && stableMinH != 0) {
                    LaunchedEffect(expanded) { stableMinH = 0 }
                }
                val minHDp = with(density) { stableMinH.toDp() }
                if (waypoints.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minHDp, max = 234.dp)
                    ) {
                        Text(
                            stringResource(R.string.nav_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            // ~4.5 stop rows tall — the half row hints it scrolls.
                            .heightIn(min = minHDp, max = 234.dp)
                            .verticalScroll(rememberScrollState())
                            .onSizeChanged { sz ->
                                if (sz.height > stableMinH) stableMinH = sz.height
                            }
                    ) {
                        ReorderableColumn(
                            list = waypoints,
                            onSettle = if (navRunning) { _, _ -> } else onReorder,
                            onMove = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            modifier = Modifier.fillMaxWidth()
                        ) { index, waypoint, _ ->
                            key("${waypoint.lat},${waypoint.lng},${waypoint.name},$index") {
                                Box {
                                    var rowMenu by remember { mutableStateOf(false) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // The drag handle is inert when there is only one stop, AND
                                        // also while guidance is running (mid-trip resorting would
                                        // recompute the live route — destructive). Both states dim
                                        // the icon to the disabled-tint to read as "not tappable".
                                        val canReorder = waypoints.size > 1 && !navRunning
                                        Icon(
                                            Icons.Default.DragHandle,
                                            contentDescription = stringResource(R.string.nav_drag_stop),
                                            modifier = Modifier
                                                .then(
                                                    if (canReorder) Modifier.draggableHandle()
                                                    else Modifier
                                                )
                                                .size(22.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = if (canReorder) 1f else 0.3f)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        // Tap the row to recentre the map on this stop (works
                                        // whether navigation is running or not). Long-press still
                                        // opens the options menu.
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .combinedClickable(
                                                    onClick = { onCenterPin(index) },
                                                    onLongClick = {
                                                        haptic.performHapticFeedback(
                                                            HapticFeedbackType.LongPress
                                                        )
                                                        rowMenu = true
                                                    }
                                                )
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "${index + 1}.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            // Home / Work badge when this stop is a saved preset.
                                            fun samePlace(p: com.eried.eucplanet.data.model.Waypoint?) =
                                                p != null &&
                                                    kotlin.math.abs(waypoint.lat - p.lat) < 1e-4 &&
                                                    kotlin.math.abs(waypoint.lng - p.lng) < 1e-4
                                            val placeIcon = when {
                                                samePlace(home) -> Icons.Default.Home
                                                samePlace(work) -> Icons.Default.Work
                                                else -> null
                                            }
                                            if (placeIcon != null) {
                                                Icon(
                                                    placeIcon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(
                                                waypointLabel(
                                                    index, waypoints.size, waypoint.name
                                                ),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        // Quick-access remove — locked while guiding. The icon
                                        // also DIMS when locked (was hard-coded red regardless of
                                        // enabled state, so it still looked tappable).
                                        IconButton(
                                            onClick = { onRemove(index) },
                                            enabled = !navRunning
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription =
                                                    stringResource(R.string.nav_remove_stop),
                                                tint = if (navRunning)
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                        .copy(alpha = 0.38f)
                                                else MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = rowMenu,
                                        onDismissRequest = { rowMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.nav_pin_center)) },
                                            onClick = { rowMenu = false; onCenterPin(index) }
                                        )
                                        // Save as Home / Work only when both:
                                        //   a) the slot is not already filled (no point
                                        //      "saving" a Home when one exists — clutter)
                                        //   b) navigation is not running (mid-trip
                                        //      changes to Home / Work are surprising)
                                        val canSaveHome = !navRunning && home == null
                                        val canSaveWork = !navRunning && work == null
                                        if (canSaveHome || canSaveWork) HorizontalDivider()
                                        if (canSaveHome) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.nav_save_home)) },
                                                onClick = { rowMenu = false; onSaveHome(index) }
                                            )
                                        }
                                        if (canSaveWork) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.nav_save_work)) },
                                                onClick = { rowMenu = false; onSaveWork(index) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
              }   // Column
            }     // AnimatedVisibility
        }
    }
}

/**
 * JavaScript → native bridge for the Leaflet map. `@JavascriptInterface` methods
 * are invoked on the WebView's private JS thread, so every callback is hopped
 * onto the main thread before it touches the [RouteBuilderViewModel] — two quick
 * callbacks racing a non-atomic waypoint update could otherwise drop a pin.
 */
private class NavJsBridge(
    private val mapClick: (Double, Double) -> Unit,
    private val markerDragged: (Int, Double, Double) -> Unit,
    private val selfTap: (Int, Int) -> Unit,
    private val markerTapped: (Int, Int, Int) -> Unit,
    private val mapViewChanged: (Double, Double, Float) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMapClick(lat: Double, lng: Double) {
        main.post { mapClick(lat, lng) }
    }

    @JavascriptInterface
    fun onMarkerDragged(index: Int, lat: Double, lng: Double) {
        main.post { markerDragged(index, lat, lng) }
    }

    @JavascriptInterface
    fun onSelfTap(x: Int, y: Int) {
        main.post { selfTap(x, y) }
    }

    @JavascriptInterface
    fun onMarkerTapped(index: Int, x: Int, y: Int) {
        main.post { markerTapped(index, x, y) }
    }

    @JavascriptInterface
    fun onMapViewChanged(lat: Double, lng: Double, zoom: Float) {
        main.post { mapViewChanged(lat, lng, zoom) }
    }
}

/** Wraps a string as a safely-escaped JavaScript single-quoted literal. */
private fun jsString(raw: String): String =
    "'" + raw
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace(" ", "\\u2028")
        .replace(" ", "\\u2029") + "'"
