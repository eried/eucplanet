package com.eried.eucplanet.data.repository

import android.content.Context
import android.util.Log
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.BleConnectionManager
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.DecodeResult
import com.eried.eucplanet.ble.WheelAdapter
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ChargeStatus
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings
import com.eried.eucplanet.service.AlarmEngine
import com.eried.eucplanet.service.ChargingEstimate
import com.eried.eucplanet.service.ChargingEstimator
import com.eried.eucplanet.service.VoiceService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
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

/**
 * One snapshot of the running prediction. Appended to [ChargingSnapshot.predictionHistory]
 * every ~30 s while a charge session is active so the chart can plot how the
 * predicted finish times have drifted over the course of the charge -- a
 * stable cluster around the actual end time means the model was accurate;
 * spread means it was chasing reality.
 *
 * `sampleTimeMs` is when the prediction was made, the two ETAs are absolute
 * clock times for the target (e.g. 80 %) and 100 % respectively, or null if
 * the estimator wasn't warmed up at that point.
 */
data class PredictionSample(
    val sampleTimeMs: Long,
    val targetEtaMs: Long?,
    val fullEtaMs: Long?,
)

/** Persistent charging-session snapshot — lives in the singleton repository so
 *  the prediction/history survives navigating in and out of the Battery screen. */
data class ChargingSnapshot(
    val estimate: ChargingEstimate = ChargingEstimate(),
    val chargeHistory: List<MetricSample> = emptyList(),
    val voltageHistory: List<MetricSample> = emptyList(),
    val tempHistory: List<MetricSample> = emptyList(),
    /** Smoothed absolute finish time (ms) for the target / 100 %, or null. */
    val targetEtaMs: Long? = null,
    val fullEtaMs: Long? = null,
    /** Rolling log of the running prediction, see [PredictionSample]. */
    val predictionHistory: List<PredictionSample> = emptyList(),
    /** Signed Wh integrated since session start (+ charging, − discharging). */
    val sessionEnergyWh: Float = 0f,
    /** Wh added while charging this session (positive-power integration). */
    val sessionEnergyInWh: Float = 0f,
    /** Wh used while discharging this session (magnitude of the negative-power part). */
    val sessionEnergyOutWh: Float = 0f,
    /** Per-pack cell-spread history, one list per pack, sampled on the SAME
     *  cadence as [chargeHistory] so the X axes line up. Three parallel series
     *  per pack: the min, max and average cell voltage. On a smart-BMS wheel the
     *  values are per-cell volts ("V"); on a non-BMS wheel min == max == avg ==
     *  the per-pack SoC ("%"), so its band is zero-thickness. */
    val packMinHistory: List<List<MetricSample>> = emptyList(),
    val packMaxHistory: List<List<MetricSample>> = emptyList(),
    val packAvgHistory: List<List<MetricSample>> = emptyList(),
    /** Unit shared by the pack histories: "V" (smart BMS) or "%" (per-pack SoC). */
    val packSeriesUnit: String = "%",
)

data class FullMetricHistory(
    val battery: List<MetricSample> = emptyList(),
    val temperature: List<MetricSample> = emptyList(),
    val voltage: List<MetricSample> = emptyList(),
    val current: List<MetricSample> = emptyList(),
    val load: List<MetricSample> = emptyList(),
    val speed: List<MetricSample> = emptyList(),
    /**
     * Rolling history for every other catalog metric the dashboard
     * displays. Indexed by metric key (MOTOR_POWER, PITCH, MOTOR_TEMP,
     * etc.) so MetricDetailScreen can graph any tile in the catalog,
     * not just the legacy 6. Same retention window as the legacy
     * buffers (HISTORY_WINDOW_MS).
     */
    val extras: Map<String, List<MetricSample>> = emptyMap()
)

/**
 * Metric keys that are extracted from WheelData on every 1 Hz tick and
 * appended to FullMetricHistory.extras so their tiles can graph. The
 * extractor returns the raw value in WheelData's canonical units; the
 * MetricDetailScreen applies the rider's display unit conversion.
 *
 * Adding a metric here is the only step needed for its tap-to-graph to
 * start working -- no separate buffer or sample-tick code, no schema
 * change to FullMetricHistory.
 */
