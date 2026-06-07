package com.eried.eucplanet.ui.scan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.BleConnectionManager
import com.eried.eucplanet.ble.BleDevice
import com.eried.eucplanet.ble.BleScanner
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.service.WheelService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val bleScanner: BleScanner,
    private val settingsRepository: SettingsRepository,
    private val bleConnectionManager: BleConnectionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Set once the rider picks a wheel here, so the dismiss-time reconnect
    // doesn't pull them back to the old one. The cached last-wheel snapshot
    // lets onCleared re-arm auto-connect without suspending.
    private var deviceSelected = false
    private var cachedAutoConnect = false
    private var cachedLastAddress: String? = null
    private var cachedLastName: String? = null

    // Reacts to the adapter toggling while the scan screen is open. Android
    // stops delivering scan results when Bluetooth goes off WITHOUT calling
    // onScanFailed, so an in-flight scan would otherwise spin "Searching…"
    // forever; and when it comes back we resume scanning so the rider isn't
    // stranded on the "Bluetooth is off" card.
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> {
                    if (_isScanning.value) stopScan()
                    _bluetoothOff.value = true
                }
                BluetoothAdapter.STATE_ON -> {
                    // Auto-resume, but not immediately: the BLE stack isn't ready
                    // the instant the adapter reports ON, and a scan started right
                    // away returns no results. resumeScanAfterBtOn() waits a beat
                    // then (re)starts cleanly.
                    _bluetoothOff.value = false
                    resumeScanAfterBtOn()
                }
            }
        }
    }

    init {
        // While the rider is on the scan screen choosing a wheel, hold the
        // auto-reconnect AND drop any in-flight/active auto-connection so it
        // can't pull them back to the previous wheel mid-search. Restored in
        // onCleared, which re-arms (and reconnects) as needed.
        bleConnectionManager.suppressAutoConnect()
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        viewModelScope.launch {
            val s = settingsRepository.get()
            cachedAutoConnect = s.autoConnect
            cachedLastAddress = s.lastDeviceAddress
            cachedLastName = s.lastDeviceName
        }
    }

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _missingPermissions = MutableStateFlow<List<String>>(emptyList())
    val missingPermissions: StateFlow<List<String>> = _missingPermissions.asStateFlow()

    // True when permissions are fine but Bluetooth itself is turned off. Drives
    // the "Enable Bluetooth" prompt so the scan button isn't a dead end.
    private val _bluetoothOff = MutableStateFlow(false)
    val bluetoothOff: StateFlow<Boolean> = _bluetoothOff.asStateFlow()

    /**
     * When true the BLE scan emits every named peripheral, regardless of
     * whether the name matches a known wheel prefix. Toggled from the scan
     * screen; restarts the active scan so the UI reflects the new filter
     * immediately.
     */
    private val _showAllDevices = MutableStateFlow(false)
    val showAllDevices: StateFlow<Boolean> = _showAllDevices.asStateFlow()

    private var scanJob: Job? = null
    private var resumeJob: Job? = null

    /**
     * Restart scanning a short beat after Bluetooth comes back on. Starting the
     * instant the adapter reports STATE_ON yields a scan that finds nothing -
     * the BLE stack needs a moment to come up - so we wait, then restart even if
     * an early empty scan is already running.
     */
    fun resumeScanAfterBtOn() {
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            delay(1200)
            if (_isScanning.value) stopScan()
            startScan()
        }
    }

    private fun requiredScanPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: neverForLocation is declared, so no location needed for scan
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11-: legacy BLE scan requires fine location
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun refreshPermissions(): Boolean {
        val missing = requiredScanPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        _missingPermissions.value = missing
        return missing.isEmpty()
    }

    fun startScan() {
        if (_isScanning.value) return
        if (!refreshPermissions()) return
        // Bluetooth itself must be on — otherwise the scan silently throws
        // (null leScanner) and nothing happens. Surface it instead.
        if (!bleScanner.isBluetoothEnabled()) {
            _bluetoothOff.value = true
            return
        }
        _bluetoothOff.value = false
        _isScanning.value = true
        _devices.value = emptyList()

        scanJob = viewModelScope.launch {
            bleScanner.scanForDevices(showAll = _showAllDevices.value)
                .catch { _isScanning.value = false }
                .collect { device ->
                    val current = _devices.value.toMutableList()
                    val existing = current.indexOfFirst { it.address == device.address }
                    if (existing >= 0) {
                        current[existing] = device
                    } else {
                        current.add(device)
                    }
                    _devices.value = current
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        bleScanner.stopScan()
        _isScanning.value = false
    }

    /**
     * Toggle the "show every BLE device" filter. Restarts the scan so the
     * device list switches between filtered and unfiltered immediately.
     */
    fun setShowAllDevices(showAll: Boolean) {
        if (_showAllDevices.value == showAll) return
        _showAllDevices.value = showAll
        if (_isScanning.value) {
            stopScan()
            startScan()
        }
    }

    fun selectDevice(device: BleDevice) {
        // The rider explicitly picked a wheel: don't reconnect to the old one
        // on dismiss, and let this connect through the auto-hold (it isn't
        // flagged auto, so the chokepoint allows it).
        deviceSelected = true
        stopScan()
        viewModelScope.launch {
            settingsRepository.updateLastDevice(device.address, device.name)

            val intent = Intent(context, WheelService::class.java).apply {
                action = WheelService.ACTION_CONNECT
                putExtra(WheelService.EXTRA_ADDRESS, device.address)
                putExtra(WheelService.EXTRA_NAME, device.name)
            }
            context.startForegroundService(intent)
        }
    }

    override fun onCleared() {
        resumeJob?.cancel()
        stopScan()
        runCatching { context.unregisterReceiver(bluetoothStateReceiver) }
        // Rider left the scan screen. Re-enable auto-reconnect; if they didn't
        // pick a new wheel, reconnect to the last one (resumeAutoConnect only
        // does so while still disconnected) — "reconnect as normal on dismiss".
        val reconnectAddress =
            if (!deviceSelected && cachedAutoConnect) cachedLastAddress else null
        bleConnectionManager.resumeAutoConnect(reconnectAddress, cachedLastName)
        super.onCleared()
    }
}
