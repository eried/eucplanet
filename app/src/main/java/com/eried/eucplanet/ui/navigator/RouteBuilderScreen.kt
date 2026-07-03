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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInNew
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import coil.compose.AsyncImage
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.nav.NavFormat
import com.eried.eucplanet.nav.OcmCharger
import com.eried.eucplanet.nav.PoiKind
import com.eried.eucplanet.nav.PointOfInterest
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableColumn
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.ui.theme.themedSegmentedColors
import com.eried.eucplanet.ui.theme.appColors

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
    val solveFullPath by viewModel.solveFullPath.collectAsState()
    val tourDistanceM by viewModel.tourDistanceM.collectAsState()
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
    val advancedMap by viewModel.advancedMap.collectAsState()
    val showChargers by viewModel.showChargers.collectAsState()
    val placeCats by viewModel.placeCats.collectAsState()
    val showPlaces = placeCats.isNotEmpty()
    val pois by viewModel.pois.collectAsState()
    val selectedPoi by viewModel.selectedPoi.collectAsState()
    val selectedPoiOcm by viewModel.selectedPoiOcm.collectAsState()
    val ocmLoading by viewModel.ocmLoading.collectAsState()
    val chargerLoading by viewModel.chargerLoading.collectAsState()
    val placeLoading by viewModel.placeLoading.collectAsState()
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
    // the picker would be confusing, they may cancel out, and the prompt
    // would be for nothing). Null when no pending choice.
    var pendingGpxUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var markerMenuIndex by remember { mutableStateOf(-1) }
    // Screen position of the tapped marker, so its menu opens at the pin.
    var markerMenuOffset by remember { mutableStateOf(DpOffset.Zero) }

    var searchText by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }

    // An address-shape Share intent pushes its raw text here so the rider
    // sees what was passed in (and can edit / cancel) instead of the VM
    // silently picking one of several Nominatim hits.
    LaunchedEffect(Unit) {
        viewModel.fillSearchText.collect { txt ->
            searchText = txt
        }
    }

    // Backgrounding the app dismisses any pending "Add shared destination?"
    // dialog — matches the rider's mental model that walking away from the
    // prompt cancels it, and avoids a stale dialog reappearing days later.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.dismissPendingShare()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val pendingShare by viewModel.pendingShare.collectAsState()
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
    var pageReady by remember { mutableStateOf(false) }
    var didInitialCenter by remember { mutableStateOf(false) }
    // True once the WebView's first nativeRender has completed -- i.e.,
    // the map has been fitBounds'd / setView'd to the rider's area
    // instead of Leaflet's init [20,0] zoom 2 world view.
    var firstRenderApplied by remember { mutableStateOf(false) }
    // True once at least one tile in the active layer has rendered (the
    // Leaflet tile layer fires 'load' when all currently visible tiles
    // are in). Together with firstRenderApplied this is the "the map
    // looks like a map" cue we hold the locating cover behind.
    var tilesLoaded by remember { mutableStateOf(false) }
    // Cover the map until the GPS fix lands AND the first nativeRender
    // has settled the view AND the tile layer has rendered. The cover
    // fades out -- so the rider sees the locating panel smoothly cross-
    // dissolve into the live map, with no white blink, no world-view
    // of Africa, no dark flash. Each precondition addresses one of the
    // staged glitches the previous fixes left behind.
    var locationGateDone by remember { mutableStateOf(false) }
    LaunchedEffect(userLocation, firstRenderApplied, tilesLoaded) {
        if (userLocation != null && firstRenderApplied && tilesLoaded) {
            locationGateDone = true
        }
    }

    BackHandler { onExit() }

    // Surface ViewModel messages as snackbars.
    LaunchedEffect(Unit) {
        viewModel.messages.collect { resId ->
            snackbarHost.showSnackbar(context.getString(resId))
        }
    }

    // The builder is useless without a fix, ask for location up front so the
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
    //
    // Collect mapRender directly instead of using it as a LaunchedEffect
    // key -- the keyed form lets Compose coalesce rapid bumpRender calls
    // (e.g. restore's bumpRender(fit=true) followed an instant later by
    // the activeLeg observer's bumpRender(fit=false)) and only the
    // LATEST value triggers the body. With navigation active that meant
    // the fit=true frame got dropped, the map opened at world zoom 2,
    // and the auto-follow tween couldn't claw it back. Collecting
    // processes every distinct emission so each bumpRender drives one
    // nativeRender, in order.
    LaunchedEffect(pageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        viewModel.mapRender.collect { mr ->
            val fit = mr.fit && viewModel.savedView.value == null
            wv.evaluateJavascript(
                "nativeRender(${jsString(viewModel.waypointsJson())}," +
                    "${jsString(viewModel.geometryJson())},$fit," +
                    "${jsString(viewModel.pendingPreviewJson())});"
            ) {
                if (!firstRenderApplied) firstRenderApplied = true
            }
        }
    }


    // Restore the saved map view (centre + zoom) once, the moment the page
    // becomes ready, this is what makes "go to Settings and swipe back"
    // not snap the map to the world view. Keyed ONLY on pageReady so a
    // user pan that updates savedView doesn't re-fire this effect (which
    // would call nativeRecenter on every drag, an infinite move-end
    // recursion).
    //
    // Stored in the VM only, no settings persistence: a fresh app launch
    // starts from the rider's current location like before; only
    // within-session sub-navigation restores.
    LaunchedEffect(pageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val v = viewModel.savedView.value
        if (v == null) {
            android.util.Log.i(
                "RouteBuilderVM",
                "MAP-OPEN no savedView -- auto-follow / fit will set view"
            )
            return@LaunchedEffect
        }
        android.util.Log.i(
            "RouteBuilderVM",
            "MAP-OPEN restoring savedView lat=${v.lat} lng=${v.lng} zoom=${v.zoom}"
        )
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
    // Skipped when a current route is present, that route frames itself.
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

    // Push the themeable per-mode route colors + preview line so editing the
    // routeWalk/Bike/Drive/Straight/Preview tokens recolors the map (applied on
    // the next route/preview redraw). Order matches nativeSetRouteColors().
    val rc = MaterialTheme.appColors
    val routeColorsArg = listOf(rc.routeWalk, rc.routeBike, rc.routeDrive, rc.routeStraight, rc.routePreview)
        .joinToString(",") { "'#%06X'".format(0xFFFFFF and it.toArgb()) }
    LaunchedEffect(pageReady, routeColorsArg) {
        if (pageReady) {
            webView?.evaluateJavascript("nativeSetRouteColors($routeColorsArg);", null)
        }
    }

    // Apply the saved base map style (dark / light / satellite).
    LaunchedEffect(pageReady, mapType) {
        if (pageReady) {
            webView?.evaluateJavascript("nativeSetMapType('$mapType');", null)
        }
    }

    // Push the faint charger/station layer to the map whenever it changes (an
    // empty list clears it, e.g. when the rider turns the layer off).
    LaunchedEffect(pageReady, pois) {
        if (pageReady) {
            webView?.evaluateJavascript(
                "nativeSetPois(${jsString(viewModel.poisJson())});", null
            )
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
                "${jsString(viewModel.geometryJson())},false," +
                "${jsString(viewModel.pendingPreviewJson())});",
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

    // Push the Full path / Next segment choice so the map suppresses the dashed
    // preview (Full path) or shows it through the remaining stops (Next segment).
    LaunchedEffect(pageReady, solveFullPath) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        wv.evaluateJavascript("nativeSetFullPath(${if (solveFullPath) "true" else "false"});", null)
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> uri?.let { viewModel.saveGpx(it) } }

    val loadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // If the current route is an unsaved multi-stop one, confirm before
        // overwriting it, we already have the file, just hold the URI and
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
    //
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
                // Shared downsampled decode -- see [decodeDownsampledBitmap].
                // Full-res phone photos (50+ MP, the crash log once showed a
                // 191 MB bitmap) OOM or exceed RecordingCanvas's 100 MB
                // per-bitmap limit and take the activity down; the helper
                // brings the long edge to ~1024 px before we ever draw it.
                decodeDownsampledBitmap(context, uri)
            }.getOrNull()
            if (bmp != null) pendingMarkerSource = bmp
        }
    }

    // Starting navigation, Direct mode has no street-by-street routing, so it
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
        snackbarHost = {
            SnackbarHost(snackbarHost) {
                androidx.compose.material3.Snackbar(
                    it,
                    containerColor = MaterialTheme.appColors.snackbarBackground,
                    contentColor = MaterialTheme.appColors.snackbarText,
                    actionContentColor = MaterialTheme.appColors.snackbarAction
                )
            }
        },
        topBar = {
            TopAppBar(
                modifier = Modifier.onSizeChanged { sz -> topBarHeightPx = sz.height },
                colors = TopAppBarDefaults.topAppBarColors(
                    // 80 % alpha so the map shows through the top bar (the
                    // map area extends under it via the contentWindowInsets
                    // override below).
                    containerColor = MaterialTheme.appColors.topBar.copy(alpha = 0.80f)
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
                            .onFocusChanged { searchFocused = it.isFocused },
                        colors = themedFieldColors(),
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
                                // No "*/*", that wildcard showed every file.
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
                                // Skip the 'Start a new route?' confirmation when:
                                //   * there's nothing meaningful to confirm
                                //     discarding (single stop / freshly-loaded
                                //     'clean' route), OR
                                //   * every stop is already passed -- the trip
                                //     is over, the rider's intent is unambiguous.
                                val allPassed = waypoints.isNotEmpty() &&
                                    waypoints.all { it.passed }
                                if (waypoints.size > 1 && !routeClean && !allPassed) {
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
        // Landscape: the control stack (mode + waypoints + start + FABs) docks
        // as a fixed-width sidebar on the left, leaving the map visible to its
        // right, instead of a full-width panel across the bottom.
        val landscape = LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
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
        // In landscape the stops panel sits in a left sidebar, so nothing
        // occludes the map vertically. The Y offset formula would misread the
        // sidebar's content height as a bottom occlusion and shift the rider
        // toward the sidebar edge, so it is zeroed instead.
        val recenterOffsetPx =
            if (landscape) 0f
            else (effectivePanelPx - effectiveTopBarPx) / 2f / density.density
        // Push the recenter offset to JS whenever it changes -- the
        // auto-follow pan tween (only active during navigation) uses it
        // to keep the rider centred in the visible map area as the map
        // glides under them.
        LaunchedEffect(pageReady, recenterOffsetPx) {
            val wv = webView ?: return@LaunchedEffect
            if (!pageReady) return@LaunchedEffect
            wv.evaluateJavascript("nativeSetRecenterOffset($recenterOffsetPx);", null)
        }
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
                        // file:///android_asset/, without file access enabled
                        // they silently fail on API 30+ and the map is blank.
                        settings.allowFileAccess = true
                        // Match the WebView bg to the rider's chosen map style
                        // so the first frame painted before tiles arrive isn't
                        // a dark navy that clashes with a LIGHT / SATELLITE
                        // tile choice. mapType is collected at the top of this
                        // composable; we read its snapshot value here for the
                        // initial paint and re-apply on every recomposition
                        // below in case the rider cycles the style.
                        setBackgroundColor(
                            android.graphics.Color.parseColor(
                                mapTypeInitialBg(viewModel.mapType.value)
                            )
                        )
                        addJavascriptInterface(
                            NavJsBridge(
                                mapClick = { lat, lng ->
                                    if (!viewModel.navRunning.value) {
                                        viewModel.addWaypoint(lat, lng)
                                    }
                                },
                                routeLineClick = { lat, lng ->
                                    if (!viewModel.navRunning.value) {
                                        viewModel.insertWaypointOnRoute(lat, lng)
                                    }
                                },
                                markerDragged = { i, lat, lng ->
                                    if (!viewModel.navRunning.value) {
                                        viewModel.moveWaypoint(i, lat, lng)
                                    }
                                },
                                markerDragStart = { viewModel.setUserDragging(true) },
                                markerDragEnd = { viewModel.setUserDragging(false) },
                                selfTap = { x, y ->
                                    selfMenuOffset = DpOffset(x.dp, y.dp)
                                    selfMenuOpen = true
                                },
                                mapViewChanged = { lat, lng, zoom ->
                                    viewModel.setSavedView(lat, lng, zoom)
                                },
                                mapBoundsChanged = { s, w, n, e ->
                                    viewModel.onMapViewportChanged(s, w, n, e)
                                },
                                tilesLoaded = {
                                    if (!tilesLoaded) tilesLoaded = true
                                },
                                markerTapped = { idx, x, y ->
                                    // While navigation is running the only sensible
                                    // action on a stop is to recenter the map onto
                                    // it, Save as Home / Work would mid-trip change
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
                                },
                                poiTapped = { id -> viewModel.onPoiTapped(id) }
                            ),
                            "AndroidNav"
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageReady = true
                            }
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
                        loadDataWithBaseURL(
                            "file:///android_asset/",
                            routeBuilderHtmlFor(viewModel.mapType.value),
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

            // Locating cover, sits IMMEDIATELY above the WebView in z-order
            // so the bottom stops panel, FABs, top app bar (and every other
            // overlay added below) draw on top of it. The rider never sees
            // the cover swallow the panel: only the map area itself fades
            // from the map-style colour to the live tiles once they load.
            //
            // The Locating UI is hidden for the first 800 ms so the common
            // hot-path (cached fix, fast tiles ~200-400 ms) shows a clean
            // tinted cover that fades to the map. Only if the wait drags
            // on does the rider see the spinner + Finding-your-location.
            var showLocatingUi by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(800)
                if (!locationGateDone) showLocatingUi = true
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !locationGateDone,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(0)
                ),
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(400)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color(
                        android.graphics.Color.parseColor(
                            mapTypeInitialBg(mapType)
                        )
                    )
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (showLocatingUi) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(stringResource(R.string.nav_locating))
                            }
                        }
                    }
                }
            }

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
                        // Saved Home / Work presets, tap to drop as the next
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
                                color = MaterialTheme.appColors.divider.copy(alpha = 0.2f)
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
                                color = MaterialTheme.appColors.divider.copy(alpha = 0.2f)
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

            // Overlay FABs (charger / places / layers / my-location) as a
            // reusable block: they float on the map (landscape) or sit above
            // the bottom dock (portrait).
            val overlayFabs: @Composable ColumnScope.() -> Unit = {
                // The charger + places overlay toggles only exist when advanced
                // map features are on. The icon stays visible while loading (a
                // ring overlays it) so the button is always tappable, and the
                // spinner only shows on the layer(s) actually being fetched.
                if (advancedMap) {
                    OverlayFab(
                        active = showChargers,
                        loading = chargerLoading,
                        icon = Icons.Default.EvStation,
                        contentDescription = stringResource(R.string.nav_show_chargers),
                        onClick = { viewModel.toggleChargers() },
                        // Long-press jumps to Navigation settings (where the
                        // Open Charge Map / charger community key lives).
                        onLongClick = { onOpenNavSettings() },
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(end = 16.dp, bottom = 12.dp)
                    )
                    Box(modifier = Modifier.align(Alignment.End)) {
                        var placesMenu by remember { mutableStateOf(false) }
                        OverlayFab(
                            active = showPlaces,
                            loading = placeLoading,
                            icon = Icons.Default.Explore,
                            contentDescription = stringResource(R.string.nav_show_places),
                            onClick = { viewModel.togglePlaces() },
                            onLongClick = { placesMenu = true },
                            modifier = Modifier.padding(end = 16.dp, bottom = 12.dp)
                        )
                        DropdownMenu(
                            expanded = placesMenu,
                            onDismissRequest = { placesMenu = false },
                            containerColor = MaterialTheme.appColors.menuBackground
                        ) {
                            PoiKind.PLACES.forEach { kind ->
                                val on = kind in placeCats
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            if (on) Icons.Default.CheckBox
                                            else Icons.Default.CheckBoxOutlineBlank,
                                            contentDescription = null
                                        )
                                    },
                                    text = { Text(stringResource(placeCategoryLabel(kind))) },
                                    onClick = { viewModel.setPlaceCategory(kind, !on) }
                                )
                            }
                        }
                    }
                }
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
            }
            // The route/stops panel. Always expanded in landscape (the sidebar
            // has room for the full stops list), collapsible in portrait.
            val bottomPanelContent: @Composable (Modifier) -> Unit = { panelMod ->
                BottomPanel(
                    expanded = landscape || panelExpanded,
                    onToggle = { if (!landscape) panelExpanded = !panelExpanded },
                    travelMode = travelMode,
                    onModeChange = viewModel::setTravelMode,
                    solveFullPath = solveFullPath,
                    waypoints = waypoints,
                    home = homePlace,
                    work = workPlace,
                    routeDistanceM = tourDistanceM,
                    routing = routing,
                    imperial = imperial,
                    onRemove = viewModel::removeWaypoint,
                    onReorder = viewModel::reorderWaypoints,
                    onListReorderStart = { viewModel.setUserDragging(true) },
                    onListReorderEnd = { viewModel.setUserDragging(false) },
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
                    onClearRoute = viewModel::clear,
                    canStartNavigation = userLocation != null && waypoints.isNotEmpty(),
                    navRunning = navRunning && !navStarting,
                    modifier = panelMod.onSizeChanged { sz -> panelHeightPx = sz.height }
                )
            }

            // Portrait: FABs stacked above a full-width bottom dock. Landscape:
            // FABs float bottom-right on the map, the panel is a left sidebar
            // (always expanded) so the stops list is always visible.
            if (landscape) {
                Column(modifier = Modifier.align(Alignment.BottomEnd)) { overlayFabs() }
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(340.dp)
                        .background(MaterialTheme.appColors.menuBackground)
                        .padding(top = padding.calculateTopPadding())
                        .verticalScroll(rememberScrollState())
                ) {
                    bottomPanelContent(Modifier.fillMaxWidth())
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    overlayFabs()
                    bottomPanelContent(Modifier.fillMaxWidth())
                }
            }

            // Charger / station details, opened by tapping a faint POI marker.
            selectedPoi?.let { poi ->
                PoiDetailsSheet(
                    poi = poi,
                    imperial = imperial,
                    canAdd = !navRunning,
                    ocm = selectedPoiOcm,
                    ocmLoading = ocmLoading,
                    onAddStop = { viewModel.addPoiAsStop(poi) },
                    onOpenUrl = { url ->
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onDismiss = { viewModel.dismissPoiDetails() }
                )
            }

            // Locating gate is rendered right after the WebView (further up
            // in this Box) so it covers only the map area, not the top bar
            // or the bottom stops panel / FABs which sit on top of it in
            // z-order. See the AnimatedVisibility just below the AndroidView
            // for the actual cover.

            // Rider-position menu, anchored at the rider's marker via
            // selfMenuOffset (the JS bridge pushes the marker's screen-space
            // position on tap). TopStart alignment lets the offset be the
            // absolute pixel position the menu opens at.
            Box(modifier = Modifier.align(Alignment.TopStart)) {
                DropdownMenu(
                    expanded = selfMenuOpen,
                    onDismissRequest = { selfMenuOpen = false },
                    offset = selfMenuOffset,
                    containerColor = MaterialTheme.appColors.menuBackground
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
                    // Marker-customization cluster, separated from the
                    // place-saving / add-stop items above because the actions
                    // affect a different thing (the rider's avatar, not the
                    // route). Short labels because the context (your-marker
                    // menu) already implies "your marker". The divider only
                    // renders when something ELSE is also in the menu, a
                    // lone divider on top of Customize looks pointless.
                    val hasAddStop = waypoints.isNotEmpty() && !navRunning
                    val hasOtherItems = homePlace == null || workPlace == null || hasAddStop
                    if (hasOtherItems) HorizontalDivider(color = MaterialTheme.appColors.divider)
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

            // Stop-marker menu, save that stop as a Home / Work preset.
            // Anchored at the tapped pin via markerMenuOffset. Each "Save as"
            // option only shows when that slot is empty; once Home (or Work)
            // is saved the option vanishes from this menu and reappears only
            // after the rider forgets it via the main menu.
            Box(modifier = Modifier.align(Alignment.TopStart)) {
                DropdownMenu(
                    expanded = markerMenuIndex >= 0,
                    onDismissRequest = { markerMenuIndex = -1 },
                    offset = markerMenuOffset,
                    containerColor = MaterialTheme.appColors.menuBackground
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
                    // Remove this stop. Hidden while navigation is running or once
                    // the whole route is passed (matches the list-row menu).
                    val canRemoveMarker = !navRunning &&
                        !(waypoints.isNotEmpty() && waypoints.all { it.passed })
                    if (canRemoveMarker) {
                        if (homePlace == null || workPlace == null) {
                            HorizontalDivider(color = MaterialTheme.appColors.divider)
                        }
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            text = {
                                Text(
                                    stringResource(R.string.nav_remove_stop),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                val i = markerMenuIndex; markerMenuIndex = -1
                                if (i >= 0) viewModel.removeWaypoint(i)
                            }
                        )
                    }
                }
            }

            // Confirm before discarding an edited multi-stop route.
            if (clearConfirmOpen) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { clearConfirmOpen = false },
                    title = { Text(stringResource(R.string.nav_clear_confirm)) },
                    text = { Text(stringResource(R.string.nav_clear_confirm_body)) },
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

            // A Share-to-app intent landed on a route that already has
            // stops. Ask before stomping on the rider's current route —
            // "New route" wipes stops then drops the shared point, "Add
            // as next" appends. Dismissing (back / outside tap) cancels
            // the share; the lifecycle observer also clears it on app
            // backgrounding so a stale dialog never reappears later.
            if (pendingShare != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { viewModel.dismissPendingShare() },
                    title = { Text(stringResource(R.string.nav_share_dialog_title)) },
                    text = { Text(stringResource(R.string.nav_share_dialog_body)) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.acceptPendingShareAppend() }) {
                            Text(stringResource(R.string.nav_share_dialog_append))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.acceptPendingShareAsNewRoute() }) {
                            Text(stringResource(R.string.nav_share_dialog_new))
                        }
                    }
                )
            }

            // Confirm before replacing an unsaved multi-stop route with the
            // GPX the rider just picked. We already have the URI in hand ,
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
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = MaterialTheme.appColors.menuBackground) {
        // "Start navigation" is omitted here, it is already a primary button.
        // Save is allowed even while nav is running so the rider can capture
        // the remaining stops as a GPX checkpoint (the trim from
        // syncBuilderRoute means the saved file only contains stops still
        // ahead). Load / Clear stay hidden during nav, both would replace
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
        HorizontalDivider(color = MaterialTheme.appColors.divider)
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
        HorizontalDivider(color = MaterialTheme.appColors.divider)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.nav_setting_params)) },
            onClick = { onDismiss(); onNavSettings() }
        )
        // Forget Home / Work sit last, they are rare, destructive actions.
        if (hasHome || hasWork) {
            HorizontalDivider(color = MaterialTheme.appColors.divider)
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
 * The list label for a pin: its positional role, Nth stop / Destination (the
 * rider is the implicit start), with the solved address appended in
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
    solveFullPath: Boolean,
    waypoints: List<com.eried.eucplanet.data.model.Waypoint>,
    home: com.eried.eucplanet.data.model.Waypoint?,
    work: com.eried.eucplanet.data.model.Waypoint?,
    routeDistanceM: Double?,
    routing: Boolean,
    imperial: Boolean,
    onRemove: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onListReorderStart: () -> Unit,
    onListReorderEnd: () -> Unit,
    onCenterPin: (Int) -> Unit,
    onSaveHome: (Int) -> Unit,
    onSaveWork: (Int) -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onClearRoute: () -> Unit,
    canStartNavigation: Boolean,
    navRunning: Boolean,
    modifier: Modifier = Modifier
) {
    // When every stop is passed, the rider's journey is complete -- the
    // Start/Stop control becomes a "New route" reset instead.
    val allPassed = waypoints.isNotEmpty() && waypoints.all { it.passed }
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
                } else if (!allPassed && routeDistanceM != null && routeDistanceM > 0) {
                    // Hidden when the trip is over (allPassed). This is the
                    // whole-tour distance (every remaining stop summed), not
                    // just the next leg, so it's shown as a plain total.
                    val distText = NavFormat.distance(context, routeDistanceM, imperial)
                    Text(
                        distText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.weight(1f))
                // Compact start / stop. Hidden when expanded (the big button
                // at the bottom of the expanded panel does the same job) and
                // when there is nothing to start (no waypoints, no fix yet).
                //
                // Animation rule:
                //   * Expanding -- the compact button vanishes INSTANTLY
                //     (zero-duration exit), so the panel grows into the
                //     space cleanly without the button overlapping the
                //     animation.
                //   * Collapsing -- we wait the full AnimatedVisibility
                //     duration of the panel before fading the button IN,
                //     so it doesn't appear mid-animation while the panel
                //     body is still shrinking past it.
                // Compact button is ALWAYS visible when the panel is
                // collapsed (it's the only way to start nav from the
                // collapsed state), even when no stops are placed yet --
                // it just sits there disabled so the rider knows where
                // to look.
                val canShowCompact = true
                var compactArmed by remember { mutableStateOf(!expanded) }
                LaunchedEffect(expanded) {
                    if (expanded) {
                        compactArmed = false
                    } else {
                        // Match the panel collapse animation (~300 ms
                        // default for shrinkVertically + fadeOut) with a
                        // tiny buffer so the button starts fading in
                        // right as the panel settles.
                        kotlinx.coroutines.delay(320)
                        compactArmed = true
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = canShowCompact && compactArmed,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(0)
                    )
                ) {
                    val label = stringResource(
                        when {
                            allPassed -> R.string.nav_menu_clear
                            navRunning -> R.string.nav_stop_short
                            else -> R.string.nav_start_short
                        }
                    )
                    Button(
                        onClick = {
                            when {
                                allPassed -> onClearRoute()
                                navRunning -> onStopNavigation()
                                else -> onStartNavigation()
                            }
                        },
                        // Disabled when there's no work to do (no stops +
                        // no live nav). New route + Stop nav are always
                        // actionable when they apply.
                        enabled = allPassed || navRunning || canStartNavigation,
                        contentPadding = androidx.compose.foundation.layout
                            .PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        // Fixed width holds the longest label ('Stop navigation')
                        // so the row doesn't reflow when the state flips between
                        // Start / Stop / New route.
                        modifier = Modifier.widthIn(min = 150.dp)
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
                // The mode row dims to 40% alpha while nav is running OR
                // when the trip is over (allPassed) so the "selected mode
                // pill" doesn't read as enabled. Functional disable is
                // also on each SegmentedButton.
                val modesLocked = navRunning || allPassed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .weight(1f)
                            .then(if (modesLocked) Modifier.alpha(0.4f) else Modifier)
                    ) {
                        modes.forEachIndexed { index, (mode, icon, labelRes) ->
                            // Icon tint matches the route line colour for that
                            // mode so the chip visually previews what the line
                            // on the map will look like. Keep in sync with
                            // routeColorFor() in MapHtml.kt.
                            //   Walk     lavender    #7E57C2
                            //   Bike     teal        #26A69A
                            //   Drive    soft orange #FB8C00
                            //   Straight sky blue    #42A5F5
                            val modeColor = when (mode) {
                                TravelMode.WALKING  -> Color(0xFF7E57C2)
                                TravelMode.CYCLING  -> Color(0xFF26A69A)
                                TravelMode.DRIVING  -> Color(0xFFFB8C00)
                                TravelMode.STRAIGHT -> Color(0xFF42A5F5)
                            }
                            SegmentedButton(
                                selected = travelMode == mode,
                                onClick = { onModeChange(mode) },
                                enabled = !modesLocked,
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                icon = {},
                                colors = themedSegmentedColors(),
                            ) {
                                if (!solveFullPath && mode != TravelMode.STRAIGHT) {
                                    // Next segment + routed mode: icon with a
                                    // short segmented dashed underline in the
                                    // mode's own colour, echoing the dashed
                                    // remaining legs on the map. ONLY this case
                                    // adds the underline (and the slight upward
                                    // nudge it causes). Direct and Full path keep
                                    // the plain centred icon, unchanged in
                                    // position.
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            icon,
                                            contentDescription = stringResource(labelRes),
                                            tint = modeColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            repeat(3) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(4.dp)
                                                        .height(2.dp)
                                                        .background(modeColor, RoundedCornerShape(1.dp))
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Icon(
                                        icon,
                                        contentDescription = stringResource(labelRes),
                                        tint = modeColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = when {
                            allPassed -> onClearRoute
                            navRunning -> onStopNavigation
                            else -> onStartNavigation
                        },
                        enabled = allPassed || navRunning || canStartNavigation,
                        // Hold the row's width steady across Start/Stop/New
                        // route (matches the compact button width above).
                        modifier = Modifier.widthIn(min = 180.dp)
                    ) {
                        Text(
                            stringResource(
                                when {
                                    allPassed -> R.string.nav_menu_clear
                                    navRunning -> R.string.nav_stop_short
                                    else -> R.string.nav_start_short
                                }
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
                            // ~4.5 stop rows tall, the half row hints it scrolls.
                            .heightIn(min = minHDp, max = 234.dp)
                            .verticalScroll(rememberScrollState())
                            .onSizeChanged { sz ->
                                if (sz.height > stableMinH) stableMinH = sz.height
                            }
                    ) {
                        ReorderableColumn(
                            list = waypoints,
                            onSettle = if (navRunning) { _, _ -> } else { from, to ->
                                onListReorderEnd()
                                onReorder(from, to)
                            },
                            onMove = {
                                onListReorderStart()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
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
                                        // Drag/reorder lock states:
                                        //   * disabled while nav is running (mid-trip resort
                                        //     would destroy the live route)
                                        //   * disabled when only one stop is left
                                        //   * disabled when the whole route is done (allPassed
                                        //     -- the rider is expected to hit 'New route')
                                        // A passed stop sitting alongside still-pending stops
                                        // CAN be dragged / removed -- the rider might want to
                                        // re-add it or reshuffle the remaining list.
                                        val canReorder =
                                            waypoints.size > 1 && !navRunning && !allPassed
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
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    textDecoration = if (waypoint.passed)
                                                        androidx.compose.ui.text.style.TextDecoration.LineThrough
                                                    else null
                                                ),
                                                color = if (waypoint.passed)
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                        .copy(alpha = 0.6f)
                                                else Color.Unspecified,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        // Quick-access remove. Locked while nav is running;
                                        // locked when the whole route is allPassed (rider
                                        // should hit 'New route'). A single passed stop
                                        // alongside pending ones IS removable -- treat it
                                        // like any other entry once nav is stopped.
                                        val canDelete = !navRunning && !allPassed
                                        IconButton(
                                            onClick = { onRemove(index) },
                                            enabled = canDelete
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription =
                                                    stringResource(R.string.nav_remove_stop),
                                                tint = if (canDelete)
                                                    MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.38f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = rowMenu,
                                        onDismissRequest = { rowMenu = false },
                                        containerColor = MaterialTheme.appColors.menuBackground
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.nav_pin_center)) },
                                            onClick = { rowMenu = false; onCenterPin(index) }
                                        )
                                        // Save as Home / Work only when both:
                                        //   a) the slot is not already filled (no point
                                        //      "saving" a Home when one exists, clutter)
                                        //   b) navigation is not running (mid-trip
                                        //      changes to Home / Work are surprising)
                                        val canSaveHome = !navRunning && home == null
                                        val canSaveWork = !navRunning && work == null
                                        if (canSaveHome || canSaveWork) HorizontalDivider(color = MaterialTheme.appColors.divider)
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
                                        if (!navRunning && !allPassed) {
                                            HorizontalDivider(color = MaterialTheme.appColors.divider)
                                            DropdownMenuItem(
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                },
                                                text = {
                                                    Text(
                                                        stringResource(R.string.nav_remove_stop),
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                },
                                                onClick = { rowMenu = false; onRemove(index) }
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
 * Bottom sheet shown when the rider taps a faint charger / station marker.
 * Surfaces the place's name, kind, brand, opening hours, on-route distance and
 * an Add-as-stop action (which inserts it intelligently into the route).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PoiDetailsSheet(
    poi: PointOfInterest,
    imperial: Boolean,
    canAdd: Boolean,
    ocm: OcmCharger?,
    ocmLoading: Boolean,
    onAddStop: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Open fully expanded so the (taller) charger flyout with OCM details shows
    // without the rider needing to drag the sheet up.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The Open Charge Map card -- and its photos -- load async and grow the
    // content in BURSTS. The earlier "expand on every measured-height change"
    // failed because each burst restarts this effect and cancels the in-flight
    // expand() animation mid-way, leaving the sheet half-open. Fix: debounce so
    // the expand runs only once the height has settled (the cancel-restart no
    // longer interrupts a real animation), then confirm a beat later in case the
    // first attempt raced the sheet's own anchor refresh.
    var contentHeight by remember { mutableStateOf(0) }
    LaunchedEffect(contentHeight) {
        if (!sheetState.isVisible || contentHeight == 0) return@LaunchedEffect
        delay(110)
        runCatching { sheetState.expand() }
        delay(180)
        runCatching { sheetState.expand() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .onSizeChanged { contentHeight = it.height }
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val kindLabel = when (poi.kind) {
                PoiKind.CHARGER -> stringResource(R.string.nav_poi_charger)
                PoiKind.STORE -> stringResource(R.string.nav_poi_store)
                PoiKind.FOOD -> stringResource(R.string.nav_poi_food)
                PoiKind.REST -> stringResource(R.string.nav_poi_rest)
                PoiKind.SIGHTS -> stringResource(R.string.nav_poi_sights)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    poi.name.ifBlank { kindLabel },
                    style = MaterialTheme.typography.titleLarge
                )
                val subtitle = if (poi.brand.isNotBlank() && poi.brand != poi.name)
                    "$kindLabel · ${poi.brand}" else kindLabel
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // All the OSM detail gathered into one tidy card, each row shown
            // only when that tag is present.
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val infoLabel = when (poi.kind) {
                        PoiKind.CHARGER -> stringResource(R.string.nav_poi_sockets)
                        PoiKind.FOOD -> stringResource(R.string.nav_poi_cuisine)
                        else -> stringResource(R.string.nav_poi_type)
                    }
                    PoiInfoLine(infoLabel, poi.info)
                    PoiInfoLine(stringResource(R.string.nav_poi_network), poi.network)
                    PoiInfoLine(stringResource(R.string.nav_poi_operator), poi.operator)
                    PoiInfoLine(stringResource(R.string.nav_poi_capacity), poi.capacity)
                    PoiInfoLine(stringResource(R.string.nav_poi_hours), poi.openingHours)
                    PoiInfoLine(stringResource(R.string.nav_poi_access), poi.access)
                    PoiInfoLine(stringResource(R.string.nav_poi_fee), poi.fee)
                    PoiInfoLine(stringResource(R.string.nav_poi_phone), poi.phone)
                    PoiInfoLine(
                        stringResource(R.string.nav_poi_distance),
                        formatDistanceShort(poi.distanceFromRouteM, imperial)
                    )
                    // Tiny attribution tucked into the card, bottom-right.
                    Text(
                        stringResource(R.string.nav_poi_source),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }

            // Community data from Open Charge Map, for chargers when a key is set.
            if (ocmLoading) {
                // Reserve roughly an OCM card's height while loading so the sheet
                // opens tall enough to show the action buttons right away and
                // barely needs to grow when the card arrives -- belt-and-braces
                // with the debounced re-expand above.
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.nav_ocm_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            ocm?.let { OcmCommunityCard(it, onOpenUrl) }

            // Add-as-stop and open-online sit side by side, half and half. There
            // is always somewhere to open online: the place's own website when
            // OSM has one, otherwise its OpenStreetMap page (where any photos /
            // extra tags / edits live).
            val hasWebsite = poi.website.startsWith("http")
            val onlineUrl = if (hasWebsite) poi.website
            else "https://www.openstreetmap.org/node/${poi.id}"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onAddStop,
                    enabled = canAdd,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.nav_poi_add_stop), maxLines = 1)
                }
                OutlinedButton(
                    onClick = { onOpenUrl(onlineUrl) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (hasWebsite) R.string.nav_poi_website
                            else R.string.nav_poi_osm
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Open Charge Map community card for a charger: connectors, status, cost, an
 * average rating, recent comments / check-ins, photos and a link out to OCM.
 */
@Composable
private fun OcmCommunityCard(ocm: OcmCharger, onOpenUrl: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Title doubles as the link out to the OCM page (open-in-new icon).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onOpenUrl(ocm.ocmUrl) }
                ) {
                    Text(
                        stringResource(R.string.nav_ocm_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = stringResource(R.string.nav_ocm_open),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                ocm.avgRating?.let {
                    Text(
                        "★ %.1f (%d)".format(it, ocm.ratingCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            PoiInfoLine(stringResource(R.string.nav_ocm_connectors), ocm.connectors)
            ocm.numberOfPoints?.let {
                PoiInfoLine(stringResource(R.string.nav_ocm_points), it.toString())
            }
            PoiInfoLine(stringResource(R.string.nav_ocm_usage), ocm.usageType)
            PoiInfoLine(stringResource(R.string.nav_ocm_cost), ocm.usageCost)
            PoiInfoLine(stringResource(R.string.nav_poi_operator), ocm.operator)
            PoiInfoLine(stringResource(R.string.nav_ocm_status), ocm.status)
            PoiInfoLine(stringResource(R.string.nav_ocm_address), ocm.address)
            PoiInfoLine(stringResource(R.string.nav_poi_phone), ocm.phone)
            PoiInfoLine(stringResource(R.string.nav_ocm_access), ocm.accessComments)
            PoiInfoLine(stringResource(R.string.nav_ocm_verified), ocm.lastVerified)
            // Inline photo strip (Coil). Size the box to the native
            // ItemThumbnailURL resolution (~250-300 px) so the thumbnail
            // renders crisp without upscaling. OCM ships only two sizes
            // (this thumb + full), so a bigger inline box means visible
            // blur on high-density screens. Tap opens the full URL.
            if (ocm.photos.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ocm.photos.take(8).forEach { photo ->
                        AsyncImage(
                            model = photo.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenUrl(photo.fullUrl) }
                        )
                    }
                }
            }
            ocm.comments.take(3).forEach { c ->
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            c.user,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (c.rating != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "★".repeat(c.rating.coerceIn(1, 5)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            c.date,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val line = listOf(c.checkin, c.text).filter { it.isNotBlank() }
                        .joinToString(". ")
                    if (line.isNotBlank()) {
                        // Body text in this sheet uses onSurface (see
                        // PoiInfoLine); spell it out so the review text
                        // doesn't read as the same accent colour as the
                        // reviewer name above.
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.nav_ocm_attrib),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

/**
 * A FAB-styled toggle whose icon stays visible while [loading] (a ring overlays
 * it, so the button is always tappable) and that supports an optional
 * long-press. Used for the charger / places overlay toggles.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OverlayFab(
    active: Boolean,
    loading: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val onColor = if (active) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = modifier
            .size(56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = onColor)
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 2.dp,
                    color = onColor.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** String resource for a place category label (used by the long-press picker). */
private fun placeCategoryLabel(kind: PoiKind): Int = when (kind) {
    PoiKind.STORE -> R.string.nav_poi_store
    PoiKind.FOOD -> R.string.nav_poi_food
    PoiKind.REST -> R.string.nav_poi_rest
    PoiKind.SIGHTS -> R.string.nav_poi_sights
    PoiKind.CHARGER -> R.string.nav_poi_charger
}

/** One "Label  value" row inside the details card; hidden when value is blank. */
@Composable
private fun PoiInfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

/** Short distance readout in the rider's units (m/km or ft/mi). */
private fun formatDistanceShort(meters: Double, imperial: Boolean): String =
    if (imperial) {
        val ft = meters * 3.28084
        if (ft < 1000) "${ft.roundToInt()} ft" else String.format("%.1f mi", meters / 1609.344)
    } else {
        if (meters < 1000) "${meters.roundToInt()} m" else String.format("%.1f km", meters / 1000.0)
    }

/**
 * JavaScript → native bridge for the Leaflet map. `@JavascriptInterface` methods
 * are invoked on the WebView's private JS thread, so every callback is hopped
 * onto the main thread before it touches the [RouteBuilderViewModel], two quick
 * callbacks racing a non-atomic waypoint update could otherwise drop a pin.
 */
private class NavJsBridge(
    private val mapClick: (Double, Double) -> Unit,
    private val routeLineClick: (Double, Double) -> Unit,
    private val markerDragged: (Int, Double, Double) -> Unit,
    private val markerDragStart: () -> Unit,
    private val markerDragEnd: () -> Unit,
    private val selfTap: (Int, Int) -> Unit,
    private val markerTapped: (Int, Int, Int) -> Unit,
    private val mapViewChanged: (Double, Double, Float) -> Unit,
    private val mapBoundsChanged: (Double, Double, Double, Double) -> Unit,
    private val tilesLoaded: () -> Unit,
    private val poiTapped: (Long) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMapClick(lat: Double, lng: Double) {
        main.post { mapClick(lat, lng) }
    }

    @JavascriptInterface
    fun onRouteLineClick(lat: Double, lng: Double) {
        main.post { routeLineClick(lat, lng) }
    }

    @JavascriptInterface
    fun onMarkerDragged(index: Int, lat: Double, lng: Double) {
        main.post {
            markerDragEnd()
            markerDragged(index, lat, lng)
        }
    }

    @JavascriptInterface
    fun onMarkerDragStart() {
        main.post { markerDragStart() }
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

    @JavascriptInterface
    fun onMapBounds(south: Double, west: Double, north: Double, east: Double) {
        main.post { mapBoundsChanged(south, west, north, east) }
    }

    @JavascriptInterface
    fun onTilesLoaded() {
        main.post { tilesLoaded() }
    }

    @JavascriptInterface
    fun onPoiTapped(id: String) {
        val parsed = id.toLongOrNull() ?: return
        main.post { poiTapped(parsed) }
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
