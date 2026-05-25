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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
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

    /**
     * State of the axis-autodetect wizard. Each motion phase has two screens:
     * an [Instruct] page (Cancel + Next) and a [Capture] page that polls the
     * accelerometer (Cancel only, except the final capture which auto-applies
     * the result so the abort button would be misleading).
     */
    sealed class AutoDetectPhase {
        object Idle : AutoDetectPhase()
        object Prep : AutoDetectPhase()
        object ForwardInstruct : AutoDetectPhase()
        object ForwardCapture : AutoDetectPhase()
        object LateralInstruct : AutoDetectPhase()
        object LateralCapture : AutoDetectPhase()
        object VerticalInstruct : AutoDetectPhase()
        object VerticalCapture : AutoDetectPhase()
        data class Done(val mapX: String, val mapY: String, val mapZ: String) : AutoDetectPhase()
    }

    private val _autoDetect = MutableStateFlow<AutoDetectPhase>(AutoDetectPhase.Idle)
    val autoDetect: StateFlow<AutoDetectPhase> = _autoDetect.asStateFlow()

    private val nextSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var autoDetectJob: Job? = null

    /** Output axis we assign each captured raw axis to. Convention:
     *   forward (axis X) = lateral push of the wheel direction of travel
     *   lateral (axis Y) = side-to-side
     *   vertical (axis Z) = up/down
     * which mirrors the rider's body frame and the existing crosshair plot. */
    fun startAutoDetect(onApply: (mapX: String, mapY: String, mapZ: String) -> Unit) {
        if (autoDetectJob != null) return
        autoDetectJob = viewModelScope.launch {
            try {
                // Prep screen, rider gets ready to start moving.
                _autoDetect.value = AutoDetectPhase.Prep
                nextSignal.first()

                // Forward: walk forward. 1 s warmup then 2 s capture, enough
                // motion samples (~50–100 IMU frames) to pick the dominant
                // axis without dragging the wizard out.
                _autoDetect.value = AutoDetectPhase.ForwardInstruct
                nextSignal.first()
                _autoDetect.value = AutoDetectPhase.ForwardCapture
                kotlinx.coroutines.delay(1000)
                val forward = repository.captureDominantAxis(2000) ?: "Y"

                // Lateral: walk left without changing device orientation.
                _autoDetect.value = AutoDetectPhase.LateralInstruct
                nextSignal.first()
                _autoDetect.value = AutoDetectPhase.LateralCapture
                kotlinx.coroutines.delay(1000)
                val lateral = repository.captureDominantAxis(2000) ?: "X"

                // Vertical: slowly lower the device to the ground. Shorter
                // window because the motion itself is brief (~2 s).
                _autoDetect.value = AutoDetectPhase.VerticalInstruct
                nextSignal.first()
                _autoDetect.value = AutoDetectPhase.VerticalCapture
                kotlinx.coroutines.delay(1000)
                val vertical = repository.captureDominantAxis(2000) ?: "Z"

                onApply(lateral, forward, vertical)
                _autoDetect.value = AutoDetectPhase.Done(lateral, forward, vertical)
            } finally {
                autoDetectJob = null
            }
        }
    }

    /** Advance to the next phase from any *Instruct screen. */
    fun nextAutoDetectStep() {
        nextSignal.tryEmit(Unit)
    }

    fun cancelAutoDetect() {
        autoDetectJob?.cancel()
        autoDetectJob = null
        _autoDetect.value = AutoDetectPhase.Idle
    }

    fun dismissAutoDetect() {
        _autoDetect.value = AutoDetectPhase.Idle
    }
}
