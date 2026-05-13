package com.eried.eucplanet.data.repository

import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.gps.ExternalGpsAdapter
import com.eried.eucplanet.ble.gps.ExternalGpsConnectionManager
import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the external-GPS BLE connection and exposes the current sample.
 *
 * State flows:
 *  * [connectionState] mirrors the underlying GATT manager.
 *  * [currentSample] is the latest decoded fix from whichever adapter is active.
 *
 * Pairing persists in three AppSettings columns (address / name / source enum)
 * so [autoConnect] can re-open the paired device on app start without prompting.
 */
@Singleton
class ExternalGpsRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val connectionManager: ExternalGpsConnectionManager,
    private val adapters: Set<@JvmSuppressWildcards ExternalGpsAdapter>
) {
    companion object {
        private const val TAG = "ExtGpsRepo"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Debug-only override so the fake-RaceBox emitter can show as Connected
    // while it spins synthetic samples (the real GATT layer obviously stays
    // Disconnected since no BLE handshake happens). REMOVE with the demo.
    private val _demoConnectionOverride = MutableStateFlow<ConnectionState?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val connectionState: StateFlow<ConnectionState> = kotlinx.coroutines.flow.combine(
        connectionManager.connectionState,
        _demoConnectionOverride
    ) { real, demo -> demo ?: real }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ConnectionState.DISCONNECTED)

    private val _currentSample = MutableStateFlow<ExternalGpsSample?>(null)
    val currentSample: StateFlow<ExternalGpsSample?> = _currentSample.asStateFlow()

    // Volatile mirror of the per-axis remap settings, refreshed whenever the
    // user changes one. Reading from a volatile field in the hot sample path
    // is cheaper than re-collecting the settings flow on every BLE frame.
    // Values: "X", "-X", "Y", "-Y", "Z", "-Z".
    @Volatile private var mapX: String = "X"
    @Volatile private var mapY: String = "Y"
    @Volatile private var mapZ: String = "Z"

    init {
        // Bridge the connection manager's sample stream into a StateFlow so the
        // dashboard can render the latest fix without subscribing to a hot
        // SharedFlow each time it recomposes. Apply per-axis invert flips here
        // so every downstream consumer (compare tab, recording, etc.) sees the
        // same orientation-corrected values.
        scope.launch {
            connectionManager.samples.collect { raw ->
                _currentSample.value = raw?.let(::applyAxisRemap)
            }
        }
        scope.launch {
            settingsRepository.settings.collect { s ->
                mapX = s.raceboxMapX
                mapY = s.raceboxMapY
                mapZ = s.raceboxMapZ
            }
        }
        // One-time cleanup of the old demo state: if a previous build paired
        // the synthetic "DEMO:RACEBOX" device, drop it on startup so the user
        // doesn't see a permanently-disconnected fake pairing. Also reset the
        // master toggle that the demo force-enabled.
        scope.launch {
            val current = settingsRepository.get()
            if (current.externalGpsAddress == "DEMO:RACEBOX") {
                settingsRepository.update(
                    current.copy(
                        externalGpsAddress = null,
                        externalGpsName = null,
                        externalGpsSource = null,
                        gpsLogAdditional = false
                    )
                )
            }
        }

        /*
        // TEMP DEMO: synthesize a RaceBox session so the UI can be reviewed
        // without hardware. Auto-pairs a fake device, marks the connection
        // as Connected, then emits sinusoidal speed + g-force samples at 5 Hz.
        // Re-enable only when validating UI changes without real hardware.
        if (com.eried.eucplanet.BuildConfig.DEBUG) {
            scope.launch {
                kotlinx.coroutines.delay(200)
                val current = settingsRepository.get()
                if (current.externalGpsAddress == null ||
                    current.externalGpsAddress == "DEMO:RACEBOX") {
                    settingsRepository.update(
                        current.copy(
                            externalGpsAddress = "DEMO:RACEBOX",
                            externalGpsName = "RaceBox Demo",
                            externalGpsSource = "RACEBOX",
                            gpsLogAdditional = true
                        )
                    )
                }
                _demoConnectionOverride.value = ConnectionState.CONNECTED
                var t = 0.0
                while (true) {
                    val sample = com.eried.eucplanet.data.model.ExternalGpsSample(
                        source = com.eried.eucplanet.data.model.ExternalGpsSource.RACEBOX,
                        speedKmh = 30f + 10f * kotlin.math.sin(t).toFloat(),
                        latitude = 59.91 + 0.001 * kotlin.math.cos(t * 0.1),
                        longitude = 10.74 + 0.001 * kotlin.math.sin(t * 0.1),
                        altitudeMeters = 50f,
                        accuracyMeters = 1.5f,
                        accelXG = 0.25f * kotlin.math.sin(t * 1.5).toFloat(),
                        accelYG = 0.20f * kotlin.math.cos(t * 1.3).toFloat(),
                        accelZG = 0.10f * kotlin.math.sin(t * 0.7).toFloat(),
                        headingDeg = ((t * 30) % 360).toFloat(),
                        verticalSpeedMps = 0f,
                        numSatellites = 12
                    )
                    _currentSample.value = applyAxisRemap(sample)
                    kotlinx.coroutines.delay(200)
                    t += 0.2
                }
            }
        }
        */
    }

    private fun applyAxisRemap(s: ExternalGpsSample): ExternalGpsSample {
        // Fast path: nothing to remap when no accel data is in the frame
        // (e.g. standard NAV-PVT samples).
        if (s.accelXG == null && s.accelYG == null && s.accelZG == null) return s
        return s.copy(
            accelXG = pickAxis(mapX, s.accelXG, s.accelYG, s.accelZG),
            accelYG = pickAxis(mapY, s.accelXG, s.accelYG, s.accelZG),
            accelZG = pickAxis(mapZ, s.accelXG, s.accelYG, s.accelZG)
        )
    }

    private fun pickAxis(key: String, x: Float?, y: Float?, z: Float?): Float? = when (key) {
        "X" -> x
        "-X" -> x?.let { -it }
        "Y" -> y
        "-Y" -> y?.let { -it }
        "Z" -> z
        "-Z" -> z?.let { -it }
        else -> x
    }

    /** Address of the paired device, or null if no pairing. Mirrors AppSettings. */
    suspend fun pairedAddress(): String? = settingsRepository.get().externalGpsAddress

    /** Display name of the paired device. */
    suspend fun pairedName(): String? = settingsRepository.get().externalGpsName

    /** Source family of the paired device (RaceBox today; more later). */
    suspend fun pairedSource(): ExternalGpsSource? =
        settingsRepository.get().externalGpsSource?.let {
            runCatching { ExternalGpsSource.valueOf(it) }.getOrNull()
        }

    suspend fun setPairing(address: String, name: String, source: ExternalGpsSource) {
        val s = settingsRepository.get()
        settingsRepository.update(
            s.copy(
                externalGpsAddress = address,
                externalGpsName = name,
                externalGpsSource = source.name
            )
        )
        Log.i(TAG, "Paired ${source.displayName}: $name ($address)")
    }

    suspend fun clearPairing() {
        val s = settingsRepository.get()
        settingsRepository.update(
            s.copy(
                externalGpsAddress = null,
                externalGpsName = null,
                externalGpsSource = null
            )
        )
        disconnect()
        Log.i(TAG, "Pairing cleared")
    }

    /**
     * Open the GATT connection to the currently-paired device. Returns silently
     * if no pairing exists. Picks the matching adapter from the registry by
     * source-family enum; if the family no longer ships an adapter (e.g. user
     * upgraded a build that removed Draggy support) the call is a no-op.
     */
    suspend fun connectPaired(): Boolean {
        val address = pairedAddress() ?: return false
        val source = pairedSource() ?: return false
        val adapter = adapters.firstOrNull { it.source == source } ?: run {
            Log.w(TAG, "No adapter for paired source $source — skipping connect")
            return false
        }
        connectionManager.connect(address, adapter)
        return true
    }

    fun connect(address: String, adapter: ExternalGpsAdapter) {
        connectionManager.connect(address, adapter)
    }

    fun disconnect() {
        _currentSample.value = null
        connectionManager.disconnect()
    }

    /**
     * Sample the raw (pre-remap) accel for [durationMs] and return which raw
     * axis carried the most motion plus its dominant sign. Caller is expected
     * to ask the user to move the device along one logical direction (forward,
     * lateral, vertical) before calling.
     *
     * Algorithm: high-pass each axis by subtracting its session mean, then
     * pick the one with the largest peak-to-peak swing. Sign is the sign of
     * the integrated displacement direction (sum of consecutive positive minus
     * negative samples) so a "push forward then back" motion still resolves
     * to a forward intent.
     *
     * Returns one of "X", "-X", "Y", "-Y", "Z", "-Z" or null if no data
     * arrived during the window.
     */
    suspend fun captureDominantAxis(durationMs: Long): String? {
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        val zs = mutableListOf<Float>()
        val end = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < end) {
            val s = _currentSample.value
            if (s != null) {
                // applyAxisRemap was applied on entry — we want raw axes, so
                // re-resolve them by reversing the current map.
                val (rx, ry, rz) = reverseRemap(s.accelXG, s.accelYG, s.accelZG)
                if (rx != null) xs += rx
                if (ry != null) ys += ry
                if (rz != null) zs += rz
            }
            kotlinx.coroutines.delay(40)  // ~25 Hz polling
        }
        if (xs.isEmpty() && ys.isEmpty() && zs.isEmpty()) return null

        val xRange = (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f)
        val yRange = (ys.maxOrNull() ?: 0f) - (ys.minOrNull() ?: 0f)
        val zRange = (zs.maxOrNull() ?: 0f) - (zs.minOrNull() ?: 0f)
        val biggest = listOf("X" to xRange, "Y" to yRange, "Z" to zRange).maxBy { it.second }.first
        val data = when (biggest) {
            "X" -> xs
            "Y" -> ys
            else -> zs
        }
        // Sign: average of the first quarter of the samples minus average of
        // the last quarter. A push-forward-then-back motion gives a positive
        // early peak and a negative late peak → sign "+", which we encode as
        // no leading minus. If the user moved in reverse first the sign flips.
        val q = data.size / 4
        if (q < 1) return biggest
        val firstAvg = data.take(q).average().toFloat()
        val lastAvg = data.takeLast(q).average().toFloat()
        val signPositive = firstAvg - lastAvg >= 0f
        return if (signPositive) biggest else "-$biggest"
    }

    private fun reverseRemap(
        rxOut: Float?, ryOut: Float?, rzOut: Float?
    ): Triple<Float?, Float?, Float?> {
        // Build a raw triple by reversing the map currently applied. Each
        // output value tells us about one raw axis; if the same raw axis is
        // assigned to two outputs, the second assignment wins (rare; the user
        // would have to misconfigure on purpose).
        var rawX: Float? = null
        var rawY: Float? = null
        var rawZ: Float? = null
        fun assign(key: String, v: Float?) {
            if (v == null) return
            when (key) {
                "X" -> rawX = v
                "-X" -> rawX = -v
                "Y" -> rawY = v
                "-Y" -> rawY = -v
                "Z" -> rawZ = v
                "-Z" -> rawZ = -v
            }
        }
        assign(mapX, rxOut)
        assign(mapY, ryOut)
        assign(mapZ, rzOut)
        return Triple(rawX, rawY, rawZ)
    }
}
