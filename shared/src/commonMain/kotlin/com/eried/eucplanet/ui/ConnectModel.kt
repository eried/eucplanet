package com.eried.eucplanet.ui

import com.eried.eucplanet.ble.CompositeWheelAdapter
import com.eried.eucplanet.ble.WheelSession
import com.eried.eucplanet.ble.transport.BleDevice
import com.eried.eucplanet.ble.transport.createBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the Connect screen: owns the platform [createBleTransport], runs a scan,
 * and accumulates discovered peripherals into an observable list. On the iOS
 * Simulator (no Bluetooth radio) the list stays empty and the UI shows the demo
 * wheels instead; on a real iPhone it fills with nearby wheels and tapping one
 * opens a live [WheelSession].
 */
class ConnectModel(private val scope: CoroutineScope) {
    private val transport = createBleTransport()

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    init {
        scope.launch {
            transport.scan().collect { dev ->
                // Dedup by address, keep the freshest entry, strongest signal first.
                val merged = (_devices.value.associateBy { it.address } + (dev.address to dev))
                    .values.sortedByDescending { it.rssi }
                _devices.value = merged
            }
        }
    }

    /** Connect to [device], build the routing adapter, and start a live session. */
    suspend fun connect(device: BleDevice): WheelSession {
        val adapter = CompositeWheelAdapter()
        // Pre-select the family from the advertised name so we bind the right
        // GATT profile (HM-10 / Nordic UART / InMotion V1) on connect.
        adapter.notifyConnectingTo(device.name)
        val connection = transport.connect(device.address, adapter.bleProfile())
        val session = WheelSession(connection, adapter, scope, device.name)
        session.start()
        return session
    }
}
