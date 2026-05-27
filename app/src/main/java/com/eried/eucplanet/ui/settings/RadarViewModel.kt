package com.eried.eucplanet.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.radar.RadarScanResult
import com.eried.eucplanet.ble.radar.RadarScanner
import com.eried.eucplanet.data.model.RadarFrame
import com.eried.eucplanet.data.model.RadarVendor
import com.eried.eucplanet.data.repository.RadarRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Radar subsection of the Integration settings tab. Mirrors
 * [ExternalGpsViewModel]: holds the deduplicated scan-result list and
 * exposes the persisted pairing + live frame as flows so the UI can flip
 * between "not paired → scan card" and "paired → debug card" without
 * blocking on suspend reads.
 */
@HiltViewModel
class RadarViewModel @Inject constructor(
    private val repository: RadarRepository,
    private val scanner: RadarScanner,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val currentFrame: StateFlow<RadarFrame?> = repository.currentFrame

    val pairedAddress: StateFlow<String?> = settingsRepository.settings
        .map { it.radarAddress }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pairedName: StateFlow<String?> = settingsRepository.settings
        .map { it.radarName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pairedVendor: StateFlow<RadarVendor?> = settingsRepository.settings
        .map { s -> s.radarVendor?.let { runCatching { RadarVendor.valueOf(it) }.getOrNull() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanResults = MutableStateFlow<List<RadarScanResult>>(emptyList())
    val scanResults: StateFlow<List<RadarScanResult>> = _scanResults.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        if (_scanning.value) return
        _scanResults.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            scanner.scan().collect { result ->
                val current = _scanResults.value
                val idx = current.indexOfFirst { it.device.address == result.device.address }
                _scanResults.value = if (idx < 0) current + result
                else current.toMutableList().also { it[idx] = result }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanner.stop()
        _scanning.value = false
    }

    fun pair(result: RadarScanResult) {
        viewModelScope.launch {
            stopScan()
            repository.setPairing(result.device.address, result.device.name, result.vendor)
            repository.connectPaired()
        }
    }

    fun unpair() {
        viewModelScope.launch { repository.clearPairing() }
    }

    fun reconnect() {
        viewModelScope.launch { repository.connectPaired() }
    }

    fun disconnect() {
        repository.disconnect()
    }

    fun updateShowOverlay(show: Boolean) {
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(radarShowOverlay = show))
        }
    }

    fun updateOverlaySide(side: String) {
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(radarOverlaySide = side))
        }
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }
}
