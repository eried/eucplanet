package com.eried.eucplanet.ui.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.RadarFrame
import com.eried.eucplanet.data.repository.RadarRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Drives [RadarOverlay] visibility. The overlay is on screen when:
 *  - a radar is paired AND the radar-overlay setting is on,
 *  - the connection is CONNECTED OR INITIALIZING (so connect/handshake
 *    flicker doesn't hide the lane), and
 *  - at least one threat is present in the current frame, OR the last
 *    non-empty frame is younger than the clear-decay window
 *    (AppSettings.radarClearDecayMs).
 *
 * The clear-decay rule prevents the bar from blinking in and out as cars
 * appear and disappear at the edge of the radar's range ,  once the rider
 * has seen "a car is back there", the bar stays for a beat after the
 * radar declares the lane clear.
 */

@HiltViewModel
class RadarOverlayViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val radarRepository: RadarRepository
) : ViewModel() {

    /** Latest frame, propagated as-is for the canvas to read. */
    val frame: StateFlow<RadarFrame?> = radarRepository.currentFrame

    val side: StateFlow<String> = settingsRepository.settings
        .map { it.radarOverlaySide }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "RIGHT")

    // Advanced: how long the lane bar lingers after the road clears. Refreshed in
    // the combine below (which already collects settings); sanitized() floors it.
    @Volatile private var clearDecayMs: Long = 3000L

    @OptIn(ExperimentalCoroutinesApi::class)
    val shouldShow: StateFlow<Boolean> = combine(
        settingsRepository.settings,
        radarRepository.connectionState,
        radarRepository.currentFrame
    ) { s, cs, f ->
        clearDecayMs = s.radarClearDecayMs.toLong()
        Triple(
            s.radarAddress != null && s.radarShowOverlay,
            cs == ConnectionState.CONNECTED || cs == ConnectionState.INITIALIZING,
            f
        )
    }.transformLatest { (enabled, connected, f) ->
        if (!enabled || !connected) {
            emit(false)
            return@transformLatest
        }
        val threats = f?.threats?.size ?: 0
        if (threats > 0) {
            emit(true)
        } else {
            // Lane just cleared ,  stay up for the decay window, then hide.
            emit(true)
            delay(clearDecayMs)
            emit(false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
}
