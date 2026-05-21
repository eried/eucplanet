package com.eried.eucplanet.ui.navigator

import androidx.lifecycle.ViewModel
import com.eried.eucplanet.data.model.NavState
import com.eried.eucplanet.nav.NavigationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin bridge between the always-on [NavigationOverlay] composable and the
 * singleton [NavigationEngine]. The overlay lives above the nav graph in
 * [com.eried.eucplanet.MainActivity], so it just mirrors the engine's state.
 */
@HiltViewModel
class NavigationOverlayViewModel @Inject constructor(
    private val navigationEngine: NavigationEngine
) : ViewModel() {

    val navState: StateFlow<NavState> = navigationEngine.navState

    fun setMinimized(minimized: Boolean) = navigationEngine.setMinimized(minimized)

    fun setCueVisible(visible: Boolean) = navigationEngine.setCueVisible(visible)

    /** Re-opens the centred popup — used by the dashboard navigator button. */
    fun requestPopup() = navigationEngine.requestPopup()

    fun endNavigation() = navigationEngine.stop()
}
