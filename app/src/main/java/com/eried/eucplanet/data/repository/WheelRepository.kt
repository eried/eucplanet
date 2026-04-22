package com.eried.eucplanet.data.repository

import android.content.Context
import android.util.Log
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.BleConnectionManager
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.InMotionV2Commands
import com.eried.eucplanet.ble.InMotionV2Parser
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

    // Max speed the wheel supports - 90 km/h per official InMotion app (after 30km ridden)
    // The wheel itself enforces the limit, not the app
    private val _maxSpeedCap = MutableStateFlow(90f)
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

    private var lastConnectedAddress: String? = null
    private var initState = 0
    private var pollingActive = false

    // True only for the first 0x20 settings packet after each (re)connect.
    // Used to reconcile stored speed limits with the wheel's actual values.
    @Volatile private var reconcileNextSettings = false

    init {
        // Process incoming packets
        scope.launch {
            bleManager.receivedPackets.collect { packet ->
                handlePacket(packet.command, packet.data)
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
                        // Reset states that depend on wheel connection
                        _safetySpeedActive.value = false
                        _locked.value = false
                        // History is preserved across disconnects (cleared only on new wheel)
                    }
                    else -> {}
                }
            }
        }
    }

    fun connect(address: String) {
        if (lastConnectedAddress != null && lastConnectedAddress != address) {
            // Different wheel — clear history
            battHist.clear(); tempHist.clear(); voltHist.clear()
            ampsHist.clear(); loadHist.clear(); speedHist.clear()
            _fullHistory.value = FullMetricHistory()
        }
        lastConnectedAddress = address
        bleManager.connect(address)
    }

    fun disconnect() {
        pollingActive = false
        bleManager.disconnect()
    }

    // --- Control commands ---

    fun sendHorn() {
        bleManager.writeCommand(InMotionV2Commands.horn())
    }

    fun toggleLight() {
        val current = _wheelData.value.lightOn
        bleManager.writeCommand(InMotionV2Commands.setLight(!current))
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
            bleManager.writeCommand(InMotionV2Commands.getCurrentSettings())
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
     * V14 lock/unlock requires password authentication before the lock command.
     * Flow: requestAuthKey → receive 16-byte key → verifyAuth (echo key) → receive confirm → send lock.
     */
    private suspend fun authenticateAndLock(locked: Boolean): Boolean {
        // Step 1: Request auth key from wheel
        val keyDeferred = CompletableDeferred<ByteArray>()
        pendingAuthKeyDeferred = keyDeferred
        bleManager.writeCommand(InMotionV2Commands.requestAuthKey())
        Log.i(TAG, "Lock: requesting auth key...")

        val key = withTimeoutOrNull(4000L) { keyDeferred.await() }
        pendingAuthKeyDeferred = null

        if (key == null) {
            Log.e(TAG, "Lock: auth key timeout")
            return false
        }
        authKey = key
        Log.i(TAG, "Lock: got auth key (${key.size} bytes): ${key.joinToString(" ") { "%02X".format(it) }}")

        // Step 2: Verify auth by echoing the key back
        val confirmDeferred = CompletableDeferred<Boolean>()
        pendingAuthConfirmDeferred = confirmDeferred
        bleManager.writeCommand(InMotionV2Commands.verifyAuth(key))
        Log.i(TAG, "Lock: verifying auth...")

        val confirmed = withTimeoutOrNull(4000L) { confirmDeferred.await() } ?: false
        pendingAuthConfirmDeferred = null

        if (!confirmed) {
            Log.e(TAG, "Lock: auth verify failed or timeout")
            return false
        }
        Log.i(TAG, "Lock: auth verified, sending lock=$locked")

        // Step 3: Send lock/unlock command
        val packet = InMotionV2Commands.setLock(locked)
        Log.i(TAG, "Lock packet (${packet.size} bytes): ${packet.joinToString(" ") { "%02X".format(it) }}")
        bleManager.writeCommand(packet)
        return true
    }

    fun setDRL(on: Boolean) {
        bleManager.writeCommand(InMotionV2Commands.setDRL(on))
    }

    fun setSpeed(tiltbackKmh: Float, beepKmh: Float) {
        bleManager.writeCommand(InMotionV2Commands.setMaxSpeedV14(tiltbackKmh, beepKmh))
    }

    suspend fun toggleSafetySpeed() {
        val wantActive = !_safetySpeedActive.value
        val settings = settingsRepository.get()

        if (wantActive) {
            setSpeed(settings.safetyTiltbackKmh, settings.safetyAlarmKmh)
        } else {
            setSpeed(settings.tiltbackSpeedKmh, settings.alarmSpeedKmh)
        }
        Log.i(TAG, "Safety speed toggle requested: active=$wantActive")

        // Request settings back from wheel to confirm
        delay(300)
        bleManager.writeCommand(InMotionV2Commands.getCurrentSettings())
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
        var realtimeCycle = 0
        while (pollingActive && bleManager.connectionState.value == ConnectionState.CONNECTED) {
            when (initState) {
                0 -> {
                    bleManager.writeCommand(InMotionV2Commands.getCarType())
                    initState++
                }
                1 -> {
                    bleManager.writeCommand(InMotionV2Commands.getSerialNumber())
                    initState++
                }
                2 -> {
                    bleManager.writeCommand(InMotionV2Commands.getVersions())
                    initState++
                }
                3 -> {
                    bleManager.writeCommand(InMotionV2Commands.getCurrentSettings())
                    initState++
                }
                4 -> {
                    bleManager.writeCommand(InMotionV2Commands.getUselessData())
                    initState++
                }
                5 -> {
                    bleManager.writeCommand(InMotionV2Commands.getStatistics())
                    initState++
                }
                else -> {
                    // Normal polling - realtime data, interleaved with periodic settings refresh
                    // so externally-changed state (lock via InMotion app, etc.) is detected.
                    if (realtimeCycle % SETTINGS_REFRESH_INTERVAL == 0) {
                        bleManager.writeCommand(InMotionV2Commands.getCurrentSettings())
                    } else {
                        bleManager.writeCommand(InMotionV2Commands.getRealTimeData())
                    }
                    realtimeCycle++
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    // --- Speed limits reconciliation on (re)connect ---

    /**
     * The wheel only stores one active (tiltback, alarm) pair at a time, but we keep
     * both a "normal" pair and a "legal" pair persistently. On reconnect we compare
     * what the wheel reports against both stored pairs:
     *
     *  A) wheel tilt ≈ stored legal   → wheel is in legal mode; keep both stored pairs
     *  B) wheel tilt ≈ stored normal  → wheel is in normal mode; keep both stored pairs
     *  C) wheel tilt < stored legal   → user lowered legal externally → adopt as new legal
     *  D) wheel tilt > legal, ≠ normal → user picked new normal externally → adopt as new normal
     *
     * In A/B we never overwrite stored values — so the other pair is preserved across
     * reconnects even though the wheel forgets it.
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
            else -> {
                // Wheel above legal but != stored normal — adopt as new normal
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

    // --- Packet handling ---

    private suspend fun handlePacket(command: Byte, data: ByteArray) {
        when (command.toInt() and 0x7F) {
            0x02 -> {
                // MainInfo response OR auth response (both arrive as command=0x02)
                if (data.isNotEmpty()) {
                    when (data[0].toInt() and 0xFF) {
                        0x01 -> {
                            val info = InMotionV2Parser.parseCarType(data.copyOfRange(1, data.size))
                            info?.let { _modelName.value = it.modelName }
                        }
                        0x06 -> {
                            val fw = InMotionV2Parser.parseVersions(data.copyOfRange(1, data.size))
                            if (fw != null) {
                                _firmwareVersion.value = fw.displayString
                                Log.i(TAG, "Firmware: Main=${fw.mainBoardVersion} Drv=${fw.driverBoardVersion} BLE=${fw.bleVersion}")
                            }
                        }
                        0x80 -> {
                            // Auth response (routing byte 0x80 = response from routing [02,00])
                            // data = [0x80, sub_cmd, payload...]
                            if (data.size >= 2) {
                                val subCmd = data[1].toInt() and 0xFF
                                when (subCmd) {
                                    0x02 -> {
                                        // Auth key response: 16 bytes of encrypted password
                                        if (data.size >= 18) {
                                            val key = data.copyOfRange(2, 18)
                                            Log.i(TAG, "Auth key received: ${key.joinToString(" ") { "%02X".format(it) }}")
                                            pendingAuthKeyDeferred?.complete(key)
                                        }
                                    }
                                    0x82 -> {
                                        // Auth verify response: data[2] = 0x01 for success
                                        val success = data.size >= 3 && data[2].toInt() == 0x01
                                        Log.i(TAG, "Auth verify result: $success")
                                        pendingAuthConfirmDeferred?.complete(success)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            0x04 -> {
                // RealTimeInfo
                val telemetry = InMotionV2Parser.parseTelemetry(data)
                if (telemetry != null) {
                    _wheelData.value = telemetry.copy(
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
            }
            0x11 -> {
                // TotalStats
                val stats = InMotionV2Parser.parseTotalStats(data)
                if (stats != null) {
                    _wheelData.value = _wheelData.value.copy(
                        totalDistance = stats.totalDistanceKm
                    )
                }
            }
            0x20 -> {
                // Settings
                val ws = InMotionV2Parser.parseSettings(data)
                if (ws != null) {
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
                        // Subsequent packet: simple state detection
                        val isSafety = kotlin.math.abs(ws.maxSpeedKmh - appSettings.safetyTiltbackKmh) < 0.5f
                        _safetySpeedActive.value = isSafety
                        if (isSafety != wasSafety && appSettings.announceSafetyMode) {
                            voiceService.announceEvent(context.getString(if (isSafety) R.string.voice_legal_on else R.string.voice_legal_off))
                        }
                    }
                }
            }
        }
    }
}
