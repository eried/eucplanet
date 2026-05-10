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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val voiceService: VoiceService
) {
    companion object {
        private const val TAG = "WheelRepo"
        private const val POLL_INTERVAL_MS = 250L
        private const val HISTORY_SAMPLE_INTERVAL_MS = 1000L
        // Re-request settings every N realtime polls to pick up external changes
        // (lock/unlock via InMotion app or physical button). 12 * 250ms = 3s.
        private const val SETTINGS_REFRESH_INTERVAL = 12

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

        // React to connection state changes
        scope.launch {
            bleManager.connectionState.collect { state ->
                when (state) {
                    ConnectionState.CONNECTED -> {
                        reconcileNextSettings = true
                        startInitSequence()
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
        val current = _wheelData.value.lightOn
        com.eried.eucplanet.ble.P6DebugLogger.note(
            "toggleLight: lightOn was=$current, sending ${!current}"
        )
        wheelAdapter.setLight(!current)?.let { bleManager.writeCommand(it) }
        // Announcement is emitted by WheelService when the wheel confirms
        // the new state in telemetry — covers DRL / wheel-side toggles too.
    }

    fun toggleLock() {
        val targetState = !_locked.value
        // Optimistic flip: the button reflects the requested state immediately so the user
        // doesn't mash it again. Telemetry (0x20 settings) is the final source of truth —
        // if the wheel reports back a different state, _locked is corrected then.
        _locked.value = targetState
        scope.launch {
            val success = authenticateAndLock(targetState)
            // Re-read settings so the wheel can confirm (or override) the optimistic UI.
            delay(800)
            bleManager.writeCommand(wheelAdapter.pollSettings())
            if (success) {
                val s = settingsRepository.get()
                if (s.announceWheelLock) {
                    voiceService.announceEvent(context.getString(if (targetState) R.string.voice_wheel_locked else R.string.voice_wheel_unlocked))
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
                } else if (realtimeCycle % SETTINGS_REFRESH_INTERVAL == 0) {
                    bleManager.writeCommand(wheelAdapter.pollSettings())
                } else {
                    bleManager.writeCommand(wheelAdapter.pollRealtime())
                }
                realtimeCycle++
            }
            delay(POLL_INTERVAL_MS)
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
                // wTilt is between legal and normal: could be a firmware cap clamp
                // OR an external lower of normal. We can't tell apart here, and
                // overwriting stored normal with a clamped value loses the user's
                // preference forever. Keep stored values; treat as normal mode.
                isLegalOn = false
                appSettings
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
                _wheelData.value = result.data.copy(
                    totalDistance = _wheelData.value.totalDistance
                )
                // Sample history at 1 Hz
                val now = System.currentTimeMillis()
                if (now - lastHistorySampleMs >= HISTORY_SAMPLE_INTERVAL_MS) {
                    lastHistorySampleMs = now
                    val d = _wheelData.value
                    battHist.add(MetricSample(now, d.batteryPercent.toFloat()))
                    tempHist.add(MetricSample(now, d.maxTemperature))
                    voltHist.add(MetricSample(now, d.voltage))
                    ampsHist.add(MetricSample(now, d.current.absoluteValue))
                    loadHist.add(MetricSample(now, d.pwm.absoluteValue))
                    speedHist.add(MetricSample(now, d.speed.absoluteValue))
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
            is DecodeResult.Settings -> {
                val ws = result.data
                _wheelSettings.value = ws
                Log.i(TAG, "Wheel settings: tiltback=${ws.maxSpeedKmh} beep=${ws.alarmSpeedKmh} lockState=${ws.lockState}")

                val appSettings = settingsRepository.get()
                val wasSafety = _safetySpeedActive.value
                val wasLocked = _locked.value
                val isLocked = ws.lockState != 0
                _locked.value = isLocked
                if (isLocked != wasLocked && appSettings.announceWheelLock) {
                    voiceService.announceEvent(
                        context.getString(
                            if (isLocked) R.string.voice_wheel_locked
                            else R.string.voice_wheel_unlocked
                        )
                    )
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
                }
            }
            DecodeResult.Unknown -> { /* skip */ }
        }
    }
}
