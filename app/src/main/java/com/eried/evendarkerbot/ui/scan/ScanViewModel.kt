package com.eried.evendarkerbot.ui.scan

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.evendarkerbot.ble.BleDevice
import com.eried.evendarkerbot.ble.BleScanner
import com.eried.evendarkerbot.data.repository.SettingsRepository
import com.eried.evendarkerbot.service.WheelService
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

    private var scanJob: Job? = null

    fun startScan() {
        if (_isScanning.value) return
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
