package com.eried.eucplanet.ui.dashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Open the weather panel", handed from a launcher tap to the dashboard.
 *
 * A home screen widget can only start an activity, and the panel is a piece of
 * dashboard state rather than a route of its own, so the intent extra lands
 * here and the dashboard picks it up on its next composition. Process-scoped
 * plain state, like the flyout's own here-or-destination toggle: a request
 * that outlived the process would open the panel on some unrelated launch.
 */
object WeatherPanelLaunch {

    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    /** Read once. The dashboard clears it so returning from another screen
     *  does not re-open the panel the rider just closed. */
    fun consume(): Boolean {
        val v = _pending.value
        _pending.value = false
        return v
    }
}
