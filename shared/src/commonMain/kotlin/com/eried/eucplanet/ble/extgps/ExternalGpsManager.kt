package com.eried.eucplanet.ble.extgps

import com.eried.eucplanet.ble.BleProfile
import com.eried.eucplanet.ble.transport.BleConnState
import com.eried.eucplanet.ble.transport.BleConnection
import com.eried.eucplanet.ble.transport.BleDevice
import com.eried.eucplanet.ble.transport.createBleTransport
import com.eried.eucplanet.data.ExternalGpsSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns an external BLE GPS box (RaceBox) on its OWN [createBleTransport] instance —
 * a second, independent CoreBluetooth central, completely separate from the wheel's
 * transport, so connecting the GPS never disturbs the wheel connection. Scans for
 * RaceBox advertisements, connects over Nordic UART, and decodes the UBX stream into
 * a live [ExternalGpsSample] flow. Android-parity External GPS, minus the per-vendor
 * registry (only RaceBox ships today).
 */
class ExternalGpsManager(private val scope: CoroutineScope) {
    private val transport = createBleTransport()
    private val adapter = RaceBoxAdapter()

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    private val _sample = MutableStateFlow<ExternalGpsSample?>(null)
    val sample: StateFlow<ExternalGpsSample?> = _sample.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var scanJob: Job? = null
    private var readJob: Job? = null
    private var stateJob: Job? = null
    private var conn: BleConnection? = null

    fun startScan() {
        _devices.value = emptyList()
        _scanning.value = true
        scanJob?.cancel()
        scanJob = scope.launch {
            transport.scan().collect { d ->
                val name = d.name ?: return@collect
                if (adapter.matches(name)) {
                    _devices.update { cur -> if (cur.any { it.address == d.address }) cur else cur + d }
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel(); scanJob = null
        _scanning.value = false
    }

    fun connect(address: String) {
        scope.launch {
            stopScan()
            try {
                val c = transport.connect(address, BleProfile.NORDIC_UART)
                conn = c
                _connected.value = true
                readJob?.cancel()
                readJob = scope.launch {
                    c.incoming.collect { frame -> adapter.decode(frame)?.let { _sample.value = it } }
                }
                stateJob?.cancel()
                stateJob = scope.launch {
                    c.state.collect { if (it == BleConnState.Disconnected) { _connected.value = false } }
                }
            } catch (e: Exception) {
                _connected.value = false
            }
        }
    }

    fun disconnect() {
        readJob?.cancel(); readJob = null
        stateJob?.cancel(); stateJob = null
        conn?.close(); conn = null
        _connected.value = false
        _sample.value = null
    }
}
