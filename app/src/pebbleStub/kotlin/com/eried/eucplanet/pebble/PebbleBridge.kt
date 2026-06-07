package com.eried.eucplanet.pebble

import android.content.Context
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op stub used on the slim `-PpebbleEnabled=false` build, where
 * PebbleKitAndroid2 isn't on the classpath.
 *
 * Keep the public surface 1:1 with
 * `src/pebbleEnabled/kotlin/.../PebbleBridge.kt` so EucPlanetApp.kt and the
 * listener service don't have to know which variant they're calling. The stub
 * build ships no listener service (its manifest entry only exists on the
 * enabled source set), so [onWatchAppOpened] / [onWatchAppClosed] are never
 * actually invoked here -- they exist only to match the API. The state flows
 * stay at their initial values (no watchapp, never sent) so the Settings
 * "Paired devices" card simply never adds a Pebble row on this build.
 */
@Singleton
class PebbleBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
) {
    val watchAppOpen: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    val lastSentAtMs: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()

    fun start() = Unit
    fun onWatchAppOpened() = Unit
    fun onWatchAppClosed() = Unit
}
