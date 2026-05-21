package com.eried.eucplanet.ui.navigator

import android.content.Context
import android.location.Location
import android.net.Uri
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        /** Listed destinations + stops (the rider is the implicit origin). */
        const val MAX_WAYPOINTS = 9

        /** How far the rider moves before the route preview is recomputed. */
        private const val ORIGIN_REROUTE_M = 25.0
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

    init {
        // The route builder needs a live fix for the "my location" button.
        // WheelService owns the GPS lifecycle in general; this is an idempotent
        // nudge so location works even when no wheel is connected.
        tripRepository.startLocationUpdates()
        viewModelScope.launch {
            val s = settingsRepository.get()
            geocoderUrl = s.navGeocoderUrl.ifBlank { RoutingService.DEFAULT_GEOCODER }
            routerUrl = RoutingService.effectiveRouterUrl(s.navRouterUrl)
            _travelMode.value = TravelMode.fromName(s.navDefaultTravelMode)
            _mapType.value = s.navMapType.ifBlank { "LIGHT" }
            defaultRadiusM = s.navArrivalRadiusM
            _home.value = placeFromJson(s.navHomeJson)
            _work.value = placeFromJson(s.navWorkJson)
            // Re-open whatever route was last in the builder.
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

    fun addWaypoint(lat: Double, lng: Double, name: String = "", fit: Boolean = false) {
        if (_waypoints.value.size >= MAX_WAYPOINTS) {
            _messages.tryEmit(R.string.nav_max_stops)
            return
        }
        _waypoints.value = _waypoints.value + Waypoint(lat, lng, name)
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
        lastRouteOrigin = null
        bumpRender(fit = false)
        persist()
        _messages.tryEmit(R.string.nav_cleared)
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

    private fun scheduleRecompute(fit: Boolean) {
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
            persist()
            return
        }
        lastRouteOrigin = GeoPoint(origin.latitude, origin.longitude)
        val navWps = listOf(Waypoint(origin.latitude, origin.longitude)) + dests
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
            persist()
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
            persist()
        }
    }

    private fun bumpRender(fit: Boolean) {
        _mapRender.value = MapRender(_mapRender.value.version + 1, fit)
    }

    private fun persist() {
        viewModelScope.launch {
            val s = settingsRepository.get()
            val r = _route.value
                ?: if (_waypoints.value.isNotEmpty())
                    RoutingService.straightLineRoute(routeName, _waypoints.value) else null
            settingsRepository.update(s.copy(navCurrentRouteJson = r?.toJson()?.toString()))
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
                        persist()
                    }
                    else -> {
                        _messages.tryEmit(R.string.nav_load_failed)
                        return@launch
                    }
                }
                _messages.tryEmit(R.string.nav_loaded)
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
                context.contentResolver.openOutputStream(uri)?.use { GpxIO.write(route, it) }
                _messages.tryEmit(R.string.nav_saved)
                _routeClean.value = true
            } catch (e: Exception) {
                Log.w("RouteBuilder", "saveGpx failed", e)
                _messages.tryEmit(R.string.nav_save_failed)
            }
        }
    }

    // --- Map data ----------------------------------------------------------------

    /** Default arrival radius (metres) for waypoints with no custom value. */
    private var defaultRadiusM: Int = 40

    fun waypointsJson(): String {
        val arr = JSONArray()
        _waypoints.value.forEach { w ->
            arr.put(JSONObject().apply {
                put("lat", w.lat); put("lng", w.lng); put("name", w.name)
                put("radius", w.radiusM ?: defaultRadiusM.toDouble())
            })
        }
        return arr.toString()
    }

    // --- Home / Work presets -------------------------------------------------

    private val _home = MutableStateFlow<Waypoint?>(null)
    val home: StateFlow<Waypoint?> = _home.asStateFlow()
    private val _work = MutableStateFlow<Waypoint?>(null)
    val work: StateFlow<Waypoint?> = _work.asStateFlow()

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

    /** True right after a load or save — Clear route then needs no confirm. */
    private val _routeClean = MutableStateFlow(false)
    val routeClean: StateFlow<Boolean> = _routeClean.asStateFlow()

    /** Drops a saved preset onto the map as the next waypoint. */
    fun addPreset(w: Waypoint) = addWaypoint(w.lat, w.lng, w.name, fit = true)

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
        viewModelScope.launch {
            // Position 0 is always the rider; the listed pins are destinations.
            val navWps = listOf(Waypoint(loc.latitude, loc.longitude)) + dests
            val tMode = _travelMode.value
            val navRoute = if (tMode == TravelMode.STRAIGHT) {
                RoutingService.straightLineRoute(routeName, navWps)
            } else {
                routingService.route(routeName, navWps, tMode, routerUrl)
                    ?: RoutingService.straightLineRoute(routeName, navWps)
            }
            navigationEngine.start(navRoute, mode)
            onStarted()
        }
    }

    override fun onCleared() {
        super.onCleared()
        routeJob?.cancel()
        searchJob?.cancel()
        enrichJob?.cancel()
    }
}
