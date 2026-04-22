package com.eried.eucplanet.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.repository.FullMetricHistory
import com.eried.eucplanet.data.repository.MetricSample
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.service.AutomationManager
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.service.WheelService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

data class MetricHistory(
    val battery: List<Float> = emptyList(),
    val temperature: List<Float> = emptyList(),
    val voltage: List<Float> = emptyList(),
    val current: List<Float> = emptyList(),
    val load: List<Float> = emptyList(),
    val speed: List<Float> = emptyList()
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val voiceService: VoiceService,
    private val automationManager: AutomationManager,
    private val flicManager: FlicManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val SPARKLINE_SIZE = 300  // 5 minutes at 1 sample/sec
    }

    // Synchronous initial settings read so StateFlows start with the user's persisted values
    // instead of hardcoded defaults (prevents a visible flash on app open).
    private val initialSettings: com.eried.eucplanet.data.model.AppSettings =
        runBlocking(Dispatchers.IO) { settingsRepository.get() }

    val wheelData: StateFlow<com.eried.eucplanet.data.model.WheelData> = wheelRepository.wheelData

    val connectionState: StateFlow<ConnectionState> = wheelRepository.connectionState

    val safetySpeedActive: StateFlow<Boolean> = wheelRepository.safetySpeedActive

    val locked: StateFlow<Boolean> = wheelRepository.locked

    val recording: StateFlow<Boolean> = tripRepository.recording

    val tripCount: StateFlow<Int> = tripRepository.tripCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Newest trip id (first row of trips sorted by startTime DESC), or null if none.
    val latestTripId: StateFlow<Long?> = tripRepository.allTrips
        .map { it.firstOrNull()?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentTripId: StateFlow<Long?> = tripRepository.currentTripId

    val gpsFix: StateFlow<Boolean> = tripRepository.currentLocation
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _locationPermissionGranted = kotlinx.coroutines.flow.MutableStateFlow(hasLocationPermission())
    val locationPermissionGranted: StateFlow<Boolean> = _locationPermissionGranted

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun refreshLocationPermission() {
        _locationPermissionGranted.value = hasLocationPermission()
    }

    val tiltbackSpeed: StateFlow<Float> = settingsRepository.settings
        .map { it.tiltbackSpeedKmh }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.tiltbackSpeedKmh)

    val safetyTiltbackSpeed: StateFlow<Float> = settingsRepository.settings
        .map { it.safetyTiltbackKmh }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.safetyTiltbackKmh)

    val imperialUnits: StateFlow<Boolean> = settingsRepository.settings
        .map { it.imperialUnits }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.imperialUnits)

    val accentKey: StateFlow<String> = settingsRepository.settings
        .map { it.accentColor }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.accentColor)

    val showGaugeColorBand: StateFlow<Boolean> = settingsRepository.settings
        .map { it.showGaugeColorBand }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.showGaugeColorBand)

    // Orange/red threshold percentages for the gauge color band (both 0-100).
    val gaugeOrangePct: StateFlow<Int> = settingsRepository.settings
        .map { it.gaugeOrangeThresholdPct }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.gaugeOrangeThresholdPct)

    val gaugeRedPct: StateFlow<Int> = settingsRepository.settings
        .map { it.gaugeRedThresholdPct }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.gaugeRedThresholdPct)

    val currentDisplayMode: StateFlow<String> = settingsRepository.settings
        .map { it.currentDisplayMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.currentDisplayMode)

    val hasFlicConfigured: StateFlow<Boolean> = settingsRepository.settings
        .map { it.flic1Address != null || it.flic2Address != null || it.flic3Address != null || it.flic4Address != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            initialSettings.flic1Address != null || initialSettings.flic2Address != null ||
                initialSettings.flic3Address != null || initialSettings.flic4Address != null)

    val voicePeriodicEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.voicePeriodicEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.voicePeriodicEnabled)

    val flicFlashAt: StateFlow<Long> = flicManager.lastActionAt

    fun toggleCurrentDisplayMode() {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val next = if (current.currentDisplayMode == "WATTS") "AMPS" else "WATTS"
            settingsRepository.update(current.copy(currentDisplayMode = next))
        }
    }

    fun toggleVoicePeriodic() {
        viewModelScope.launch {
            val current = settingsRepository.get()
            settingsRepository.update(current.copy(voicePeriodicEnabled = !current.voicePeriodicEnabled))
        }
    }

    fun startRecording() {
        viewModelScope.launch { tripRepository.startRecording() }
    }

    fun stopRecording() {
        viewModelScope.launch { tripRepository.stopRecording() }
    }

    val modelName: StateFlow<String?> = wheelRepository.modelName

    val firmwareVersion: StateFlow<String?> = wheelRepository.firmwareVersion

    val fullHistory: StateFlow<FullMetricHistory> = wheelRepository.fullHistory

    // Sparklines: last 60 samples from full history
    val history: StateFlow<MetricHistory> = wheelRepository.fullHistory
        .map { full ->
            MetricHistory(
                battery = full.battery.takeLast(SPARKLINE_SIZE).map { it.value },
                temperature = full.temperature.takeLast(SPARKLINE_SIZE).map { it.value },
                voltage = full.voltage.takeLast(SPARKLINE_SIZE).map { it.value },
                current = full.current.takeLast(SPARKLINE_SIZE).map { it.value },
                load = full.load.takeLast(SPARKLINE_SIZE).map { it.value },
                speed = full.speed.takeLast(SPARKLINE_SIZE).map { it.value }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MetricHistory())

    init {
        voiceService.initialize()
        voiceService.welcomeOnce()
        autoConnectIfNeeded()
    }

    private fun autoConnectIfNeeded() {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            if (settings.autoConnect && settings.lastDeviceAddress != null &&
                connectionState.value == ConnectionState.DISCONNECTED
            ) {
                connectToLastDevice()
            }
        }
    }

    fun stopEverything() {
        val intent = Intent(context, WheelService::class.java)
        context.stopService(intent)
    }

    fun onHornPress() {
        wheelRepository.sendHorn()
    }

    fun onLightToggle() {
        automationManager.notifyManualLightChange()
        wheelRepository.toggleLight()
    }

    fun onLockToggle() {
        wheelRepository.toggleLock()
    }

    fun onSafetySpeedToggle() {
        viewModelScope.launch {
            wheelRepository.toggleSafetySpeed()
        }
    }

    fun onVoiceAnnounce() {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            voiceService.announceTrigger(
                wheelRepository.wheelData.value,
                settings,
                isRecording = tripRepository.recording.value
            )
        }
    }

    fun connectToLastDevice() {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            val address = settings.lastDeviceAddress ?: return@launch
            val intent = Intent(context, WheelService::class.java).apply {
                action = WheelService.ACTION_CONNECT
                putExtra(WheelService.EXTRA_ADDRESS, address)
            }
            context.startForegroundService(intent)
        }
    }

    fun disconnect() {
        val intent = Intent(context, WheelService::class.java).apply {
            action = WheelService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    override fun onCleared() {
        voiceService.shutdown()
        super.onCleared()
    }
}
