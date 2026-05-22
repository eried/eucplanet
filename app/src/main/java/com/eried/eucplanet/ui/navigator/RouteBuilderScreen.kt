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
import androidx.compose.ui.focus.onFocusChanged
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
    val routeClean by viewModel.routeClean.collectAsState()
    var selfMenuOpen by remember { mutableStateOf(false) }
    var clearConfirmOpen by remember { mutableStateOf(false) }
    var markerMenuIndex by remember { mutableStateOf(-1) }
    // Screen position of the tapped marker, so its menu opens at the pin.
    var markerMenuOffset by remember { mutableStateOf(DpOffset.Zero) }

    var searchText by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var panelExpanded by rememberSaveable { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
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
        wv.evaluateJavascript(
            "nativeRender(${jsString(viewModel.waypointsJson())}," +
                "${jsString(viewModel.geometryJson())},${mapRender.fit});",
            null
        )
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

    // Keep the "you are here" dot live.
    LaunchedEffect(pageReady, userLocation) {
        val wv = webView ?: return@LaunchedEffect
        val loc = userLocation ?: return@LaunchedEffect
        if (pageReady) {
            wv.evaluateJavascript("nativeSetUser(${loc.latitude},${loc.longitude});", null)
        }
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

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> uri?.let { viewModel.saveGpx(it) } }

    val loadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.loadGpx(it) } }

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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
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
                            onNavSettings = onOpenNavSettings
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                                selfTap = { selfMenuOpen = true },
                                markerTapped = { idx, x, y ->
                                    markerMenuIndex = idx
                                    markerMenuOffset = DpOffset(x.dp, y.dp)
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
                                        viewModel.addPreset(
                                            place, if (label == "Home") "HOME" else "WORK"
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
                            webView?.evaluateJavascript(
                                "nativeRecenter(${l.latitude},${l.longitude},16);", null
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
                                "nativeCenterOn(${wp.lat},${wp.lng});", null
                            )
                        }
                    },
                    onSaveHome = viewModel::saveWaypointAsHome,
                    onSaveWork = viewModel::saveWaypointAsWork,
                    onStartNavigation = startNav,
                    onStopNavigation = viewModel::stopNavigation,
                    canStartNavigation = userLocation != null && waypoints.isNotEmpty(),
                    navRunning = navRunning && !navStarting
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

            // Rider-position menu — a simple dropdown near the map centre.
            Box(modifier = Modifier.align(Alignment.Center)) {
                DropdownMenu(
                    expanded = selfMenuOpen,
                    onDismissRequest = { selfMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.nav_save_home)) },
                        onClick = { selfMenuOpen = false; viewModel.saveSelfAsHome() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.nav_save_work)) },
                        onClick = { selfMenuOpen = false; viewModel.saveSelfAsWork() }
                    )
                    if (waypoints.isNotEmpty()) {
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
                }
            }

            // Stop-marker menu — save that stop as a Home / Work preset. Anchored
            // at the tapped pin via markerMenuOffset.
            Box(modifier = Modifier.align(Alignment.TopStart)) {
                DropdownMenu(
                    expanded = markerMenuIndex >= 0,
                    onDismissRequest = { markerMenuIndex = -1 },
                    offset = markerMenuOffset
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.nav_save_home)) },
                        onClick = {
                            val i = markerMenuIndex; markerMenuIndex = -1
                            if (i >= 0) viewModel.saveWaypointAsHome(i)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.nav_save_work)) },
                        onClick = {
                            val i = markerMenuIndex; markerMenuIndex = -1
                            if (i >= 0) viewModel.saveWaypointAsWork(i)
                        }
                    )
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
    onNavSettings: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // "Start navigation" is omitted here — it is already a primary button.
        // Save / Clear only make sense once the route actually has stops.
        if (hasStops) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_menu_save)) },
                onClick = { onDismiss(); onSave() }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.nav_menu_load)) },
            onClick = { onDismiss(); onLoad() }
        )
        if (hasStops) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_menu_clear)) },
                onClick = { onDismiss(); onClear() }
            )
        }
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
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.nav_setting_params)) },
            onClick = { onDismiss(); onNavSettings() }
        )
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
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            // Header: title + count/distance + collapse chevron.
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

            if (expanded) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                        modes.forEachIndexed { index, (mode, icon, labelRes) ->
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

                if (waypoints.isEmpty()) {
                    Text(
                        stringResource(R.string.nav_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            // ~4.5 stop rows tall — the half row hints it scrolls.
                            .heightIn(max = 234.dp)
                            .verticalScroll(rememberScrollState())
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
                                        // A single stop cannot be reordered — leave the handle dim and inert.
                                        val canReorder = waypoints.size > 1
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
                                        // Long-press the row to open its options menu.
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .combinedClickable(
                                                    onClick = {},
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
                                        // Quick-access remove — locked while guiding.
                                        IconButton(
                                            onClick = { onRemove(index) },
                                            enabled = !navRunning
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription =
                                                    stringResource(R.string.nav_remove_stop),
                                                tint = MaterialTheme.colorScheme.error,
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
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.nav_save_home)) },
                                            onClick = { rowMenu = false; onSaveHome(index) }
                                        )
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
    private val selfTap: () -> Unit,
    private val markerTapped: (Int, Int, Int) -> Unit
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
    fun onSelfTap() {
        main.post { selfTap() }
    }

    @JavascriptInterface
    fun onMarkerTapped(index: Int, x: Int, y: Int) {
        main.post { markerTapped(index, x, y) }
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
