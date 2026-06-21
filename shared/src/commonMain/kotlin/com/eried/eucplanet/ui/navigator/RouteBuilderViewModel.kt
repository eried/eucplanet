package com.eried.eucplanet.ui.navigator

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavMode
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.location.GpsFix
import com.eried.eucplanet.nav.CurrentRouteStore
import com.eried.eucplanet.nav.GeoMath
import com.eried.eucplanet.nav.GeoResult
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.nav.RoutingService
import com.eried.eucplanet.util.GpxIO
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Backing state for the Route Builder screen — faithful port of Android's
 * RouteBuilderViewModel (the ordered waypoint list, resolved [NavRoute], debounced
 * routing, address search, Home/Work presets, GPX import/export, and the in-memory
 * current route). Adapted to the iOS seams: Android ViewModel/Hilt → plain class +
 * [scope]; Location → [GpsFix]; SettingsRepository → [settings]/[updateSettings];
 * org.json → kotlinx; GPX file Uris → String content (wired to the iOS file layer).
 */
class RouteBuilderViewModel(
    private val routingService: RoutingService,
    private val currentRouteStore: CurrentRouteStore,
    private val navigationEngine: NavigationEngine,
    private val location: StateFlow<GpsFix?>,
    private val settings: StateFlow<AppSettings>,
    private val updateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val startLocation: () -> Unit,
    private val scope: CoroutineScope,
) {
    companion object {
        const val MAX_WAYPOINTS = 25
        private const val ORIGIN_REROUTE_M = 25.0
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

    private val _route = MutableStateFlow<NavRoute?>(null)
    val route: StateFlow<NavRoute?> = _route.asStateFlow()

    private val _pendingPreview = MutableStateFlow<List<GeoPoint>>(emptyList())
    val pendingPreview: StateFlow<List<GeoPoint>> = _pendingPreview.asStateFlow()

    private val _travelMode = MutableStateFlow(TravelMode.CYCLING)
    val travelMode: StateFlow<TravelMode> = _travelMode.asStateFlow()

    private val _solveFullPath = MutableStateFlow(true)
    val solveFullPath: StateFlow<Boolean> = _solveFullPath.asStateFlow()

    val tourDistanceM: StateFlow<Double?> =
        combine(_route, _waypoints, _solveFullPath) { route, wps, full ->
            val routed = route?.totalDistanceM ?: 0.0
            if (route == null || routed <= 0.0) return@combine null
            if (full) return@combine routed
            val nonPassed = wps.filter { !it.passed }
            if (nonPassed.size <= 1) return@combine routed
            routed + GeoMath.polylineLengthM(nonPassed.map { it.point() })
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _searchResults = MutableStateFlow<List<GeoResult>>(emptyList())
    val searchResults: StateFlow<List<GeoResult>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _routing = MutableStateFlow(false)
    val routing: StateFlow<Boolean> = _routing.asStateFlow()

    private val _mapType = MutableStateFlow("DARK")
    val mapType: StateFlow<String> = _mapType.asStateFlow()

    /** Custom rider-marker photo — not yet wired on iOS (defaults to the puck). */
    val userMarkerPhoto: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    data class MapView(val lat: Double, val lng: Double, val zoom: Float)
    private val _savedView = MutableStateFlow<MapView?>(null)
    val savedView: StateFlow<MapView?> = _savedView.asStateFlow()

    fun setSavedView(lat: Double, lng: Double, zoom: Float) { _savedView.value = MapView(lat, lng, zoom) }
    fun setUserMarkerPhoto(dataUrl: String?) { /* no-op until the iOS marker store lands */ }

    data class MapRender(val version: Int, val fit: Boolean)
    private val _mapRender = MutableStateFlow(MapRender(0, false))
    val mapRender: StateFlow<MapRender> = _mapRender.asStateFlow()

    /** One-shot user messages shown as snackbars. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 6)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val currentLocation: StateFlow<GpsFix?> = location

    val imperialUnits: StateFlow<Boolean> = settings
        .map { it.unitDistance == "mi" || it.unitDistance == "ft" }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var routeJob: Job? = null
    private var searchJob: Job? = null
    private var enrichJob: Job? = null
    private var lastRouteOrigin: GeoPoint? = null
    private var geocoderUrl = RoutingService.DEFAULT_GEOCODER
    private var routerUrl = RoutingService.DEFAULT_ROUTER
    private var routeName = "Route"

    private val _home = MutableStateFlow<Waypoint?>(null)
    val home: StateFlow<Waypoint?> = _home.asStateFlow()
    private val _work = MutableStateFlow<Waypoint?>(null)
    val work: StateFlow<Waypoint?> = _work.asStateFlow()

    private var defaultRadiusM: Int = 40

    private val _routeClean = MutableStateFlow(false)
    val routeClean: StateFlow<Boolean> = _routeClean.asStateFlow()

    private val _userDragging = MutableStateFlow(false)
    private var recomputeQueuedDuringDrag = false
    private var pendingRecomputeFit = false

    private val _lastAddedPresetKind = MutableStateFlow<String?>(null)
    val lastAddedPresetKind: StateFlow<String?> = _lastAddedPresetKind.asStateFlow()

    val navRunning: StateFlow<Boolean> = navigationEngine.navState
        .map { it.active }
        .stateIn(scope, SharingStarted.Eagerly, navigationEngine.isActive)

    init {
        startLocation()
        // Restore settings + the in-memory draft route.
        scope.launch {
            val s = settings.value
            geocoderUrl = s.navGeocoderUrl.ifBlank { RoutingService.DEFAULT_GEOCODER }
            routerUrl = RoutingService.effectiveRouterUrl(s.navRouterUrl)
            _travelMode.value = TravelMode.fromName(s.navDefaultTravelMode)
            _mapType.value = s.navMapType.ifBlank { "DARK" }
            defaultRadiusM = s.navArrivalRadiusM
            _solveFullPath.value = s.navSolveFullPath
            _home.value = placeFromJson(s.navHomeJson)
            _work.value = placeFromJson(s.navWorkJson)
            if (navigationEngine.isActive || currentRouteStore.get() != null) {
                currentRouteStore.get()?.let { existing ->
                    if (existing.waypoints.isNotEmpty() || existing.geometry.isNotEmpty()) {
                        routeName = existing.name
                        _travelMode.value = existing.travelMode
                        _waypoints.value = existing.waypoints
                        if (existing.geometry.isNotEmpty()) _route.value = existing
                        bumpRender(fit = true)
                        if (!navigationEngine.isActive) scheduleRecompute(fit = false)
                    }
                }
            }
        }
        // Mirror the engine's active leg into _route while guidance runs.
        scope.launch {
            navigationEngine.activeLeg.collect { leg ->
                if (leg == null) return@collect
                _route.value = leg.copy(waypoints = _waypoints.value.ifEmpty { leg.waypoints })
                _routing.value = false
                bumpRender(fit = false)
            }
        }
        // Mark stops passed as the engine reports arrivals.
        var arrivalProcessed = false
        scope.launch {
            navigationEngine.navState.collect { nav ->
                if (!nav.active || !nav.arrived) { arrivalProcessed = false; return@collect }
                if (arrivalProcessed) return@collect
                arrivalProcessed = true
                val current = _waypoints.value
                val nextIdx = current.indexOfFirst { !it.passed }
                if (nextIdx < 0) return@collect
                val updated = current.toMutableList().apply { this[nextIdx] = this[nextIdx].copy(passed = true) }
                _waypoints.value = updated
                bumpRender(fit = false)
                if (updated.firstOrNull { !it.passed } == null) {
                    _route.value = null; _routing.value = false; bumpRender(fit = false); return@collect
                }
                _route.value = _route.value?.copy(waypoints = updated)
                bumpRender(fit = false)
            }
        }
        // The rider is route origin; a fresh fix shifts it — recompute when moved enough.
        scope.launch {
            location.collect { loc ->
                if (loc == null || _waypoints.value.isEmpty()) return@collect
                val last = lastRouteOrigin
                val moved = last == null ||
                    GeoMath.distanceM(GeoPoint(loc.lat, loc.lng), last) > ORIGIN_REROUTE_M
                if (moved) scheduleRecompute(fit = false)
            }
        }
        // Full path / Next segment lives in settings; observe it live.
        scope.launch {
            settings.collect { s ->
                if (_solveFullPath.value != s.navSolveFullPath) {
                    _solveFullPath.value = s.navSolveFullPath
                    if (!navigationEngine.isActive) scheduleRecompute(fit = false)
                }
            }
        }
    }

    // --- Waypoint editing --------------------------------------------------------

    fun notifyTapOnSelf() { _messages.tryEmit("Tap the map to add a stop") }

    fun addWaypoint(lat: Double, lng: Double, name: String = "", fit: Boolean = false) {
        if (_waypoints.value.size >= MAX_WAYPOINTS) { _messages.tryEmit("Maximum stops reached"); return }
        val before = _waypoints.value
        val neighbor = before.lastOrNull { !it.passed }?.point()
            ?: location.value?.let { GeoPoint(it.lat, it.lng) }
        _waypoints.value = before + Waypoint(lat, lng, name)
        _lastAddedPresetKind.value = null
        cacheDraft()
        val edge = if (neighbor != null) listOf(neighbor, GeoPoint(lat, lng)) else emptyList()
        scheduleRecompute(fit = fit, previewEdge = edge)
    }

    fun insertWaypointOnRoute(lat: Double, lng: Double) {
        if (navigationEngine.isActive) return
        val dests = _waypoints.value
        if (dests.size >= MAX_WAYPOINTS) { _messages.tryEmit("Maximum stops reached"); return }
        if (dests.isEmpty()) { addWaypoint(lat, lng); return }
        val tap = GeoPoint(lat, lng)
        val origin = location.value?.let { GeoPoint(it.lat, it.lng) }
        val chain = buildList { if (origin != null) add(origin); dests.forEach { add(it.point()) } }
        val originOffset = if (origin != null) 1 else 0
        var bestSeg = 0
        var bestDist = Double.MAX_VALUE
        for (i in 0 until chain.size - 1) {
            val d = GeoMath.nearestOnPolyline(tap, listOf(chain[i], chain[i + 1]))?.distanceM ?: Double.MAX_VALUE
            if (d < bestDist) { bestDist = d; bestSeg = i }
        }
        val insertIndex = (bestSeg + 1 - originOffset).coerceIn(0, dests.size)
        val left = if (insertIndex == 0) origin else dests[insertIndex - 1].point()
        val right = dests.getOrNull(insertIndex)?.point()
        val list = dests.toMutableList()
        list.add(insertIndex, Waypoint(lat, lng))
        _waypoints.value = list
        _lastAddedPresetKind.value = null
        cacheDraft()
        val edge = listOfNotNull(left, GeoPoint(lat, lng), right)
        scheduleRecompute(fit = false, previewEdge = if (edge.size >= 2) edge else emptyList())
    }

    fun moveWaypoint(index: Int, lat: Double, lng: Double) {
        val list = _waypoints.value.toMutableList()
        if (index !in list.indices) return
        val origin = location.value?.let { GeoPoint(it.lat, it.lng) }
        val left = if (index == 0) origin else list[index - 1].point()
        val right = list.getOrNull(index + 1)?.point()
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
        updateSettings { it.copy(navDefaultTravelMode = mode.name) }
        cacheDraft()
        scheduleRecompute(fit = false)
    }

    private fun cacheDraft() {
        if (navigationEngine.isActive) return
        val wps = _waypoints.value
        if (wps.isEmpty()) { currentRouteStore.clear(); return }
        currentRouteStore.set(
            NavRoute(routeName, wps, _travelMode.value, emptyList(), emptyList(), 0.0)
        )
    }

    fun clear() {
        routeJob?.cancel(); enrichJob?.cancel()
        _waypoints.value = emptyList()
        _route.value = null
        _routing.value = false
        _lastAddedPresetKind.value = null
        lastRouteOrigin = null
        bumpRender(fit = false)
        currentRouteStore.clear()
    }

    fun recenterOnUser(): GpsFix? = location.value

    // --- Map style ---------------------------------------------------------------

    fun cycleMapType() = setMapType(when (_mapType.value) {
        "DARK" -> "LIGHT"; "LIGHT" -> "SATELLITE"; else -> "DARK"
    })

    private fun setMapType(type: String) {
        if (_mapType.value == type) return
        _mapType.value = type
        updateSettings { it.copy(navMapType = type) }
    }

    // --- Address search ----------------------------------------------------------

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) { _searchResults.value = emptyList(); _searching.value = false; return }
        searchJob = scope.launch {
            delay(450)
            _searching.value = true
            val near = location.value?.let { GeoPoint(it.lat, it.lng) }
            _searchResults.value = routingService.geocode(query, geocoderUrl, near)
            _searching.value = false
            if (_searchResults.value.isEmpty()) _messages.tryEmit("No results")
        }
    }

    fun pickSearchResult(result: GeoResult) {
        searchJob?.cancel()
        clearSearch()
        addWaypoint(result.lat, result.lng, result.name, fit = true)
    }

    fun clearSearch() { _searchResults.value = emptyList(); _searching.value = false }

    fun setUserDragging(dragging: Boolean) {
        _userDragging.value = dragging
        if (!dragging && recomputeQueuedDuringDrag) {
            recomputeQueuedDuringDrag = false
            scheduleRecompute(fit = pendingRecomputeFit)
            pendingRecomputeFit = false
        }
    }

    private fun scheduleRecompute(fit: Boolean, previewEdge: List<GeoPoint> = emptyList()) {
        if (_userDragging.value) {
            recomputeQueuedDuringDrag = true
            pendingRecomputeFit = pendingRecomputeFit || fit
            return
        }
        if (navigationEngine.isActive) return
        _routeClean.value = false
        routeJob?.cancel(); enrichJob?.cancel()
        val dests = _waypoints.value
        val origin = location.value
        if (dests.isEmpty() || origin == null) {
            lastRouteOrigin = null; _route.value = null; _routing.value = false
            _pendingPreview.value = emptyList(); bumpRender(fit); return
        }
        lastRouteOrigin = GeoPoint(origin.lat, origin.lng)
        val nonPassed = dests.filter { !it.passed }
        if (nonPassed.isEmpty()) {
            lastRouteOrigin = null; _route.value = null; _routing.value = false
            _pendingPreview.value = emptyList(); bumpRender(fit); return
        }
        val routedTargets = if (_solveFullPath.value) nonPassed else listOf(nonPassed.first())
        val navWps = listOf(Waypoint(origin.lat, origin.lng)) + routedTargets
        val mode = _travelMode.value
        if (mode == TravelMode.STRAIGHT) {
            _pendingPreview.value = emptyList()
            _route.value = RoutingService.straightLineRoute(routeName, navWps).copy(waypoints = dests)
            _routing.value = false
            bumpRender(fit)
            enrichWaypointNames()
            return
        }
        _pendingPreview.value = previewEdge
        _routing.value = true
        bumpRender(fit)
        routeJob = scope.launch {
            delay(300)
            val computed = routingService.route(routeName, navWps, mode, routerUrl) ?: run {
                _messages.tryEmit("Routing failed, using a straight line")
                RoutingService.straightLineRoute(routeName, navWps)
            }
            _route.value = computed.copy(waypoints = dests)
            _pendingPreview.value = emptyList()
            _routing.value = false
            bumpRender(fit)
            enrichWaypointNames()
        }
    }

    private fun enrichWaypointNames() {
        enrichJob?.cancel()
        val targets = _waypoints.value.withIndex().filter { it.value.name.isBlank() }.map { it.index to it.value }
        if (targets.isEmpty()) return
        enrichJob = scope.launch {
            for ((idx, wp) in targets) {
                val addr = routingService.reverseGeocode(wp.lat, wp.lng, geocoderUrl)
                if (addr != null) {
                    val cur = _waypoints.value
                    if (idx < cur.size && cur[idx].lat == wp.lat && cur[idx].lng == wp.lng && cur[idx].name.isBlank()) {
                        val updated = cur.toMutableList()
                        updated[idx] = updated[idx].copy(name = RoutingService.placeLabel(addr))
                        _waypoints.value = updated
                    }
                }
                delay(1100)
            }
            _route.value = _route.value?.copy(waypoints = _waypoints.value)
        }
    }

    private fun bumpRender(fit: Boolean) { _mapRender.value = MapRender(_mapRender.value.version + 1, fit) }

    // --- GPX import / export (String content; the screen does file I/O) ----------

    fun loadGpx(content: String) {
        scope.launch {
            try {
                val gpx = GpxIO.parse(content)
                routeName = gpx.name
                when {
                    gpx.waypoints.isNotEmpty() -> {
                        _waypoints.value = gpx.waypoints.take(MAX_WAYPOINTS)
                        scheduleRecompute(fit = true)
                    }
                    gpx.track.size >= 2 -> {
                        _waypoints.value = listOf(gpx.track.first(), gpx.track.last()).map { Waypoint(it.lat, it.lng) }
                        _route.value = NavRoute(
                            gpx.name, _waypoints.value, TravelMode.STRAIGHT,
                            gpx.track, emptyList(), GeoMath.polylineLengthM(gpx.track),
                        )
                        bumpRender(fit = true)
                    }
                    else -> { _messages.tryEmit("Couldn't load the GPX"); return@launch }
                }
                _routeClean.value = true
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't load the GPX")
            }
        }
    }

    /** Serialises the current route to GPX text. */
    fun saveGpx(): String {
        val route = _route.value ?: RoutingService.straightLineRoute(routeName, _waypoints.value)
        _routeClean.value = true
        return GpxIO.write(route)
    }

    // --- Map data (JSON for the Leaflet bridge) ----------------------------------

    fun waypointsJson(): String = buildJsonArray {
        _waypoints.value.forEach { w ->
            add(buildJsonObject {
                put("lat", w.lat); put("lng", w.lng); put("name", w.name)
                put("radius", w.radiusM ?: defaultRadiusM.toDouble())
                put("passed", w.passed)
            })
        }
    }.toString()

    fun geometryJson(): String = buildJsonArray {
        _route.value?.geometry?.forEach { p -> add(buildJsonArray { add(p.lat); add(p.lng) }) }
    }.toString()

    fun pendingPreviewJson(): String = buildJsonArray {
        _pendingPreview.value.forEach { p -> add(buildJsonArray { add(p.lat); add(p.lng) }) }
    }.toString()

    fun placesJson(): String = buildJsonArray {
        _home.value?.let { add(buildJsonObject { put("lat", it.lat); put("lng", it.lng); put("kind", "home") }) }
        _work.value?.let { add(buildJsonObject { put("lat", it.lat); put("lng", it.lng); put("kind", "work") }) }
    }.toString()

    // --- Home / Work presets -----------------------------------------------------

    private fun placeFromJson(s: String): Waypoint? = runCatching {
        if (s.isBlank()) null else JSON.parseToJsonElement(s).jsonObject.let { o ->
            Waypoint(
                o["lat"]!!.jsonPrimitive.doubleOrNull!!,
                o["lng"]!!.jsonPrimitive.doubleOrNull!!,
                o["name"]?.jsonPrimitive?.content ?: "",
            )
        }
    }.getOrNull()

    private fun placeToJson(w: Waypoint): String =
        buildJsonObject { put("lat", w.lat); put("lng", w.lng); put("name", w.name) }.toString()

    private fun savePreset(w: Waypoint, home: Boolean) {
        if (home) _home.value = w else _work.value = w
        updateSettings { if (home) it.copy(navHomeJson = placeToJson(w)) else it.copy(navWorkJson = placeToJson(w)) }
    }

    fun clearHome() = clearPreset(home = true)
    fun clearWork() = clearPreset(home = false)

    private fun clearPreset(home: Boolean) {
        if (home) _home.value = null else _work.value = null
        updateSettings { if (home) it.copy(navHomeJson = "") else it.copy(navWorkJson = "") }
    }

    fun saveSelfAsHome() = location.value?.let { savePreset(Waypoint(it.lat, it.lng), home = true) }
    fun saveSelfAsWork() = location.value?.let { savePreset(Waypoint(it.lat, it.lng), home = false) }

    fun addPreset(w: Waypoint, kind: String) { addWaypoint(w.lat, w.lng, w.name, fit = true); _lastAddedPresetKind.value = kind }
    fun saveWaypointAsHome(index: Int) { _waypoints.value.getOrNull(index)?.let { savePreset(it, home = true) } }
    fun saveWaypointAsWork(index: Int) { _waypoints.value.getOrNull(index)?.let { savePreset(it, home = false) } }

    // --- Navigation --------------------------------------------------------------

    fun stopNavigation() = navigationEngine.stop()

    fun startNavigation(mode: NavMode, onStarted: () -> Unit) {
        val dests = _waypoints.value
        if (dests.isEmpty()) { _messages.tryEmit("Add a destination first"); return }
        val loc = location.value
        if (loc == null) { _messages.tryEmit("Waiting for your location"); return }
        onStarted()
        scope.launch {
            val tMode = _travelMode.value
            val fullRoute = _solveFullPath.value && tMode != TravelMode.STRAIGHT
            val targets = if (fullRoute) dests.filter { !it.passed }.ifEmpty { listOf(dests.first()) }
            else listOf(dests.firstOrNull { !it.passed } ?: dests.first())
            val legWps = listOf(Waypoint(loc.lat, loc.lng)) + targets
            val navRoute = if (tMode == TravelMode.STRAIGHT) RoutingService.straightLineRoute(routeName, legWps)
            else routingService.route(routeName, legWps, tMode, routerUrl) ?: RoutingService.straightLineRoute(routeName, legWps)
            _route.value = navRoute.copy(waypoints = dests)
            _routing.value = false
            bumpRender(fit = false)
            navigationEngine.start(navRoute, mode)
            currentRouteStore.set(navRoute.copy(waypoints = dests, geometry = emptyList(), maneuvers = emptyList(), totalDistanceM = 0.0))
        }
    }
}