internal val EXTRA_HISTORY_METRICS: List<Pair<String, (com.eried.eucplanet.data.model.WheelData) -> Float>> = listOf(
    "MOTOR_POWER" to { it.motorPower.toFloat() },
    "BATTERY_POWER" to { it.batteryPower.toFloat() },
    // POWER is an alias of BATTERY_POWER kept for backwards-compat with
    // dashboards saved before the catalog rename; same buffer.
    "POWER" to { it.batteryPower.toFloat() },
    "BATTERY_1" to { it.battery1Percent },
    "BATTERY_2" to { it.battery2Percent },
    "PITCH" to { it.pitchAngle },
    "ROLL" to { it.rollAngle },
    "G_FORCE" to { it.gForce },
    "LATERAL_G" to { it.accelX },
    "FORWARD_G" to { it.accelY },
    "TORQUE" to { it.torque },
    "DYN_SPEED_LIMIT" to { it.dynamicSpeedLimit },
    "DYN_CURRENT_LIMIT" to { it.dynamicCurrentLimit },
    "MOTOR_TEMP" to { it.temperatures.getOrNull(0) ?: 0f },
    "CONTROLLER_TEMP" to { it.temperatures.getOrNull(1) ?: 0f },
    "BATTERY_TEMP" to { it.temperatures.getOrNull(2) ?: 0f }
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
    private val cheatState: com.eried.eucplanet.cheats.CheatState,
    private val phoneSensorRepository: PhoneSensorRepository,
    private val externalGpsRepository: ExternalGpsRepository,
    private val appNotifier: com.eried.eucplanet.util.AppNotifier
) {
    companion object {
        private const val TAG = "WheelRepo"
        // Default wheel-poll interval (ms). Overridden per-rider by
        // AppSettings.wheelPollIntervalMs; this is only the fallback / initial
        // value. Fully decoupled from the watch feed — watchUpdateRate now paces
        // WearBridge alone. Only request/response wheels (InMotion, Ninebot)
        // honour this; push-only families ignore it (they free-run).
        private const val POLL_INTERVAL_MS = 250L
        // Default dashboard-chart sampling interval (ms); overridden by
        // AppSettings.graphSampleIntervalMs. Charts only — not alarms/recording.
        private const val HISTORY_SAMPLE_INTERVAL_MS = 1000L
        // Hard 5-minute window on the metric history buffers. Without this,
        // each list grows unbounded at 1 Hz (memory leak) and the chart's
        // takeLast(300) shows ~5m10s instead of a clean 5m because the
        // sampler drifts. Time-bounding here makes the chart truly 5 min.
        private const val HISTORY_WINDOW_MS = 5 * 60 * 1000L
        // Append one PredictionSample to the charge-session log every 30 s
        // while charging. Spans a 4 h session in ~480 entries (cheap), with
        // enough resolution to see the prediction stabilise over the first
        // few minutes after warm-up.
        private const val PREDICTION_LOG_INTERVAL_MS = 30_000L
        private const val PREDICTION_LOG_MAX_ENTRIES = 600
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

        // Fallback slider ceiling when no wheel is connected or when the
        // model ID isn't in our registry; 90 matches what the V14-only
        // build always showed.
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

    /** Whether the currently connected wheel's adapter implements a lock
     *  command. Drives the dashboard so the lock button can fall back to a
     *  "not supported on this wheel" snackbar instead of optimistically
     *  flipping the lock icon and then silently snapping back when the next
     *  settings poll resets it. False while disconnected (no adapter yet) so
     *  the button shows the same hint until a real capability is known. */
    private val _wheelHasLock = MutableStateFlow(false)
    val wheelHasLock: StateFlow<Boolean> = _wheelHasLock.asStateFlow()

    // Charging state — explicit firmware flag (V14/V12/KingSong) when available,
    // otherwise inferred from sustained negative current. Drives the dashboard
    // spark icon and the Charging Monitor screen.
    private val _chargeStatus = MutableStateFlow(ChargeStatus.Disconnected)
    val chargeStatus: StateFlow<ChargeStatus> = _chargeStatus.asStateFlow()

    // Hysteresis state for the current-based charging inference (families that
    // don't ship an explicit flag). Latches on after a run of negative samples,
    // off after a run of near-zero ones; the trickle band in between holds the
    // last state so the icon doesn't flicker as the charger tapers near full.
    private var chargeInferred = false
    private var chargeNegSamples = 0
    private var chargePosSamples = 0

    // Charging session (estimator + per-session history) lives here so the
    // prediction persists across navigation; updated each telemetry frame.
    // Defaults from ChargingEstimator: targetTaperFactor 1.05, cvTaperFactor
    // 1.3 -- a tiny safety margin for the 80 % ETA, modest pessimism for
    // 80 % -> 100 %. Riders consistently saw the old 2.2 multiplier produce
    // 100 % ETAs that were ~1 h late on a 4 h charge.
    private val chargingEstimator = ChargingEstimator(
        warmupMinPercentGain = 1.0f,    // wait for a stabler rate before the first prediction
        warmupMinDurationMs = 40_000L,  // two jitter windows for a steady rate
    )
    private var chargingSessionActive = false
    private val chargePctHist = ArrayDeque<MetricSample>()
    private val chargeVoltHist = ArrayDeque<MetricSample>()
    private val chargeTempHist = ArrayDeque<MetricSample>()
    // Per-pack charging-session history: three rings per pack (min / max / avg
    // cell voltage), all sampled on the SAME cadence as chargePctHist so their X
    // axes align. packHistUnit is "V" (per-cell voltage on a smart-BMS wheel) or
    // "%" (per-pack SoC on battery1/battery2 wheels, where min == max == avg). The
    // buffers reset when the unit or pack count changes so a "%" -> "V" switch
    // (BMS data arriving a few seconds after connect) never mixes units on one axis.
    private val packMinHist = mutableListOf<ArrayDeque<MetricSample>>()
    private val packMaxHist = mutableListOf<ArrayDeque<MetricSample>>()
    private val packAvgHist = mutableListOf<ArrayDeque<MetricSample>>()
    private var packHistUnit = "%"
    private var chargeLastHistMs = 0L
    // Committed finish times: chosen once (remaining rounded up to the minute),
    // counted down in real time, only gently re-anchored every ~2 min so the UI
    // never jumps frame-to-frame.
    private var committedTargetEtaMs: Long? = null
    private var committedTargetAnchorMs = 0L
    private var committedFullEtaMs: Long? = null
    private var committedFullAnchorMs = 0L
    // Rolling log of (sampleTime, predicted ETAs) appended every
    // PREDICTION_LOG_INTERVAL_MS while a charge session is active so the
    // chart can visualise how the prediction drifted over the session.
    private val predictionHistory = ArrayDeque<PredictionSample>()
    private var lastPredictionLogMs = 0L
    // Trapezoidal energy integral for the session (sign matches V*I).
    private var sessionEnergyWh = 0f
    // Session energy split by direction so the Battery screen can show the ride
    // loss and the charge gain separately (net = in - out). Sign-based on the
    // instantaneous power, so a V14 (~0 A while charging) still accumulates a
    // real "out" over the ride while its "in" stays ~0.
    private var sessionEnergyInWh = 0f
    private var sessionEnergyOutWh = 0f
    private var sessionLastEnergyMs = 0L
    private var sessionLastPowerW = 0f
    private val _chargingSnapshot = MutableStateFlow(ChargingSnapshot())
    val chargingSnapshot: StateFlow<ChargingSnapshot> = _chargingSnapshot.asStateFlow()

    private val _modelName = MutableStateFlow<String?>(null)
    val modelName: StateFlow<String?> = _modelName.asStateFlow()

    // Wheel serial number reported by the firmware (currently emitted by the
    // KingSong 0xB3 sub-cmd and the InMotion P6 0x06 info bundle). Separate
    // from modelName so eucstats meta carries them in different JSON fields
    // and the dashboard's model label stays uniform across riders.
    private val _wheelSerial = MutableStateFlow<String?>(null)
    val wheelSerial: StateFlow<String?> = _wheelSerial.asStateFlow()

    // Stitched smart-BMS state. The Veteran adapter ships BMS sub-frames as
    // DecodeResult.Bms slices covering a 12-15 cell window each; handleDecoded
    // merges successive slices into a full per-pack view. Empty packs list
    // means "no smart BMS / no data yet" — the Battery monitor's Cells tab
    // gates on this so non-BMS wheels (older Sherman / KingSong / P6) don't
    // see an empty tab.
    private val _bmsState = MutableStateFlow(com.eried.eucplanet.data.model.BmsState())
    val bmsState: StateFlow<com.eried.eucplanet.data.model.BmsState> = _bmsState.asStateFlow()

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
    // One buffer per entry in EXTRA_HISTORY_METRICS. Keys mirror the
    // catalog so the metric-detail screen can fish the right list out
    // by name without a hard-coded switch per metric.
    private val extrasHist: MutableMap<String, MutableList<MetricSample>> =
        EXTRA_HISTORY_METRICS.associate { (key, _) -> key to mutableListOf<MetricSample>() }
            .toMutableMap()
    private var lastHistorySampleMs = 0L

    private val _fullHistory = MutableStateFlow(FullMetricHistory())
    val fullHistory: StateFlow<FullMetricHistory> = _fullHistory.asStateFlow()

    /**
     * Clears the in-memory rolling history buffer for one metric key.
     * Used by the metric-detail Reset button so the rider can re-seed
     * a clean chart (e.g. after a recovery from a noisy connection).
     * Settings and trip records are untouched — fresh samples re-seed
     * the buffer at the next 1Hz tick.
     */
    fun resetHistory(key: String) {
        val current = _fullHistory.value
        _fullHistory.value = when (key) {
            "BATTERY" -> { battHist.clear(); current.copy(battery = emptyList()) }
            "TEMPERATURE" -> { tempHist.clear(); current.copy(temperature = emptyList()) }
            "VOLTAGE" -> { voltHist.clear(); current.copy(voltage = emptyList()) }
            "CURRENT" -> { ampsHist.clear(); current.copy(current = emptyList()) }
            "LOAD" -> { loadHist.clear(); current.copy(load = emptyList()) }
            "SPEED" -> { speedHist.clear(); current.copy(speed = emptyList()) }
            else -> {
                // Extras live in the keyed map; clear that one list and
                // emit a new extras map so downstream collectors see the
                // reset. Other metrics' buffers untouched.
                extrasHist[key]?.clear()
                current.copy(extras = extrasHist.mapValues { (_, list) -> list.toList() })
            }
        }
    }

    /** Clears every in-memory rolling history buffer. */
    fun resetAllHistory() {
        _fullHistory.value = FullMetricHistory()
    }

    // Auth state for lock/unlock (V14 requires password verification)
    private var authKey: ByteArray? = null
    private var pendingAuthKeyDeferred: CompletableDeferred<ByteArray>? = null
    private var pendingAuthConfirmDeferred: CompletableDeferred<Boolean>? = null

    // Serialise the lock auth handshake so rapid taps can't strand each
    // other's deferreds. Without this, two concurrent toggleLock() calls
    // both write the singleton `pendingAuthKeyDeferred`; the first call's
    // deferred is overwritten and times out without ever reaching the
    // setLock write, exactly the symptom seen on the P6 ("lock doesn't
    // work" while WIM works because WIM serialises taps in its UI).
    private val authMutex = Mutex()

    private var lastConnectedAddress: String? = null
    private var initState = 0
    private var pollingActive = false

    // True only for the first 0x20 settings packet after each (re)connect.
    // Used to reconcile stored speed limits with the wheel's actual values.
    @Volatile private var reconcileNextSettings = false
    /** Mirrors AppSettings.gpsPrioritizeExternal for the g-force source merge. */
    @Volatile private var prioritizeExternalGps = true

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
     * Wheel poll interval in milliseconds, resolved from
     * AppSettings.wheelPollIntervalMs and mirrored into the hot poll path so the
     * loop never re-collects the settings flow per cycle. Independent of the
     * watch feed. Falls back to [POLL_INTERVAL_MS].
     */
    @Volatile private var pollIntervalMs: Long = POLL_INTERVAL_MS

    /**
     * Dashboard-chart sampling interval in milliseconds, resolved from
     * AppSettings.graphSampleIntervalMs. Drives only the rolling-history buffers;
     * alarms run at the full telemetry rate and trip recording has its own
     * interval. Falls back to [HISTORY_SAMPLE_INTERVAL_MS].
     */
    @Volatile private var graphSampleIntervalMs: Long = HISTORY_SAMPLE_INTERVAL_MS

    /**
     * How much rolling history to retain in the metric buffers, in ms. Follows
     * the rider's dashboard rolling-window setting (up to 15 min) but never drops
     * below [HISTORY_WINDOW_MS], so the detail charts keep a sane minimum. Fixes
     * the buffer silently capping a 10 / 15-min window at 5 min.
     */
    @Volatile private var historyWindowMs: Long = HISTORY_WINDOW_MS

    /**
     * Riders have lock-toggle bindings on Flic buttons, the watch buttons, the
     * volume keys, and the dashboard. A misfire while moving locks the wheel
     * mid-ride and causes an instant motor cutout, so every lock-direction
     * call goes through one gate here. Unlock always proceeds (a locked wheel
     * has speed = 0 by definition). 5 km/h covers the standstill / push-assist
     * range without false rejecting at IMU noise around zero.
     */
    @Volatile private var lockMaxSpeedKmh = 5f

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

        // Tracks the "prioritize external GPS" setting so the g-force merge
        // below doesn't read settings on every IMU tick.
        scope.launch {
            settingsRepository.settings.collect { prioritizeExternalGps = it.gpsPrioritizeExternal }
        }

        // Merge accelerometer (g-force) into the telemetry stream so it shows
        // live and is recorded into the trip CSV. Sampled to ~8 Hz so it
        // doesn't flood downstream collectors. A paired external box (RaceBox)
        // is mounted on the wheel, so when the rider has one and "prioritize
        // external" is on, its accelerometer wins over the phone's IMU.
        scope.launch {
            phoneSensorRepository.imu.sample(120L).collect { s ->
                val ext = externalGpsRepository.currentSample.value
                val useExt = prioritizeExternalGps &&
                    externalGpsRepository.connectionState.value == ConnectionState.CONNECTED &&
                    ext?.accelXG != null
                _wheelData.value = if (useExt) {
                    val ax = ext!!.accelXG ?: 0f
                    val ay = ext.accelZG ?: 0f
                    _wheelData.value.copy(
                        gForce = kotlin.math.sqrt(ax * ax + ay * ay),
                        accelX = ax,
                        accelY = ay
                    )
                } else _wheelData.value.copy(
                    gForce = s?.magnitude ?: 0f,
                    accelX = s?.xG ?: 0f,
                    accelY = s?.zG ?: 0f
                )
            }
        }

        // Forward-G estimated from wheel-speed change (dv/dt / g). Orientation-independent,
        // so far more reliable than the phone IMU's forward axis. Samples speed at 10 Hz and
        // takes the slope over a short window. Drives the FORWARD_G dashboard metric only;
        // the recorded IMU g-force columns are untouched.
        scope.launch {
            val buf = ArrayDeque<MetricSample>()
            val windowMs = 600L
            while (true) {
                kotlinx.coroutines.delay(100L)
                val now = System.currentTimeMillis()
                buf.addLast(MetricSample(now, _wheelData.value.speed / 3.6f)) // km/h -> m/s
                while (buf.isNotEmpty() && now - buf.first().timestampMs > windowMs) buf.removeFirst()
                val fwdG = if (buf.size >= 2) {
                    val dt = (buf.last().timestampMs - buf.first().timestampMs) / 1000f
                    if (dt > 0.05f) (buf.last().value - buf.first().value) / dt / 9.80665f else 0f
                } else 0f
                val rounded = Math.round(fwdG * 100f) / 100f
                if (rounded != _wheelData.value.forwardGFromSpeed) {
                    _wheelData.value = _wheelData.value.copy(forwardGFromSpeed = rounded)
                }
            }
        }

        // Track the rider's speed calibration. We mirror it into a volatile
        // multiplier so the hot telemetry path applies it without re-reading
        // the settings flow per frame. Also persist a copy into the per-wheel
        // profile whenever the value changes, keyed by the BLE-advertised
        // name (AppSettings.lastDeviceName), so a reconnect to the same
        // wheel restores everything (tiltback, alarm, safety, calibration).
        scope.launch {
            settingsRepository.settings.collect { s ->
                val clamped = s.speedCalibrationOffsetPct.coerceIn(-15f, 15f)
                speedCalibrationMultiplier = 1f + clamped / 100f
                // Wheel poll + chart sampling are independent rider settings now,
                // both decoupled from the watch feed (watchUpdateRate paces only
                // WearBridge). Mirror into volatiles so the hot loops never
                // re-collect the flow per cycle.
                pollIntervalMs = s.wheelPollIntervalMs.toLong()
                graphSampleIntervalMs = s.graphSampleIntervalMs.toLong()
                // Retain at least the rider's dashboard rolling window (up to
                // 15 min), floored at the 5-min default so detail charts don't
                // shrink below it when the window is set small.
                historyWindowMs = maxOf(s.dashboardRollingWindowSeconds * 1000L, HISTORY_WINDOW_MS)
                lockMaxSpeedKmh = s.lockMaxSpeedKmh.toFloat()
                // Push the rider's charging-ETA tuning into the live estimator; it
                // reads these each estimate() so a change applies without losing the
                // running session. Taper factors are stored x100.
                chargingEstimator.targetPercent = s.chargingTargetPercent.toFloat()
                chargingEstimator.targetTaperFactor = s.chargingTargetTaperX100 / 100f
                chargingEstimator.cvTaperFactor = s.chargingCvTaperX100 / 100f
                chargingEstimator.warmupMinPercentGain = s.chargingWarmupMinPercentGain.toFloat()
                chargingEstimator.warmupMinDurationMs = s.chargingWarmupMinDurationMs.toLong()
                chargingEstimator.windowMs = s.chargingWindowMs.toLong()
                chargingEstimator.sanityCapMinutes = s.chargingSanityCapMinutes.toFloat()
                chargingEstimator.medianFilterSize = s.chargingMedianFilterSize
                val wheelName = s.lastDeviceName
                if (wheelName != null && bleManager.connectionState.value == ConnectionState.CONNECTED) {
                    persistWheelProfile(wheelName, s)
                }
            }
        }

        // The wheel-data merge needs IMU samples for its lifetime (we feed them
        // into accelX / accelY / gForce regardless of BLE state, the IMU is
        // the phone's, not the wheel's). Hold a single start ref forever so
        // BLE disconnect flaps can't tear the listener down out from under
        // active consumers like the Overlay Studio. The matching stop() lives
        // on no code path, this is a singleton, the IMU is cheap, and any
        // attempt to "balance" this with a stop reintroduces the bug.
        phoneSensorRepository.start()

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
                        // Publish per-adapter capabilities now that the BLE
                        // name has resolved which sub-adapter the Composite
                        // is routing through. Today only `hasLock` drives a
                        // UI gate (lock button on the dashboard), but the
                        // pattern is generic enough to grow other gates
                        // without touching the connection observer.
                        _wheelHasLock.value = wheelAdapter.capabilities.hasLock
                    }
                    ConnectionState.DISCONNECTED -> {
                        pollingActive = false
                        initState = 0
                        authKey = null
                        pendingAuthKeyDeferred = null
                        pendingAuthConfirmDeferred = null
                        lastSentTiltbackKmh = null
                        // Drop the per-connection mirror so the next wheel
                        // (a different one, possibly) gets a fresh sync from
                        // its own firmware-reported limits.
                        lastSyncedWheelMaxKmh = -1f
                        lastSyncedWheelAlarmKmh = -1f
                        // Reset states that depend on wheel connection
                        _safetySpeedActive.value = false
                        _locked.value = false
                        _wheelHasLock.value = false
                        _chargeStatus.value = ChargeStatus.Disconnected
                        chargeInferred = false
                        chargeNegSamples = 0
                        chargePosSamples = 0
                        chargingSessionActive = false
                        chargingEstimator.reset()
                        chargePctHist.clear()
                        chargeVoltHist.clear()
                        chargeTempHist.clear()
                        clearPackHist()
                        _chargingSnapshot.value = ChargingSnapshot()
                        _modelName.value = null
                        _wheelSerial.value = null
                        _bmsState.value = com.eried.eucplanet.data.model.BmsState()
                        _firmwareVersion.value = null
                        _maxSpeedCap.value = DEFAULT_MAX_SPEED_KMH
                        _wheelData.value =
                            _wheelData.value.copy(
                                totalDistance = 0f, gForce = 0f,
                                accelX = 0f, accelY = 0f
                            )
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
     * seed one from the current AppSettings, that becomes the starting
     * point for this wheel and future tweaks are written back via
     * [persistWheelProfile].
     */
    /**
     * Map the latest telemetry to a [ChargeStatus]. Prefers the explicit
     * firmware charging flag (InMotion V14/V12 bit 7, KingSong 0xB9); for
     * families without one, infers from sustained negative current with
     * hysteresis (latches on after 5 negative samples, off after 10 near-zero
     * ones, holds through the trickle band) so the dashboard doesn't flicker as
     * the charger tapers near full. A moving wheel is never reported charging.
     */
    private fun updateChargingSession(data: WheelData, status: ChargeStatus) {
        // Connection-scoped: the session runs whenever a wheel is connected (so the
        // charts / Added / Rate keep updating while riding too) and only resets on
        // disconnect. Charging itself only drives the prediction + green colour.
        val connected = status != ChargeStatus.Disconnected
        if (connected) {
            if (!chargingSessionActive) {
                chargingSessionActive = true
                chargingEstimator.reset()
                chargePctHist.clear(); chargeVoltHist.clear(); chargeTempHist.clear(); clearPackHist()
                chargeLastHistMs = 0L
                committedTargetEtaMs = null
                committedTargetAnchorMs = 0L
                committedFullEtaMs = null
                committedFullAnchorMs = 0L
                predictionHistory.clear()
                lastPredictionLogMs = 0L
                sessionEnergyWh = 0f
                sessionEnergyInWh = 0f
                sessionEnergyOutWh = 0f
                sessionLastEnergyMs = data.timestamp
                sessionLastPowerW = data.voltage * data.current
            }
            val pct = batteryPercentOf(data)
            chargingEstimator.addSample(data.timestamp, pct)
            // Trapezoidal energy integration: avg(prev, now) * dt. Capped dt
            // so a long pause/clock jump doesn't accumulate a phantom bucket.
            val nowPowerW = data.voltage * data.current
            val dtMs = (data.timestamp - sessionLastEnergyMs).coerceIn(0L, 5_000L)
            if (dtMs > 0L) {
                val incWh = ((sessionLastPowerW + nowPowerW) * 0.5f) * (dtMs / 3_600_000f)
                sessionEnergyWh += incWh
                if (incWh >= 0f) sessionEnergyInWh += incWh else sessionEnergyOutWh -= incWh
            }
            sessionLastEnergyMs = data.timestamp
            sessionLastPowerW = nowPowerW
            // History for the charts is downsampled (~5 s) so a multi-hour charge
            // still fits the rolling buffer.
            // ~15 s spacing × 1000-sample cap ≈ a 4-hour charge window in the chart.
            if (data.timestamp - chargeLastHistMs >= 15_000L) {
                chargeLastHistMs = data.timestamp
                pushHist(chargePctHist, data.timestamp, pct)
                pushHist(chargeVoltHist, data.timestamp, data.voltage)
                pushHist(chargeTempHist, data.timestamp, data.maxTemperature)
                recordPackHist(data)
            }
        } else if (chargingSessionActive) {
            chargingSessionActive = false
            chargingEstimator.reset()
            chargePctHist.clear(); chargeVoltHist.clear(); chargeTempHist.clear(); clearPackHist()
            committedTargetEtaMs = null
            committedTargetAnchorMs = 0L
            committedFullEtaMs = null
            committedFullAnchorMs = 0L
            predictionHistory.clear()
            lastPredictionLogMs = 0L
            sessionEnergyWh = 0f
            sessionEnergyInWh = 0f
            sessionEnergyOutWh = 0f
            sessionLastEnergyMs = 0L
            sessionLastPowerW = 0f
        }
        val est = chargingEstimator.estimate()
        val (te, ta) = commitEta(committedTargetEtaMs, committedTargetAnchorMs, est.minutesToTarget, data.timestamp)
        committedTargetEtaMs = te; committedTargetAnchorMs = ta
        val (fe, fa) = commitEta(committedFullEtaMs, committedFullAnchorMs, est.minutesToFull, data.timestamp)
        committedFullEtaMs = fe; committedFullAnchorMs = fa
        // Periodic prediction snapshot for the "history of predictions"
        // overlay on the Battery chart. We log even when both ETAs are null
        // (pre-warmup) so the gap is visible; the chart draws nothing for
        // those entries.
        if (connected && est.warmedUp &&
            data.timestamp - lastPredictionLogMs >= PREDICTION_LOG_INTERVAL_MS) {
            lastPredictionLogMs = data.timestamp
            predictionHistory.addLast(
                PredictionSample(
                    sampleTimeMs = data.timestamp,
                    targetEtaMs = committedTargetEtaMs,
                    fullEtaMs = committedFullEtaMs,
                )
            )
            while (predictionHistory.size > PREDICTION_LOG_MAX_ENTRIES) {
                predictionHistory.removeFirst()
            }
        }
        _chargingSnapshot.value = ChargingSnapshot(
            estimate = est,
            chargeHistory = chargePctHist.toList(),
            voltageHistory = chargeVoltHist.toList(),
            tempHistory = chargeTempHist.toList(),
            targetEtaMs = committedTargetEtaMs,
            fullEtaMs = committedFullEtaMs,
            predictionHistory = predictionHistory.toList(),
            sessionEnergyWh = sessionEnergyWh,
            sessionEnergyInWh = sessionEnergyInWh,
            sessionEnergyOutWh = sessionEnergyOutWh,
            packMinHistory = packMinHist.map { it.toList() },
            packMaxHistory = packMaxHist.map { it.toList() },
            packAvgHistory = packAvgHist.map { it.toList() },
            packSeriesUnit = packHistUnit,
        )
    }

    /** Clears the per-pack session history and resets its unit. */
    private fun clearPackHist() {
        packMinHist.clear()
        packMaxHist.clear()
        packAvgHist.clear()
        packHistUnit = "%"
    }

    /**
     * Sample the per-pack cell spread on the charge-history cadence. A smart-BMS
     * wheel (packs with non-empty cell voltages) records each pack's whole-pack
     * voltage in "V" (min / max = the lowest / highest cell scaled by the cell
     * count, avg = the summed pack voltage), so a band's thickness is the pack's
     * internal cell spread expressed in whole-pack volts; otherwise it falls back
     * to the per-pack SoC (battery1 / battery2) in "%", where min == max == avg (a
     * zero-thickness band). All packs in one graph share a single unit. The buffers reset when
     * the unit or the pack count changes so a "%" -> "V" switch (BMS data lands a
     * few seconds after connect) never mixes units on one axis. A single-pack
     * source is simply not recorded, so the Packs graph needs at least two packs.
     */
    private fun recordPackHist(data: WheelData) {
        val bmsPacks = _bmsState.value.packs.filter { it.knownCells.isNotEmpty() }
        val mins: List<Float>
        val maxs: List<Float>
        val avgs: List<Float>
        val unit: String
        if (bmsPacks.isNotEmpty()) {
            // Whole-pack voltage, not per-cell: the graph's left axis then reads
            // the real pack voltage (~127 V) and each band = the pack's cell
            // spread expressed in whole-pack volts. min / max = the lowest /
            // highest cell scaled by the cell count; avg = the actual pack
            // voltage (the sum of its known cells).
            mins = bmsPacks.map { (it.minCellV ?: 0f) * it.knownCells.size }
            maxs = bmsPacks.map { (it.maxCellV ?: 0f) * it.knownCells.size }
            avgs = bmsPacks.map { pack -> pack.knownCells.sumOf { it.second.toDouble() }.toFloat() }
            unit = "V"
        } else {
            val soc = buildList {
                if (data.battery1Percent > 0f) add(data.battery1Percent)
                if (data.battery2Percent > 0f) add(data.battery2Percent)
            }
            mins = soc; maxs = soc; avgs = soc
            unit = "%"
        }
        if (avgs.size < 2) {
            clearPackHist()
            return
        }
        if (unit != packHistUnit || packAvgHist.size != avgs.size) {
            packMinHist.clear(); packMaxHist.clear(); packAvgHist.clear()
            packHistUnit = unit
            repeat(avgs.size) {
                packMinHist.add(ArrayDeque()); packMaxHist.add(ArrayDeque()); packAvgHist.add(ArrayDeque())
            }
        }
        val t = data.timestamp
        for (i in avgs.indices) {
            pushHist(packMinHist[i], t, mins[i])
            pushHist(packMaxHist[i], t, maxs[i])
            pushHist(packAvgHist[i], t, avgs[i])
        }
    }

    /**
     * Commit to a finish time and count it down in real time, re-anchoring toward
     * the fresh estimate every ~20 s. The step is PROPORTIONAL to the relative
     * error: a badly-wrong prediction (e.g. 12 min showing when it's really 3)
     * corrects within a minute or two, while small frame-to-frame jitter is heavily
     * damped — so it tracks reality without swinging on noise. Returns (etaMs, anchorMs).
     */
    private fun commitEta(prevEta: Long?, prevAnchorMs: Long, minutes: Float?, nowMs: Long): Pair<Long?, Long> {
        if (minutes == null || minutes < 0f) return null to 0L
        val rawRem = (minutes * 60_000f).toLong()
        if (prevEta == null) return (nowMs + ceilToMinuteMs(rawRem)) to nowMs
        if (nowMs - prevAnchorMs < 20_000L) return prevEta to prevAnchorMs
        val remPrev = (prevEta - nowMs).coerceAtLeast(0L)
        val gap = rawRem - remPrev
        val relGap = kotlin.math.abs(gap).toFloat() / remPrev.coerceAtLeast(60_000L)
        val blend = relGap.coerceIn(0.15f, 0.8f)
        val newRem = remPrev + (gap * blend).toLong()
        return (nowMs + ceilToMinuteMs(newRem)) to nowMs
    }

    private fun ceilToMinuteMs(ms: Long): Long =
        kotlin.math.ceil(ms / 60_000.0).toLong().coerceAtLeast(1L) * 60_000L

    private fun pushHist(dq: ArrayDeque<MetricSample>, t: Long, v: Float) {
        dq.addLast(MetricSample(t, v))
        while (dq.size > 1000) dq.removeFirst()
    }

    private fun batteryPercentOf(d: WheelData): Float = when {
        d.battery1Percent > 0f && d.battery2Percent > 0f -> (d.battery1Percent + d.battery2Percent) / 2f
        d.battery1Percent > 0f -> d.battery1Percent
        else -> d.batteryPercent.toFloat()
    }

    private fun deriveChargeStatus(data: WheelData): ChargeStatus {
        if (bleManager.connectionState.value != ConnectionState.CONNECTED) {
            chargeInferred = false
            chargeNegSamples = 0
            chargePosSamples = 0
            return ChargeStatus.Disconnected
        }
        when {
            data.current < -0.3f -> {
                chargeNegSamples++
                chargePosSamples = 0
                if (chargeNegSamples >= 5) chargeInferred = true
            }
            data.current > -0.05f -> {
                chargePosSamples++
                chargeNegSamples = 0
                if (chargePosSamples >= 10) chargeInferred = false
            }
            // trickle band (-0.3..-0.05 A): hold the latched state
        }
        val moving = data.speed > 1f
        val charging = !moving && (data.charging || chargeInferred)
        return when {
            charging && data.batteryPercent >= 100 -> ChargeStatus.Full
            charging -> ChargeStatus.Charging
            else -> ChargeStatus.Idle
        }
    }

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

    fun connect(address: String, name: String? = null, isAuto: Boolean = false) {
        if (lastConnectedAddress != null && lastConnectedAddress != address) {
            // Different wheel, clear history
            battHist.clear(); tempHist.clear(); voltHist.clear()
            ampsHist.clear(); loadHist.clear(); speedHist.clear()
            extrasHist.values.forEach { it.clear() }
            _fullHistory.value = FullMetricHistory()
        }
        lastConnectedAddress = address
        bleManager.connect(address, name, isAuto)
    }

    fun disconnect() {
        pollingActive = false
        bleManager.disconnect()
    }

    // Last wheel-reported limits we synced into AppSettings, in km/h. Cached
    // here so we don't issue a DataStore write on every telemetry frame; the
    // wheel firmware re-emits the same values continuously.
    @Volatile private var lastSyncedWheelMaxKmh: Float = -1f
    @Volatile private var lastSyncedWheelAlarmKmh: Float = -1f

    private fun syncWheelEnforcedLimits(maxKmh: Float, alarmKmh: Float) {
        // -1f from the parser means "this adapter doesn't surface the limit"
        // (every family except Veteran today). Don't touch settings in that
        // case so wheels we can write to keep their app-side value.
        val haveMax = maxKmh > 0f
        val haveAlarm = alarmKmh > 0f
        if (!haveMax && !haveAlarm) return
        val maxChanged = haveMax && kotlin.math.abs(maxKmh - lastSyncedWheelMaxKmh) > 0.05f
        val alarmChanged = haveAlarm && kotlin.math.abs(alarmKmh - lastSyncedWheelAlarmKmh) > 0.05f
        if (!maxChanged && !alarmChanged) return
        if (haveMax) lastSyncedWheelMaxKmh = maxKmh
        if (haveAlarm) lastSyncedWheelAlarmKmh = alarmKmh
        scope.launch {
            val current = settingsRepository.get()
            val newMax = if (haveMax) maxKmh else current.tiltbackSpeedKmh
            val newAlarm = if (haveAlarm) alarmKmh else current.alarmSpeedKmh
            if (newMax != current.tiltbackSpeedKmh || newAlarm != current.alarmSpeedKmh) {
                settingsRepository.update(
                    current.copy(
                        tiltbackSpeedKmh = newMax,
                        alarmSpeedKmh = newAlarm.coerceAtMost(newMax)
                    )
                )
            }
        }
    }

    // --- Control commands ---

    /**
     * Hard floor (chokepoint B) for every direct wheel-write entry path.
     * The HUD's fixed ToggleLight/Horn and Garmin's horn/light/safety call
     * these methods directly (bypassing FlicManager.executeAction), and the
     * dashboard buttons land here too. Gate all of them in one place so a
     * BLE write — or an optimistic state flip like toggleLight's lightOn —
     * never happens with no wheel connected.
     */
    private fun wheelConnected() = bleManager.connectionState.value == ConnectionState.CONNECTED

    fun sendHorn() {
        if (!wheelConnected()) return  // no wheel -> ignore (HUD/Garmin/Flic/UI all land here)
        val cmd = wheelAdapter.horn()
        if (cmd != null) {
            bleManager.writeCommand(cmd)
            // Veteran (Lynx-class) needs an LdAp companion frame right after
            // the LkAp horn or the wheel stays silent; queued as a second
            // write so it lands in order. Null for every other family
            // (single-frame horn).
            wheelAdapter.hornFollowup()?.let { bleManager.writeCommand(it) }
        } else {
            // Wheel family has no horn opcode (Ninebot Z protocol 19 doesn't
            // define one, Ninebot Legacy is read-only). Fall back to a phone
            // beep + vibration so the rider gets some feedback instead of
            // tapping into the void. The audit flagged this as DANGEROUS:
            // the button rendered enabled but did absolutely nothing.
            playPhoneHornFallback()
        }
    }

    /** Phone-side fallback for wheel families that lack a horn opcode.
     *  Brief tone + vibration -- not a real horn but at least the rider
     *  knows the tap registered. Best-effort; silently catches every
     *  audio / vibrator error so a locked-down audio policy never throws
     *  out of a horn tap. */
    private fun playPhoneHornFallback() {
        try {
            val tone = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_MUSIC, 100
            )
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 400)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { tone.release() } catch (_: Throwable) {}
            }, 600L)
        } catch (_: Throwable) {}
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (vib != null && android.os.Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        300L, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(300L)
            }
        } catch (_: Throwable) {}
    }

    /**
     * The connected wheel's adapter family id ("veteran", "kingsong", ...), or
     * null when disconnected. Gates family-scoped custom BLE commands so raw
     * user bytes never reach a wheel they were not authored for.
     */
    val connectedFamilyId: String?
        get() = if (bleManager.connectionState.value == ConnectionState.CONNECTED) {
            wheelAdapter.familyId
        } else null

    /** Write a custom BLE command's frames verbatim — one BLE write each, in order. */
    fun sendCustomBle(frames: List<ByteArray>) {
        if (!wheelConnected()) return  // no wheel -> ignore (HUD/Garmin/Flic/UI all land here)
        frames.forEach { if (it.isNotEmpty()) bleManager.writeCommand(it) }
    }

    /**
     * Send the family-specific "reset trip meter" command. Returns true when
     * the active adapter has a documented command (the wheel takes a frame or
     * two to zero offset 8..11 in its realtime stream); false when the
     * adapter doesn't support a trip reset, so the caller can surface
     * "not supported on this wheel" feedback.
     */
    fun resetTripMeter(): Boolean {
        if (!wheelConnected()) return false  // no wheel -> ignore (HUD/Garmin/Flic/UI all land here)
        val cmd = wheelAdapter.resetTripMeter() ?: return false
        bleManager.writeCommand(cmd)
        return true
    }

    fun toggleLight() {
        if (!wheelConnected()) return  // no wheel -> ignore (HUD/Garmin/Flic/UI all land here)
        if (_lightBusy.value) return  // cooldown active, ignore the spam tap
        val current = _wheelData.value.lightOn
        val next = !current
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
            "toggleLight: lightOn was=$current, sending $next"
        )
        wheelAdapter.setLight(next)?.let { bleManager.writeCommand(it) }
        // Veteran high beam needs an LdAp companion frame right after the LkAp
        // frame; queued as a second write so it lands in order. Null for the
        // ASCII low beam and every other family (single-frame headlight).
        wheelAdapter.setLightFollowup(next)?.let { bleManager.writeCommand(it) }
        _wheelData.value = _wheelData.value.copy(lightOn = next)
        startCooldown(_lightBusy, LIGHT_COOLDOWN_MS) { lightCooldownUntilMs = it }
    }

    fun toggleLock() {
        if (!wheelConnected()) return  // no wheel -> ignore (HUD/Garmin/Flic/UI all land here)
        if (_lockBusy.value) return  // cooldown active, ignore the spam tap
        // Capability gate. Without this the optimistic `_locked.value = !..`
        // below flips the lock icon for a moment even on wheels whose
        // adapter returns null from setLock(); the next settings poll then
        // snaps it back and the rider sees a misleading "it worked, then
        // un-worked" UI. The dashboard / Flic / wear paths all land here so
        // the early-return covers every entry.
        if (!wheelAdapter.capabilities.hasLock) {
            Log.d(TAG, "toggleLock: ${wheelAdapter.familyId} doesn't expose a BLE lock command")
            return
        }
        val targetState = !_locked.value
        // Hard block the lock direction when the wheel is moving, any entry
        // path (Flic, watch, volume keys, dashboard) lands here. Unlock is
        // always allowed; if the wheel is already locked, speed is 0 anyway.
        if (targetState && kotlin.math.abs(_wheelData.value.speed) >= lockMaxSpeedKmh &&
            !cheatState.lockAtAnySpeed.value) {
            Log.d(TAG, "lock blocked: speed=${_wheelData.value.speed} >= $lockMaxSpeedKmh")
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
                Log.e(TAG, "Lock command failed: auth unsuccessful, awaiting wheel telemetry resync")
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
        // Lynx-class Veteran returns a second 5-byte tail because its 25-byte
        // lock frame is split across two writes; pulled IMMEDIATELY after
        // setLock so the cached full-frame in the adapter is still valid.
        // Null on every other family (single-write lock).
        val lockTail = wheelAdapter.setLockFollowup(locked)

        if (!wheelAdapter.capabilities.needsAuthForLock) {
            bleManager.writeCommand(lockPacket)
            lockTail?.let { bleManager.writeCommand(it) }
            return@withLock true
        }

        val authReqPacket = wheelAdapter.requestAuthKey()
        val verifyBuilder = wheelAdapter::verifyAuth
        if (authReqPacket == null) {
            // Capabilities say auth is required but adapter exposes no key request, bug.
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
        lockTail?.let { bleManager.writeCommand(it) }
        true
    }

    fun setDRL(on: Boolean) {
        wheelAdapter.setDRL(on)?.let { bleManager.writeCommand(it) }
    }

    fun setSpeed(tiltbackKmh: Float, beepKmh: Float) {
        // Remember what we asked for so the settings handler can tell the
        // difference between "wheel echoed our value" and "wheel clamped it".
        // Without this, a firmware-capped V14 (e.g. 80 km/h max while we send
        // 85) would echo back 80, and if 80 happened to equal stored Legal
        // tiltback, the readback-based detector would lock the toggle on.
        lastSentTiltbackKmh = tiltbackKmh
        // Timestamp the write so the auto-sync handler doesn't mistake the
        // wheel's echo of our own write for an external change.
        lastSetSpeedAtMs = System.currentTimeMillis()
        wheelAdapter.setMaxSpeed(tiltbackKmh, beepKmh)?.let { bleManager.writeCommand(it) }
        // P6 needs two flash-commit packets after the live drag write, one
        // for the tiltback, one for the alarm threshold. V14 returns null
        // here since both values land in the single setMaxSpeed packet.
        wheelAdapter.setMaxSpeedCommit(tiltbackKmh)?.let { bleManager.writeCommand(it) }
        wheelAdapter.setAlarmSpeedCommit(beepKmh)?.let { bleManager.writeCommand(it) }
    }

    suspend fun toggleSafetySpeed() {
        if (!wheelConnected()) return  // no wheel -> ignore (HUD/Garmin/Flic/UI all land here)
        val wantActive = !_safetySpeedActive.value
        val settings = settingsRepository.get()

        // Flip the flag to user intent immediately. The settings handler
        // already trusts intent (lastSentTiltbackKmh) over a clamped readback,
        // but the optimistic flip keeps the UI responsive while the wheel's
        // confirmation is in flight, same pattern as toggleLock.
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
        if (!wheelConnected()) return  // no wheel -> ignore (HUD/Garmin/Flic/UI all land here)
        if (!_safetySpeedActive.value) {
            scope.launch { toggleSafetySpeed() }
        }
    }

    fun disableSafetySpeed() {
        if (!wheelConnected()) return  // no wheel -> ignore (HUD/Garmin/Flic/UI all land here)
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
     * auth steps but stops after the verify ACK, there is no lock packet
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
            Log.w(TAG, "Connect-auth: key timeout, control commands may not work until lock toggle")
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
        else Log.w(TAG, "Connect-auth: verify failed, control commands may not work")
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
     * In A/B/E we never overwrite stored values, the other pair is preserved across
     * reconnects even though the wheel forgets it. Case E is the conservative split that
     * stops a firmware-capped readback (e.g. V14 80 km/h cap when stored normal is 85)
     * from silently downgrading the user's stored preference.
     */
    private suspend fun reconcileSpeedLimits(ws: WheelSettings, appSettings: AppSettings) {
        val wTilt = ws.maxSpeedKmh
        val wAlarm = ws.alarmSpeedKmh
        // The wheel reports 0 when tiltback is either unsupported by the
        // adapter (some Begode/Veteran families) or explicitly disabled on
        // the wheel itself. Treat both as "don't know", overwriting the
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
                // User lowered their legal via another app, adopt as new legal
                isLegalOn = true
                appSettings.copy(safetyTiltbackKmh = wTilt, safetyAlarmKmh = wAlarm)
            }
            wTilt > normalTilt + tolerance -> {
                // Wheel above stored normal, user raised normal externally; adopt
                isLegalOn = false
                appSettings.copy(tiltbackSpeedKmh = wTilt, alarmSpeedKmh = wAlarm)
            }
            else -> {
                // wTilt is between legal and normal. Adopt it as the new
                // normal, covers the P6/V12 case where the user lowered
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
                // actually supports. Published per-model speeds for the
                // V14 family; P6 gets 130 km/h since it isn't in the
                // canonical reference table.
                val model = result.model as? com.eried.eucplanet.ble.InMotionV2Model
                _maxSpeedCap.value = model?.maxSpeedKmh?.toFloat() ?: DEFAULT_MAX_SPEED_KMH
                // Veteran-family brand is model-dependent (NOSFET vs
                // LeaperKim). The initial brand at connect time was set
                // pre-detection; pulse the connection manager so the
                // dashboard / Settings / eucstats meta pick up the
                // correct manufacturer.
                bleManager.refreshBrand()
            }
            is DecodeResult.Firmware -> {
                _firmwareVersion.value = result.display
                Log.i(TAG, "Firmware: Main=${result.mainBoard} Drv=${result.driverBoard} BLE=${result.ble}")
            }
            is DecodeResult.Serial -> {
                _wheelSerial.value = result.serial
                Log.i(TAG, "Wheel serial: ${result.serial}")
            }
            is DecodeResult.Bms -> {
                _bmsState.value = mergeBmsSlice(_bmsState.value, result)
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
                // sees the calibrated value, there is no second source of
                // truth elsewhere in the app.
                val cal = speedCalibrationMultiplier
                // During the LIGHT_COOLDOWN_MS window after a manual tap, a
                // Telemetry frame the adapter emitted *before* the tap can
                // still land here and overwrite the optimistic lightOn we
                // just set in toggleLight(). That brief flip back to the old
                // value triggers a stray TTS "lights on/off" transition in
                // WheelService.checkLightTransition (race seen ~3-4 times
                // per 20 taps in tester reports). Preserve the optimistic
                // value during the cooldown for every family, same defensive
                // pattern P6 already uses unconditionally because its parser
                // can't recover lightOn from telemetry at all.
                val lightOn = if (isP6 || _lightBusy.value) previous.lightOn
                              else result.data.lightOn
                _wheelData.value = result.data.copy(
                    speed = kotlin.math.abs(result.data.speed * cal),
                    totalDistance = totalKm,
                    lightOn = lightOn
                )
                _chargeStatus.value = deriveChargeStatus(_wheelData.value)
                // Never let the charging-session bookkeeping throw out of the
                // telemetry path — telemetry/dashboard must keep flowing regardless.
                runCatching { updateChargingSession(_wheelData.value, _chargeStatus.value) }
                // Mirror wheel-reported tilt-back / alarm thresholds into the
                // app's settings store on adapters that surface them (Veteran),
                // so the Settings UI shows what the wheel firmware is actually
                // enforcing (set via the vendor app) instead of our local
                // default. Cached lastSyncedWheel*Kmh prevents a write on
                // every frame; we only write when the wheel's value changes.
                syncWheelEnforcedLimits(
                    result.data.wheelMaxSpeedKmh,
                    result.data.wheelAlarmSpeedKmh
                )
                // Sample history at the rider-configured graph interval
                val now = System.currentTimeMillis()
                if (now - lastHistorySampleMs >= graphSampleIntervalMs) {
                    lastHistorySampleMs = now
                    val d = _wheelData.value
                    battHist.add(MetricSample(now, d.batteryPercent.toFloat()))
                    tempHist.add(MetricSample(now, d.maxTemperature))
                    voltHist.add(MetricSample(now, d.voltage))
                    ampsHist.add(MetricSample(now, d.current))
                    loadHist.add(MetricSample(now, d.pwm.absoluteValue))
                    speedHist.add(MetricSample(now, d.speed.absoluteValue))
                    // Sample every extra metric on the same tick. Each
                    // EXTRA_HISTORY_METRICS entry feeds one buffer in
                    // extrasHist; the extractor is read against the same
                    // WheelData snapshot the legacy 6 use, so all rolling
                    // history is time-aligned.
                    for ((key, extractor) in EXTRA_HISTORY_METRICS) {
                        extrasHist[key]?.add(MetricSample(now, extractor(d)))
                    }
                    // Drop anything older than the 5-min window from every
                    // buffer in one pass. List.removeAll touches each list
                    // once so this stays linear in buffer size.
                    val cutoff = now - historyWindowMs
                    listOf(battHist, tempHist, voltHist, ampsHist, loadHist, speedHist)
                        .forEach { it.removeAll { s -> s.timestampMs < cutoff } }
                    extrasHist.values.forEach { it.removeAll { s -> s.timestampMs < cutoff } }
                    _fullHistory.value = FullMetricHistory(
                        battery = battHist.toList(),
                        temperature = tempHist.toList(),
                        voltage = voltHist.toList(),
                        current = ampsHist.toList(),
                        load = loadHist.toList(),
                        speed = speedHist.toList(),
                        extras = extrasHist.mapValues { (_, list) -> list.toList() }
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
                    // freeze the toggle ON forever, using lastSentTiltbackKmh
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
                        // downward wheel-side changes are honored, earlier
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
                            // Surface a snackbar so the silent adoption is
                            // visible no matter which screen the user is on.
                            appNotifier.post(
                                context.getString(
                                    R.string.toast_external_speed_change,
                                    newTilt.toInt(), newAlarm.toInt()
                                )
                            )
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

/**
 * Stitch a fresh BMS slice into the per-pack rolling state. Each Veteran
 * smart-BMS sub-frame carries only a 12-15 cell window plus an optional
 * temp / current header; the wheel cycles through pages 1+2+3 (cells 0..41
 * for pack 0) and 5+6+7 (pack 1) at ~5 Hz so the full per-cell view
 * assembles over ~1.5 s. Fields the slice didn't carry are preserved from
 * the previous state for that pack.
 */
internal fun mergeBmsSlice(
    prev: com.eried.eucplanet.data.model.BmsState,
    slice: com.eried.eucplanet.ble.DecodeResult.Bms,
): com.eried.eucplanet.data.model.BmsState {
    val existing = prev.packs.firstOrNull { it.packIndex == slice.packIndex }
        ?: com.eried.eucplanet.data.model.BmsState.PackState(packIndex = slice.packIndex)
    // Grow the per-pack cell-voltage array as new ranges arrive. Index by
    // absolute cell number so a slice covering cells 30..41 lands in the
    // right slot without disturbing the 0..29 entries we already have.
    val cells = existing.cellVoltages.toMutableList()
    val sliceCells = slice.cellVoltages
    val rangeStart = slice.cellRangeStart
    if (sliceCells != null && rangeStart != null) {
        val needed = rangeStart + sliceCells.size
        while (cells.size < needed) cells.add(0f)
        for (i in sliceCells.indices) {
            val v = sliceCells[i]
            if (v > 0f) cells[rangeStart + i] = v
        }
    }
    // Slice 3/7 also carries 6 BMS temperatures. Slice 0/4 carries pack
    // currents (two values: one per pack). For pack 0 we use packCurrent1A;
    // for pack 1 we use packCurrent2A.
    val newCurrent = when (slice.packIndex) {
        0 -> slice.packCurrent1A ?: existing.currentA
        1 -> slice.packCurrent2A ?: existing.currentA
        else -> existing.currentA
    }
    val newTemps = slice.bmsTempsC ?: existing.temperaturesC
    val updated = existing.copy(
        cellVoltages = cells,
        temperaturesC = newTemps,
        currentA = newCurrent,
    )
    val others = prev.packs.filter { it.packIndex != slice.packIndex }
    return com.eried.eucplanet.data.model.BmsState(
        packs = (others + updated).sortedBy { it.packIndex },
        updatedAt = System.currentTimeMillis(),
    )
}
