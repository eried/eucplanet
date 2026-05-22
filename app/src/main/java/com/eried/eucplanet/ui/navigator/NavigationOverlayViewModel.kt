package com.eried.eucplanet.ui.navigator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.data.model.NavMode
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.NavState
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.nav.RoutingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin bridge between the always-on [NavigationOverlay] composable and the
 * singleton [NavigationEngine]. The overlay lives above the nav graph in
 * [com.eried.eucplanet.MainActivity], so it just mirrors the engine's state.
 *
 * It also backs the dashboard map button's long-press menu: [savedRoute] tells
 * the menu whether there is anything to start, and [startSavedRoute] launches
 * guidance without a trip through the route builder.
 */
@HiltViewModel
class NavigationOverlayViewModel @Inject constructor(
    private val navigationEngine: NavigationEngine,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val routingService: RoutingService
) : ViewModel() {

    init {
        // Idempotent nudge so a "my location" fix is ready if the rider starts
        // a saved route straight from the dashboard.
        tripRepository.startLocationUpdates()
    }

    val navState: StateFlow<NavState> = navigationEngine.navState

    /**
     * The route last left in the builder, or null when it has no stops. The
     * dashboard map menu offers "Start navigation" only while this is non-null.
     */
    val savedRoute: StateFlow<NavRoute?> = settingsRepository.settings
        .map { s ->
            NavRoute.fromJson(s.navCurrentRouteJson)?.takeIf { it.waypoints.isNotEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setMinimized(minimized: Boolean) = navigationEngine.setMinimized(minimized)

    fun setCueVisible(visible: Boolean) = navigationEngine.setCueVisible(visible)

    /** Re-opens the centred popup — used by the dashboard navigator button. */
    fun requestPopup() = navigationEngine.requestPopup()

    fun endNavigation() = navigationEngine.stop()

    /**
     * Starts guidance on the saved route's stops. When a GPS fix is available
     * it re-routes fresh from the rider's current position (so the origin is
     * never stale, mirroring the route builder's own Start button); otherwise
     * it falls back to the stored route geometry.
     */
    fun startSavedRoute() {
        val saved = savedRoute.value ?: return
        val dests = saved.waypoints
        if (dests.isEmpty()) return
        val mode = if (saved.travelMode == TravelMode.STRAIGHT) NavMode.TREASURE_HUNT
        else NavMode.TURN_BY_TURN
        viewModelScope.launch {
            val loc = tripRepository.currentLocation.value
            if (loc == null) {
                navigationEngine.start(saved, mode)
                return@launch
            }
            val s = settingsRepository.get()
            val navWps = listOf(Waypoint(loc.latitude, loc.longitude)) + dests
            val route = if (saved.travelMode == TravelMode.STRAIGHT) {
                RoutingService.straightLineRoute(saved.name, navWps)
            } else {
                routingService.route(
                    saved.name, navWps, saved.travelMode,
                    RoutingService.effectiveRouterUrl(s.navRouterUrl)
                ) ?: RoutingService.straightLineRoute(saved.name, navWps)
            }
            navigationEngine.start(route, mode)
        }
    }
}
