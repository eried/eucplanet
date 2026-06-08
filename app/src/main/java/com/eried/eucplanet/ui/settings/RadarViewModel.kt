package com.eried.eucplanet.ui.settings

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
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

    // True when a scan was requested but Bluetooth is off. Drives a hint in the
    // section instead of crashing on the scanner's null leScanner.
    private val _bluetoothOff = MutableStateFlow(false)
    val bluetoothOff: StateFlow<Boolean> = _bluetoothOff.asStateFlow()

    private var scanJob: Job? = null
    private var resumeJob: Job? = null

    // Detects Bluetooth toggling while the radar pairing scan is open: stop +
    // show the "Bluetooth is off" hint when it goes off, auto-resume when back.
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> {
                    if (_scanning.value) stopScan()
                    _bluetoothOff.value = true
                }
                BluetoothAdapter.STATE_ON -> resumeScanAfterBtOn()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Resume a scan that Bluetooth-off interrupted, a beat after it returns
     *  (the stack isn't ready instantly). Only if we were showing the off hint. */
    private fun resumeScanAfterBtOn() {
        if (!_bluetoothOff.value) return
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            delay(1200)
            _bluetoothOff.value = false
            startScan()
        }
    }

    fun startScan() {
        if (_scanning.value) return
        // Bluetooth must be on, otherwise scanner.scan() throws inside the flow
        // and the uncaught exception crashes the app.
        if (!scanner.isBluetoothEnabled()) {
            _bluetoothOff.value = true
            return
        }
        _bluetoothOff.value = false
        _scanResults.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            scanner.scan()
                .catch { _scanning.value = false }
                .collect { result ->
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
        resumeJob?.cancel()
        runCatching { context.unregisterReceiver(bluetoothStateReceiver) }
        stopScan()
        super.onCleared()
    }
}
