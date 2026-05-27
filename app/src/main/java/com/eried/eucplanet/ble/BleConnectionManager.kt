package com.eried.eucplanet.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.eried.eucplanet.ble.virtual.VirtualWheel
import com.eried.eucplanet.ble.virtual.VirtualWheelRegistry
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    INITIALIZING,
    CONNECTED
}

@Singleton
class BleConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelAdapter: WheelAdapter
) {
    companion object {
        private const val TAG = "BleConnection"

        // Client Characteristic Configuration Descriptor: same for every wheel family.
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Connected wheel's display label: the BLE advertised name, with
     *  " (virtual)" appended for simulator wheels. Set on every connect;
     *  the UI only reads it while [connectionState] is CONNECTED. */
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    /** Brand of the connected wheel, from the active adapter. Set on connect. */
    private val _connectedBrand = MutableStateFlow<String?>(null)
    val connectedBrand: StateFlow<String?> = _connectedBrand.asStateFlow()

    private val _decodedResults = MutableSharedFlow<DecodeResult>(extraBufferCapacity = 64)
    /** Stream of decoded results from the active wheel adapter. */
    val decodedResults: SharedFlow<DecodeResult> = _decodedResults.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var currentAddress: String? = null
    /** BLE advertised name from the most recent connect call, kept across reconnects. */
    private var currentName: String? = null
    private var shouldReconnect = true

    /**
     * Set true while the scan / "Searching" screen is open. The auto-reconnect
     * holds off until it clears, so the app doesn't pull the rider back to the
     * previous wheel while they're on that screen choosing one. Cleared when
     * they leave the screen, at which point a held reconnect resumes.
     */
    @Volatile var autoConnectSuppressed: Boolean = false

    // Write serialization - only one BLE write at a time
    private val writeChannel = Channel<ByteArray>(Channel.BUFFERED)
    private var writeReady = false

    // Active virtual wheel when in demo mode (address starts with "VIRTUAL:").
    // When non-null, writes are routed to the simulator instead of GATT and the
    // simulator's response bytes are fed back through the adapter pipeline as if
    // they had arrived as real BLE notifications. GATT handles stay null.
    @Volatile private var virtualWheel: VirtualWheel? = null

    init {
        scope.launch { processWriteQueue() }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String? = null) {
        // Demo / simulator mode: VIRTUAL:<id> bypasses GATT and connects to a fake wheel.
        val virtualId = VirtualWheelRegistry.parsePseudoAddress(address)
        if (virtualId != null) {
            connectVirtual(virtualId)
            return
        }

        currentAddress = address
        // Hold on to the name so the auto-reconnect path keeps the same hint;
        // otherwise a P6 that briefly drops would come back as an unknown wheel.
        currentName = name ?: currentName
        _connectedDeviceName.value = currentName
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
            "Connect requested: name=${currentName ?: "(unknown)"} address=$address"
        )
        shouldReconnect = true
        _connectionState.value = ConnectionState.CONNECTING

        // Adapter pre-selects model from the BLE name; needed for the InMotion
        // P6 because its legacy carType query returns zeros and we'd otherwise
        // never identify it before sending V14-shaped queries the wheel ignores.
        // If the adapter returned a ModelName from the name alone, surface it
        // immediately so the speed-limit slider cap (and other model-keyed UI
        // bits) reflect the wheel's real ceiling instead of the V14 fallback.
        wheelAdapter.notifyConnectingTo(currentName)?.let {
            _decodedResults.tryEmit(it)
        }
        _connectedBrand.value = wheelAdapter.brand

        val device: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Skip GATT entirely and run a simulated wheel. The fake produces the same
     * raw-byte responses a real wheel would emit, so the parser/adapter/repository
     * pipeline runs unchanged. Disconnect by calling [disconnect] as usual.
     */
    @Volatile private var virtualTickJob: kotlinx.coroutines.Job? = null

    private fun connectVirtual(id: String) {
        val wheel = VirtualWheelRegistry.create(id) ?: run {
            Log.e(TAG, "Unknown virtual wheel id: $id")
            return
        }
        Log.i(TAG, "Connecting to virtual wheel: ${wheel.displayName}")
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
            "Connect requested: virtual id=$id name=${wheel.bleName.ifEmpty { "(none)" }} display=\"${wheel.displayName}\""
        )
        wheel.reset()
        virtualWheel = wheel
        currentAddress = VirtualWheelRegistry.pseudoAddress(id)
        currentName = wheel.bleName.takeIf { it.isNotEmpty() }
        _connectedDeviceName.value = "${wheel.displayName} (virtual)"
        shouldReconnect = false
        _connectionState.value = ConnectionState.CONNECTING

        // Route the adapter dispatcher to the right family BEFORE the on-connect
        // responses get parsed: same path real BLE follows via the connect()
        // method above. Without this, a Begode virtual wheel would feed bytes
        // through the InMotion V2 adapter (default) and get dropped.
        if (currentName != null) {
            wheelAdapter.notifyConnectingTo(currentName)?.let {
                _decodedResults.tryEmit(it)
            }
        }
        _connectedBrand.value = wheelAdapter.brand

        scope.launch {
            // Brief delays so the UI's connection-state animations actually animate.
            delay(150)
            _connectionState.value = ConnectionState.INITIALIZING
            for (resp in wheel.onConnect()) emitVirtualResponse(resp)
            delay(150)
            // Mark CONNECTED last so writeReady gates open AFTER on-connect responses
            // have already filtered through the adapter: same ordering a real wheel
            // gets, where notifications start landing once the GATT subscription is up.
            writeReady = true
            _connectionState.value = ConnectionState.CONNECTED
            com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                "Connected (virtual): name=${currentName ?: "(none)"} adapter=${wheelAdapter.familyId}"
            )
        }

        // Push-only wheels (Begode, Veteran, push KingSong frames) stream
        // telemetry without being polled. Drive that with a coroutine that
        // calls onTick every 100 ms and emits whatever the simulator hands
        // back. onTick defaults to emptyList() so request/response sims like
        // V14 / P6 stay silent here.
        virtualTickJob?.cancel()
        val tickStart = System.currentTimeMillis()
        virtualTickJob = scope.launch {
            while (true) {
                delay(100)
                val w = virtualWheel ?: break
                val elapsed = System.currentTimeMillis() - tickStart
                for (resp in w.onTick(elapsed)) emitVirtualResponse(resp)
            }
        }
    }

    private fun emitVirtualResponse(rawBytes: ByteArray) {
        for (result in wheelAdapter.onRawNotification(rawBytes)) {
            _decodedResults.tryEmit(result)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        shouldReconnect = false
        currentAddress = null
        currentName = null
        rxCharacteristic = null
        writeReady = false

        // Virtual wheel: just drop the reference; no GATT to tear down.
        if (virtualWheel != null) {
            virtualTickJob?.cancel()
            virtualTickJob = null
            wheelAdapter.onDisconnect()
            virtualWheel = null
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        val g = gatt
        if (g == null) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        // Request clean GATT teardown; close() runs in the STATE_DISCONNECTED callback
        // so the wheel's BLE stack receives the disconnect before we drop the handle.
        try {
            g.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "gatt.disconnect() threw", e)
        }
        // Safety net: if callback doesn't fire, force-close after a short delay.
        scope.launch {
            delay(1000)
            val still = gatt
            if (still != null) {
                Log.w(TAG, "Forcing GATT close after timeout")
                try { still.close() } catch (_: Exception) {}
                gatt = null
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    fun writeCommand(data: ByteArray) {
        // Push-only adapters (Begode, Veteran, KingSong) return an empty
        // array from poll* to signal "nothing to send; just wait for the
        // wheel's notifications". Drop those on the floor instead of
        // burning a GATT write that some BLE stacks log as an error.
        if (data.isEmpty()) return
        Log.d(TAG, "Queuing write: ${data.joinToString(" ") { "%02x".format(it) }}")
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.tx(data)
        writeChannel.trySend(data)
    }

    @SuppressLint("MissingPermission")
    private suspend fun processWriteQueue() {
        for (data in writeChannel) {
            // Virtual mode: hand the write to the simulator and feed any responses
            // back through the adapter pipeline. No GATT, no writeReady gating.
            val virtual = virtualWheel
            if (virtual != null) {
                for (resp in virtual.onWrite(data)) emitVirtualResponse(resp)
                continue
            }
            if (!writeReady || rxCharacteristic == null || gatt == null) {
                Log.w(TAG, "Write skipped: ready=$writeReady rx=${rxCharacteristic != null} gatt=${gatt != null}")
                continue
            }

            writeReady = false
            val characteristic = rxCharacteristic ?: continue
            val g = gatt ?: continue

            // Pick the write type from the active adapter's profile. HM-10
            // (KingSong / Begode / Veteran) uses WRITE_TYPE_NO_RESPONSE to
            // match WheelLog - those modules don't reliably ACK
            // WRITE_TYPE_DEFAULT writes. InMotion V2 / V1 stay on the
            // safer WRITE_TYPE_DEFAULT.
            val writeType = wheelAdapter.bleProfile().writeType
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = g.writeCharacteristic(characteristic, data, writeType)
                Log.d(TAG, "writeCharacteristic result: $result (${data.size} bytes, type=$writeType)")
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                val result = g.writeCharacteristic(characteristic)
                Log.d(TAG, "writeCharacteristic result: $result (${data.size} bytes, type=$writeType)")
            }

            // Wait for write callback or timeout
            delay(20)
            if (!writeReady) {
                delay(180) // longer wait for write-with-response
                writeReady = true // assume success after timeout
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server")
                    _connectionState.value = ConnectionState.INITIALIZING
                    // Go straight to service discovery instead of gating on an
                    // MTU exchange the wheel may never acknowledge. KingSong
                    // S18 (and other cheap HM-10 modules) silently drop the
                    // MTU request and never deliver onMtuChanged, which used
                    // to wedge us in INITIALIZING forever. MTU 512 is now
                    // requested fire-and-forget AFTER the CCCD subscription
                    // is up - it's a latency optimization for the InMotion V2
                    // family (V14 / P6 telemetry frames are 65-86 bytes and
                    // would otherwise arrive as multi-chunk reassembly), not
                    // a correctness requirement; the V2 adapter reassembles
                    // either way. WheelLog upstream uses the same fire-and-
                    // forget pattern via the Blessed library.
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT (status=$status, shouldReconnect=$shouldReconnect)")
                    com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                        "Disconnected: name=${currentName ?: "(unknown)"} status=$status reconnect=$shouldReconnect"
                    )
                    rxCharacteristic = null
                    writeReady = false
                    // Reset adapter framing state for the next connection
                    wheelAdapter.onDisconnect()
                    // Close the GATT here so the underlying connection is fully torn down
                    try { gatt.close() } catch (_: Exception) {}
                    if (this@BleConnectionManager.gatt === gatt) {
                        this@BleConnectionManager.gatt = null
                    }
                    _connectionState.value = ConnectionState.DISCONNECTED

                    if (shouldReconnect && status != 0) {
                        val reconnectAddress = currentAddress
                        scope.launch {
                            delay(2000)
                            // Hold the auto-reconnect while the rider is on the
                            // scan screen choosing a wheel; resume once they
                            // leave it. Skip if they meanwhile picked another
                            // wheel or a connection is already underway.
                            while (autoConnectSuppressed) delay(300)
                            if (shouldReconnect &&
                                reconnectAddress != null &&
                                currentAddress == reconnectAddress &&
                                _connectionState.value == ConnectionState.DISCONNECTED
                            ) {
                                connect(reconnectAddress)
                            }
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Log only - no state transition gated on this callback. See
            // onConnectionStateChange for the reasoning behind the decoupling.
            Log.i(TAG, "MTU changed to $mtu (status=$status)")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                failConnectAndTeardown(gatt, "Service discovery failed (status=$status)")
                return
            }

            var profile = wheelAdapter.bleProfile()
            var service = gatt.getService(profile.serviceUuid)
            if (service == null) {
                // Name-based routing picked the wrong adapter (most often: an
                // unrecognised name fell through to the InMotion V2 default,
                // but the wheel is a KingSong-class HM-10 module advertising
                // as `RW` or similar). Ask the dispatcher to re-route based
                // on the GATT-discovered service set, then retry.
                val discoveredUuids = gatt.services.map { it.uuid }.toSet()
                Log.w(TAG, "Adapter ${wheelAdapter.familyId} service ${profile.serviceUuid} not on wheel; " +
                        "discovered services=$discoveredUuids - attempting fallback")
                val rerouted = wheelAdapter.pickAdapterByDiscoveredServices(discoveredUuids, currentName)
                if (rerouted) {
                    profile = wheelAdapter.bleProfile()
                    service = gatt.getService(profile.serviceUuid)
                    if (service != null) {
                        Log.i(TAG, "Adapter rerouted by service-UUID to ${wheelAdapter.familyId}")
                        _connectedBrand.value = wheelAdapter.brand
                    }
                }
                if (service == null) {
                    failConnectAndTeardown(gatt, "No known wheel service on this device")
                    return
                }
            }

            rxCharacteristic = service.getCharacteristic(profile.writeCharacteristic)
            val txCharacteristic = service.getCharacteristic(profile.notifyCharacteristic)

            if (rxCharacteristic == null || txCharacteristic == null) {
                failConnectAndTeardown(gatt,
                    "Adapter characteristics not found on service ${profile.serviceUuid}")
                return
            }

            // Enable notifications on TX. The CCCD descriptor write is what
            // actually subscribes us to the wheel's push stream; it completes
            // asynchronously via onDescriptorWrite. We must NOT go CONNECTED
            // (which lets the init sequence start writing) until that write
            // lands; otherwise the init writes race it on the single GATT
            // operation slot, the subscription is lost, and the wheel's
            // replies are silently dropped. (KingSong KS-16X: telemetry only
            // started after a manual write nudged the stack into settling the
            // CCCD ~30 s after connect.)
            gatt.setCharacteristicNotification(txCharacteristic, true)
            Log.i(TAG, "Service ${profile.serviceUuid} ready (adapter=${wheelAdapter.familyId})")
            val descriptor = txCharacteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                writeEnableNotificationDescriptor(gatt, descriptor)
                // HM-10 (KingSong / Begode / Veteran) modules occasionally
                // drop the first CCCD write silently; WheelLog ships a
                // belt-and-braces redundant write for the same family. Fire
                // a second write ~750 ms after the first if we're still
                // INITIALIZING and the descriptor write looks "stuck".
                // Gated on the HM-10 notify char (0xFFE1) so V14 / P6
                // (Nordic UART) and InMotion V1 (0xFFE4) aren't touched.
                if (txCharacteristic.uuid == BleProfile.HM10.notifyCharacteristic) {
                    scope.launch {
                        kotlinx.coroutines.delay(750L)
                        if (_connectionState.value == ConnectionState.INITIALIZING &&
                            this@BleConnectionManager.gatt === gatt
                        ) {
                            Log.i(TAG, "HM-10 redundant CCCD write")
                            writeEnableNotificationDescriptor(gatt, descriptor)
                        }
                    }
                }
                // Safety net: if the stack never delivers onDescriptorWrite,
                // force the connection through so we can't hang in INITIALIZING.
                scope.launch {
                    kotlinx.coroutines.delay(4000L)
                    if (_connectionState.value == ConnectionState.INITIALIZING) {
                        Log.w(TAG, "CCCD onDescriptorWrite not seen, forcing connect")
                        markReadyAndConnected()
                    }
                }
            } else {
                // No CCCD on this characteristic, nothing to wait for.
                markReadyAndConnected()
            }
        }

        @SuppressLint("MissingPermission")
        private fun writeEnableNotificationDescriptor(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }

        /**
         * Tear down the GATT and surface a visible disconnect when the
         * connect flow fails after STATE_CONNECTED but before CONNECTED.
         * Without this, the UI sits in INITIALIZING forever because no
         * other code path transitions state out of that. The app-level
         * auto-reconnect at line ~348 picks up from the
         * STATE_DISCONNECTED callback that gatt.disconnect() triggers.
         */
        @SuppressLint("MissingPermission")
        private fun failConnectAndTeardown(gatt: BluetoothGatt, reason: String) {
            Log.e(TAG, "Connect failed: $reason")
            com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                "Connect failed: name=${currentName ?: "(unknown)"} reason=$reason"
            )
            try { gatt.disconnect() } catch (_: Exception) {}
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // The CCCD write completing means the wheel will now actually push
            // notifications to us; only now is it safe to go CONNECTED and let
            // the init sequence start writing.
            if (descriptor.uuid == CCCD_UUID) {
                Log.i(TAG, "CCCD notification-enable confirmed (status=$status)")
                markReadyAndConnected()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite status=$status")
            writeReady = true
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            processIncomingData(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processIncomingData(value)
        }
    }

    /**
     * Idempotent transition into CONNECTED. Called once the CCCD
     * notification-enable write has completed (onDescriptorWrite), or by the
     * no-CCCD / watchdog fallbacks in onServicesDiscovered. Guarded on the
     * INITIALIZING state; onConnectionStateChange sets that the moment GATT
     * connects, and the flow stays there until this runs, so the watchdog and
     * the real callback cannot double-fire it, and a late callback after a
     * disconnect is a no-op.
     */
    @SuppressLint("MissingPermission")
    private fun markReadyAndConnected() {
        if (_connectionState.value != ConnectionState.INITIALIZING) return
        writeReady = true
        _connectionState.value = ConnectionState.CONNECTED
        Log.i(TAG, "Connected (adapter=${wheelAdapter.familyId})")
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
            "Connected: name=${currentName ?: "(unknown)"} adapter=${wheelAdapter.familyId}"
        )
        // Fire-and-forget MTU bump. Has to happen AFTER the CCCD descriptor
        // write completes - Android GATT is strictly serial and overlapping
        // requestMtu with a pending descriptor write can wedge with status
        // 133 on stricter stacks. If the wheel honors it, V14 / P6 telemetry
        // frames arrive in one notification instead of being reassembled; if
        // not, the V2 adapter's reassembly path picks up the chunked frames
        // exactly as it has always done.
        try { gatt?.requestMtu(512) } catch (_: Exception) {}
    }

    /**
     * Forward each BLE notification to the active wheel adapter and emit any
     * DecodeResults it produces. Framing (reassembly, parsing) lives in the
     * adapter; each protocol family has its own.
     */
    private fun processIncomingData(data: ByteArray) {
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.rx(data)
        for (result in wheelAdapter.onRawNotification(data)) {
            _decodedResults.tryEmit(result)
        }
    }
}
