package com.eried.eucplanet.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.gps.ExternalGpsScanResult
import com.eried.eucplanet.ble.gps.ExternalGpsScanner
import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import com.eried.eucplanet.data.repository.ExternalGpsRepository
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
 * Drives the External GPS section of the Integration settings tab.
 *
 * Holds the scan-results list (deduplicated by address, freshest RSSI wins)
 * and exposes the persisted pairing as flows so the UI can flip between
 * "no device paired → scan card" and "device X paired → status card" without
 * blocking on suspend reads.
 */
@HiltViewModel
class ExternalGpsViewModel @Inject constructor(
    private val repository: ExternalGpsRepository,
    private val scanner: ExternalGpsScanner,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val currentSample: StateFlow<ExternalGpsSample?> = repository.currentSample

    val pairedAddress: StateFlow<String?> = settingsRepository.settings
        .map { it.externalGpsAddress }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pairedName: StateFlow<String?> = settingsRepository.settings
        .map { it.externalGpsName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pairedSource: StateFlow<ExternalGpsSource?> = settingsRepository.settings
        .map { s -> s.externalGpsSource?.let { runCatching { ExternalGpsSource.valueOf(it) }.getOrNull() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ExternalGpsScanResult>>(emptyList())
    val scanResults: StateFlow<List<ExternalGpsScanResult>> = _scanResults.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        if (_scanning.value) return
        _scanResults.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            scanner.scan().collect { result ->
                // Dedupe by address, refresh in place so the list doesn't churn.
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

    fun pair(result: ExternalGpsScanResult) {
        viewModelScope.launch {
            stopScan()
            repository.setPairing(result.device.address, result.device.name, result.source)
            repository.connectPaired()
        }
    }

    fun unpair() {
        viewModelScope.launch {
            repository.clearPairing()
        }
    }

    fun reconnect() {
        viewModelScope.launch { repository.connectPaired() }
    }

    fun disconnect() {
        repository.disconnect()
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }
}
