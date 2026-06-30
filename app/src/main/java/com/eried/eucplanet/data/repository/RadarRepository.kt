package com.eried.eucplanet.data.repository

import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.radar.DecodedThreat
import com.eried.eucplanet.ble.radar.RadarAdapter
import com.eried.eucplanet.ble.radar.RadarConnectionManager
import com.eried.eucplanet.data.model.RadarFrame
import com.eried.eucplanet.data.model.RadarThreat
import com.eried.eucplanet.data.model.RadarVendor
import com.eried.eucplanet.data.model.ThreatLevel
import com.eried.eucplanet.service.AlarmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the rear-view radar BLE connection and exposes the current frame.
 *
 * State flows:
 *  * [connectionState] mirrors the underlying GATT manager.
 *  * [currentFrame] is the latest decoded frame (threats + battery).
 *
 * Pairing persists in three AppSettings columns (address / name / vendor enum)
 * so [connectPaired] can re-open the paired device on app start without
 * prompting. Mirrors [ExternalGpsRepository] closely on purpose ,  same auto-
 * reconnect cadence, same explicit-disconnect veto, same demo-mode hook
 * pattern so the UI can be reviewed without hardware.
 */
@Singleton
class RadarRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val connectionManager: RadarConnectionManager,
    private val adapters: Set<@JvmSuppressWildcards RadarAdapter>,
    private val alarmEngine: AlarmEngine
) {
    companion object {
        private const val TAG = "RadarRepo"

        /** Standard battery_level characteristic UUID, used to tag battery frames. */
        private val BATTERY_LEVEL_UUID: UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        /** Distance at which a closing threat is classified FAST_APPROACH. */
        private const val FAST_APPROACH_DISTANCE_M = 50

        /**
         * Approach speed (km/h) above which a threat is FAST_APPROACH
         * regardless of distance. 60 km/h matches "vehicle is meaningfully
         * faster than the rider on a typical road" for an EUC; below this
         * a closing target is still classified APPROACHING.
         */
        private const val FAST_APPROACH_SPEED_KMH = 60

        /** Below this approach speed (km/h) a target is treated as static, NONE. */
        private const val STATIC_TARGET_KMH = 3

        /**
         * Closing-rate threshold (m/s) for the fallback APPROACHING tag when
         * the vendor's reported approach_speed looks unreliable. 10 m/s ~=
         * 36 km/h closing, which is the same effective threshold the old
         * frame-count code used at the nominal 1 Hz notify rate, but now
         * holds at 2 Hz too if Garmin firmware ever bumps the rate.
         */
        private const val FALLBACK_CLOSING_MPS = 10.0

        /**
         * Lower bound on elapsed time between two frames for the closing-rate
         * calculation. Caps the divisor so a same-millisecond pair (battery
         * notify arriving in the same tick) can't divide by zero or produce
         * an unrealistic 1000 m/s rate.
         */
        private const val MIN_ELAPSED_MS_FOR_RATE = 100L

        /**
         * TEMPORARY demo flag. When true the repository ignores the BLE
         * stack entirely and acts as if a Garmin Varia is paired, connected,
         * and pushing a synthetic 3-car frame every second. Used to review
         * the connected-state UI on an emulator without hardware.
         * MUST be flipped back to false before shipping.
         */
        private const val DEMO_MODE = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Local mirror of the connection state. Normally tracks the BLE manager
     * 1:1; in [DEMO_MODE] we hold it at CONNECTED so the UI shows the
     * paired-and-streaming card without a real GATT link.
     */
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Subscribers today: [RadarOverlayViewModel] (the floating lane bar),
    // the alarm engine via [publishFrame] -> [AlarmEngine.evaluateRadar].
    // Future dashboard-tile work (separate branch) will subscribe here too;
    // see the FUTURE block in ui/dashboard/sources/DataSource.kt for the
    // proposed tiles and the RadarFrame field map.
    private val _currentFrame = MutableStateFlow<RadarFrame?>(null)
    val currentFrame: StateFlow<RadarFrame?> = _currentFrame.asStateFlow()

    /**
     * Tracks whether the user has explicitly disconnected this session.
     * While set, the auto-reconnect loop stops attempting to re-establish a
     * connection so the rider's deliberate action sticks until they actively
     * reconnect (from the Integration settings screen). Resets to false on
     * every app launch so a paired device naturally reconnects on boot.
     */
    @Volatile private var explicitlyDisconnected: Boolean = false

    /**
     * The last battery percentage the device emitted, retained so the next
     * threat-only frame still carries it on the [RadarFrame].
     */
    @Volatile private var latestBattery: Int? = null

    /**
     * Per-id history of the last seen distance + first-seen timestamp.
     * Used to compute closing rate (m/s) so we can derive a threat level
     * without needing the NDA-gated `…3201` characteristic.
     */
    private data class TrackState(val distanceM: Int, val firstSeenMs: Long, val lastSeenMs: Long)
    private val tracks = HashMap<Int, TrackState>()

    // Advanced threat-classification thresholds, mirrored from settings.
    @Volatile private var fastApproachDistM: Int = FAST_APPROACH_DISTANCE_M
    @Volatile private var fastApproachSpeedKmh: Int = FAST_APPROACH_SPEED_KMH
    @Volatile private var staticTargetKmh: Int = STATIC_TARGET_KMH
    @Volatile private var fallbackClosingMps: Double = FALLBACK_CLOSING_MPS
    @Volatile private var minElapsedMsForRate: Long = MIN_ELAPSED_MS_FOR_RATE

    init {
        scope.launch {
            settingsRepository.settings.collect { s ->
                fastApproachDistM = s.radarFastApproachDistM
                fastApproachSpeedKmh = s.radarFastApproachSpeedKmh
                staticTargetKmh = s.radarStaticTargetKmh
                fallbackClosingMps = s.radarFallbackClosingMps.toDouble()
                minElapsedMsForRate = s.radarMinFrameRateMs.toLong()
            }
        }
        if (DEMO_MODE) {
            // Hardware-free preview: write a fake pairing, hold the
            // connection state at CONNECTED, and push synthetic frames so
            // the dashboard mini + settings paired-card + threat debug list
            // all render exactly as they would with a real Varia behind us.
            // The real BLE collectors (notifications, auto-reconnect) are
            // skipped entirely so the demo can't tangle with the GATT stack.
            scope.launch { runDemoMode() }
        } else {
            initRealBleCollectors()
        }
    }

    private fun initRealBleCollectors() {
        scope.launch {
            connectionManager.connectionState.collect { _connectionState.value = it }
        }
        scope.launch {
            connectionManager.notifications.collect { n ->
                if (n.uuid == BATTERY_LEVEL_UUID) {
                    if (n.bytes.isNotEmpty()) {
                        latestBattery = n.bytes[0].toInt() and 0xFF
                        // Re-publish the current frame with the new battery
                        // so the UI doesn't have to wait for a threat frame.
                        _currentFrame.value?.let { prev ->
                            _currentFrame.value = prev.copy(batteryPercent = latestBattery)
                        }
                    }
                    return@collect
                }
                val adapter = adapters.firstOrNull { it.notifyCharacteristicUuid == n.uuid } ?: return@collect
                val decoded = adapter.decode(n.bytes) ?: return@collect
                publishFrame(adapter.vendor, decoded)
            }
        }
        // Auto-reconnect loop. Mirrors ExternalGpsRepository.
        scope.launch {
            var failedAttempts = 0
            connectionManager.connectionState.collect { state ->
                when (state) {
                    ConnectionState.CONNECTED -> {
                        failedAttempts = 0
                    }
                    ConnectionState.DISCONNECTED -> {
                        val paired = settingsRepository.get().radarAddress != null
                        if (!paired || explicitlyDisconnected) return@collect
                        val delayMs = when (failedAttempts) {
                            0 -> 1_500L
                            1 -> 5_000L
                            2 -> 10_000L
                            else -> 30_000L
                        }
                        Log.i(TAG, "Auto-reconnect attempt ${failedAttempts + 1} after ${delayMs}ms")
                        kotlinx.coroutines.delay(delayMs)
                        val recheck = settingsRepository.get().radarAddress != null
                        if (!recheck || explicitlyDisconnected) return@collect
                        if (connectionState.value == ConnectionState.DISCONNECTED) {
                            failedAttempts += 1
                            connectPaired()
                        }
                    }
                    else -> { /* CONNECTING / INITIALIZING, let it complete */ }
                }
            }
        }
    }

    private fun publishFrame(vendor: RadarVendor, decoded: List<DecodedThreat>) {
        val now = System.currentTimeMillis()
        // Forget tracks the radar has dropped (id no longer present this frame).
        val presentIds = decoded.map { it.id }.toSet()
        tracks.keys.retainAll(presentIds)

        val threats = decoded.map { d ->
            val prev = tracks[d.id]
            val firstSeen = prev?.firstSeenMs ?: now
            tracks[d.id] = TrackState(d.distanceM, firstSeen, now)
            val level = classify(prev, d, now)
            RadarThreat(
                id = d.id,
                distanceM = d.distanceM,
                approachSpeedKmh = d.approachSpeedKmh,
                threatLevel = level,
                firstSeenMs = firstSeen
            )
        }
        val frame = RadarFrame(
            vendor = vendor,
            threats = threats,
            batteryPercent = latestBattery,
            timestamp = now
        )
        _currentFrame.value = frame
        // Feed the alarm engine so the rider's RADAR_DISTANCE /
        // RADAR_APPROACH_SPEED rules fire on the same cadence as the radar.
        alarmEngine.evaluateRadar(frame)
    }

    /**
     * Local severity classifier. We can't read Garmin's own level byte
     * (NDA), so we derive it from distance, the vendor-reported approach
     * speed, and the change in distance between frames. Errs on the side
     * of "louder" ,  false negatives on a closing car are dangerous; false
     * positives on a slow truck are just a yellow dot.
     */
    private fun classify(prev: TrackState?, d: DecodedThreat, now: Long): ThreatLevel {
        // A clearly static target (parked car the rider is approaching, or a
        // tracker that just appeared at constant distance) gets NONE.
        if (d.approachSpeedKmh < staticTargetKmh && prev == null) return ThreatLevel.NONE

        // Fast closing: either speed is high, or the target is already inside
        // the safety bubble. The OR is intentional ,  a parked car 10 m behind
        // the rider with approach_speed=0 is still a hazard to flag amber, but
        // a 30 km/h car 30 m back is the textbook "behind you, watch out".
        if (d.approachSpeedKmh >= fastApproachSpeedKmh) return ThreatLevel.FAST_APPROACH
        if (d.distanceM <= fastApproachDistM && d.approachSpeedKmh >= staticTargetKmh) {
            return ThreatLevel.FAST_APPROACH
        }

        // Closing-rate fallback: if the vendor's approach_speed looks weird
        // (some firmware ticks it to 0 between samples), but our own delta
        // says the target is closing at >= FALLBACK_CLOSING_MPS, flag
        // APPROACHING anyway. Use elapsed wall-clock between samples rather
        // than a per-frame delta so the threshold stays correct if the
        // notify rate drifts from the nominal ~1 Hz (BLE drops can stretch
        // a frame to 2 s; pycycling/Garmin never published a guaranteed
        // rate, and the ANT+ Bike Radar profile is faster than the BLE
        // mirror).
        if (prev != null) {
            val closingMeters = (prev.distanceM - d.distanceM).toDouble()
            val elapsedMs = (now - prev.lastSeenMs).coerceAtLeast(minElapsedMsForRate)
            val closingMps = closingMeters * 1000.0 / elapsedMs
            if (closingMps >= fallbackClosingMps) return ThreatLevel.APPROACHING
        }

        return if (d.approachSpeedKmh >= staticTargetKmh) ThreatLevel.APPROACHING else ThreatLevel.NONE
    }

    suspend fun setPairing(address: String, name: String, vendor: RadarVendor) {
        val s = settingsRepository.get()
        settingsRepository.update(
            s.copy(
                radarAddress = address,
                radarName = name,
                radarVendor = vendor.name
            )
        )
        Log.i(TAG, "Paired ${vendor.displayName}: $name ($address)")
    }

    suspend fun clearPairing() {
        val s = settingsRepository.get()
        settingsRepository.update(
            s.copy(
                radarAddress = null,
                radarName = null,
                radarVendor = null
            )
        )
        disconnect()
        Log.i(TAG, "Pairing cleared")
    }

    suspend fun pairedAddress(): String? = settingsRepository.get().radarAddress
    suspend fun pairedName(): String? = settingsRepository.get().radarName
    suspend fun pairedVendor(): RadarVendor? =
        settingsRepository.get().radarVendor?.let {
            runCatching { RadarVendor.valueOf(it) }.getOrNull()
        }

    /**
     * Open the GATT connection to the currently-paired device. Returns false
     * if no pairing exists or no adapter is registered for the saved vendor.
     */
    suspend fun connectPaired(): Boolean {
        val address = pairedAddress() ?: return false
        val vendor = pairedVendor() ?: return false
        val adapter = adapters.firstOrNull { it.vendor == vendor } ?: run {
            Log.w(TAG, "No adapter for paired vendor $vendor, skipping connect")
            return false
        }
        explicitlyDisconnected = false
        connectionManager.connect(address, adapter)
        return true
    }

    fun connect(address: String, adapter: RadarAdapter) {
        explicitlyDisconnected = false
        connectionManager.connect(address, adapter)
    }

    fun disconnect() {
        explicitlyDisconnected = true
        latestBattery = null
        tracks.clear()
        _currentFrame.value = null
        connectionManager.disconnect()
    }

    /**
     * Hardware-free preview path. Pins a fake Varia pairing into settings
     * so the Integration screen shows the paired card, flips
     * [_connectionState] to CONNECTED so the overlay's gate opens, and
     * emits one synthetic frame per second with three threats whose
     * distances drift in a loop ,  one fast closer, one steady approacher,
     * one drifting away. Skips [AlarmEngine.evaluateRadar] so the demo
     * doesn't spam TTS / vibrate on the emulator.
     */
    private suspend fun runDemoMode() {
        // Always overwrite on launch so a stale side / hidden-overlay
        // value from a prior run can't make the demo render half-empty.
        val s = settingsRepository.get()
        settingsRepository.update(
            s.copy(
                radarAddress = "DEMO:00:00:00:00:00",
                radarName = "Garmin Varia (demo)",
                radarVendor = RadarVendor.VARIA.name,
                radarShowOverlay = true,
                radarOverlaySide = "BOTH"
            )
        )
        _connectionState.value = ConnectionState.CONNECTED
        var tick = 0
        while (true) {
            // Slow oscillation so the rider sees the dots move between
            // ticks ,  proves the flow is alive without confusing the eye.
            val phase = tick % 12
            val closeM = 14 + phase * 2          // 14..36 m, oscillates
            val midM = 55 + (phase - 6).let { it * it } / 2  // ~55..73 m
            val farM = 100 + phase * 3           // 100..133 m
            val frame = RadarFrame(
                vendor = RadarVendor.VARIA,
                threats = listOf(
                    RadarThreat(
                        id = 1,
                        distanceM = closeM,
                        approachSpeedKmh = 48,
                        threatLevel = ThreatLevel.FAST_APPROACH,
                        firstSeenMs = 0L
                    ),
                    RadarThreat(
                        id = 2,
                        distanceM = midM,
                        approachSpeedKmh = 20,
                        threatLevel = ThreatLevel.APPROACHING,
                        firstSeenMs = 0L
                    ),
                    RadarThreat(
                        id = 3,
                        distanceM = farM,
                        approachSpeedKmh = 4,
                        threatLevel = ThreatLevel.NONE,
                        firstSeenMs = 0L
                    )
                ),
                batteryPercent = 72
            )
            _currentFrame.value = frame
            tick += 1
            kotlinx.coroutines.delay(1_000L)
        }
    }
}
