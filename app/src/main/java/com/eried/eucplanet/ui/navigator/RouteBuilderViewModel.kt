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
import com.eried.eucplanet.nav.GeoMath
import com.eried.eucplanet.nav.GeoResult
import com.eried.eucplanet.nav.NavigationEngine
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Backing state for the Route Builder screen: the ordered waypoint list, the
 * resolved [NavRoute], address search, GPX import/export, and persistence of
 * the "current route" so it survives leaving the screen.
 *
 * Whenever the waypoints or travel mode change the route is recomputed (with a
 * short debounce so dragging a pin doesn't fire a request per frame), and once
 * it solves, pins still missing an address are reverse-geocoded.
 */
@HiltViewModel
class RouteBuilderViewModel @Inject constructor(
    private val routingService: RoutingService,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val navigationEngine: NavigationEngine,
    private val incomingShareRepository:
        com.eried.eucplanet.data.repository.IncomingShareRepository,
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

        /**
         * How long a navigation route persists on disk for the Builder
         * to restore on next open. Beyond this we assume the rider has
         * moved on (or the route came back via Google Auto Backup from
         * a previous install) and starting fresh is friendlier.
         */
        private const val RESTORE_TTL_MS: Long = 24 * 60 * 60 * 1000L
    }

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

    private val _route = MutableStateFlow<NavRoute?>(null)
    val route: StateFlow<NavRoute?> = _route.asStateFlow()

    private val _travelMode = MutableStateFlow(TravelMode.CYCLING)
    val travelMode: StateFlow<TravelMode> = _travelMode.asStateFlow()

    private val _searchResults = MutableStateFlow<List<GeoResult>>(emptyList())
    val searchResults: StateFlow<List<GeoResult>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _routing = MutableStateFlow(false)
    val routing: StateFlow<Boolean> = _routing.asStateFlow()

    /** Route Builder base map style: DARK / LIGHT / SATELLITE. */
    private val _mapType = MutableStateFlow("DARK")
    val mapType: StateFlow<String> = _mapType.asStateFlow()

    /** Persisted custom rider-marker photo as a base64 data URL, or null
     *  when the rider hasn't set one (fall back to the default puck). */
    private val _userMarkerPhoto = MutableStateFlow<String?>(null)
    val userMarkerPhoto: StateFlow<String?> = _userMarkerPhoto.asStateFlow()

    /** Last (centre, zoom) the rider was looking at on the map. Cached
     *  in-memory only (per VM lifetime) so a sub-navigation to Settings and
     *  back doesn't reset the view to the world-fit default. The JS side
     *  posts this back on every moveend / zoomend. */
    data class MapView(val lat: Double, val lng: Double, val zoom: Float)
    private val _savedView = MutableStateFlow<MapView?>(null)
    val savedView: StateFlow<MapView?> = _savedView.asStateFlow()

    fun setSavedView(lat: Double, lng: Double, zoom: Float) {
        _savedView.value = MapView(lat, lng, zoom)
    }

    /** Sets the rider-marker photo (or clears it) and persists to settings. */
    fun setUserMarkerPhoto(dataUrl: String?) {
        _userMarkerPhoto.value = dataUrl
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(navUserMarkerPhotoDataUrl = dataUrl))
        }
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

    private var routeJob: Job? = null
    private var searchJob: Job? = null
    private var enrichJob: Job? = null
    private var lastRouteOrigin: GeoPoint? = null
    private var geocoderUrl = RoutingService.DEFAULT_GEOCODER
    private var routerUrl = RoutingService.DEFAULT_ROUTER
    private var routeName = context.getString(R.string.nav_default_route_name)

    // Declared above init{}: the init coroutine can resume synchronously (an
    // already-cached settings read) and touch these before properties declared
    // lower in the class would have been initialised — that was a startup crash.
    private val _home = MutableStateFlow<Waypoint?>(null)
    val home: StateFlow<Waypoint?> = _home.asStateFlow()
    private val _work = MutableStateFlow<Waypoint?>(null)
    val work: StateFlow<Waypoint?> = _work.asStateFlow()

    /** Default arrival radius (metres) for waypoints with no custom value. */
    private var defaultRadiusM: Int = 40

    /** True right after a load or save — Clear route then needs no confirm.
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
        viewModelScope.launch {
            val s = settingsRepository.get()
            geocoderUrl = s.navGeocoderUrl.ifBlank { RoutingService.DEFAULT_GEOCODER }
            routerUrl = RoutingService.effectiveRouterUrl(s.navRouterUrl)
            _travelMode.value = TravelMode.fromName(s.navDefaultTravelMode)
            _mapType.value = s.navMapType.ifBlank { "LIGHT" }
            defaultRadiusM = s.navArrivalRadiusM
            _home.value = placeFromJson(s.navHomeJson)
            _work.value = placeFromJson(s.navWorkJson)
            _userMarkerPhoto.value = s.navUserMarkerPhotoDataUrl
            // navCurrentRouteJson is now only ever written at the moment
            // navigation actually STARTS (and trimmed as goals are reached,
            // cleared on arrival / explicit Clear). Draft pins live only
            // in-memory -- this stops Google Auto Backup from shipping a
            // long-forgotten route to the cloud and silently restoring it
            // as ghost stops after a reinstall.
            //
            // Restore only when the saved route is one the rider actually
            // started in this install: either guidance is still running,
            // OR the route was saved within RESTORE_TTL_MS. Older entries
            // typically came back via Google Auto Backup from a previous
            // install and would otherwise appear as ghost stops with a
            // matching ghost travel mode -- the bug we are fixing.
            //
            // When the gate passes we restore everything, including
            // travelMode, because the rider explicitly chose that mode
            // when they started this navigation and the Builder should
            // mirror the active session faithfully.
            val now = System.currentTimeMillis()
            val isFresh = s.navCurrentRouteSavedAt != 0L &&
                (now - s.navCurrentRouteSavedAt) < RESTORE_TTL_MS
            if (navigationEngine.isActive || isFresh) {
                NavRoute.fromJson(s.navCurrentRouteJson)?.let { existing ->
                    if (existing.waypoints.isNotEmpty() || existing.geometry.isNotEmpty()) {
                        routeName = existing.name
                        _travelMode.value = existing.travelMode
                        _waypoints.value = existing.waypoints
                        _route.value = existing
                        bumpRender(fit = true)
                    }
                }
            }
        }
        // While navigation is running, drop already-reached stops from the
        // builder's visible waypoint list so the map matches reality. The
        // engine's navState carries the index of the *next* unvisited goal;
        // each time that advances, we trim the corresponding stops from the
        // head of [_waypoints]. The map redraws automatically from the
        // bumpRender() call inside the trim. When nav stops, the saved
        // navCurrentRouteJson holds whatever is left, so the next time the
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
            navigationEngine.navState.collect { nav ->
                if (!nav.active || !nav.arrived) {
                    arrivalProcessed = false
                    return@collect
                }
                if (arrivalProcessed) return@collect
                arrivalProcessed = true
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
                // Mirror the new passed flags to the on-disk saved route so a
                // VM re-creation (rider leaves and re-enters the navigator
                // tab) doesn't blow them away when init re-reads the JSON.
                viewModelScope.launch {
                    runCatching {
                        val current = _route.value ?: return@runCatching
                        val s = settingsRepository.get()
                        settingsRepository.update(
                            s.copy(
                                navCurrentRouteJson =
                                    current.copy(waypoints = updated).toJson().toString(),
                                navCurrentRouteSavedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
                val nextNonPassed = updated.firstOrNull { !it.passed }
                if (nextNonPassed == null) return@collect
                val originLoc = currentLocation.value ?: return@collect
                val originWp = Waypoint(originLoc.latitude, originLoc.longitude)
                val mode = _travelMode.value
                val legWps = listOf(originWp, nextNonPassed)
                viewModelScope.launch {
                    val computed = if (mode == TravelMode.STRAIGHT) {
                        RoutingService.straightLineRoute(routeName, legWps)
                    } else {
                        routingService.route(routeName, legWps, mode, routerUrl)
                            ?: RoutingService.straightLineRoute(routeName, legWps)
                    }
                    android.util.Log.i(
                        "RouteBuilderVM",
                        "ARRIVE-NEW-LEG nextStop=(${nextNonPassed.lat},${nextNonPassed.lng}) " +
                            "originUsed=(${originLoc.latitude},${originLoc.longitude}) " +
                            "legGeomPts=${computed.geometry.size} legDistM=${"%.1f".format(computed.totalDistanceM)}"
                    )
                    _route.value = computed.copy(waypoints = updated.filter { !it.passed })
                    _routing.value = false
                    bumpRender(fit = false)
                    navigationEngine.advanceLeg(computed)
                }
            }
        }
        // Position 0 is always the rider, so a fresh fix shifts the route's
        // origin — recompute the preview once they have moved far enough.
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
    }

    // --- Waypoint editing --------------------------------------------------------

    /**
     * The rider tapped their own position marker on the map — hint how to make
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
        val lat = req.lat
        val lng = req.lng
        if (lat != null && lng != null) {
            addWaypoint(lat, lng, name = req.label.orEmpty(), fit = true)
            return
        }
        val q = req.query
        if (q.isNullOrBlank()) return
        viewModelScope.launch {
            val origin = currentLocation.value?.let {
                GeoPoint(it.latitude, it.longitude)
            }
            val results = routingService.geocode(q, geocoderUrl, origin)
            val first = results.firstOrNull()
            if (first != null) {
                addWaypoint(first.lat, first.lng, name = first.name, fit = true)
            } else {
                _messages.tryEmit(R.string.nav_search_no_results)
            }
        }
    }

    fun addWaypoint(lat: Double, lng: Double, name: String = "", fit: Boolean = false) {
        if (_waypoints.value.size >= MAX_WAYPOINTS) {
            _messages.tryEmit(R.string.nav_max_stops)
            return
        }
        _waypoints.value = _waypoints.value + Waypoint(lat, lng, name)
        // A plain stop was just added — clear the "last preset" memory so the
        // Home / Work search suggestions both come back. addPreset() re-sets it.
        _lastAddedPresetKind.value = null
        scheduleRecompute(fit = fit)
    }

    /** Called when the user drags a pin on the map. */
    fun moveWaypoint(index: Int, lat: Double, lng: Double) {
        val list = _waypoints.value.toMutableList()
        if (index !in list.indices) return
        // A moved pin's address is now stale — clear it so the list shows just
        // the role until the next route solves and re-resolves the address.
        list[index] = list[index].copy(lat = lat, lng = lng, name = "")
        _waypoints.value = list
        scheduleRecompute(fit = false)
    }

    fun removeWaypoint(index: Int) {
        val list = _waypoints.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _waypoints.value = list
        scheduleRecompute(fit = false)
    }

    fun reorderWaypoints(from: Int, to: Int) {
        val list = _waypoints.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _waypoints.value = list
        scheduleRecompute(fit = false)
    }

    fun setTravelMode(mode: TravelMode) {
        if (_travelMode.value == mode) return
        _travelMode.value = mode
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(navDefaultTravelMode = mode.name))
        }
        scheduleRecompute(fit = false)
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
        clearPersistedRoute()
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
        // No toast on a map-style change — the change is its own feedback.
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
            _searchResults.value = routingService.geocode(query, geocoderUrl, near)
            _searching.value = false
            if (_searchResults.value.isEmpty()) _messages.tryEmit(R.string.nav_no_results)
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

    private fun scheduleRecompute(fit: Boolean) {
        if (_userDragging.value) {
            // The rider's finger is on a stop. Don't yank the preview now;
            // remember we owe one and fire it after dragend.
            recomputeQueuedDuringDrag = true
            pendingRecomputeFit = pendingRecomputeFit || fit
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
            bumpRender(fit)
            return
        }
        lastRouteOrigin = GeoPoint(origin.latitude, origin.longitude)
        // Always solve only the next leg (origin -> first non-passed
        // stop), in both navigation and route-building. Passed stops
        // are kept in the list but skipped here; subsequent stops are
        // drawn as a straight-line dashed preview, never sent to the
        // router.
        val nextNonPassed = dests.firstOrNull { !it.passed }
        if (nextNonPassed == null) {
            lastRouteOrigin = null
            _route.value = null
            _routing.value = false
            bumpRender(fit)
            return
        }
        val routedTargets = listOf(nextNonPassed)
        val navWps = listOf(Waypoint(origin.latitude, origin.longitude)) + routedTargets
        // Drop the stale solution; show the dashed preview + spinner meanwhile.
        _route.value = null
        _routing.value = true
        bumpRender(fit)
        routeJob = viewModelScope.launch {
            delay(300)
            val mode = _travelMode.value
            val computed = if (mode == TravelMode.STRAIGHT) {
                RoutingService.straightLineRoute(routeName, navWps)
            } else {
                routingService.route(routeName, navWps, mode, routerUrl) ?: run {
                    _messages.tryEmit(R.string.nav_route_failed)
                    RoutingService.straightLineRoute(routeName, navWps)
                }
            }
            // The rider stays out of the listed waypoints — only destinations
            // are listed; the origin lives in the route geometry.
            _route.value = computed.copy(waypoints = dests)
            _routing.value = false
            bumpRender(fit)
            enrichWaypointNames()
        }
    }

    /**
     * After a route solves, reverse-geocodes any pin still missing an address
     * (map taps and freshly-moved pins land without one) and fills it in. Runs
     * sequentially to respect Nominatim's ~1 request/second policy and never
     * re-triggers routing — a name change doesn't move the geometry.
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
                    // Apply only if that slot still holds the same un-named pin —
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
     * Clears the disk-side current-route snapshot. Used by [clear] and the
     * GPX load-replace flow so the rider's "I'm done with this route"
     * action is honoured across the next Builder open. We deliberately
     * NEVER persist a draft route here (that's done only when navigation
     * actually starts -- see startNavigation), because:
     *   * draft pins are explicitly disposable per the design (rider saves
     *     a GPX if they want them back),
     *   * persisting them caused them to be uploaded by Google Auto Backup
     *     and silently restored as ghost stops on reinstall.
     */
    private fun clearPersistedRoute() {
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(
                s.copy(navCurrentRouteJson = null, navCurrentRouteSavedAt = 0L)
            )
        }
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
                // system save dialog — otherwise the file is hard to spot and
                // won't filter back into the open dialog later.
                val target = ensureGpxExtension(uri)
                context.contentResolver.openOutputStream(target)?.use { GpxIO.write(route, it) }
                _routeClean.value = true
                // Confirm the save — a long file-picker round-trip without any
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

    // Declaration moved up earlier in the file — see the top of the class.

    /**
     * The kind of preset added last ("HOME" / "WORK"), or null if the last
     * waypoint added was a plain stop. The search field hides whichever preset
     * this names — it was just used, so re-suggesting it is noise. Adding any
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

    /** True while guidance is running — the screen locks editing then. */
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
        return arr.toString()
    }

    // --- Starting navigation -----------------------------------------------------

    /**
     * Hands a route to the [NavigationEngine] and runs [onStarted] so the
     * screen can close the builder. Position 0 is always the rider: when the
     * start pin isn't already the live location, the rider's current position
     * is prepended as the origin — so navigating to a single dropped pin works.
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
        // Close the builder immediately — before guidance flips on — so the
        // rider never sees the button flash to "Stop navigation".
        onStarted()
        viewModelScope.launch {
            // Single-leg routing on Start, same as scheduleRecompute --
            // the engine is handed [origin, first non-passed stop] only.
            // Without this, the initial nav frame briefly showed the full
            // multi-stop solid route before the first advanceLeg trimmed
            // it. Subsequent stops still render as the dashed orange
            // preview, fed off the full _waypoints list.
            val nextNonPassed = dests.firstOrNull { !it.passed } ?: dests.first()
            val legWps = listOf(
                Waypoint(loc.latitude, loc.longitude),
                nextNonPassed
            )
            val tMode = _travelMode.value
            val navRoute = if (tMode == TravelMode.STRAIGHT) {
                RoutingService.straightLineRoute(routeName, legWps)
            } else {
                routingService.route(routeName, legWps, tMode, routerUrl)
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
            // Keep the saved builder route in sync with what's being navigated
            // (minus the prepended rider pin) so re-opening the builder during
            // guidance shows that route with its real travel mode, not a stale
            // draft. The engine clears this again once the trip is finished.
            runCatching {
                val s = settingsRepository.get()
                settingsRepository.update(
                    s.copy(
                        navCurrentRouteJson =
                            navRoute.copy(waypoints = dests).toJson().toString(),
                        navCurrentRouteSavedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        routeJob?.cancel()
        searchJob?.cancel()
        enrichJob?.cancel()
    }
}
