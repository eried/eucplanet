package com.eried.eucplanet.ui.lockdown

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A blocked legal-mode press can come from a Flic button, a volume key, the
 * watch or the HUD, none of which have a Compose scope. They post here and the
 * locked screen opens its unlock dialog, so the rider is shown the way out
 * instead of the press appearing to do nothing.
 *
 * Same shape as [com.eried.eucplanet.ui.dashboard.DashboardDialogBus].
 */
object LockdownPromptBus {

    private val _showUnlock = MutableStateFlow(false)
    val showUnlock: StateFlow<Boolean> = _showUnlock.asStateFlow()

    fun request() {
        _showUnlock.value = true
    }

    fun consume() {
        _showUnlock.value = false
    }
}
