package com.eried.eucplanet.ui.lockdown

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.LegalLockdownController
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.util.Units
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * State for the locked screen. Deliberately narrow: it exposes the six fixed
 * metrics, the gauge inputs and what the two dialogs show, and nothing else.
 *
 * Nothing here reads dashboardMetricOrder, dashboardActionOrder or any of the
 * layout settings. The locked screen is hard-coded so it cannot be shaped by a
 * rider's configuration, and so arming does not have to rewrite that
 * configuration to get the simple layout.
 */
@HiltViewModel
class LegalLockdownViewModel @Inject constructor(
    private val wheelRepository: WheelRepository,
    settingsRepository: SettingsRepository,
    private val lockdown: LegalLockdownController
) : ViewModel() {

    // Synchronous initial read so the StateFlows start on the rider's persisted
    // values instead of defaults, the same reason DashboardViewModel does it.
    private val initialSettings = runBlocking(Dispatchers.IO) { settingsRepository.get() }

    val wheelData: StateFlow<WheelData> = wheelRepository.wheelData
    val connectionState: StateFlow<ConnectionState> = wheelRepository.connectionState
    val lightBusy: StateFlow<Boolean> = wheelRepository.lightBusy

    val connectedDeviceName: StateFlow<String?> = wheelRepository.connectedDeviceName
    val connectedBrand: StateFlow<String?> = wheelRepository.connectedBrand
    val modelName: StateFlow<String?> = wheelRepository.modelName

    /** The legal tiltback, which is both the gauge ceiling and the "max speed limit" row. */
    val legalTiltbackKmh: StateFlow<Float> = settingsRepository.settings
        .map { it.safetyTiltbackKmh }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.safetyTiltbackKmh)

    val legalAlarmKmh: StateFlow<Float> = settingsRepository.settings
        .map { it.safetyAlarmKmh }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.safetyAlarmKmh)

    val speedUnit: StateFlow<String> = settingsRepository.settings
        .map { Units.effectiveSpeedUnit(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Units.effectiveSpeedUnit(initialSettings))

    val distanceUnit: StateFlow<String> = settingsRepository.settings
        .map { Units.effectiveDistanceUnit(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Units.effectiveDistanceUnit(initialSettings))

    val tempUnit: StateFlow<String> = settingsRepository.settings
        .map { Units.effectiveTempUnit(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Units.effectiveTempUnit(initialSettings))

    fun onHornPress() = wheelRepository.sendHorn()

    /**
     * No [com.eried.eucplanet.service.AutomationManager.notifyManualLightChange]
     * call, unlike the dashboard's light button. Lockdown is temporary and must
     * not leave auto-lights suspended for the session. AutomationManager guards
     * that call internally too, so this is belt and braces.
     */
    fun onLightToggle() = wheelRepository.toggleLight()

    /** True on a correct code. The screen shows the error and starts the cooldown on false. */
    suspend fun tryUnlock(pin: String): Boolean = lockdown.tryDisarm(pin)
}
