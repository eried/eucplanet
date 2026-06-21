@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.eried.eucplanet.ble.transport

import com.eried.eucplanet.ble.BleProfile
import com.eried.eucplanet.ble.BleWriteType
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy

actual fun createBleTransport(): BleTransport = IosBleTransport()

/** CoreBluetooth-backed transport. Plain Kotlin (implements [BleTransport]) that
 *  owns a CBCentralManager + a separate NSObject delegate (Kotlin/Native forbids
 *  one class mixing a Kotlin interface with Obj-C supertypes). */
private class IosBleTransport : BleTransport {
    private val discoveries = MutableSharedFlow<BleDevice>(extraBufferCapacity = 128)
    private val peripherals = mutableMapOf<String, CBPeripheral>()
    private var poweredOn = false
    private var wantScan = false
    private var active: IosBleConnection? = null
    private var connectCont: CompletableDeferred<Unit>? = null
    private val delegate = CentralDelegate()
    private val central = CBCentralManager(delegate, null)

    private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            poweredOn = central.state == CBManagerStatePoweredOn
            if (poweredOn && wantScan) central.scanForPeripheralsWithServices(null, null)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val id = didDiscoverPeripheral.identifier.UUIDString
            peripherals[id] = didDiscoverPeripheral
            discoveries.tryEmit(BleDevice(id, didDiscoverPeripheral.name, RSSI.intValue))
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            connectCont?.complete(Unit)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            connectCont?.completeExceptionally(IllegalStateException("BLE connect failed: ${error?.localizedDescription}"))
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            // Only react to the ACTIVE connection's peripheral — a superseded
            // connect's late disconnect must not tear down the connection that
            // replaced it (user double-tapped a second wheel before the first resolved).
            if (active?.peripheral === didDisconnectPeripheral) active?.onDisconnected()
        }
    }

    override fun scan(): Flow<BleDevice> = callbackFlow {
        wantScan = true
        if (poweredOn) central.scanForPeripheralsWithServices(null, null)
        val job = launch { discoveries.collect { trySend(it) } }
        awaitClose { wantScan = false; central.stopScan(); job.cancel() }
    }

    override suspend fun connect(address: String, profile: BleProfile): BleConnection {
        // A new connect supersedes any in-flight one (e.g. the user tapped a
        // second wheel before the first resolved): fail the old continuation and
        // tear down the old connection so we never abandon a suspended coroutine.
        connectCont?.completeExceptionally(CancellationException("superseded by a new connect"))
        connectCont = null
        active?.close()
        active = null
        // Stop scanning AND clear the intent, so a later centralManagerDidUpdateState
        // (e.g. on background resume) doesn't restart a scan during the connection.
        wantScan = false
        central.stopScan()
        val p = peripherals[address]
            ?: (central.retrievePeripheralsWithIdentifiers(listOf(NSUUID(uUIDString = address)))
                .firstOrNull() as? CBPeripheral)
            ?: throw IllegalStateException("peripheral $address not found")
        val conn = IosBleConnection(central, p, profile)
        active = conn
        val cont = CompletableDeferred<Unit>()
        connectCont = cont
        central.connectPeripheral(p, null)
        cont.await()
        // Begin discovery, then DO NOT return until the write characteristic is
        // bound — otherwise the adapter's init/auth writes (issued immediately by
        // WheelSession) would hit a null writeChar and be silently dropped.
        conn.start()
        withTimeout(GATT_READY_TIMEOUT_MS) { conn.awaitReady() }
        return conn
    }

    private companion object {
        const val GATT_READY_TIMEOUT_MS = 15_000L
    }
}

private class IosBleConnection(
    private val central: CBCentralManager,
    val peripheral: CBPeripheral,
    private val profile: BleProfile,
) : BleConnection {

    private val _state = MutableStateFlow(BleConnState.Connecting)
    override val state = _state.asStateFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val incoming = _incoming.asSharedFlow()
    private var writeChar: CBCharacteristic? = null
    private val delegate = PeripheralDelegate()

    /** Completed once the write characteristic is bound (or discovery fails), so
     *  the transport doesn't start writing into a null characteristic. */
    private val ready = CompletableDeferred<Unit>()

    fun start() {
        peripheral.delegate = delegate
        // Stay Connecting until characteristics are discovered; only then is the
        // connection actually usable for writes (writeChar bound, notify enabled).
        peripheral.discoverServices(listOf(CBUUID.UUIDWithString(profile.serviceUuid)))
    }

    /** Suspends until services/characteristics are discovered and bound. */
    suspend fun awaitReady() = ready.await()

    fun onDisconnected() {
        _state.value = BleConnState.Disconnected
        if (!ready.isCompleted) {
            ready.completeExceptionally(IllegalStateException("disconnected during discovery"))
        }
    }

    private inner class PeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val svc = peripheral.services?.firstOrNull {
                (it as CBService).UUID.UUIDString.equals(profile.serviceUuid, ignoreCase = true)
            } as? CBService ?: return
            peripheral.discoverCharacteristics(
                listOf(
                    CBUUID.UUIDWithString(profile.writeCharacteristic),
                    CBUUID.UUIDWithString(profile.notifyCharacteristic),
                ),
                svc,
            )
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            (didDiscoverCharacteristicsForService.characteristics ?: emptyList<Any?>()).forEach { ch ->
                val c = ch as CBCharacteristic
                val u = c.UUID.UUIDString
                if (u.equals(profile.notifyCharacteristic, ignoreCase = true)) peripheral.setNotifyValue(true, c)
                if (u.equals(profile.writeCharacteristic, ignoreCase = true)) writeChar = c
            }
            // Now that the write characteristic is bound, the connection is usable:
            // flip to Connected and release the transport's connect() gate so the
            // first init/auth writes actually reach the wheel.
            if (writeChar != null && !ready.isCompleted) {
                _state.value = BleConnState.Connected
                ready.complete(Unit)
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val data = didUpdateValueForCharacteristic.value ?: return
            _incoming.tryEmit(data.toByteArray())
        }
    }

    override suspend fun write(bytes: ByteArray) {
        val c = writeChar ?: return
        val type = if (profile.writeType == BleWriteType.NO_RESPONSE) {
            CBCharacteristicWriteWithoutResponse
        } else {
            CBCharacteristicWriteWithResponse
        }
        peripheral.writeValue(bytes.toNSData(), c, type)
    }

    override fun close() {
        central.cancelPeripheralConnection(peripheral)
    }
}

private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
}
