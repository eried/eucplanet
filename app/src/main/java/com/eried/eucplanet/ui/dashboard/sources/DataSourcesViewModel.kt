package com.eried.eucplanet.ui.dashboard.sources

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.repository.ExternalGpsRepository
import com.eried.eucplanet.data.repository.PhoneSensorRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Aggregates the three live data sources (Phone IMU + GPS, Wheel BLE, optional
 * RaceBox) into a single map keyed by [DataSource]. The sheet recomposes off
 * [snapshots] for the per-source readouts and uses [phoneImuTrail] / [raceboxTrail]
 * for the fading-dot G-force visualisation.
 *
 * Lifecycle: [onSheetOpened] starts the phone IMU listener, [onSheetClosed]
 * stops it. The other two sources are already running app-wide (wheel BLE
 * loop and RaceBox GATT connection) so they need no explicit start/stop here.
 */
@HiltViewModel
class DataSourcesViewModel @Inject constructor(
    private val phoneSensors: PhoneSensorRepository,
    private val tripRepository: TripRepository,
    private val wheelRepository: WheelRepository,
    private val externalGpsRepository: ExternalGpsRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        // Trail length picked so a typical 50 Hz IMU sample stream gives ~1.5 s
        // of history before old samples fade off the bottom of the alpha curve.
        // Visually this maps to "a smooth tail behind the dot" rather than a
        // full path replay.
        // Trail length on the X/Z g-force crosshair. RaceBox streams at ~25 Hz
        // and the phone IMU at ~50 Hz; 120 samples spans ~5 s of RaceBox or
        // ~2.5 s of phone, enough to follow a sustained corner without the
        // trail smearing into a solid disk.
        private const val TRAIL_MAX = 120
        /** Rolling window for Compare-tab line charts. 5 minutes covers a
         *  full ride episode; the chart filters to the visible time range so
         *  redraw stays fast even at this size. */
        private const val SERIES_WINDOW_MS = 300_000L
    }

    // Phone IMU trail, rolling buffer of (xG, zG) offsets used by the
    // crosshair. Kept as Offset rather than the full PhoneImuSample to keep
    // the recomposition payload small.
    private val _phoneImuTrail = MutableStateFlow<List<Offset>>(emptyList())
    val phoneImuTrail: StateFlow<List<Offset>> = _phoneImuTrail.asStateFlow()

    private val _raceboxTrail = MutableStateFlow<List<Offset>>(emptyList())
    val raceboxTrail: StateFlow<List<Offset>> = _raceboxTrail.asStateFlow()

    /**
     * Per-source, per-metric rolling time-series used by the Compare tab to
     * draw line graphs. Each entry is (timestampMs, value). Old entries beyond
     * [SERIES_WINDOW_MS] are dropped on every sample tick.
     *
     * Kept flat (one MutableStateFlow per series) rather than nested in a map
     * so Compose subscribers only recompose when the specific series they're
     * watching changes.
     */
    data class TimedSeries(val points: List<Pair<Long, Float>> = emptyList())

    private val _speedSeries = DataSource.values().associateWith {
        MutableStateFlow(TimedSeries())
    }
    val speedSeries: Map<DataSource, StateFlow<TimedSeries>> =
        _speedSeries.mapValues { it.value.asStateFlow() }

    private val _gMagnitudeSeries = DataSource.values().associateWith {
        MutableStateFlow(TimedSeries())
    }
    val gMagnitudeSeries: Map<DataSource, StateFlow<TimedSeries>> =
        _gMagnitudeSeries.mapValues { it.value.asStateFlow() }

    private val _headingSeries = DataSource.values().associateWith {
        MutableStateFlow(TimedSeries())
    }
    val headingSeries: Map<DataSource, StateFlow<TimedSeries>> =
        _headingSeries.mapValues { it.value.asStateFlow() }

    private val _vertSpeedSeries = DataSource.values().associateWith {
        MutableStateFlow(TimedSeries())
    }
    val vertSpeedSeries: Map<DataSource, StateFlow<TimedSeries>> =
        _vertSpeedSeries.mapValues { it.value.asStateFlow() }

    init {
        // Wire the sensor stream into the trail buffer. New samples land at
        // the end of the list; old ones are dropped past TRAIL_MAX.
        viewModelScope.launch {
            phoneSensors.imu.collect { sample ->
                if (sample == null) return@collect
                _phoneImuTrail.update { it.takeLast(TRAIL_MAX - 1) + Offset(sample.xG, sample.zG) }
            }
        }
        viewModelScope.launch {
            externalGpsRepository.currentSample.collect { sample ->
                if (sample == null) return@collect
                val ax = sample.accelXG ?: return@collect
                val az = sample.accelZG ?: return@collect
                _raceboxTrail.update { it.takeLast(TRAIL_MAX - 1) + Offset(ax, az) }
            }
        }
        // The time-series append launch is in a second init block below,
        // AFTER snapshots is declared, referencing it here would NPE
        // because init blocks run interleaved with property initialisers
        // in declaration order.
    }

    private fun appendSeries(flow: MutableStateFlow<TimedSeries>?, now: Long, v: Float?) {
        if (flow == null || v == null) return
        flow.update { current ->
            val cutoff = now - SERIES_WINDOW_MS
            val kept = current.points.dropWhile { it.first < cutoff }
            TimedSeries(kept + (now to v))
        }
    }

    /**
     * Map of each known source to its current snapshot. The sheet renders
     * "--" when a field is null (source doesn't know that metric, or device
     * hasn't reported in yet).
     */
    val snapshots: StateFlow<Map<DataSource, SourceSnapshot>> = combine(
        phoneSensors.imu,
        tripRepository.currentLocation,
        wheelRepository.wheelData,
        wheelRepository.connectionState,
        externalGpsRepository.currentSample,
        externalGpsRepository.connectionState
    ) { values ->
        val imu = values[0] as com.eried.eucplanet.data.repository.PhoneImuSample?
        val loc = values[1] as android.location.Location?
        val wheel = values[2] as com.eried.eucplanet.data.model.WheelData
        val wheelState = values[3] as ConnectionState
        val ext = values[4] as com.eried.eucplanet.data.model.ExternalGpsSample?
        val extState = values[5] as ConnectionState

        val phoneSpeedKmh: Float? = loc?.takeIf { it.hasSpeed() }?.let { it.speed * 3.6f }
        val phoneLive = (imu != null) || (phoneSpeedKmh != null)

        // Last-update timestamps. IMU and Location both expose a sample time;
        // Wheel telemetry doesn't, so we stamp now when wheelData ticks
        // (frequent enough that the "Xs ago" line stays accurate). External
        // GPS carries its own timestamp inside the sample.
        val phoneStamp = imu?.timestamp ?: loc?.takeIf { it.time > 0 }?.time
        val wheelStamp = if (wheelState == ConnectionState.CONNECTED)
            System.currentTimeMillis() else null
        val extStamp = ext?.timestamp

        mapOf(
            DataSource.PHONE to SourceSnapshot(
                speedKmh = phoneSpeedKmh,
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                isLive = phoneLive,
                accelXG = imu?.xG,
                accelYG = imu?.yG,
                accelZG = imu?.zG,
                headingDeg = loc?.takeIf { it.hasBearing() && it.speed > 0.5f }?.bearing,
                verticalSpeedMps = null,
                numSatellites = null,
                accuracyMeters = loc?.accuracy,
                lastUpdateMs = phoneStamp
            ),
            DataSource.WHEEL to SourceSnapshot(
                speedKmh = wheel.speed.takeIf { wheelState == ConnectionState.CONNECTED },
                isLive = wheelState == ConnectionState.CONNECTED,
                lastUpdateMs = wheelStamp
            ),
            DataSource.RACEBOX to SourceSnapshot(
                speedKmh = ext?.speedKmh,
                latitude = ext?.latitude,
                longitude = ext?.longitude,
                isLive = extState == ConnectionState.CONNECTED && ext != null,
                accelXG = ext?.accelXG,
                accelYG = ext?.accelYG,
                accelZG = ext?.accelZG,
                headingDeg = ext?.headingDeg,
                verticalSpeedMps = ext?.verticalSpeedMps,
                numSatellites = ext?.numSatellites,
                accuracyMeters = ext?.accuracyMeters,
                lastUpdateMs = extStamp
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Currently saved wheel speed calibration in % (range -15..+15). Exposed
     *  so the Compare tab can both decalibrate the wheel speed it displays
     *  (so the rider sees the true raw-vs-ground-truth error) and preview
     *  what a fresh "apply from comparison" action would write.
     *
     *  Declared BEFORE the snapshot-ingest init block below, that block
     *  reads `calibrationOffsetPct.value` synchronously on every emission,
     *  and StateFlow.collect emits the current value immediately when the
     *  coroutine starts, so the property must already be initialised by
     *  the time the launched coroutine runs. Crash report v0.6.5-rc2 had
     *  this in the wrong order (NPE on opening the Live data sheet). */
    val calibrationOffsetPct: StateFlow<Float> = settingsRepository.settings
        .map { it.speedCalibrationOffsetPct }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    init {
        // Second init block, runs AFTER `snapshots` is initialised above.
        // Each emit appends to the per-source per-metric rolling buffers
        // used by the Compare-tab line charts. Null fields are skipped
        // inside [appendSeries] so we don't taint the series with NaN.
        //
        // Wheel speed is DECALIBRATED at ingest: the wheel telemetry stack
        // multiplies every raw reading by (1 + curPct/100) before it reaches
        // us, so we divide it back out and store the raw value in the
        // _speedSeries[WHEEL] buffer. That makes the buffer curPct-invariant
        //, applying a new calibration doesn't shift the historical samples,
        // which means the Compare-tab "Calibrate wheel" math is idempotent
        // (re-applying a perfect calibration proposes the same %, not a
        // larger one). The Compare tab is responsible for multiplying the
        // raw samples back up when rendering the chart line, so the
        // RIDER sees the calibrated wheel speed there.
        viewModelScope.launch {
            snapshots.collect { map ->
                val now = System.currentTimeMillis()
                val curMul = 1f + calibrationOffsetPct.value / 100f
                map.forEach { (src, snap) ->
                    val speedForBuffer = if (src == DataSource.WHEEL && snap.speedKmh != null && curMul > 0f)
                        snap.speedKmh / curMul
                    else snap.speedKmh
                    appendSeries(_speedSeries[src], now, speedForBuffer)
                    appendSeries(_gMagnitudeSeries[src], now, snap.horizGMagnitude)
                    appendSeries(_headingSeries[src], now, snap.headingDeg)
                    appendSeries(_vertSpeedSeries[src], now, snap.verticalSpeedMps)
                }
            }
        }
    }

    /** Whether the wheel is connected right now. Gates the "apply" button so
     *  the user can't write calibration with no wheel to write it to. */
    val wheelConnected: StateFlow<Boolean> = wheelRepository.connectionState
        .map { it == ConnectionState.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Compute the calibration % that would align the wheel's raw sensor
     * reading with the reference source's average over the rolling window.
     *
     * The caller passes the average of the RAW wheel speed (i.e. the
     * calibrated value divided by 1 + curPct/100) so this function deals in
     * absolute multipliers, not deltas-on-top-of-current. That keeps the
     * Compare tab usable as a verification tool: with calibration applied,
     * the wheel/ref delta on the chart reflects the actual sensor offset
     * rather than collapsing to zero.
     *
     * Math: raw * (1 + newPct/100) = ref => newPct = (ref/raw - 1) * 100.
     *
     * The only gate is that the wheel itself must be reporting motion (we
     * can't divide by zero). The reference may be near zero (e.g. a phone
     * GPS that hasn't picked up motion yet); the rider still gets to see
     * the dialog and decide. The ±15 clamp keeps extreme outliers sane.
     */
    fun computeCalibrationPct(avgWheelRawKmh: Float, avgRefKmh: Float): Float? {
        if (avgWheelRawKmh <= 0f) return null
        val newPct = (avgRefKmh / avgWheelRawKmh - 1f) * 100f
        return (kotlin.math.round(newPct * 10f) / 10f).coerceIn(-15f, 15f)
    }

    /** Persist a new wheel speed calibration. Same clamp/rounding rules as the
     *  Wheel parameters slider.
     *
     *  Note: we don't need to clear the rolling buffer here. The buffer
     *  stores RAW wheel speed (decalibrated at ingest in the init block),
     *  so it's invariant to curPct changes. The chart line and proposal
     *  both update immediately because they read the current curPct from
     *  [calibrationOffsetPct] at render time. */
    fun applyCalibrationPct(newPct: Float) {
        viewModelScope.launch {
            val rounded = (kotlin.math.round(newPct * 10f) / 10f).coerceIn(-15f, 15f)
            val current = settingsRepository.get()
            settingsRepository.update(current.copy(speedCalibrationOffsetPct = rounded))
        }
    }

    /** Drop the rolling speed buffer for every source so the Compare tab's
     *  average + computed calibration restart from "now". Exposed so the
     *  rider can tap Reset avg from the UI after a stretch of stop-and-go
     *  riding to refresh the comparison without waiting 5 minutes. */
    fun resetRollingAverages() {
        _speedSeries.values.forEach { it.value = TimedSeries() }
    }

    fun onSheetOpened() {
        phoneSensors.start()
        // Drop any stale trail from a previous opening so the visualisation
        // starts clean. The new IMU samples will fill it back in within a
        // handful of frames.
        _phoneImuTrail.value = emptyList()
        _raceboxTrail.value = emptyList()
    }

    fun onSheetClosed() {
        phoneSensors.stop()
    }

    // ---- Compare-tab selection state ----
    //
    // Kept on the ViewModel (not in Composable remember{}) so the rider's last
    // A/B pick survives dismissing and re-opening the sheet.
    //
    // Rule for auto-picking B on Compare entry, derived from two nullable
    // fields (no boolean "first time" flag):
    //   On entry, if [lastCompareA] != currentA  -> auto-pick B from A
    //                                              (prefer Wheel; or
    //                                              External when A is Wheel)
    //             if [lastCompareA] == currentA  -> restore [lastCompareB]
    //
    // [lastCompareA] is updated on every A change AND on Compare entry.
    // [lastCompareB] is updated whenever a B is chosen (manual or auto).
    // The two together let us detect "rider switched single-source A while
    // Compare was off" (= re-auto-pick) versus "rider toggled Compare off
    // and on without changing anything" (= preserve last B).

    private val _selectedSource = MutableStateFlow(DataSource.PHONE)
    val selectedSource: StateFlow<DataSource> = _selectedSource.asStateFlow()

    private val _compareWith = MutableStateFlow<DataSource?>(null)
    val compareWith: StateFlow<DataSource?> = _compareWith.asStateFlow()

    private var lastCompareA: DataSource? = null
    private var lastCompareB: DataSource? = null

    fun setSelectedSource(source: DataSource) {
        _selectedSource.value = source
        // Inside Compare, the rider's explicit A pick is the "last seen"
        // for next entry. Outside Compare we deliberately leave
        // [lastCompareA] alone so the diff vs the next entry's currentA
        // tells us to re-auto-pick B.
        if (_compareWith.value != null) {
            lastCompareA = source
        }
    }

    fun setCompareWith(source: DataSource) {
        _compareWith.value = source
        lastCompareB = source
    }

    fun toggleCompare() {
        if (_compareWith.value != null) {
            // Exiting Compare. lastCompareA/B are kept; they're already in
            // sync from the setters above.
            _compareWith.value = null
            return
        }
        // Entering Compare. Keep the rider's A as-is (their explicit pick)
        // and auto-pick B only if A changed since the last entry.
        val currentA = _selectedSource.value
        val keepLastB = lastCompareA == currentA && lastCompareB != null
        val pickedB = if (keepLastB) lastCompareB!! else autoPickBFor(currentA)
        _compareWith.value = pickedB
        lastCompareA = currentA
        lastCompareB = pickedB
    }

    /** Preferred B given A. Always prefers the wheel except when A is the
     *  wheel itself, in which case we fall back to External (when actively
     *  reporting) or Phone. Guaranteed to be != [a]. */
    private fun autoPickBFor(a: DataSource): DataSource = when (a) {
        DataSource.WHEEL -> {
            val externalLive = snapshots.value[DataSource.RACEBOX]?.isLive == true
            if (externalLive) DataSource.RACEBOX else DataSource.PHONE
        }
        else -> DataSource.WHEEL
    }
}

// Local helper for MutableStateFlow.update, keeps the call site tight.
private fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val current = value
        val next = transform(current)
        if (compareAndSet(current, next)) return
    }
}
