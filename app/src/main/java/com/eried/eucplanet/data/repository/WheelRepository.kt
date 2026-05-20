package com.eried.eucplanet.data.repository

import android.content.Context
import android.util.Log
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.BleConnectionManager
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.DecodeResult
import com.eried.eucplanet.ble.WheelAdapter
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings
import com.eried.eucplanet.service.AlarmEngine
import com.eried.eucplanet.service.VoiceService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

data class MetricSample(val timestampMs: Long, val value: Float)

data class FullMetricHistory(
    val battery: List<MetricSample> = emptyList(),
    val temperature: List<MetricSample> = emptyList(),
    val voltage: List<MetricSample> = emptyList(),
    val current: List<MetricSample> = emptyList(),
    val load: List<MetricSample> = emptyList(),
    val speed: List<MetricSample> = emptyList()
)

@Singleton
class WheelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleConnectionManager,
    private val wheelAdapter: WheelAdapter,
    private val settingsRepository: SettingsRepository,
    private val alarmEngine: AlarmEngine,
    private val voiceService: VoiceService,
    private val wheelProfileDao: com.eried.eucplanet.data.db.WheelProfileDao,
    private val cheatState: com.eried.eucplanet.cheats.CheatState
) {
    companion object {
        private const val TAG = "WheelRepo"
        // Realtime poll-and-push intervals for the three watchUpdateRate tiers.
        private const val POLL_INTERVAL_MS = 250L              // "NORMAL" (default)
        private const val FAST_POLL_INTERVAL_MS = 150L         // "FAST"
        private const val CONSERVATIVE_POLL_INTERVAL_MS = 750L // "CONSERVATIVE"
        private const val HISTORY_SAMPLE_INTERVAL_MS = 1000L
        // Hard 5-minute window on the metric history buffers. Without this,
        // each list grows unbounded at 1 Hz (memory leak) and the chart's
        // takeLast(300) shows ~5m10s instead of a clean 5m because the
        // sampler drifts. Time-bounding here makes the chart truly 5 min.
        private const val HISTORY_WINDOW_MS = 5 * 60 * 1000L
        // Re-request settings every N realtime polls to pick up external changes
        // (lock/unlock via InMotion app or physical button). 12 * 250ms = 3s.
        private const val SETTINGS_REFRESH_INTERVAL = 12

        // Re-request extended stats (P6: motor / driver-board temps) every
        // N polls. Lands on a different cycle from auth (24) and settings (12)
        // so the three queries don't compete for the same response slot.
        // 18 * 250ms = 4.5s.
        private const val STATS_REFRESH_INTERVAL = 18

        // Re-run the connect-auth handshake every N polls for wheels that
        // require it (P6). The InMotion app re-primes ~1× per 6 s; the wheel's
        // "control endpoint primed" state expires shortly after the handshake
        // and a single one-shot prime at connect doesn't survive long enough
        // for the user's first light/horn/max-speed tap. 24 * 250ms = 6s.
        private const val CONNECT_AUTH_REFRESH_INTERVAL = 24

        // Fallback slider ceiling when no wheel is connected or when the model
        // ID isn't in our registry (mirrors WheelLog's default of 100; we use
        // the historical 90 to match what the V14-only build always showed).
        private const val DEFAULT_MAX_SPEED_KMH = 90f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _wheelData = MutableStateFlow(WheelData())
    val wheelData: StateFlow<WheelData> = _wheelData.asStateFlow()

    private val _wheelSettings = MutableStateFlow(WheelSettings())
    val wheelSettings: StateFlow<WheelSettings> = _wheelSettings.asStateFlow()

    private val _safetySpeedActive = MutableStateFlow(false)
    val safetySpeedActive: StateFlow<Boolean> = _safetySpeedActive.asStateFlow()

    // Lock state derived from wheel telemetry pcMode
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _modelName = MutableStateFlow<String?>(null)
    val modelName: StateFlow<String?> = _modelName.asStateFlow()

    private val _firmwareVersion = MutableStateFlow<String?>(null)
    val firmwareVersion: StateFlow<String?> = _firmwareVersion.asStateFlow()

    // Upper bound for the tiltback / alarm sliders in km/h. Updated to the
    // model's [InMotionV2Model.maxSpeedKmh] when the wheel reports its
    // identity (the V14 family stays at 120, the P6 jumps to 130, the
    // older V11/V12HS/HT/PRO drop to 60–70). Default is the historical 90
    // until detection lands; it also serves as the fallback when the wheel
    // reports an InMotion model ID we don't yet recognize.
    private val _maxSpeedCap = MutableStateFlow(DEFAULT_MAX_SPEED_KMH)
    val maxSpeedCap: StateFlow<Float> = _maxSpeedCap.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState

    /** Connected wheel's BLE name (with " (virtual)" for simulators), or null. */
    val connectedDeviceName: StateFlow<String?> = bleManager.connectedDeviceName

    /** Connected wheel's brand (InMotion / Begode / ...), or null. */
    val connectedBrand: StateFlow<String?> = bleManager.connectedBrand

    // --- Persistent metric history (survives navigation, cleared on disconnect) ---
    private val battHist = mutableListOf<MetricSample>()
    private val tempHist = mutableListOf<MetricSample>()
    private val voltHist = mutableListOf<MetricSample>()
    private val ampsHist = mutableListOf<MetricSample>()
    private val loadHist = mutableListOf<MetricSample>()
    private val speedHist = mutableListOf<MetricSample>()
    private var lastHistorySampleMs = 0L

    private val _fullHistory = MutableStateFlow(FullMetricHistory())
    val fullHistory: StateFlow<FullMetricHistory> = _fullHistory.asStateFlow()

    // Auth state for lock/unlock (V14 requires password verification)
    private var authKey: ByteArray? = null
    private var pendingAuthKeyDeferred: CompletableDeferred<ByteArray>? = null
    private var pendingAuthConfirmDeferred: CompletableDeferred<Boolean>? = null

    // Serialise the lock auth handshake so rapid taps can't strand each
    // other's deferreds. Without this, two concurrent toggleLock() calls
    // both write the singleton `pendingAuthKeyDeferred`; the first call's
    // deferred is overwritten and times out without ever reaching the
    // setLock write — exactly the symptom seen on the P6 ("lock doesn't
    // work" while WIM works because WIM serialises taps in its UI).
    private val authMutex = Mutex()

    private var lastConnectedAddress: String? = null
    private var initState = 0
    private var pollingActive = false

    // True only for the first 0x20 settings packet after each (re)connect.
    // Used to reconcile stored speed limits with the wheel's actual values.
    @Volatile private var reconcileNextSettings = false

    /** Wall-clock of the most recent app-side speed write. Used to ignore
     *  the wheel's echo (which arrives in the next settings packet ~1-2 s
     *  later) when deciding whether a wheel-reported change came from the
     *  user pressing buttons on the wheel's own screen. */
    @Volatile private var lastSetSpeedAtMs = 0L
    private val WHEEL_SCREEN_DEBOUNCE_MS = 3000L

    /** Transient one-shot event for the UI: wheel-side change auto-applied
     *  to the user's stored settings. Consumed by the dashboard / settings
     *  screen to surface a toast/snackbar so the change isn't silent. */
    private val _externalSpeedChange = MutableSharedFlow<ExternalSpeedChange>(
        replay = 0, extraBufferCapacity = 4
    )
    val externalSpeedChange: SharedFlow<ExternalSpeedChange> = _externalSpeedChange.asSharedFlow()

    // --- Action cooldowns ---
    // Lock and Light each get a brief cooldown after the user taps them so a
    // stale settings frame can't repaint the optimistic state and so spam
    // taps don't queue up multiple auth handshakes. The button observes
    // *Busy and shows itself disabled until the cooldown elapses.
    private val _lockBusy = MutableStateFlow(false)
    val lockBusy: StateFlow<Boolean> = _lockBusy.asStateFlow()
    private val _lightBusy = MutableStateFlow(false)
    val lightBusy: StateFlow<Boolean> = _lightBusy.asStateFlow()
    @Volatile private var lockCooldownUntilMs = 0L
    @Volatile private var lightCooldownUntilMs = 0L
    private val LOCK_COOLDOWN_MS = 3000L
    private val LIGHT_COOLDOWN_MS = 1500L

    /**
     * Current speed calibration multiplier (1.0 + offsetPct / 100). Mirrored
     * from AppSettings via the init flow below so the hot telemetry path
     * reads from a volatile field instead of re-collecting the settings flow
     * on every BLE frame. Bounded to [0.85, 1.15] (matching the UI -15..+15%).
     */
    @Volatile private var speedCalibrationMultiplier: Float = 1f

    /**
     * Realtime poll interval in milliseconds, resolved from
     * AppSettings.watchUpdateRate and mirrored into the hot poll path so the
     * loop never re-collects the settings flow per cycle. Unknown values fall
     * back to [POLL_INTERVAL_MS].
     */
    @Volatile private var pollIntervalMs: Long = POLL_INTERVAL_MS

    /**
     * Riders have lock-toggle bindings on Flic buttons, the watch buttons, the
     * volume keys, and the dashboard. A misfire while moving locks the wheel
     * mid-ride and causes an instant motor cutout, so every lock-direction
     * call goes through one gate here. Unlock always proceeds (a locked wheel
     * has speed = 0 by definition). 5 km/h covers the standstill / push-assist
     * range without false rejecting at IMU noise around zero.
     */
    private val LOCK_MAX_SPEED_KMH = 5f

    private fun startCooldown(busyFlag: MutableStateFlow<Boolean>, durationMs: Long, setUntil: (Long) -> Unit) {
        val now = System.currentTimeMillis()
        setUntil(now + durationMs)
        busyFlag.value = true
        scope.launch {
            delay(durationMs)
            busyFlag.value = false
        }
    }

    /**
     * Tiltback the app most recently asked the wheel to set, in km/h. Used to
     * disambiguate settings readback: if the wheel reports a tiltback below
     * what we sent, the wheel's firmware capped our request, and the readback
     * should not be treated as the authoritative current intent. Cleared on
     * disconnect so a fresh connection can't be misled by stale state.
     */
    @Volatile private var lastSentTiltbackKmh: Float? = null

    init {
        // The adapter does its own framing + decoding; the connection manager
        // forwards already-decoded results from raw BLE notifications.
        scope.launch {
            bleManager.decodedResults.collect { result ->
                handleDecoded(result)
            }
        }

        // Track the rider's speed calibration. We mirror it into a volatile
        // multiplier so the hot telemetry path applies it without re-reading
        // the settings flow per frame. Also persist a copy into the per-wheel
        // profile whenever the value changes — keyed by the BLE-advertised
        // name (AppSettings.lastDeviceName) — so a reconnect to the same
        // wheel restores everything (tiltback, alarm, safety, calibration).
        scope.launch {
            settingsRepository.settings.collect { s ->
                val clamped = s.speedCalibrationOffsetPct.coerceIn(-15f, 15f)
                speedCalibrationMultiplier = 1f + clamped / 100f
                pollIntervalMs = when (s.watchUpdateRate) {
                    "CONSERVATIVE" -> CONSERVATIVE_POLL_INTERVAL_MS
                    "FAST" -> FAST_POLL_INTERVAL_MS
                    else -> POLL_INTERVAL_MS
                }
                val wheelName = s.lastDeviceName
                if (wheelName != null && bleManager.connectionState.value == ConnectionState.CONNECTED) {
                    persistWheelProfile(wheelName, s)
                }
            }
        }

        // React to connection state changes
        scope.launch {
            bleManager.connectionState.collect { state ->
                when (state) {
                    ConnectionState.CONNECTED -> {
                        reconcileNextSettings = true
                        startInitSequence()
                        // Restore the per-wheel saved parameters (tiltback,
                        // alarm, safety, calibration). Profile is keyed by
                        // BLE-advertised name; if no profile exists, we save
                        // the current values as the seed.
                        scope.launch { loadOrSeedWheelProfile() }
                    }
                    ConnectionState.DISCONNECTED -> {
                        pollingActive = false
                        initState = 0
                        authKey = null
                        pendingAuthKeyDeferred = null
                        pendingAuthConfirmDeferred = null
                        lastSentTiltbackKmh = null
                        // Reset states that depend on wheel connection
                        _safetySpeedActive.value = false
                        _locked.value = false
                        _modelName.value = null
                        _firmwareVersion.value = null
                        _maxSpeedCap.value = DEFAULT_MAX_SPEED_KMH
                        _wheelData.value = _wheelData.value.copy(totalDistance = 0f)
                        // History is preserved across disconnects (cleared only on new wheel)
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * On wheel connect: if a saved profile exists for the BLE-advertised
     * name, restore tiltback / alarm / safety speeds and the speed
     * calibration into the live AppSettings so the dashboard, alarms, voice
     * and recording all pick the right values up. If no profile exists,
     * seed one from the current AppSettings — that becomes the starting
     * point for this wheel and future tweaks are written back via
     * [persistWheelProfile].
     */
    private suspend fun loadOrSeedWheelProfile() {
        val s = settingsRepository.get()
        val name = s.lastDeviceName ?: return
        val existing = runCatching { wheelProfileDao.getByName(name) }.getOrNull()
        if (existing != null) {
            settingsRepository.update(
                s.copy(
                    tiltbackSpeedKmh = existing.tiltbackSpeedKmh,
                    alarmSpeedKmh = existing.alarmSpeedKmh,
                    safetyTiltbackKmh = existing.safetyTiltbackKmh,
                    safetyAlarmKmh = existing.safetyAlarmKmh,
                    speedCalibrationOffsetPct = existing.speedCalibrationOffsetPct
                )
            )
        } else {
            persistWheelProfile(name, s)
        }
    }

    private suspend fun persistWheelProfile(
        wheelName: String,
        s: com.eried.eucplanet.data.model.AppSettings
    ) {
        runCatching {
            wheelProfileDao.upsert(
                com.eried.eucplanet.data.model.WheelProfile(
                    bleName = wheelName,
                    tiltbackSpeedKmh = s.tiltbackSpeedKmh,
                    alarmSpeedKmh = s.alarmSpeedKmh,
                    safetyTiltbackKmh = s.safetyTiltbackKmh,
                    safetyAlarmKmh = s.safetyAlarmKmh,
                    speedCalibrationOffsetPct = s.speedCalibrationOffsetPct,
                    lastConnectedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun connect(address: String, name: String? = null) {
        if (lastConnectedAddress != null && lastConnectedAddress != address) {
            // Different wheel — clear history
            battHist.clear(); tempHist.clear(); voltHist.clear()
            ampsHist.clear(); loadHist.clear(); speedHist.clear()
            _fullHistory.value = FullMetricHistory()
        }
        lastConnectedAddress = address
        bleManager.connect(address, name)
    }

    fun disconnect() {
        pollingActive = false
        bleManager.disconnect()
    }

    // --- Control commands ---

    fun sendHorn() {
        wheelAdapter.horn()?.let { bleManager.writeCommand(it) }
    }

    fun toggleLight() {
        if (_lightBusy.value) return  // cooldown active, ignore the spam tap
        val current = _wheelData.value.lightOn
        val next = !current
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
            "toggleLight: lightOn was=$current, sending $next"
        )
        wheelAdapter.setLight(next)?.let { bleManager.writeCommand(it) }
        _wheelData.value = _wheelData.value.copy(lightOn = next)
        startCooldown(_lightBusy, LIGHT_COOLDOWN_MS) { lightCooldownUntilMs = it }
    }

    fun toggleLock() {
        if (_lockBusy.value) return  // cooldown active, ignore the spam tap
        val targetState = !_locked.value
        // Hard block the lock direction when the wheel is moving — any entry
        // path (Flic, watch, volume keys, dashboard) lands here. Unlock is
        // always allowed; if the wheel is already locked, speed is 0 anyway.
        if (targetState && kotlin.math.abs(_wheelData.value.speed) >= LOCK_MAX_SPEED_KMH &&
            !cheatState.lockAtAnySpeed.value) {
            Log.d(TAG, "lock blocked: speed=${_wheelData.value.speed} >= ${LOCK_MAX_SPEED_KMH}")
            return
        }
        _locked.value = targetState
        startCooldown(_lockBusy, LOCK_COOLDOWN_MS) { lockCooldownUntilMs = it }
        scope.launch {
            val success = authenticateAndLock(targetState)
            // Re-read settings so the wheel can confirm. During the cooldown
            // the settings handler will skip overwriting _locked from a stale
            // frame, so the UI stays at the requested state until the wheel
            // catches up; if the wheel still disagrees after the cooldown
            // ends, the next settings poll wins (final source of truth).
            delay(800)
            bleManager.writeCommand(wheelAdapter.pollSettings())
            if (success) {
                // Wait for the cooldown to elapse so the announcement
                // reflects the settled state, not the optimistic flip.
                delay(LOCK_COOLDOWN_MS)
                val s = settingsRepository.get()
                if (s.announceWheelLock) {
                    val finalLocked = _locked.value
                    voiceService.announceEvent(context.getString(
                        if (finalLocked) R.string.voice_wheel_locked else R.string.voice_wheel_unlocked
                    ))
                }
            } else {
                Log.e(TAG, "Lock command failed: auth unsuccessful — awaiting wheel telemetry resync")
            }
        }
    }

    /**
     * Lock/unlock with optional password authentication. The adapter declares whether
     * auth is required (V14: yes, others: no). When required, we run the
     * requestAuthKey → receive 16-byte key → verifyAuth → receive confirm → setLock
     * handshake; otherwise we just send setLock directly.
     */
    private suspend fun authenticateAndLock(locked: Boolean): Boolean = authMutex.withLock {
        val lockPacket = wheelAdapter.setLock(locked) ?: return@withLock false

        if (!wheelAdapter.capabilities.needsAuthForLock) {
            bleManager.writeCommand(lockPacket)
            return@withLock true
        }

        val authReqPacket = wheelAdapter.requestAuthKey()
        val verifyBuilder = wheelAdapter::verifyAuth
        if (authReqPacket == null) {
            // Capabilities say auth is required but adapter exposes no key request — bug.
            Log.e(TAG, "Lock: adapter requires auth but provides no requestAuthKey()")
            return@withLock false
        }

        // Step 1: Request auth key from wheel
        val keyDeferred = CompletableDeferred<ByteArray>()
        pendingAuthKeyDeferred = keyDeferred
        bleManager.writeCommand(authReqPacket)
        Log.i(TAG, "Lock: requesting auth key...")

        val key = withTimeoutOrNull(4000L) { keyDeferred.await() }
        pendingAuthKeyDeferred = null

        if (key == null) {
            Log.e(TAG, "Lock: auth key timeout")
            return@withLock false
        }
        authKey = key
        Log.i(TAG, "Lock: got auth key (${key.size} bytes): ${key.joinToString(" ") { "%02X".format(it) }}")

        // Step 2: Verify auth by echoing the key back
        val verifyPacket = verifyBuilder(key) ?: run {
            Log.e(TAG, "Lock: adapter returned null for verifyAuth")
            return@withLock false
        }
        val confirmDeferred = CompletableDeferred<Boolean>()
        pendingAuthConfirmDeferred = confirmDeferred
        bleManager.writeCommand(verifyPacket)
        Log.i(TAG, "Lock: verifying auth...")

        val confirmed = withTimeoutOrNull(4000L) { confirmDeferred.await() } ?: false
        pendingAuthConfirmDeferred = null

        if (!confirmed) {
            Log.e(TAG, "Lock: auth verify failed or timeout")
            return@withLock false
        }
        Log.i(TAG, "Lock: auth verified, sending lock=$locked")

        // Step 3: Send lock/unlock command
        Log.i(TAG, "Lock packet (${lockPacket.size} bytes): ${lockPacket.joinToString(" ") { "%02X".format(it) }}")
        bleManager.writeCommand(lockPacket)
        true
    }

    fun setDRL(on: Boolean) {
        wheelAdapter.setDRL(on)?.let { bleManager.writeCommand(it) }
    }

    fun setSpeed(tiltbackKmh: Float, beepKmh: Float) {
        // Remember what we asked for so the settings handler can tell the
        // difference between "wheel echoed our value" and "wheel clamped it".
        // Without this, a firmware-capped V14 (e.g. 80 km/h max while we send
        // 85) would echo back 80 — and if 80 happened to equal stored Legal
        // tiltback, the readback-based detector would lock the toggle on.
        lastSentTiltbackKmh = tiltbackKmh
        // Timestamp the write so the auto-sync handler doesn't mistake the
        // wheel's echo of our own write for an external change.
        lastSetSpeedAtMs = System.currentTimeMillis()
        wheelAdapter.setMaxSpeed(tiltbackKmh, beepKmh)?.let { bleManager.writeCommand(it) }
        // P6 needs two flash-commit packets after the live drag write — one
        // for the tiltback, one for the alarm threshold. V14 returns null
        // here since both values land in the single setMaxSpeed packet.
        wheelAdapter.setMaxSpeedCommit(tiltbackKmh)?.let { bleManager.writeCommand(it) }
        wheelAdapter.setAlarmSpeedCommit(beepKmh)?.let { bleManager.writeCommand(it) }
    }

    suspend fun toggleSafetySpeed() {
        val wantActive = !_safetySpeedActive.value
        val settings = settingsRepository.get()

        // Flip the flag to user intent immediately. The settings handler
        // already trusts intent (lastSentTiltbackKmh) over a clamped readback,
        // but the optimistic flip keeps the UI responsive while the wheel's
        // confirmation is in flight — same pattern as toggleLock.
        _safetySpeedActive.value = wantActive

        if (wantActive) {
            setSpeed(settings.safetyTiltbackKmh, settings.safetyAlarmKmh)
        } else {
            setSpeed(settings.tiltbackSpeedKmh, settings.alarmSpeedKmh)
        }
        Log.i(TAG, "Safety speed toggle requested: active=$wantActive")

        // Request settings back from wheel to confirm
        delay(300)
        bleManager.writeCommand(wheelAdapter.pollSettings())
    }

    fun enableSafetySpeed() {
        if (!_safetySpeedActive.value) {
            scope.launch { toggleSafetySpeed() }
        }
    }

    fun disableSafetySpeed() {
        if (_safetySpeedActive.value) {
            scope.launch { toggleSafetySpeed() }
        }
    }

    // --- Initialization sequence ---

    private fun startInitSequence() {
        initState = 0
        pollingActive = true
        scope.launch { runPollingLoop() }
    }

    private suspend fun runPollingLoop() {
        val initSequence = wheelAdapter.initSequence()
        var realtimeCycle = 0
        val needsConnectAuth = wheelAdapter.requiresConnectAuth()
        var initialAuthDone = !needsConnectAuth
        while (pollingActive && bleManager.connectionState.value == ConnectionState.CONNECTED) {
            if (initState < initSequence.size) {
                bleManager.writeCommand(initSequence[initState])
                initState++
            } else if (!initialAuthDone) {
                // First-connect priming for wheels that silently drop control
                // commands pre-auth (P6). Mark done even on failure so we don't
                // loop forever; the periodic re-prime below will retry.
                runConnectAuthHandshake()
                initialAuthDone = true
            } else {
                // Periodic re-prime for the P6: the wheel's "control endpoint
                // primed" state expires shortly after the handshake. The
                // InMotion app re-runs auth ~every 6 s; we mirror that so
                // light/horn/max-speed writes keep working through a session.
                if (needsConnectAuth && realtimeCycle > 0 &&
                    realtimeCycle % CONNECT_AUTH_REFRESH_INTERVAL == 0) {
                    runConnectAuthHandshake()
                } else if (realtimeCycle > 0 &&
                    realtimeCycle % STATS_REFRESH_INTERVAL == 0) {
                    wheelAdapter.pollStats()?.let { bleManager.writeCommand(it) }
                        ?: bleManager.writeCommand(wheelAdapter.pollRealtime())
                } else if (realtimeCycle % SETTINGS_REFRESH_INTERVAL == 0) {
                    bleManager.writeCommand(wheelAdapter.pollSettings())
                } else {
                    bleManager.writeCommand(wheelAdapter.pollRealtime())
                }
                realtimeCycle++
            }
            // Pace the loop by the rider's chosen update rate. Read each cycle
            // so a mid-session change takes effect on the next poll. The
            // cycle-count refresh constants are unchanged; only the wall-clock
            // pacing between writes shifts.
            delay(pollIntervalMs)
        }
    }

    /**
     * Run the password handshake right after init completes for wheels that
     * gate control writes on it (the P6). Mirrors [authenticateAndLock]'s
     * auth steps but stops after the verify ACK — there is no lock packet
     * to send. Failure is logged and ignored; the user keeps their telemetry.
     */
    private suspend fun runConnectAuthHandshake() {
        val authReqPacket = wheelAdapter.requestAuthKey() ?: return
        val keyDeferred = CompletableDeferred<ByteArray>()
        pendingAuthKeyDeferred = keyDeferred
        bleManager.writeCommand(authReqPacket)
        Log.i(TAG, "Connect-auth: requesting auth key…")
        val key = withTimeoutOrNull(4000L) { keyDeferred.await() }
        pendingAuthKeyDeferred = null
        if (key == null) {
            Log.w(TAG, "Connect-auth: key timeout — control commands may not work until lock toggle")
            return
        }
        authKey = key
        val verifyPacket = wheelAdapter.verifyAuth(key) ?: return
        val confirmDeferred = CompletableDeferred<Boolean>()
        pendingAuthConfirmDeferred = confirmDeferred
        bleManager.writeCommand(verifyPacket)
        val ok = withTimeoutOrNull(4000L) { confirmDeferred.await() } ?: false
        pendingAuthConfirmDeferred = null
        if (ok) Log.i(TAG, "Connect-auth: verified, control endpoint primed")
        else Log.w(TAG, "Connect-auth: verify failed — control commands may not work")
    }

    // --- Speed limits reconciliation on (re)connect ---

    /**
     * The wheel only stores one active (tiltback, alarm) pair at a time, but we keep
     * both a "normal" pair and a "legal" pair persistently. On reconnect we compare
     * what the wheel reports against both stored pairs:
     *
     *  A) wheel tilt ≈ stored legal       → legal mode on; keep both stored pairs
     *  B) wheel tilt ≈ stored normal      → normal mode; keep both stored pairs
     *  C) wheel tilt < stored legal       → user lowered legal externally; adopt as new legal
     *  D) wheel tilt > stored normal      → user raised normal externally; adopt as new normal
     *  E) wheel tilt between legal+normal → ambiguous (firmware cap or external lower of normal);
     *                                       leave stored values alone, mark normal mode active
     *
     * In A/B/E we never overwrite stored values — the other pair is preserved across
     * reconnects even though the wheel forgets it. Case E is the conservative split that
     * stops a firmware-capped readback (e.g. V14 80 km/h cap when stored normal is 85)
     * from silently downgrading the user's stored preference.
     */
    private suspend fun reconcileSpeedLimits(ws: WheelSettings, appSettings: AppSettings) {
        val wTilt = ws.maxSpeedKmh
        val wAlarm = ws.alarmSpeedKmh
        // The wheel reports 0 when tiltback is either unsupported by the
        // adapter (some Begode/Veteran families) or explicitly disabled on
        // the wheel itself. Treat both as "don't know" — overwriting the
        // user's stored tilt with 0 would silently break their dashboard
        // gauge (gaugeMax floors at 10 km/h).
        if (wTilt <= 0f) return
        val legalTilt = appSettings.safetyTiltbackKmh
        val normalTilt = appSettings.tiltbackSpeedKmh
        val tolerance = 0.5f

        val matchesLegal = kotlin.math.abs(wTilt - legalTilt) < tolerance
        val matchesNormal = kotlin.math.abs(wTilt - normalTilt) < tolerance

        val isLegalOn: Boolean
        val updated: AppSettings = when {
            matchesLegal -> {
                isLegalOn = true
                appSettings
            }
            matchesNormal -> {
                isLegalOn = false
                appSettings
            }
            wTilt < legalTilt -> {
                // User lowered their legal via another app — adopt as new legal
                isLegalOn = true
                appSettings.copy(safetyTiltbackKmh = wTilt, safetyAlarmKmh = wAlarm)
            }
            wTilt > normalTilt + tolerance -> {
                // Wheel above stored normal — user raised normal externally; adopt
                isLegalOn = false
                appSettings.copy(tiltbackSpeedKmh = wTilt, alarmSpeedKmh = wAlarm)
            }
            else -> {
                // wTilt is between legal and normal. Adopt it as the new
                // normal — covers the P6/V12 case where the user lowered
                // the speed on the wheel's own screen, which is the common
                // path. The V14-clamp risk this used to guard against is
                // small in practice (V14 hardware caps don't shift across
                // sessions), and a one-toast adopt is still recoverable
                // by dragging the slider.
                isLegalOn = false
                appSettings.copy(tiltbackSpeedKmh = wTilt, alarmSpeedKmh = wAlarm)
            }
        }

        if (updated !== appSettings) {
            settingsRepository.update(updated)
        }
        _safetySpeedActive.value = isLegalOn

        Log.i(TAG, "Reconciled: wheel=$wTilt/$wAlarm → " +
                "normal=${updated.tiltbackSpeedKmh}/${updated.alarmSpeedKmh} " +
                "legal=${updated.safetyTiltbackKmh}/${updated.safetyAlarmKmh} legalOn=$isLegalOn")

        if (isLegalOn && appSettings.announceSafetyMode) {
            voiceService.announceEvent(context.getString(R.string.voice_legal_on))
        }
    }

    // --- Decoded packet handling ---

    private suspend fun handleDecoded(result: DecodeResult) {
        when (result) {
            is DecodeResult.ModelName -> {
                _modelName.value = result.name
                // Resize the slider ceiling to whatever the detected model
                // actually supports. WheelLog values for the V14 family etc;
                // P6 gets 130 km/h since it isn't in WheelLog's table.
                val model = result.model as? com.eried.eucplanet.ble.InMotionV2Model
                _maxSpeedCap.value = model?.maxSpeedKmh?.toFloat() ?: DEFAULT_MAX_SPEED_KMH
            }
            is DecodeResult.Firmware -> {
                _firmwareVersion.value = result.display
                Log.i(TAG, "Firmware: Main=${result.mainBoard} Drv=${result.driverBoard} BLE=${result.ble}")
            }
            is DecodeResult.AuthKey -> {
                Log.i(TAG, "Auth key received: ${result.encryptedKey.joinToString(" ") { "%02X".format(it) }}")
                pendingAuthKeyDeferred?.complete(result.encryptedKey)
            }
            is DecodeResult.AuthConfirm -> {
                Log.i(TAG, "Auth verify result: ${result.success}")
                pendingAuthConfirmDeferred?.complete(result.success)
            }
            is DecodeResult.Telemetry -> {
                // For wheels where the parser can't recover headlight state
                // from telemetry (P6: no live byte), preserve the
                // optimistic lightOn from the previous wheelData so a
                // toggleLight call survives the next realtime frame
                // overwriting it ~250ms later.
                val previous = _wheelData.value
                val isP6 = _modelName.value?.contains("P6") == true
                // V14 etc. don't carry total distance in realtime frames, so
                // preserve whatever was set via the separate TotalDistance
                // decode. The P6 ships the lifetime odometer inline at offset
                // 58, so when the parser already filled it in we keep that
                // fresh value instead of overwriting with the stale previous.
                val totalKm = if (result.data.totalDistance > 0f) result.data.totalDistance
                              else previous.totalDistance
                // Apply the per-wheel speed calibration at the source. Every
                // downstream consumer (alarms, voice, dashboard, recording)
                // sees the calibrated value — there is no second source of
                // truth elsewhere in the app.
                val cal = speedCalibrationMultiplier
                _wheelData.value = result.data.copy(
                    speed = kotlin.math.abs(result.data.speed * cal),
                    totalDistance = totalKm,
                    lightOn = if (isP6) previous.lightOn else result.data.lightOn
                )
                // Sample history at 1 Hz
                val now = System.currentTimeMillis()
                if (now - lastHistorySampleMs >= HISTORY_SAMPLE_INTERVAL_MS) {
                    lastHistorySampleMs = now
                    val d = _wheelData.value
                    battHist.add(MetricSample(now, d.batteryPercent.toFloat()))
                    tempHist.add(MetricSample(now, d.maxTemperature))
                    voltHist.add(MetricSample(now, d.voltage))
                    ampsHist.add(MetricSample(now, d.current))
                    loadHist.add(MetricSample(now, d.pwm.absoluteValue))
                    speedHist.add(MetricSample(now, d.speed.absoluteValue))
                    // Drop anything older than the 5-min window from every
                    // buffer in one pass. List.removeAll touches each list
                    // once so this stays linear in buffer size.
                    val cutoff = now - HISTORY_WINDOW_MS
                    listOf(battHist, tempHist, voltHist, ampsHist, loadHist, speedHist)
                        .forEach { it.removeAll { s -> s.timestampMs < cutoff } }
                    _fullHistory.value = FullMetricHistory(
                        battery = battHist.toList(),
                        temperature = tempHist.toList(),
                        voltage = voltHist.toList(),
                        current = ampsHist.toList(),
                        load = loadHist.toList(),
                        speed = speedHist.toList()
                    )
                }
                // Evaluate alarm rules against new telemetry
                alarmEngine.evaluate(_wheelData.value)
            }
            is DecodeResult.TotalDistance -> {
                _wheelData.value = _wheelData.value.copy(totalDistance = result.km)
            }
            is DecodeResult.P6Temperatures -> {
                // Out-of-band sensor block from the P6's 0x84 response. Merge
                // the values into wheelData so the dashboard's TEMP pill
                // reads max(MOS, motor, driver) instead of the empty list
                // the realtime parser leaves behind. Null entries from the
                // parser (sensor not populated yet or out of range) drop
                // through and don't pollute maxTemperature.
                val temps = listOfNotNull(result.mosC, result.motorC, result.driverBoardC)
                _wheelData.value = _wheelData.value.copy(
                    temperatures = temps,
                    maxTemperature = temps.maxOrNull() ?: 0f
                )
            }
            is DecodeResult.Settings -> {
                val ws = result.data
                _wheelSettings.value = ws
                Log.i(TAG, "Wheel settings: tiltback=${ws.maxSpeedKmh} beep=${ws.alarmSpeedKmh} lockState=${ws.lockState}")

                val appSettings = settingsRepository.get()
                val wasSafety = _safetySpeedActive.value
                val wasLocked = _locked.value
                val isLocked = ws.lockState != 0
                // Skip the lock-state overwrite while the user's tap cooldown
                // is active. Without this, a stale settings frame from before
                // the wheel processed the lock command repaints the button as
                // unlocked for ~1 s, causing the "I locked it then it shows
                // unlocked" symptom on V14. toggleLock's own announcement
                // fires after the cooldown so the voice still matches the
                // settled state.
                val cooldownActive = System.currentTimeMillis() < lockCooldownUntilMs
                if (!cooldownActive) {
                    _locked.value = isLocked
                    if (isLocked != wasLocked && appSettings.announceWheelLock) {
                        voiceService.announceEvent(
                            context.getString(
                                if (isLocked) R.string.voice_wheel_locked
                                else R.string.voice_wheel_unlocked
                            )
                        )
                    }
                }

                if (reconcileNextSettings) {
                    reconcileNextSettings = false
                    reconcileSpeedLimits(ws, appSettings)
                } else {
                    // Subsequent packet: detect Legal mode by user *intent* when
                    // the wheel echoed back a value lower than we asked for.
                    // The wheel's firmware-capped readback can collide with the
                    // stored Legal tiltback (e.g. cap=80, stored Legal=80) and
                    // freeze the toggle ON forever — using lastSentTiltbackKmh
                    // when it differs from the readback breaks that loop.
                    val sent = lastSentTiltbackKmh
                    val effectiveTilt = if (sent != null &&
                        kotlin.math.abs(ws.maxSpeedKmh - sent) > 0.5f
                    ) sent else ws.maxSpeedKmh
                    val isSafety = kotlin.math.abs(effectiveTilt - appSettings.safetyTiltbackKmh) < 0.5f
                    _safetySpeedActive.value = isSafety
                    if (isSafety != wasSafety && appSettings.announceSafetyMode) {
                        voiceService.announceEvent(context.getString(if (isSafety) R.string.voice_legal_on else R.string.voice_legal_off))
                    }

                    // Auto-sync wheel-side changes (P6/V12 let the user adjust
                    // tiltback / alarm directly on the wheel's own screen).
                    // Wait out the debounce so we don't mistake the wheel's
                    // echo of our own write for an external change.
                    val sinceWrite = System.currentTimeMillis() - lastSetSpeedAtMs
                    if (sinceWrite > WHEEL_SCREEN_DEBOUNCE_MS) {
                        val activeTilt = if (isSafety) appSettings.safetyTiltbackKmh
                                         else appSettings.tiltbackSpeedKmh
                        val activeAlarm = if (isSafety) appSettings.safetyAlarmKmh
                                          else appSettings.alarmSpeedKmh
                        // Threshold: 1 km/h either direction. Both upward and
                        // downward wheel-side changes are honored — earlier
                        // upward-only gate was meant to defeat V14 firmware
                        // clamps but blocked legitimate P6 downward changes.
                        // V14 wheels rarely change tiltback externally so the
                        // tradeoff lands in favour of P6 / V12 behaviour.
                        val tiltChanged = kotlin.math.abs(ws.maxSpeedKmh - activeTilt) > 1f
                        val alarmChanged = kotlin.math.abs(ws.alarmSpeedKmh - activeAlarm) > 1f
                        if (tiltChanged || alarmChanged) {
                            val newTilt = if (tiltChanged) ws.maxSpeedKmh else activeTilt
                            val newAlarm = if (alarmChanged) ws.alarmSpeedKmh else activeAlarm
                            val updated = if (isSafety) appSettings.copy(
                                safetyTiltbackKmh = newTilt,
                                safetyAlarmKmh = newAlarm
                            ) else appSettings.copy(
                                tiltbackSpeedKmh = newTilt,
                                alarmSpeedKmh = newAlarm
                            )
                            settingsRepository.update(updated)
                            _externalSpeedChange.tryEmit(
                                ExternalSpeedChange(newTilt, newAlarm, isSafety)
                            )
                            // Surface a system Toast so the silent adoption is
                            // visible no matter which screen the user is on.
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                val unitMsg = context.getString(
                                    R.string.toast_external_speed_change,
                                    newTilt.toInt(), newAlarm.toInt()
                                )
                                android.widget.Toast.makeText(
                                    context, unitMsg, android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                                "Wheel-side speed change adopted: tiltback=$newTilt alarm=$newAlarm legal=$isSafety"
                            )
                            Log.i(TAG, "External speed change adopted: tilt=$newTilt alarm=$newAlarm legal=$isSafety")
                        }
                    }
                }
            }
            DecodeResult.Unknown -> { /* skip */ }
        }
    }
}

/** Emitted whenever the wheel reports a tiltback / alarm value the user
 *  applied directly on the wheel's own screen (no app-side write). The UI
 *  shows a transient toast/snackbar so the silent adoption is visible. */
data class ExternalSpeedChange(
    val tiltbackKmh: Float,
    val alarmKmh: Float,
    val legalMode: Boolean
)
