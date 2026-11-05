package com.eried.eucplanet.ui.scan

import com.eried.eucplanet.ble.BleDevice
import com.eried.eucplanet.ble.WheelLink
import com.eried.eucplanet.data.repository.AppNotices
import com.eried.eucplanet.data.repository.PhoneDevice
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.resources.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope


class ScanViewModel(
    private val bleScanner: WheelScanner,
    private val settingsRepository: SettingsRepository,
    private val bleConnectionManager: WheelLink,
    private val appNotifier: AppNotices,
    private val phone: PhoneDevice,
    private val platform: ScanPlatform,
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
    private val onBluetoothChange: (Boolean) -> Unit = { on ->
        if (!on) {
            if (_isScanning.value) stopScan()
            _bluetoothOff.value = true
        } else {
            // Auto-resume, but not immediately: the BLE stack isn't ready
            // the instant the adapter reports ON, and a scan started right
            // away returns no results. resumeScanAfterBtOn() waits a beat
            // then (re)starts cleanly.
            _bluetoothOff.value = false
            resumeScanAfterBtOn()
        }
    }
    private var stopWatchingBluetooth: () -> Unit = {}

    init {
        // While the rider is on the scan screen choosing a wheel, hold the
        // auto-reconnect AND drop any in-flight/active auto-connection so it
        // can't pull them back to the previous wheel mid-search. Restored in
        // onCleared, which re-arms (and reconnects) as needed.
        bleConnectionManager.suppressAutoConnect()
        stopWatchingBluetooth = platform.watchBluetooth(onBluetoothChange)
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

    fun refreshPermissions(): Boolean {
        val missing = platform.missingScanPermissions()
        _missingPermissions.value = missing
        return missing.isEmpty()
    }

    fun startScan() {
        if (_isScanning.value) return
        if (!refreshPermissions()) return
        // Bluetooth itself must be on, otherwise the scan silently throws
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

        // Fire the connect SYNCHRONOUSLY. The tap handler dismisses the scan
        // sheet the instant it calls this, which clears the ViewModel and
        // cancels viewModelScope. The old code launched the connect inside that
        // scope AFTER the suspending updateLastDevice(), so if the DataStore
        // write hadn't returned before the dismissal, the coroutine was
        // cancelled and startForegroundService never ran - the rider tapped the
        // wheel, the list closed, and nothing connected. Starting the service
        // here (a non-suspending call, on the foreground main thread) can't be
        // dropped by that cancellation.
        // Same gate the dashboard's auto-connect applies. Without
        // BLUETOOTH_CONNECT the service cannot declare its foreground type,
        // stops itself before startForeground(), and Android then kills the
        // whole app for the broken startForegroundService() promise. Tell the
        // rider what is missing instead (seen on an emulator whose grants had
        // been reset, and it is exactly what a rider who denied the permission
        // would hit).
        if (!platform.mayConnect()) {
            appNotifier.post(phone.text(Res.string.scan_permission_body))
            return
        }
        platform.connect(device.address, device.name)

        // Persist the last device best-effort (not required for this connect;
        // the service already has the address/name from the intent).
        settingsRepository.updateLastDeviceAsync(device.address, device.name)
    }

    override fun onCleared() {
        resumeJob?.cancel()
        stopScan()
        runCatching { stopWatchingBluetooth() }
        // Rider left the scan screen. Re-enable auto-reconnect; if they didn't
        // pick a new wheel, reconnect to the last one (resumeAutoConnect only
        // does so while still disconnected), "reconnect as normal on dismiss".
        val reconnectAddress =
            if (!deviceSelected && cachedAutoConnect) cachedLastAddress else null
        bleConnectionManager.resumeAutoConnect(reconnectAddress, cachedLastName)
        super.onCleared()
    }
}
