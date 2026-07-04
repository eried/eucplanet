package com.eried.eucplanet.ui.navigator

import android.content.Context
import android.location.Location
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavMode
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.nav.BBox
import com.eried.eucplanet.nav.GeoMath
import com.eried.eucplanet.nav.GeoResult
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.nav.OcmCharger
import com.eried.eucplanet.nav.OcmService
import com.eried.eucplanet.nav.PoiKind
import com.eried.eucplanet.nav.PoiService
import com.eried.eucplanet.nav.PointOfInterest
import com.eried.eucplanet.nav.RouteAvoidances
import com.eried.eucplanet.nav.RoutingService
import com.eried.eucplanet.util.GpxIO
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Backing state for the Route Builder screen: the ordered waypoint list, the
 * resolved [NavRoute], address search, GPX import/export, and holding the
 * "current route" in memory (CurrentRouteStore) so it survives leaving the screen.
 *
 * Whenever the waypoints or travel mode change the route is recomputed (with a
 * short debounce so dragging a pin doesn't fire a request per frame), and once
 * it solves, pins still missing an address are reverse-geocoded.
 */
@HiltViewModel
class RouteBuilderViewModel @Inject constructor(
    private val routingService: RoutingService,
    private val poiService: PoiService,
    private val ocmService: OcmService,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val navigationEngine: NavigationEngine,
    private val incomingShareRepository:
        com.eried.eucplanet.data.repository.IncomingShareRepository,
    private val currentRouteStore: com.eried.eucplanet.nav.CurrentRouteStore,
    private val navMarkerStore: com.eried.eucplanet.data.store.NavMarkerStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        /**
         * Listed destinations + stops (the rider is the implicit origin).
         * OSRM solves the whole chain in one /route request, so this cap is a
         * usability limit (a manageable list), not a router limit.
         */
        const val MAX_WAYPOINTS = 25

        /** How far the rider moves before the route preview is recomputed. */
        private const val ORIGIN_REROUTE_M = 25.0
    }

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

    private val _route = MutableStateFlow<NavRoute?>(null)
    val route: StateFlow<NavRoute?> = _route.asStateFlow()

    /**
     * A short gold dashed preview of just the edge an edit is about to change
     * ([neighbor, newStop] for an append, [left, newStop, right] for an insert
     * or move). Shown OVER the existing solid route while a routed path is
     * being fetched, then cleared when the new route lands -- so adding a stop
     * no longer blanks the whole planned route. Empty outside that window.
     */
    private val _pendingPreview = MutableStateFlow<List<GeoPoint>>(emptyList())
    val pendingPreview: StateFlow<List<GeoPoint>> = _pendingPreview.asStateFlow()

    // --- Chargers & places along the route (opt-in POI layers) --------------------
    /** Advanced map features (default false): unlocks the overlay layers. */
    private val _advancedMap = MutableStateFlow(false)
    val advancedMap: StateFlow<Boolean> = _advancedMap.asStateFlow()

    /** ⚡ charger layer (electric charging only) on/off. */
    private val _showChargers = MutableStateFlow(false)
    val showChargers: StateFlow<Boolean> = _showChargers.asStateFlow()

    /** Enabled "places" categories (subset of STORE/FOOD/REST/SIGHTS). The
     *  places FAB toggles the whole group; long-press picks individual ones. */
    private val _placeCats = MutableStateFlow<Set<PoiKind>>(emptySet())
    val placeCats: StateFlow<Set<PoiKind>> = _placeCats.asStateFlow()

    /** POIs currently shown near the route (union of the enabled layers). */
    private val _pois = MutableStateFlow<List<PointOfInterest>>(emptyList())
    val pois: StateFlow<List<PointOfInterest>> = _pois.asStateFlow()

    // The charger layer (OCM, or Overpass without a key) and the places layer
    // (Overpass) are fetched and cached independently, so toggling or refreshing
    // one never refetches the other. [_pois] is just their union.
    private val _chargerPois = MutableStateFlow<List<PointOfInterest>>(emptyList())
    private val _placePois = MutableStateFlow<List<PointOfInterest>>(emptyList())

    /** The POI whose details sheet is open, or null when none. */
    private val _selectedPoi = MutableStateFlow<PointOfInterest?>(null)
    val selectedPoi: StateFlow<PointOfInterest?> = _selectedPoi.asStateFlow()

    /** Open Charge Map community data for the open charger flyout, or null. */
    private val _selectedPoiOcm = MutableStateFlow<OcmCharger?>(null)
    val selectedPoiOcm: StateFlow<OcmCharger?> = _selectedPoiOcm.asStateFlow()

    /** True while OCM data for the open flyout is being fetched. */
    private val _ocmLoading = MutableStateFlow(false)
    val ocmLoading: StateFlow<Boolean> = _ocmLoading.asStateFlow()

    private var ocmApiKey = ""
    private var ocmJob: Job? = null
    /** True once the "hold for categories" hint toast has been shown. */
    private var placesHintShown = false

    /** True while each layer's fetch is in flight (drives that FAB's spinner). */
    private val _chargerLoading = MutableStateFlow(false)
    val chargerLoading: StateFlow<Boolean> = _chargerLoading.asStateFlow()
    private val _placeLoading = MutableStateFlow(false)
    val placeLoading: StateFlow<Boolean> = _placeLoading.asStateFlow()

    private var chargerJob: Job? = null
    private var placeJob: Job? = null
    private var chargerSig: String? = null
    private var placeSig: String? = null
    /** Last visible map bounds the WebView reported (pan / zoom). When set, POIs
     *  follow what's on screen instead of a fixed radius around the route. */
    private val _viewportBounds = MutableStateFlow<BBox?>(null)

    /** The categories enabled by the layer toggles (empty in simple mode). */
    private fun enabledCategories(): Set<PoiKind> = buildSet {
        if (!_advancedMap.value) return@buildSet
        if (_showChargers.value) add(PoiKind.CHARGER)
        addAll(_placeCats.value)
    }

    private val _travelMode = MutableStateFlow(TravelMode.CYCLING)
    val travelMode: StateFlow<TravelMode> = _travelMode.asStateFlow()

    /**
     * Full path (true, the default) vs Next segment (false). Full path solves
     * the whole multi-stop tour in one routing request and draws it as one
     * solid line; Next segment solves only the next leg and draws the rest as
     * a dashed straight-line preview. Persisted via [AppSettings.navSolveFullPath].
     */
    private val _solveFullPath = MutableStateFlow(true)
    val solveFullPath: StateFlow<Boolean> = _solveFullPath.asStateFlow()

    /**
     * Distance to show in the builder header: the WHOLE remaining tour, not
     * just the first leg. In Full path the solved route already spans every
     * stop, so its [NavRoute.totalDistanceM] is the answer. In Next segment
     * the solved route is only the first leg, so we add the straight-line
     * length through the remaining non-passed stops. Null when nothing to show.
     */
    val tourDistanceM: StateFlow<Double?> =
        combine(_route, _waypoints, _solveFullPath) { route, wps, full ->
            val routed = route?.totalDistanceM ?: 0.0
            if (route == null || routed <= 0.0) return@combine null
            if (full) return@combine routed
            val nonPassed = wps.filter { !it.passed }
            if (nonPassed.size <= 1) return@combine routed
            routed + GeoMath.polylineLengthM(nonPassed.map { it.point() })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _searchResults = MutableStateFlow<List<GeoResult>>(emptyList())
    val searchResults: StateFlow<List<GeoResult>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _routing = MutableStateFlow(false)
    val routing: StateFlow<Boolean> = _routing.asStateFlow()

    /** Route Builder base map style: DARK / LIGHT / SATELLITE. */
    private val _mapType = MutableStateFlow("DARK")
    val mapType: StateFlow<String> = _mapType.asStateFlow()

    /** Custom rider-marker photo as a base64 data URL, or null when the rider
     *  hasn't set one (fall back to the default puck). Backed by an on-disk PNG
     *  (NavMarkerStore, noBackupFilesDir), never the settings JSON. */
    val userMarkerPhoto: StateFlow<String?> = navMarkerStore.photoDataUrl

    /** Last (centre, zoom) the rider was looking at on the map. Cached
     *  in-memory only (per VM lifetime) so a sub-navigation to Settings and
     *  back doesn't reset the view to the world-fit default. The JS side
     *  posts this back on every moveend / zoomend. */
    data class MapView(val lat: Double, val lng: Double, val zoom: Float)
    private val _savedView = MutableStateFlow<MapView?>(null)
    val savedView: StateFlow<MapView?> = _savedView.asStateFlow()

    fun setSavedView(lat: Double, lng: Double, zoom: Float) {
        // Throttle the log; this fires on every moveend (including each
        // auto-follow tween frame's setView, ~60 Hz when the rider is
        // navigating) and would otherwise dominate logcat.
        val now = System.currentTimeMillis()
        if (now - lastSavedViewLogMs > 500) {
            lastSavedViewLogMs = now
            android.util.Log.i(
                "RouteBuilderVM",
                "SAVED-VIEW lat=${"%.5f".format(lat)} lng=${"%.5f".format(lng)} " +
                    "zoom=${"%.2f".format(zoom)}"
            )
        }
        _savedView.value = MapView(lat, lng, zoom)
    }
    private var lastSavedViewLogMs = 0L

    /** Sets the rider-marker photo (or clears it), writing it to its on-disk PNG
     *  (NavMarkerStore) -- not the settings JSON. */
    fun setUserMarkerPhoto(dataUrl: String?) {
        navMarkerStore.set(dataUrl)
    }

    /** Bumped whenever the map should redraw; [fit] asks it to re-frame bounds. */
    data class MapRender(val version: Int, val fit: Boolean)
    private val _mapRender = MutableStateFlow(MapRender(0, false))
    val mapRender: StateFlow<MapRender> = _mapRender.asStateFlow()

    /** One-shot user messages (string resource ids) shown as snackbars. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 6)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    val currentLocation: StateFlow<Location?> = tripRepository.currentLocation

    val imperialUnits: StateFlow<Boolean> = settingsRepository.settings
        .map { it.imperialUnits }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Landscape sidebar side for the stops panel: "LEFT" or "RIGHT". */
    val navStopsSide: StateFlow<String> = settingsRepository.settings
        .map { it.navStopsSide }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "RIGHT")

    private var routeJob: Job? = null
    private var searchJob: Job? = null
    private var enrichJob: Job? = null
    private var lastRouteOrigin: GeoPoint? = null
    private var geocoderUrl = RoutingService.DEFAULT_GEOCODER
    private var routerUrl = RoutingService.DEFAULT_ROUTER
    /** Route avoidances; all-off (the default) keeps routing on OSRM. */
    private var avoidances = RouteAvoidances.NONE
    private var overpassUrl = PoiService.DEFAULT_OVERPASS
    private var routeName = context.getString(R.string.nav_default_route_name)

    // Declared above init{}: the init coroutine can resume synchronously (an
    // already-cached settings read) and touch these before properties declared
    // lower in the class would have been initialised, that was a startup crash.
    private val _home = MutableStateFlow<Waypoint?>(null)
    val home: StateFlow<Waypoint?> = _home.asStateFlow()
    private val _work = MutableStateFlow<Waypoint?>(null)
    val work: StateFlow<Waypoint?> = _work.asStateFlow()

    /** Default arrival radius (metres) for waypoints with no custom value. */
    private var defaultRadiusM: Int = 40

    /** True right after a load or save; Clear route then needs no confirm.
     *  Declared BEFORE the init block because the location collector and the
     *  nav-progress collector inside init both call scheduleRecompute(),
     *  which writes to this flow. If it were declared lower in the class,
     *  Kotlin's in-order property init would leave it null and the very first
     *  fast cached GPS fix would NPE the app on the route-builder screen. */
    private val _routeClean = MutableStateFlow(false)
    val routeClean: StateFlow<Boolean> = _routeClean.asStateFlow()

    /** True while the rider is actively dragging a stop (either a map pin
     *  or a list row in the reorder list). While true, scheduleRecompute()
     *  is a no-op -- GPS-shift recomputes mid-drag yank the dashed preview
     *  out from under the rider's finger and feel like the app is
     *  fighting them. A trailing recompute fires once the drag ends. */
    private val _userDragging = MutableStateFlow(false)
    private var recomputeQueuedDuringDrag = false

    init {
        // The route builder needs a live fix for the "my location" button.
        // WheelService owns the GPS lifecycle in general; this is an idempotent
        // nudge so location works even when no wheel is connected.
        tripRepository.startLocationUpdates()

        // Consume any "Share to EUC Planet" hand-off pending from MainActivity.
        // Runs on every Pending update so multiple shares in a session are all
        // honoured. take() clears the slot atomically so we never process the
        // same payload twice.
        viewModelScope.launch {
            incomingShareRepository.pending.collect { p ->
                if (p == null) return@collect
                val req = incomingShareRepository.take() ?: return@collect
                handleIncomingShare(req)
            }
        }
        // Signal raised once the current (in-memory) route (if any) has been
        // read back into _waypoints / _route. The arrived collector below
        // awaits this BEFORE processing any nav events, so a VM that was
        // just re-created mid-navigation (e.g. rider switched tabs and
        // came back during the arrival dismiss window) can't see
        // arrived=true with a still-empty _waypoints and skip the
        // passed-mark for the stop the rider just reached.
        val restoreReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        viewModelScope.launch {
            val s = settingsRepository.get()
            geocoderUrl = s.navGeocoderUrl.ifBlank { RoutingService.DEFAULT_GEOCODER }
            routerUrl = RoutingService.effectiveRouterUrl(s.navRouterUrl)
            avoidances = RouteAvoidances.from(s)
            overpassUrl = s.navOverpassUrl.ifBlank { PoiService.DEFAULT_OVERPASS }
            ocmApiKey = s.navOcmApiKey
            _advancedMap.value = s.navAdvancedMap
            _showChargers.value = s.navShowChargers
            _placeCats.value = parsePlaceCats(s.navPlaceCategories)
            placesHintShown = s.navPlacesHintShown
            _travelMode.value = TravelMode.fromName(s.navDefaultTravelMode)
            _mapType.value = s.navMapType.ifBlank { "LIGHT" }
            defaultRadiusM = s.navArrivalRadiusM
            _solveFullPath.value = s.navSolveFullPath
            _home.value = placeFromJson(s.navHomeJson)
            _work.value = placeFromJson(s.navWorkJson)
            // userMarkerPhoto comes from NavMarkerStore (its own on-disk PNG),
            // already loaded -- nothing to seed from settings here.
            // The current route lives only in memory (CurrentRouteStore), never
            // in settings/JSON, so nothing is backed up and a reinstall always
            // starts navigation from zero. Restore it into the Builder when
            // guidance is running or a route is loaded this session (e.g. after
            // navigating away and back -- the singleton outlived this ViewModel).
            // We restore everything, including travelMode, so the Builder mirrors
            // the active session faithfully.
            if (navigationEngine.isActive || currentRouteStore.get() != null) {
                currentRouteStore.get()?.let { existing ->
                    if (existing.waypoints.isNotEmpty() || existing.geometry.isNotEmpty()) {
                        routeName = existing.name
                        _travelMode.value = existing.travelMode
                        _waypoints.value = existing.waypoints
                        // Only adopt the current (in-memory) route as _route
                        // when it actually carries geometry. With the new
                        // "stops + travel mode only" caching, geometry is
                        // always empty -- overwriting _route with an empty
                        // leg here would clobber whatever the activeLeg
                        // observer just put in (during active nav) and
                        // erase the freshly-drawn green line on every
                        // re-entry of the Builder.
                        if (existing.geometry.isNotEmpty()) {
                            _route.value = existing
                        }
                        bumpRender(fit = true)
                        // Recompute the route fresh from the restored stops
                        // -- in draft mode this fills _route with the
                        // current router's solution; during active nav the
                        // engine's activeLeg observer already owns _route
                        // so we skip.
                        if (!navigationEngine.isActive) {
                            scheduleRecompute(fit = false)
                        }
                    }
                }
            }
            android.util.Log.i(
                "RouteBuilderVM",
                "RESTORE engineActive=${navigationEngine.isActive} " +
                    "hasRoute=${currentRouteStore.get() != null} " +
                    "_waypoints=${_waypoints.value.size}"
            )
            // Signal restore-complete after the section above finishes --
            // independent of whether anything was actually restored.
            restoreReady.complete(Unit)
        }
        // While navigation is running, drop already-reached stops from the
        // builder's visible waypoint list so the map matches reality. The
        // engine's navState carries the index of the *next* unvisited goal;
        // each time that advances, we trim the corresponding stops from the
        // head of [_waypoints]. The map redraws automatically from the
        // bumpRender() call inside the trim. When nav stops, the in-memory
        // CurrentRouteStore holds whatever is left, so the next time the
        // builder is opened it reflects the trimmed route too.
        // While navigation is running, each leg is a single-destination
        // route (origin -> next non-passed stop). On arrival, mark the
        // first non-passed stop as passed (don't remove -- the rider
        // still wants to see it in the list, struck-through). If any
        // non-passed stops remain, build a new single-destination route
        // to the new "next" and call advanceLeg so guidance continues
        // without ending the nav session. If they're all passed, the
        // final destination arrival stands.
        //
        // arrivalProcessed guards against the collector firing more than
        // once per arrival: the routing call after each arrival is async
        // (advanceLeg only resets navState.arrived AFTER the new route
        // resolves), and other navState fields keep changing meanwhile,
        // re-emitting navState with arrived=true. Without this guard,
        // each re-emission marked another stop as passed -- the rider
        // would see two or three stops vanish from one physical arrival.
        var arrivalProcessed = false
        viewModelScope.launch {
            // Wait until the current (in-memory) route has been read back into
            // _waypoints before processing arrival events. Without this,
            // a VM re-created during the engine's post-arrival window
            // would observe arrived=true with _waypoints still empty,
            // skip the mark, and the rider would see the entire route
            // disappear when the engine's stop() finally fired.
            restoreReady.await()
            android.util.Log.i("RouteBuilderVM", "COLLECT-READY")
            navigationEngine.navState.collect { nav ->
                android.util.Log.i(
                    "RouteBuilderVM",
                    "NAV-STATE active=${nav.active} arrived=${nav.arrived} " +
                        "arrivalProcessed=$arrivalProcessed wpCount=${_waypoints.value.size}"
                )
                if (!nav.active || !nav.arrived) {
                    arrivalProcessed = false
                    return@collect
                }
                if (arrivalProcessed) return@collect
                arrivalProcessed = true
                // The engine is now the sole orchestrator of multi-stop
                // progress: it writes passed=true to the in-memory current
                // route (CurrentRouteStore) itself
                // and (for intermediate stops) builds the next leg + calls
                // advanceLeg from inside its own scope. The Navigator VM
                // used to race the engine's intermediate-flash window by
                // running its own leg-building coroutine and calling
                // advanceLeg the instant arrived=true fired -- which
                // emitted arrived=false again and yanked the "Reached
                // goal N" popup off-screen ~150 ms in. Now we just mirror
                // the passed flag into the visible list so the marker
                // flips to a flag immediately; the engine's write to the
                // in-memory current route (CurrentRouteStore) below is the
                // source of truth, and
                // _route is updated by the engine's advanceLeg-driven
                // navState emission picked up by other observers /
                // bumpRender paths.
                val current = _waypoints.value
                val nextIdx = current.indexOfFirst { !it.passed }
                android.util.Log.i(
                    "RouteBuilderVM",
                    "ARRIVE-COLLECT active=true arrived=true " +
                        "wpCount=${current.size} nextNonPassedIdx=$nextIdx " +
                        "passedFlags=${current.map { it.passed }}"
                )
                if (nextIdx < 0) return@collect
                val updated = current.toMutableList().apply {
                    this[nextIdx] = this[nextIdx].copy(passed = true)
                }
                _waypoints.value = updated
                bumpRender(fit = false)
                val nextNonPassed = updated.firstOrNull { !it.passed }
                if (nextNonPassed == null) {
                    // Final stop reached -- drop the leftover route line +
                    // dashed preview so the map shows just the flag-marked
                    // stops instead of a stranded green stub.
                    _route.value = null
                    _routing.value = false
                    bumpRender(fit = false)
                    return@collect
                }
                // The new leg's geometry is mirrored from the engine's
                // activeLeg observer below -- the engine builds the next
                // leg synchronously inside handleArrival BEFORE the flag
                // flash, so by the time we get here the map should
                // already have redrawn to the new green line. Refresh
                // _route's waypoint list in place so the map markers
                // pick up the new "passed" flag immediately while we
                // wait for the engine's leg emission.
                _route.value = _route.value?.copy(waypoints = updated)
                bumpRender(fit = false)
            }
        }
        // The engine is the source of truth for which leg is being
        // navigated. Mirror its activeLeg into _route so the map's green
        // polyline always matches what the rider is being guided along
        // -- without this the map only redrew when GPS movement passed
        // ORIGIN_REROUTE_M, leaving a stale leg drawn between goal
        // transitions if the rider stopped moving on arrival (the common
        // case at a goal). The full waypoint list is grafted on so the
        // passed-flag markers continue to render alongside the leg.
        viewModelScope.launch {
            navigationEngine.activeLeg.collect { leg ->
                if (leg == null) return@collect
                // No isActive gate -- the engine only sets activeLeg while
                // navigation is running, and gating on a separate flag risks
                // racing the StateFlow update order (activeLeg arriving
                // before navState.active flips true on start()).
                val merged = leg.copy(waypoints = _waypoints.value.ifEmpty { leg.waypoints })
                android.util.Log.i(
                    "RouteBuilderVM",
                    "ACTIVE-LEG-OBS legGeomPts=${leg.geometry.size} " +
                        "mergedWpCount=${merged.waypoints.size} " +
                        "passedFlags=${merged.waypoints.map { it.passed }}"
                )
                _route.value = merged
                _routing.value = false
                bumpRender(fit = false)
            }
        }
        // Position 0 is always the rider, so a fresh fix shifts the route's
        // origin, recompute the preview once they have moved far enough.
        viewModelScope.launch {
            tripRepository.currentLocation.collect { loc ->
                if (loc == null || _waypoints.value.isEmpty()) return@collect
                val last = lastRouteOrigin
                val moved = last == null || GeoMath.distanceM(
                    GeoPoint(loc.latitude, loc.longitude), last
                ) > ORIGIN_REROUTE_M
                if (moved) scheduleRecompute(fit = false)
            }
        }
        // Full path / Next segment lives in Navigation settings (a setting)
        // now, so observe it live: when the rider flips it there and comes back
        // to the builder, update the cached flag and re-solve the preview. The
        // `!=` guard makes this a no-op on unrelated settings emissions.
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                var changed = false
                if (_solveFullPath.value != s.navSolveFullPath) {
                    _solveFullPath.value = s.navSolveFullPath
                    changed = true
                }
                val newAvoid = RouteAvoidances.from(s)
                if (avoidances != newAvoid) {
                    avoidances = newAvoid
                    changed = true
                }
                val newOverpass = s.navOverpassUrl.ifBlank { PoiService.DEFAULT_OVERPASS }
                if (overpassUrl != newOverpass) {
                    overpassUrl = newOverpass
                    chargerSig = null // force a refetch from the new source
                    placeSig = null
                    refreshPois()
                }
                ocmApiKey = s.navOcmApiKey
                // Advanced map is a setting; when the rider flips it (turning it
                // off forces the layers off), reconcile the on-map layers.
                if (_advancedMap.value != s.navAdvancedMap) {
                    _advancedMap.value = s.navAdvancedMap
                    chargerSig = null
                    placeSig = null
                    refreshPois()
                }
                if (changed && !navigationEngine.isActive) scheduleRecompute(fit = false)
            }
        }
        // Keep the POI layers in sync with the route while one is on.
        viewModelScope.launch {
            _route.collect { if (enabledCategories().isNotEmpty()) refreshPois() }
        }
        // With a POI layer on but no route, search around the rider's position;
        // refresh as they move (refreshPois dedupes by a coarse grid).
        viewModelScope.launch {
            tripRepository.currentLocation.collect {
                if (_waypoints.value.isEmpty() && enabledCategories().isNotEmpty()) refreshPois()
            }
        }
        // When a charger flyout opens and an OCM key is set, fetch community data
        // (rating, comments, photos, connectors) for that spot.
        viewModelScope.launch {
            _selectedPoi.collect { poi -> fetchOcmFor(poi) }
        }
    }

    private fun fetchOcmFor(poi: PointOfInterest?) {
        ocmJob?.cancel()
        _selectedPoiOcm.value = null
        _ocmLoading.value = false
        if (poi == null || poi.kind != com.eried.eucplanet.nav.PoiKind.CHARGER ||
            ocmApiKey.isBlank()
        ) return
        _ocmLoading.value = true
        ocmJob = viewModelScope.launch {
            val ocm = ocmService.nearestCharger(poi.lat, poi.lng, ocmApiKey)
            // Only apply if the same charger flyout is still open.
            if (_selectedPoi.value?.id == poi.id) {
                _selectedPoiOcm.value = ocm
                _ocmLoading.value = false
            }
        }
    }

    // --- Waypoint editing --------------------------------------------------------

    /**
     * The rider tapped their own position marker on the map: hint how to make
     * a pin track them rather than dropping a pin on top of them.
     */
    fun notifyTapOnSelf() {
        _messages.tryEmit(R.string.nav_tap_on_self)
    }

    /**
     * Apply a "Share to EUC Planet" payload from MainActivity. Coordinates
     * jump straight to addWaypoint with a fit-to-bounds; a search query
     * is geocoded against the rider's current location and the first hit
     * is added. While guidance is already running we refuse the add and
     * surface a snackbar -- editing the route mid-trip would also need
     * us to interrupt the engine to re-solve, which is hostile.
     */
    private fun handleIncomingShare(
        req: com.eried.eucplanet.data.repository.IncomingShareRepository.Pending
    ) {
        if (navigationEngine.isActive) {
            _messages.tryEmit(R.string.nav_cant_add_while_running)
            return
        }
        if (_waypoints.value.isEmpty()) {
            // Empty route: act immediately. Coordinates drop a pin; address
            // strings fill the search field so the rider sees what was sent
            // and can still edit / cancel before the geocoder commits to a
            // specific match.
            consumeIncomingShare(req)
            return
        }
        // Existing stops: hand off to the screen via _pendingShare so the
        // rider can pick "Start new route" (clears stops then consumes) or
        // "Add as next stop" (consumes onto the tail).
        _pendingShare.value = req
    }

    /**
     * Backing for the "what to do with this share?" AlertDialog. Set by
     * [handleIncomingShare] when the route already has stops; cleared by
     * the dialog's resolve / dismiss callbacks AND by the screen's
     * lifecycle observer on background (so a backgrounded dialog doesn't
     * silently reappear later).
     */
    private val _pendingShare =
        MutableStateFlow<com.eried.eucplanet.data.repository.IncomingShareRepository.Pending?>(null)
    val pendingShare: StateFlow<com.eried.eucplanet.data.repository.IncomingShareRepository.Pending?> =
        _pendingShare.asStateFlow()

    /** One-shot stream the screen consumes to set its search text. Used
     *  when an address share lands and we want the rider to see exactly
     *  what was passed in (instead of silently fanning out to a geocoder
     *  guess). */
    private val _fillSearchText = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 1
    )
    val fillSearchText: kotlinx.coroutines.flow.SharedFlow<String> = _fillSearchText

    fun acceptPendingShareAppend() {
        val req = _pendingShare.value ?: return
        _pendingShare.value = null
        consumeIncomingShare(req)
    }

    fun acceptPendingShareAsNewRoute() {
        val req = _pendingShare.value ?: return
        _pendingShare.value = null
        clear()
        consumeIncomingShare(req)
    }

    fun dismissPendingShare() {
        _pendingShare.value = null
    }

    private fun consumeIncomingShare(
        req: com.eried.eucplanet.data.repository.IncomingShareRepository.Pending
    ) {
        val lat = req.lat
        val lng = req.lng
        if (lat != null && lng != null) {
            addWaypoint(lat, lng, name = req.label.orEmpty(), fit = true)
            return
        }
        val q = req.query
        if (q.isNullOrBlank()) return
        // Google Maps's mobile share sheet often ships only a shortened
        // `maps.app.goo.gl/xxx` URL — no place name, no coords in the
        // payload itself. Follow the HTTP redirect off the main thread,
        // re-parse the expanded URL (which DOES carry `@lat,lng,zoom`),
        // and continue with whatever the resolved version yields.
        if (q.startsWith("http", ignoreCase = true)) {
            viewModelScope.launch {
                val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    resolveShortlink(q)
                }
                val reparsed: com.eried.eucplanet.data.repository.IncomingShareRepository.Pending? =
                    resolved?.let {
                        com.eried.eucplanet.data.repository
                            .IncomingShareRepository.parse(it)
                    }
                if (reparsed != null && reparsed.query != q &&
                    (reparsed.lat != null || !reparsed.query.isNullOrBlank())
                ) {
                    consumeIncomingShare(reparsed)
                } else {
                    // Surface the raw URL so the rider sees what we got;
                    // they can edit it into a real address by hand.
                    _fillSearchText.tryEmit(q)
                    _messages.tryEmit(R.string.nav_search_no_results)
                }
            }
            return
        }
        // Surface the query in the screen's address field so the rider sees
        // what was shared, then kick off the geocoder lookup that already
        // adds the first hit as a waypoint.
        _fillSearchText.tryEmit(q)
        search(q)
    }

    /**
     * Follow HTTP redirects (up to 5 hops) to expand a shortened map URL
     * into its full `google.com/maps/...@lat,lng,zoom...` form. Returns
     * the final URL, or null on any IO error.
     *
     * `maps.app.goo.gl` typically chains 2-3 redirects: shortlink ->
     * `www.google.com/maps?...` -> `consent.google.com/?continue=...` ->
     * actual maps page with `@lat,lng`. We follow them all manually
     * (instanceFollowRedirects is too eager for cross-host hops on
     * Android) and log each hop so non-coord results are diagnosable.
     */
    private fun resolveShortlink(url: String): String? {
        return try {
            var current = url
            repeat(5) { hop ->
                val conn = (java.net.URL(current).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) EucPlanet")
                }
                try {
                    val code = conn.responseCode
                    if (code !in 300..399) {
                        // Reached a 2xx (or error). Return the URL we tried.
                        return current
                    }
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) return current
                    current = if (loc.startsWith("http", ignoreCase = true)) loc
                              else java.net.URL(java.net.URL(current), loc).toString()
                } finally {
                    conn.disconnect()
                }
            }
            current
        } catch (e: Exception) {
            android.util.Log.w("EucShare", "shortlink resolve failed: $e")
            null
        }
    }

    fun addWaypoint(lat: Double, lng: Double, name: String = "", fit: Boolean = false) {
        if (_waypoints.value.size >= MAX_WAYPOINTS) {
            _messages.tryEmit(R.string.nav_max_stops)
            return
        }
        // The new stop connects to whatever was the last non-passed stop (or
        // the rider, if none yet) -- that single edge is what's "about to
        // change", so preview just it in gold while the route re-solves.
        val before = _waypoints.value
        val neighbor = before.lastOrNull { !it.passed }?.point()
            ?: currentLocation.value?.let { GeoPoint(it.latitude, it.longitude) }
        _waypoints.value = before + Waypoint(lat, lng, name)
        // A plain stop was just added; clear the "last preset" memory so the
        // Home / Work search suggestions both come back. addPreset() re-sets it.
        _lastAddedPresetKind.value = null
        cacheDraft()
        val edge = if (neighbor != null) listOf(neighbor, GeoPoint(lat, lng)) else emptyList()
        scheduleRecompute(fit = fit, previewEdge = edge)
    }

    /**
     * The rider tapped the drawn route/preview line between two stops. Insert a
     * new draggable stop AT the tapped point, ordered between the two stops the
     * tapped segment connects, so a detour is a single tap. The insertion index
     * is found from the straight chain [origin, stop0, stop1, ...]: whichever
     * segment of that chain the tap is closest to decides which two stops the
     * new one falls between. This stays correct for both the solid routed line
     * and the dashed straight preview -- the JS side already confirmed the tap
     * landed on a drawn line before calling here.
     */
    fun insertWaypointOnRoute(lat: Double, lng: Double, name: String = "") {
        if (navigationEngine.isActive) return
        val dests = _waypoints.value
        if (dests.size >= MAX_WAYPOINTS) {
            _messages.tryEmit(R.string.nav_max_stops)
            return
        }
        if (dests.isEmpty()) {
            addWaypoint(lat, lng, name)
            return
        }
        val tap = GeoPoint(lat, lng)
        val origin = currentLocation.value?.let { GeoPoint(it.latitude, it.longitude) }
        // The chain the rider sees, in order. Origin (the rider) is prepended
        // when known so a tap on the very first leg lands before stop 0.
        val chain = buildList {
            if (origin != null) add(origin)
            dests.forEach { add(it.point()) }
        }
        val originOffset = if (origin != null) 1 else 0
        var bestSeg = 0
        var bestDist = Double.MAX_VALUE
        for (i in 0 until chain.size - 1) {
            val d = GeoMath.nearestOnPolyline(tap, listOf(chain[i], chain[i + 1]))
                ?.distanceM ?: Double.MAX_VALUE
            if (d < bestDist) { bestDist = d; bestSeg = i }
        }
        // Segment bestSeg connects chain[bestSeg] and chain[bestSeg+1]; the new
        // stop sits just before chain[bestSeg+1]. Drop the origin offset to get
        // the destination-list index.
        val insertIndex = (bestSeg + 1 - originOffset).coerceIn(0, dests.size)
        // The two edges the detour introduces: left-neighbour -> new -> right-
        // neighbour. Preview just those in gold while the route re-solves.
        val left = if (insertIndex == 0) origin else dests[insertIndex - 1].point()
        val right = dests.getOrNull(insertIndex)?.point()
        val list = dests.toMutableList()
        list.add(insertIndex, Waypoint(lat, lng, name))
        _waypoints.value = list
        _lastAddedPresetKind.value = null
        cacheDraft()
        val edge = listOfNotNull(left, GeoPoint(lat, lng), right)
        scheduleRecompute(fit = false, previewEdge = if (edge.size >= 2) edge else emptyList())
    }

    /** Called when the user drags a pin on the map. */
    fun moveWaypoint(index: Int, lat: Double, lng: Double) {
        val list = _waypoints.value.toMutableList()
        if (index !in list.indices) return
        // The moved pin's two edges (to its previous and next neighbours, or
        // the rider for index 0) are what change -- preview just those in gold.
        val origin = currentLocation.value?.let { GeoPoint(it.latitude, it.longitude) }
        val left = if (index == 0) origin else list[index - 1].point()
        val right = list.getOrNull(index + 1)?.point()
        // A moved pin's address is now stale; clear it so the list shows just
        // the role until the next route solves and re-resolves the address.
        list[index] = list[index].copy(lat = lat, lng = lng, name = "")
        _waypoints.value = list
        cacheDraft()
        val edge = listOfNotNull(left, GeoPoint(lat, lng), right)
        scheduleRecompute(fit = false, previewEdge = if (edge.size >= 2) edge else emptyList())
    }

    fun removeWaypoint(index: Int) {
        val list = _waypoints.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _waypoints.value = list
        cacheDraft()
        scheduleRecompute(fit = false)
    }

    fun reorderWaypoints(from: Int, to: Int) {
        val list = _waypoints.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _waypoints.value = list
        cacheDraft()
        scheduleRecompute(fit = false)
    }

    fun setTravelMode(mode: TravelMode) {
        if (_travelMode.value == mode) return
        _travelMode.value = mode
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(navDefaultTravelMode = mode.name))
        }
        cacheDraft()
        scheduleRecompute(fit = false)
    }

    /**
     * Mirror the current draft (stops + travel mode) into the in-memory
     * [CurrentRouteStore] so it survives navigating away from the Builder and
     * back. Skipped while guidance is running -- the [NavigationEngine] owns the
     * route then (syncBuilderRoute trims passed stops as the rider progresses,
     * and a draft overwrite from here would race with it).
     *
     * Stops emptying out clears the store; otherwise we hold a thin route with
     * no geometry / maneuvers (the Builder recomputes those when it re-opens).
     * Nothing is persisted, so a reinstall always starts navigation from zero.
     */
    private fun cacheDraft() {
        if (navigationEngine.isActive) return
        val wps = _waypoints.value
        if (wps.isEmpty()) {
            currentRouteStore.clear()
            return
        }
        // In-memory only (CurrentRouteStore): stops + travel mode, no geometry --
        // recomputed on re-open. Survives leaving the Builder, gone on app kill.
        currentRouteStore.set(
            NavRoute(
                name = routeName,
                waypoints = wps,
                travelMode = _travelMode.value,
                geometry = emptyList(),
                maneuvers = emptyList(),
                totalDistanceM = 0.0
            )
        )
    }

    fun clear() {
        routeJob?.cancel()
        enrichJob?.cancel()
        _waypoints.value = emptyList()
        _route.value = null
        _routing.value = false
        _lastAddedPresetKind.value = null
        lastRouteOrigin = null
        bumpRender(fit = false)
        clearCurrentRoute()
    }


    fun recenterOnUser(): Location? = currentLocation.value

    // --- Map style ---------------------------------------------------------------

    /** Cycles the base map style Dark → Light → Satellite → Dark. */
    fun cycleMapType() {
        setMapType(
            when (_mapType.value) {
                "DARK" -> "LIGHT"
                "LIGHT" -> "SATELLITE"
                else -> "DARK"
            }
        )
    }

    private fun setMapType(type: String) {
        if (_mapType.value == type) return
        _mapType.value = type
        // No toast on a map-style change; the change is its own feedback.
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(navMapType = type))
        }
    }

    // --- Address search ----------------------------------------------------------

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _searching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(450) // debounce keystrokes (Nominatim asks for <=1 req/s)
            _searching.value = true
            // Bias the search to ~50 km around the rider's current location.
            val near = currentLocation.value?.let {
                com.eried.eucplanet.data.model.GeoPoint(it.latitude, it.longitude)
            }
            var results = routingService.geocode(query, geocoderUrl, near)
            // Fallback for shared "Business name, Street, City" queries -- e.g. a
            // Google Maps link with no coords, only a place path. Nominatim often
            // returns nothing for the whole string because the leading business
            // name isn't in OSM, even though the address geocodes fine. If we got
            // nothing and there are commas, drop the leading segment(s) and retry.
            if (results.isEmpty() && query.contains(',')) {
                var parts = query.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                var tries = 0
                while (results.isEmpty() && parts.size > 1 && tries < 2) {
                    parts = parts.drop(1)
                    tries++
                    delay(1100) // Nominatim asks for <= 1 request/second
                    results = routingService.geocode(parts.joinToString(", "), geocoderUrl, near)
                }
            }
            _searchResults.value = results
            _searching.value = false
            if (results.isEmpty()) _messages.tryEmit(R.string.nav_no_results)
        }
    }

    fun pickSearchResult(result: GeoResult) {
        // Cancel any in-flight geocode so a late result can't re-open the
        // dropdown on top of the pin we just dropped.
        searchJob?.cancel()
        _searching.value = false
        _searchResults.value = emptyList()
        addWaypoint(result.lat, result.lng, RoutingService.placeLabel(result.name), fit = true)
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _searching.value = false
    }

    // --- Routing -----------------------------------------------------------------

    private var pendingRecomputeFit = false

    /** Called by the screen when the rider starts / stops dragging a stop. */
    fun setUserDragging(dragging: Boolean) {
        if (_userDragging.value == dragging) return
        _userDragging.value = dragging
        // Once the drag ends, flush any recompute the GPS or list reorder
        // wanted to fire mid-drag.
        if (!dragging && recomputeQueuedDuringDrag) {
            recomputeQueuedDuringDrag = false
            scheduleRecompute(fit = pendingRecomputeFit)
            pendingRecomputeFit = false
        }
    }

    private fun scheduleRecompute(fit: Boolean, previewEdge: List<GeoPoint> = emptyList()) {
        if (_userDragging.value) {
            // The rider's finger is on a stop. Don't yank the preview now;
            // remember we owe one and fire it after dragend.
            recomputeQueuedDuringDrag = true
            pendingRecomputeFit = pendingRecomputeFit || fit
            return
        }
        if (navigationEngine.isActive) {
            // While guidance is running the engine is the authoritative
            // source of `_route` via its activeLeg StateFlow (mirrored in
            // the observer above). The location collector still fires
            // here on every GPS shift > ORIGIN_REROUTE_M, but letting it
            // launch a recompute snapshots `_waypoints` AT THAT INSTANT
            // -- and right after an arrival the snapshot can land before
            // the arrival collector marks the just-reached goal passed.
            // The 300 ms-debounced coroutine then writes a leg
            // rider->just-passed-goal back into _route, overwriting the
            // engine's correct rider->next-goal leg. So: bail.
            return
        }
        _routeClean.value = false
        routeJob?.cancel()
        enrichJob?.cancel()
        val dests = _waypoints.value
        val origin = currentLocation.value
        // A route needs the rider's position (origin) and a destination.
        if (dests.isEmpty() || origin == null) {
            lastRouteOrigin = null
            _route.value = null
            _routing.value = false
            _pendingPreview.value = emptyList()
            bumpRender(fit)
            return
        }
        lastRouteOrigin = GeoPoint(origin.latitude, origin.longitude)
        // Full path solves the whole remaining tour (origin -> every
        // non-passed stop) in a single request, so the builder draws one
        // solid line and a whole-tour distance. Next segment solves only
        // the next leg (origin -> first non-passed stop) and leaves the
        // rest to the dashed straight-line preview, lighter on the router.
        // Passed stops are kept in the list but skipped either way.
        val nonPassed = dests.filter { !it.passed }
        if (nonPassed.isEmpty()) {
            lastRouteOrigin = null
            _route.value = null
            _routing.value = false
            _pendingPreview.value = emptyList()
            bumpRender(fit)
            return
        }
        val routedTargets = if (_solveFullPath.value) nonPassed else listOf(nonPassed.first())
        val navWps = listOf(Waypoint(origin.latitude, origin.longitude)) + routedTargets
        val mode = _travelMode.value
        if (mode == TravelMode.STRAIGHT) {
            // Direct/line mode joins the stops with straight segments -- no
            // router, no network, nothing to wait for. Compute it synchronously
            // so the line appears the instant a pin is dropped or moved: no
            // 300 ms debounce, no spinner, no preview.
            _pendingPreview.value = emptyList()
            _route.value = RoutingService.straightLineRoute(routeName, navWps)
                .copy(waypoints = dests)
            _routing.value = false
            bumpRender(fit)
            enrichWaypointNames()
            return
        }
        // Routed modes (bike / walk / car) hit the network. KEEP the existing
        // solid route on screen (don't blank it) and, for an edit, overlay a
        // short gold dashed preview of just the affected edge ([previewEdge]) so
        // the rider sees what's changing; the solid line is replaced only once
        // the new route lands. Debounced so a burst of edits fires one request.
        _pendingPreview.value = previewEdge
        _routing.value = true
        bumpRender(fit)
        routeJob = viewModelScope.launch {
            delay(300)
            val computed = routingService.route(routeName, navWps, mode, routerUrl, avoidances) ?: run {
                _messages.tryEmit(R.string.nav_route_failed)
                RoutingService.straightLineRoute(routeName, navWps)
            }
            // The rider stays out of the listed waypoints; only destinations
            // are listed; the origin lives in the route geometry.
            _route.value = computed.copy(waypoints = dests)
            _pendingPreview.value = emptyList()
            _routing.value = false
            bumpRender(fit)
            enrichWaypointNames()
        }
    }

    /**
     * After a route solves, reverse-geocodes any pin still missing an address
     * (map taps and freshly-moved pins land without one) and fills it in. Runs
     * sequentially to respect Nominatim's ~1 request/second policy and never
     * re-triggers routing; a name change doesn't move the geometry.
     */
    private fun enrichWaypointNames() {
        enrichJob?.cancel()
        val targets = _waypoints.value.withIndex()
            .filter { it.value.name.isBlank() }
            .map { it.index to it.value }
        if (targets.isEmpty()) return
        enrichJob = viewModelScope.launch {
            for ((idx, wp) in targets) {
                val addr = routingService.reverseGeocode(wp.lat, wp.lng, geocoderUrl)
                if (addr != null) {
                    val cur = _waypoints.value
                    // Apply only if that slot still holds the same un-named pin,
                    // the rider may have edited the list while we were resolving.
                    if (idx < cur.size && cur[idx].lat == wp.lat &&
                        cur[idx].lng == wp.lng && cur[idx].name.isBlank()
                    ) {
                        val updated = cur.toMutableList()
                        updated[idx] = updated[idx].copy(name = RoutingService.placeLabel(addr))
                        _waypoints.value = updated
                    }
                }
                delay(1100) // Nominatim asks for <= 1 request/second
            }
            // Reflect the resolved names in the stored route too.
            _route.value = _route.value?.copy(waypoints = _waypoints.value)
        }
    }

    private fun bumpRender(fit: Boolean) {
        _mapRender.value = MapRender(_mapRender.value.version + 1, fit)
    }

    /**
     * Clears the in-memory current route ([CurrentRouteStore]). Used by [clear]
     * and the GPX load-replace flow so the rider's "I'm done with this route"
     * action is honoured across the next Builder open. We deliberately NEVER
     * cache a draft route here (that's done only when navigation actually
     * starts -- see startNavigation), because draft pins are explicitly
     * disposable per the design (the rider saves a GPX if they want them back).
     */
    private fun clearCurrentRoute() {
        currentRouteStore.clear()
    }

    // --- GPX import / export -----------------------------------------------------

    fun loadGpx(uri: Uri) {
        viewModelScope.launch {
            try {
                val gpx = context.contentResolver.openInputStream(uri)?.use { GpxIO.parse(it) }
                if (gpx == null) {
                    _messages.tryEmit(R.string.nav_load_failed)
                    return@launch
                }
                routeName = gpx.name
                when {
                    gpx.waypoints.size >= 1 -> {
                        _waypoints.value = gpx.waypoints.take(MAX_WAYPOINTS)
                        scheduleRecompute(fit = true)
                    }
                    gpx.track.size >= 2 -> {
                        // A track-only GPX: navigate the fixed line as-is.
                        _waypoints.value = listOf(gpx.track.first(), gpx.track.last())
                            .map { Waypoint(it.lat, it.lng) }
                        _route.value = NavRoute(
                            name = gpx.name,
                            waypoints = _waypoints.value,
                            travelMode = TravelMode.STRAIGHT,
                            geometry = gpx.track,
                            maneuvers = emptyList(),
                            totalDistanceM = GeoMath.polylineLengthM(gpx.track)
                        )
                        bumpRender(fit = true)
                    }
                    else -> {
                        _messages.tryEmit(R.string.nav_load_failed)
                        return@launch
                    }
                }
                _routeClean.value = true
            } catch (e: Exception) {
                Log.w("RouteBuilder", "loadGpx failed", e)
                _messages.tryEmit(R.string.nav_load_failed)
            }
        }
    }

    fun saveGpx(uri: Uri) {
        viewModelScope.launch {
            try {
                val route = _route.value
                    ?: RoutingService.straightLineRoute(routeName, _waypoints.value)
                // Keep the .gpx extension even if the rider cleared it in the
                // system save dialog; otherwise the file is hard to spot and
                // won't filter back into the open dialog later.
                val target = ensureGpxExtension(uri)
                context.contentResolver.openOutputStream(target)?.use { GpxIO.write(route, it) }
                _routeClean.value = true
                // Confirm the save: a long file-picker round-trip without any
                // visible feedback feels like nothing happened. Failure is
                // already surfaced below; this is the success counterpart.
                _messages.tryEmit(R.string.nav_route_saved)
            } catch (e: Exception) {
                Log.w("RouteBuilder", "saveGpx failed", e)
                _messages.tryEmit(R.string.nav_save_failed)
            }
        }
    }

    /** Renames the just-created document to append ".gpx" when it is missing. */
    private fun ensureGpxExtension(uri: Uri): Uri {
        val name = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return uri
        if (name.endsWith(".gpx", ignoreCase = true)) return uri
        return runCatching {
            DocumentsContract.renameDocument(context.contentResolver, uri, "$name.gpx")
        }.getOrNull() ?: uri
    }

    // --- Map data ----------------------------------------------------------------

    fun waypointsJson(): String {
        val arr = JSONArray()
        _waypoints.value.forEach { w ->
            arr.put(JSONObject().apply {
                put("lat", w.lat); put("lng", w.lng); put("name", w.name)
                put("radius", w.radiusM ?: defaultRadiusM.toDouble())
                put("passed", w.passed)
            })
        }
        android.util.Log.i(
            "RouteBuilderVM",
            "WP-JSON passedFlags=${_waypoints.value.map { it.passed }}"
        )
        return arr.toString()
    }

    /** Saved Home / Work places as JSON [{lat,lng,kind}] for the map. */
    fun placesJson(): String {
        val arr = JSONArray()
        _home.value?.let {
            arr.put(JSONObject().put("lat", it.lat).put("lng", it.lng).put("kind", "home"))
        }
        _work.value?.let {
            arr.put(JSONObject().put("lat", it.lat).put("lng", it.lng).put("kind", "work"))
        }
        return arr.toString()
    }

    // --- Home / Work presets -------------------------------------------------

    private fun placeFromJson(s: String): Waypoint? = runCatching {
        if (s.isBlank()) null
        else JSONObject(s).let { Waypoint(it.getDouble("lat"), it.getDouble("lng"), it.optString("name")) }
    }.getOrNull()

    private fun placeToJson(w: Waypoint): String =
        JSONObject().put("lat", w.lat).put("lng", w.lng).put("name", w.name).toString()

    private fun savePreset(w: Waypoint, home: Boolean) {
        if (home) _home.value = w else _work.value = w
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(
                if (home) s.copy(navHomeJson = placeToJson(w))
                else s.copy(navWorkJson = placeToJson(w))
            )
        }
    }

    fun clearHome() = clearPreset(home = true)
    fun clearWork() = clearPreset(home = false)

    private fun clearPreset(home: Boolean) {
        if (home) _home.value = null else _work.value = null
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(
                if (home) s.copy(navHomeJson = "") else s.copy(navWorkJson = "")
            )
        }
    }

    /** Saves the rider's current position as the Home / Work preset. */
    fun saveSelfAsHome() = currentLocation.value?.let {
        savePreset(Waypoint(it.latitude, it.longitude), home = true)
    }

    fun saveSelfAsWork() = currentLocation.value?.let {
        savePreset(Waypoint(it.latitude, it.longitude), home = false)
    }

    // Declaration moved up earlier in the file; see the top of the class.

    /**
     * The kind of preset added last ("HOME" / "WORK"), or null if the last
     * waypoint added was a plain stop. The search field hides whichever preset
     * this names; it was just used, so re-suggesting it is noise. Adding any
     * other stop clears this and both suggestions return.
     */
    private val _lastAddedPresetKind = MutableStateFlow<String?>(null)
    val lastAddedPresetKind: StateFlow<String?> = _lastAddedPresetKind.asStateFlow()

    /** Drops a saved Home / Work preset onto the map as the next waypoint. */
    fun addPreset(w: Waypoint, kind: String) {
        addWaypoint(w.lat, w.lng, w.name, fit = true)
        _lastAddedPresetKind.value = kind
    }

    /** Saves a stop already on the route as the Home / Work preset. */
    fun saveWaypointAsHome(index: Int) {
        _waypoints.value.getOrNull(index)?.let { savePreset(it, home = true) }
    }

    fun saveWaypointAsWork(index: Int) {
        _waypoints.value.getOrNull(index)?.let { savePreset(it, home = false) }
    }

    /** True while guidance is running; the screen locks editing then. */
    val navRunning: StateFlow<Boolean> = navigationEngine.navState
        .map { it.active }
        .stateIn(viewModelScope, SharingStarted.Eagerly, navigationEngine.isActive)

    /** Ends the running guidance session (the on-map "Stop navigation" action). */
    fun stopNavigation() = navigationEngine.stop()

    fun geometryJson(): String {
        val arr = JSONArray()
        _route.value?.geometry?.forEach { p ->
            arr.put(JSONArray().put(p.lat).put(p.lng))
        }
        android.util.Log.i(
            "RouteBuilderVM",
            "GEOM-JSON pts=${_route.value?.geometry?.size ?: 0}"
        )
        return arr.toString()
    }

    /** The gold dashed "what's changing" preview edge as [[lat,lng],...] for
     *  the map; empty array outside a routed-fetch window. */
    fun pendingPreviewJson(): String {
        val arr = JSONArray()
        _pendingPreview.value.forEach { p -> arr.put(JSONArray().put(p.lat).put(p.lng)) }
        return arr.toString()
    }

    // --- Charger & places layers -------------------------------------------------

    /** Toggles the ⚡ charger layer; persisted so it's remembered. */
    fun toggleChargers() {
        _showChargers.value = !_showChargers.value
        persistLayers()
        refreshChargers(userInitiated = true)
    }

    /** Toggles the whole "places" group: all off -> all on, otherwise -> off. */
    fun togglePlaces() {
        val turningOn = _placeCats.value.isEmpty()
        _placeCats.value = if (turningOn) PoiKind.PLACES else emptySet()
        persistLayers()
        // First time the rider enables places, point them at the long-press menu.
        if (turningOn && !placesHintShown) {
            placesHintShown = true
            _messages.tryEmit(R.string.nav_pois_places_hint)
            viewModelScope.launch {
                settingsRepository.update(settingsRepository.get().copy(navPlacesHintShown = true))
            }
        }
        refreshPlaces(userInitiated = true)
    }

    /** Long-press menu: toggles one place category on/off. */
    fun setPlaceCategory(kind: PoiKind, on: Boolean) {
        _placeCats.value = if (on) _placeCats.value + kind else _placeCats.value - kind
        persistLayers()
        refreshPlaces(userInitiated = true)
    }

    private fun persistLayers() {
        val chargers = _showChargers.value
        val cats = _placeCats.value.joinToString(",") { it.name }
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(navShowChargers = chargers, navPlaceCategories = cats))
        }
    }

    private fun parsePlaceCats(csv: String): Set<PoiKind> =
        csv.split(',').mapNotNull { s -> PoiKind.PLACES.firstOrNull { it.name == s.trim() } }.toSet()

    /**
     * Refreshes both POI layers (used by the route / location observers). The
     * charger and places layers are otherwise refreshed independently by their
     * own toggles, so neither refetches when the other changes.
     */
    private fun refreshPois(userInitiated: Boolean = false) {
        refreshChargers(userInitiated)
        refreshPlaces(userInitiated)
    }

    /** What the map shows is the union of the two independently-fetched layers. */
    private fun publishPois() {
        _pois.value = (_chargerPois.value + _placePois.value)
            .sortedBy { it.distanceFromRouteM }
            .take(PoiService.MAX_POIS)
    }

    private data class PoiAnchor(val geom: List<GeoPoint>, val center: GeoPoint?, val sig: String)

    /** Where to search: along the route if there is one, else around the rider /
     *  map centre. Null when there's nothing to anchor to yet. */
    private fun poiAnchor(): PoiAnchor? {
        val geom = _route.value?.geometry ?: emptyList()
        val center = currentLocation.value?.let { GeoPoint(it.latitude, it.longitude) }
            ?: _savedView.value?.let { GeoPoint(it.lat, it.lng) }
        if (geom.size < 2 && center == null) return null
        val sig = if (geom.size >= 2) poiSignature(geom)
        else "${round2(center!!.lat)},${round2(center.lng)}"
        return PoiAnchor(geom, center, sig)
    }

    /** The map (WebView) reported new visible bounds after a pan / zoom. Drives
     *  the viewport POI search so chargers + places follow what's on screen. */
    fun onMapViewportChanged(south: Double, west: Double, north: Double, east: Double) {
        _viewportBounds.value = BBox(south, west, north, east)
        // refreshChargers/refreshPlaces no-op when their layer is off, and the
        // signature check inside skips a refetch when the box barely moved.
        refreshPois()
    }

    /** Area to search for a layer capped at [maxKm]: the visible viewport once
     *  the map has reported it, else the route / centre anchor (before the first
     *  pan/zoom). Returns the box + a coarse signature so only a real pan/zoom
     *  (not jitter) triggers a refetch. */
    private fun searchBox(maxKm: Double): Pair<BBox, String>? {
        _viewportBounds.value?.let { vp ->
            val b = PoiService.capBounds(vp, maxKm)
            return b to "vp:${boxSig(b)}"
        }
        val anchor = poiAnchor() ?: return null
        val raw = if (anchor.geom.size >= 2) PoiService.routeBoundingBox(anchor.geom)
        else PoiService.bboxAround(anchor.center!!, PoiService.ROUTE_BUFFER_M)
        return raw?.let { PoiService.capBounds(it, maxKm) to anchor.sig }
    }

    /** ~100 m-resolution signature of a box so tiny map jitter doesn't refetch. */
    private fun boxSig(b: BBox): String {
        fun r(d: Double) = Math.round(d * 1000.0) / 1000.0
        return "${r(b.minLat)},${r(b.minLng)},${r(b.maxLat)},${r(b.maxLng)}"
    }

    /**
     * Refreshes the charger layer. Chargers come from Open Charge Map (a
     * purpose-built EV API, sub-second) when a key is set, otherwise Overpass.
     *
     * [userInitiated] is true only when the rider taps the charger toggle: it
     * always refetches and may surface a failure toast. Background calls (a GPS
     * drift, a route edit) are silent and skip a refetch when the search area
     * hasn't changed. The FAB spinner is the loading cue, so there's no "loading"
     * toast.
     */
    private fun refreshChargers(userInitiated: Boolean = false) {
        if (!_advancedMap.value || !_showChargers.value) {
            chargerJob?.cancel()
            _chargerLoading.value = false
            chargerSig = null
            if (_selectedPoi.value?.kind == PoiKind.CHARGER) _selectedPoi.value = null
            if (_chargerPois.value.isNotEmpty()) { _chargerPois.value = emptyList(); publishPois() }
            return
        }
        val (box, sigBase) = searchBox(PoiService.CHARGER_VIEWPORT_MAX_KM) ?: run {
            chargerSig = null
            if (_chargerPois.value.isNotEmpty()) { _chargerPois.value = emptyList(); publishPois() }
            return
        }
        val useOcm = ocmApiKey.isNotBlank()
        val sig = "$sigBase|charger|$useOcm"
        if (!userInitiated && sig == chargerSig) return
        chargerSig = sig
        chargerJob?.cancel()
        chargerJob = viewModelScope.launch {
            delay(700) // debounce edits and stay polite to the services
            _chargerLoading.value = true
            val center = GeoPoint((box.minLat + box.maxLat) / 2.0, (box.minLng + box.maxLng) / 2.0)
            val result = if (useOcm) {
                // OCM (purpose-built EV API) over the visible box; nearest-to-
                // centre and capped.
                val ocm = ocmService.chargersInBox(box, ocmApiKey)?.let {
                    PoiService.filterNearPoint(it, center, maxDistM = Double.MAX_VALUE)
                }
                // OCM failed (e.g. an invalid key, or the service is down): fall
                // back to OpenStreetMap chargers so the layer still works. A valid
                // key returning no chargers is an empty list, not null, so it
                // won't trigger the fallback.
                ocm ?: poiService.poisInBounds(box, overpassUrl, setOf(PoiKind.CHARGER))
            } else {
                poiService.poisInBounds(box, overpassUrl, setOf(PoiKind.CHARGER))
            }
            _chargerLoading.value = false
            if (result != null) { _chargerPois.value = result; publishPois() }
            if (userInitiated && result == null) _messages.tryEmit(R.string.nav_pois_failed)
        }
    }

    /** Refreshes the places layer (shops / food / rest / sights) from Overpass. */
    private fun refreshPlaces(userInitiated: Boolean = false) {
        if (!_advancedMap.value || _placeCats.value.isEmpty()) {
            placeJob?.cancel()
            _placeLoading.value = false
            placeSig = null
            _selectedPoi.value?.kind?.let { if (it in PoiKind.PLACES) _selectedPoi.value = null }
            if (_placePois.value.isNotEmpty()) { _placePois.value = emptyList(); publishPois() }
            return
        }
        val (box, sigBase) = searchBox(PoiService.PLACES_VIEWPORT_MAX_KM) ?: run {
            placeSig = null
            if (_placePois.value.isNotEmpty()) { _placePois.value = emptyList(); publishPois() }
            return
        }
        val cats = _placeCats.value
        val sig = "$sigBase|${cats.joinToString(",")}"
        if (!userInitiated && sig == placeSig) return
        placeSig = sig
        placeJob?.cancel()
        placeJob = viewModelScope.launch {
            delay(700)
            _placeLoading.value = true
            val result = poiService.poisInBounds(box, overpassUrl, cats)
            _placeLoading.value = false
            if (result != null) { _placePois.value = result; publishPois() }
            if (userInitiated) {
                if (result == null) _messages.tryEmit(R.string.nav_pois_failed)
                else if (result.isEmpty()) _messages.tryEmit(R.string.nav_pois_none)
            }
        }
    }

    private fun round2(d: Double) = Math.round(d * 100.0) / 100.0

    /** Coarse (~1 km) bbox signature so small edits don't trigger a refetch. */
    private fun poiSignature(geom: List<GeoPoint>): String {
        val b = PoiService.routeBoundingBox(geom) ?: return ""
        fun r(d: Double) = Math.round(d * 100.0) / 100.0
        return "${r(b.minLat)},${r(b.minLng)},${r(b.maxLat)},${r(b.maxLng)}"
    }

    fun onPoiTapped(id: Long) {
        _selectedPoi.value = _pois.value.firstOrNull { it.id == id }
    }

    fun dismissPoiDetails() {
        _selectedPoi.value = null
    }

    /**
     * Adds a POI as a stop at the position that adds the least extra distance to
     * the route: **between** the two stops whose leg passes nearest the place
     * (a detour, e.g. `1 ── * ── 2`), or **appended** after the last stop when
     * the place lies beyond the route's end (`1 ── 2 ── *`). Same cheapest-
     * insertion idea as a route-line tap, but it also considers appending, since
     * a charger can sit past the final stop. No-op while guidance is running.
     */
    fun addPoiAsStop(poi: PointOfInterest) {
        _selectedPoi.value = null
        if (navigationEngine.isActive) return
        val dests = _waypoints.value
        if (dests.size >= MAX_WAYPOINTS) {
            _messages.tryEmit(R.string.nav_max_stops)
            return
        }
        val name = poi.name.ifBlank { poi.brand }
        if (dests.isEmpty()) {
            addWaypoint(poi.lat, poi.lng, name, fit = false)
            return
        }
        val point = GeoPoint(poi.lat, poi.lng)
        val insertIndex = cheapestInsertIndex(point)
        val origin = currentLocation.value?.let { GeoPoint(it.latitude, it.longitude) }
        val left = if (insertIndex == 0) origin else dests[insertIndex - 1].point()
        val right = dests.getOrNull(insertIndex)?.point()
        val list = dests.toMutableList()
        list.add(insertIndex, Waypoint(poi.lat, poi.lng, name))
        _waypoints.value = list
        _lastAddedPresetKind.value = null
        cacheDraft()
        val edge = listOfNotNull(left, point, right)
        scheduleRecompute(fit = false, previewEdge = if (edge.size >= 2) edge else emptyList())
    }

    /**
     * The destination-list index at which inserting [point] adds the least extra
     * straight-line distance, considering every gap between consecutive stops
     * (the rider's live position is the implicit first point) AND appending
     * after the last stop. Returns [dests.size] to append.
     */
    private fun cheapestInsertIndex(point: GeoPoint): Int {
        val dests = _waypoints.value
        val origin = currentLocation.value?.let { GeoPoint(it.latitude, it.longitude) }
        val chain = buildList {
            if (origin != null) add(origin)
            dests.forEach { add(it.point()) }
        }
        if (chain.isEmpty()) return 0
        val originOffset = if (origin != null) 1 else 0
        var bestIdx = dests.size
        // Appending extends the route by the leg from the last point to here.
        var bestCost = GeoMath.distanceM(chain.last(), point)
        // Inserting between a and b costs the detour d(a,P)+d(P,b)-d(a,b).
        for (i in 0 until chain.size - 1) {
            val a = chain[i]; val b = chain[i + 1]
            val cost = GeoMath.distanceM(a, point) + GeoMath.distanceM(point, b) -
                GeoMath.distanceM(a, b)
            if (cost < bestCost) {
                bestCost = cost
                bestIdx = (i + 1 - originOffset).coerceIn(0, dests.size)
            }
        }
        return bestIdx
    }

    /** POIs as [{id,lat,lng,kind,name}] for the map's faint layer. */
    fun poisJson(): String {
        val arr = JSONArray()
        _pois.value.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("lat", p.lat); put("lng", p.lng)
                put("kind", p.kind.name); put("name", p.name)
            })
        }
        return arr.toString()
    }

    // --- Starting navigation -----------------------------------------------------

    /**
     * Hands a route to the [NavigationEngine] and runs [onStarted] so the
     * screen can close the builder. Position 0 is always the rider: when the
     * start pin isn't already the live location, the rider's current position
     * is prepended as the origin, so navigating to a single dropped pin works.
     */
    fun startNavigation(mode: NavMode, onStarted: () -> Unit) {
        val dests = _waypoints.value
        if (dests.isEmpty()) {
            _messages.tryEmit(R.string.nav_need_destination)
            return
        }
        val loc = currentLocation.value
        if (loc == null) {
            _messages.tryEmit(R.string.nav_no_location)
            return
        }
        // Close the builder immediately, before guidance flips on, so the
        // rider never sees the button flash to "Stop navigation".
        onStarted()
        viewModelScope.launch {
            val tMode = _travelMode.value
            // Full path hands the engine the WHOLE remaining tour (origin ->
            // every non-passed stop) so it follows one solved polyline with
            // turn-by-turn across all stops, announcing each intermediate stop
            // as it is crossed. STRAIGHT stays leg-by-leg even in Full path: a
            // straight route carries no maneuvers, so the engine's guideHoming
            // would aim straight at the final stop and skip the intermediate
            // ones -- the proven leg-by-leg flow advances stop by stop instead.
            // Next segment is always leg-by-leg ([origin, first non-passed]).
            val fullRoute = _solveFullPath.value && tMode != TravelMode.STRAIGHT
            val targets = if (fullRoute) {
                dests.filter { !it.passed }.ifEmpty { listOf(dests.first()) }
            } else {
                listOf(dests.firstOrNull { !it.passed } ?: dests.first())
            }
            val legWps = listOf(Waypoint(loc.latitude, loc.longitude)) + targets
            val navRoute = if (tMode == TravelMode.STRAIGHT) {
                RoutingService.straightLineRoute(routeName, legWps)
            } else {
                routingService.route(routeName, legWps, tMode, routerUrl, avoidances)
                    ?: RoutingService.straightLineRoute(routeName, legWps)
            }
            // Mirror the new leg into _route so the map redraws with the
            // single-leg solid line + dashed-preview view immediately, not
            // whatever multi-stop preview was there from the builder. The
            // full destination list lives in waypoints so dashed previews
            // still chain through them.
            _route.value = navRoute.copy(waypoints = dests)
            _routing.value = false
            bumpRender(fit = false)
            navigationEngine.start(navRoute, mode)
            // Keep the in-memory builder route in sync with what's being navigated
            // (minus the prepended rider pin) so re-opening the builder during
            // guidance shows that route with its real travel mode, not a stale
            // draft. Stops + travel mode only -- geometry/maneuvers are recomputed
            // on the next open. The engine clears this once the trip finishes.
            currentRouteStore.set(
                navRoute.copy(
                    waypoints = dests,
                    geometry = emptyList(),
                    maneuvers = emptyList(),
                    totalDistanceM = 0.0
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        routeJob?.cancel()
        searchJob?.cancel()
        enrichJob?.cancel()
    }
}
