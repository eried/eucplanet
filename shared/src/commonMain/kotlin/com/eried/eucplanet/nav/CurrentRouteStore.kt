package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.NavRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory holder for the route currently loaded in the Route Builder and
 * driven by live navigation. Deliberately **not persisted**: a route is session
 * state, so it survives navigating away from the Builder and back but is gone on
 * process death / reinstall — the rider always starts from zero. Faithful port of
 * Android's CurrentRouteStore (DI annotations dropped).
 */
class CurrentRouteStore {
    private val _route = MutableStateFlow<NavRoute?>(null)
    val route: StateFlow<NavRoute?> = _route.asStateFlow()

    fun get(): NavRoute? = _route.value
    fun set(route: NavRoute?) { _route.value = route }
    fun clear() { _route.value = null }
}
