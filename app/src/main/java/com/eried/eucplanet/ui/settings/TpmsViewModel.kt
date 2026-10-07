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

    /** The cap currently speaking for the tyre, or null when the wheel is. */
    val activeAddress: StateFlow<String?> = tpms.activeAddress

    /** True while the wheel's own relayed reading is the one being shown. */
    val wheelIsActive: StateFlow<Boolean> = tpms.wheelIsActive

    /** Every paired cap and what it last said. */
    val sensors: StateFlow<List<com.eried.eucplanet.tpms.TpmsRepository.SensorState>> = tpms.sensors

    /** The rider's temperature unit, for the row beside the pressure. */
    val tempUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveTempUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "C")

    val pairedKpa: StateFlow<Float?> = tpms.current
        .map { it?.takeIf { r -> r.source == com.eried.eucplanet.tpms.TpmsSource.PAIRED }?.kpa }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun forgetPaired() = tpms.forgetPaired()

    /** Remove one cap, leaving the rider's other wheels alone. */
    fun forget(address: String) = tpms.forget(address)

    val scanning: StateFlow<Boolean> = scanner.scanning

    fun toggleScan() {
        // Stop ends the SEARCH and goes back to watching a paired cap. A bare
        // stop() left a rider with a sensor paired and no radio running.
        if (scanner.scanning.value) scanner.resumeMonitoring() else scanner.start()
    }

    override fun onCleared() {
        // Ends a search, not the watching. A search left running is a battery
        // drain the rider cannot see; the monitor is the whole feature.
        scanner.endSearch()
        super.onCleared()
    }

    /**
     * The WHEEL's own reading, not the merged one.
     *
     * WheelData.tirePressureKpa carries whichever sensor is active, which is
     * the right answer everywhere except here: this is the row that says what
     * the wheel itself has, and drawing the cap's number on it would invent a
     * sensor the wheel does not have.
     */
    val tirePressureKpa: StateFlow<Float> = tpms.wheelSensor
        .map { it?.kpa ?: 0f }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    val connected: StateFlow<Boolean> = wheelRepository.connectionState
        .map { it == ConnectionState.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The rider's own pressure unit. */
    val pressureUnit: StateFlow<String> = settingsRepository.settings
        .map { Units.effectivePressureUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "bar")
}
