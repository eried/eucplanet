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
) : ViewModel() {

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
