package com.eried.eucplanet.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide "is a UI activity in the foreground" signal, driven by
 * [com.eried.eucplanet.EucPlanetApp]'s ActivityLifecycleCallbacks.
 *
 * Used to keep background-only process wakes - e.g. a periodic WorkManager
 * upload that re-creates the process every ~15 min - from spinning up hardware
 * the rider isn't actively using, like auto-connecting the external GPS box.
 * All mutations happen on the main thread (that's where the lifecycle callbacks
 * fire), so the plain counter needs no extra synchronisation.
 */
object AppForeground {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground

    private var startedActivities = 0

    fun onActivityStarted() {
        startedActivities++
        _isForeground.value = true
    }

    fun onActivityStopped() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) _isForeground.value = false
    }
}
