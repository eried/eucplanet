package com.eried.eucplanet.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Take me to where I join the leaderboard", handed from the crew pass dialog to the graph.
 *
 * The pass dialog is its own activity and has no nav controller, so it can only start
 * [com.eried.eucplanet.MainActivity]. It used to do exactly that and nothing more, which left a
 * rider who had just been told they need a profile standing on the dashboard with the same
 * problem and no sign of where to go. The extra lands here and MainActivity navigates once the
 * graph exists, the way a tapped charge alert already does.
 *
 * Process-scoped plain state: a request that outlived the process would throw some unrelated
 * launch into Settings.
 */
object LeaderboardSetupLaunch {

    /** Set by whoever wants the rider taken to the leaderboard settings. */
    const val EXTRA_OPEN_LEADERBOARD = "com.eried.eucplanet.OPEN_LEADERBOARD"

    /**
     * `initialTab` for the "Backups & leaderboards" section. The number maps to a stable section
     * KEY in `SettingsScreen.initialTabSectionKey` ("cloud"), not to a position, so a rider who
     * has reordered their settings still lands on the right one.
     */
    const val SETTINGS_TAB_CLOUD = 4

    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    /** Read once, so coming back from Settings does not bounce the rider into it again. */
    fun consume(): Boolean {
        val v = _pending.value
        _pending.value = false
        return v
    }
}
