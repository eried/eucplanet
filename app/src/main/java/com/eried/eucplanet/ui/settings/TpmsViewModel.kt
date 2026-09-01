package com.eried.eucplanet.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.util.Units
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the TPMS sensors settings section. For now it surfaces the wheel-relayed
 * tire pressure (InMotion P6 and any wheel that relays a bound sensor) - the
 * section shows the live value when a sensor is reporting and nothing when it
 * isn't. Direct BLE-sensor pairing lands once that sensor's profile is captured.
 */
@HiltViewModel
class TpmsViewModel @Inject constructor(
    wheelRepository: WheelRepository,
    settingsRepository: SettingsRepository,
    private val scanner: com.eried.eucplanet.tpms.TpmsScanner,
    private val tpms: com.eried.eucplanet.tpms.TpmsRepository,
) : ViewModel() {

    /** The rider's own sensor, or null when they have none. */
    val paired: StateFlow<String?> = tpms.pairedAddress

    /** Its live reading in kPa, null once it has gone quiet. */
    val pairedKpa: StateFlow<Float?> = tpms.current
        .map { it?.takeIf { r -> r.source == com.eried.eucplanet.tpms.TpmsSource.PAIRED }?.kpa }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun forgetPaired() = tpms.forgetPaired()

    /** Advertisements heard while scanning, newest first. */
    val seen: StateFlow<List<com.eried.eucplanet.tpms.TpmsScanner.Seen>> = scanner.seen

    val scanning: StateFlow<Boolean> = scanner.scanning

    /** Why the scan is not running, or what it just did. Empty when it has nothing to add. */
    val scanStatus: StateFlow<String> = scanner.scanStatus

    /** Seconds left in the current scan window. */
    val secondsLeft: StateFlow<Int> = scanner.secondsLeft

    fun toggleScan() {
        if (scanner.scanning.value) scanner.stop() else scanner.start()
    }

    override fun onCleared() {
        // A scan left running is a battery drain the rider cannot see.
        scanner.stop()
        super.onCleared()
    }

    /** Live wheel-relayed tire pressure in kPa; 0 when no sensor is reporting. */
    val tirePressureKpa: StateFlow<Float> = wheelRepository.wheelData
        .map { it.tirePressureKpa }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    val connected: StateFlow<Boolean> = wheelRepository.connectionState
        .map { it == ConnectionState.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** "psi" or "bar" for display, following the rider's distance unit. */
    val pressureUnit: StateFlow<String> = settingsRepository.settings
        .map { Units.effectivePressureUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "bar")
}
