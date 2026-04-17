package com.eried.eucplanet.ui.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.BleDevice
import com.eried.eucplanet.ble.BleScanner
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.service.WheelService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _missingPermissions = MutableStateFlow<List<String>>(emptyList())
    val missingPermissions: StateFlow<List<String>> = _missingPermissions.asStateFlow()

    private var scanJob: Job? = null

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
        _isScanning.value = true
        _devices.value = emptyList()

        scanJob = viewModelScope.launch {
            bleScanner.scanForDevices()
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

    fun selectDevice(device: BleDevice) {
        stopScan()
        viewModelScope.launch {
            settingsRepository.updateLastDevice(device.address, device.name)

            val intent = Intent(context, WheelService::class.java).apply {
                action = WheelService.ACTION_CONNECT
                putExtra(WheelService.EXTRA_ADDRESS, device.address)
            }
            context.startForegroundService(intent)
        }
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }
}
