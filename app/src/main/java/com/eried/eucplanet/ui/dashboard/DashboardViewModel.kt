package com.eried.eucplanet.ui.dashboard

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.repository.FullMetricHistory
import com.eried.eucplanet.data.repository.MetricSample
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.service.WheelService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val SPARKLINE_SIZE = 300  // 5 minutes at 1 sample/sec
    }

    val wheelData: StateFlow<com.eried.eucplanet.data.model.WheelData> = wheelRepository.wheelData

    val connectionState: StateFlow<ConnectionState> = wheelRepository.connectionState

    val safetySpeedActive: StateFlow<Boolean> = wheelRepository.safetySpeedActive

    val locked: StateFlow<Boolean> = wheelRepository.locked

    val recording: StateFlow<Boolean> = tripRepository.recording

    val tripCount: StateFlow<Int> = tripRepository.tripCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val tiltbackSpeed: StateFlow<Float> = settingsRepository.settings
        .map { it.tiltbackSpeedKmh }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 50f)

    val safetyTiltbackSpeed: StateFlow<Float> = settingsRepository.settings
        .map { it.safetyTiltbackKmh }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 25f)

    val imperialUnits: StateFlow<Boolean> = settingsRepository.settings
        .map { it.imperialUnits }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val accentKey: StateFlow<String> = settingsRepository.settings
        .map { it.accentColor }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.eried.eucplanet.ui.theme.AccentKeyDefault)

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
            voiceService.announceTrigger(wheelRepository.wheelData.value, settings)
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
