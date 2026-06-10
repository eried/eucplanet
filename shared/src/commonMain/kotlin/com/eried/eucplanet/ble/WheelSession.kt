package com.eried.eucplanet.ble

import com.eried.eucplanet.ble.transport.BleConnState
import com.eried.eucplanet.ble.transport.BleConnection
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives one connected wheel. Feeds a [BleConnection]'s incoming notification
 * frames into the brand [WheelAdapter] and exposes the decoded telemetry,
 * settings and identity as observable [StateFlow]s, while running the adapter's
 * init handshake + realtime/settings poll loop.
 *
 * This is the shared, platform-independent heart of "connected to a wheel": the
 * iOS app (CoreBluetooth transport) and a future unified Android transport both
 * produce a [BleConnection]; this class turns that into [WheelData]. It mirrors
 * the decode/poll role of the legacy `:app` WheelRepository + BleConnectionManager
 * loop, minus the Android plumbing.
 *
 * NOTE: only does anything against a real wheel — the iOS Simulator has no
 * Bluetooth radio, so [BleTransport.scan]/[connect] never produce a connection
 * there. Build it now; validate on the iPhone.
 */
class WheelSession(
    private val connection: BleConnection,
    private val adapter: WheelAdapter,
    private val scope: CoroutineScope,
    private val deviceName: String? = null,
) {
    private val _data = MutableStateFlow(WheelData())
    val data: StateFlow<WheelData> = _data.asStateFlow()

    private val _settings = MutableStateFlow(WheelSettings())
    val settings: StateFlow<WheelSettings> = _settings.asStateFlow()

    private val _modelName = MutableStateFlow<String?>(null)
    val modelName: StateFlow<String?> = _modelName.asStateFlow()

    private val _firmware = MutableStateFlow<String?>(null)
    val firmware: StateFlow<String?> = _firmware.asStateFlow()

    /** The wheel family routing landed on (e.g. "KingSong"); null until known. */
    val brand: String get() = adapter.brand

    /** Live connection state from the transport. */
    val connectionState: StateFlow<BleConnState> get() = connection.state

    private var readJob: Job? = null
    private var pollJob: Job? = null

    /** Begin decoding + polling. Idempotent-ish: call once after connect. */
    fun start() {
        // Let the adapter pre-select a sub-model / protocol from the BLE name
        // (e.g. InMotion P6 extended routing, Ninebot Z vs legacy) before I/O.
        adapter.notifyConnectingTo(deviceName)?.let { _modelName.value = it.name }

        // 1) Consume incoming notification frames -> decode -> publish.
        readJob = scope.launch {
            connection.incoming.collect { frame ->
                for (result in adapter.onRawNotification(frame)) {
                    applyResult(result)
                    // V14 / P6-style password auth: when the wheel hands us the
                    // encrypted key, echo back the verify packet to unlock writes.
                    if (result is DecodeResult.AuthKey) {
                        adapter.verifyAuth(result.encryptedKey)?.let { connection.write(it) }
                    }
                }
            }
        }

        // 2) Init handshake, then the realtime + settings poll loop.
        pollJob = scope.launch {
            for (pkt in adapter.initSequence()) {
                if (pkt.isNotEmpty()) connection.write(pkt)
                delay(INIT_PACING_MS)
            }
            if (adapter.requiresConnectAuth()) {
                adapter.requestAuthKey()?.let { connection.write(it) }
            }
            var tick = 0
            while (isActive) {
                adapter.pollRealtime().takeIf { it.isNotEmpty() }?.let { connection.write(it) }
                if (tick % SETTINGS_EVERY == 0) {
                    adapter.pollSettings().takeIf { it.isNotEmpty() }?.let { connection.write(it) }
                }
                if (tick % STATS_EVERY == 0) {
                    adapter.pollStats()?.takeIf { it.isNotEmpty() }?.let { connection.write(it) }
                }
                tick++
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // --- Control passthroughs (no-op when the family doesn't support the action) ---

    suspend fun horn() {
        adapter.horn()?.let { connection.write(it) }
        adapter.hornFollowup()?.let { connection.write(it) }
    }

    suspend fun setLight(on: Boolean) {
        adapter.setLight(on)?.let { connection.write(it) }
        adapter.setLightFollowup(on)?.let { connection.write(it) }
    }

    /** Stop polling, reset adapter state, and drop the BLE link. */
    fun stop() {
        pollJob?.cancel()
        readJob?.cancel()
        adapter.onDisconnect()
        connection.close()
    }

    private fun applyResult(result: DecodeResult) {
        when (result) {
            is DecodeResult.Telemetry -> {
                val prev = _data.value
                var d = result.data
                // totalDistance arrives on its own frame (DecodeResult.TotalDistance)
                // for some families; carry the last value so realtime frames that
                // default it to 0 don't blank the odometer between updates.
                if (d.totalDistance == 0f && prev.totalDistance != 0f) {
                    d = d.copy(totalDistance = prev.totalDistance)
                }
                _data.value = d
            }
            is DecodeResult.Settings -> _settings.value = result.data
            is DecodeResult.ModelName -> _modelName.value = result.name
            is DecodeResult.Firmware -> _firmware.value = result.display
            is DecodeResult.TotalDistance -> _data.value = _data.value.copy(totalDistance = result.km)
            is DecodeResult.P6Temperatures -> {
                val temps = listOfNotNull(result.mosC, result.motorC, result.driverBoardC)
                if (temps.isNotEmpty()) {
                    _data.value = _data.value.copy(
                        temperatures = temps,
                        maxTemperature = temps.max(),
                    )
                }
            }
            is DecodeResult.AuthKey,
            is DecodeResult.AuthConfirm,
            DecodeResult.Unknown -> Unit
        }
    }

    companion object {
        private const val INIT_PACING_MS = 60L
        private const val POLL_INTERVAL_MS = 250L
        private const val SETTINGS_EVERY = 10   // ~ every 2.5 s
        private const val STATS_EVERY = 20      // ~ every 5 s
    }
}
