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

    // Phone IMU trail — rolling buffer of (xG, zG) offsets used by the
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
        // AFTER snapshots is declared — referencing it here would NPE
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
     * "—" when a field is null (source doesn't know that metric, or device
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

    init {
        // Second init block — runs AFTER `snapshots` is initialised above.
        // Each emit appends to the per-source per-metric rolling buffers
        // used by the Compare-tab line charts. Null fields are skipped
        // inside [appendSeries] so we don't taint the series with NaN.
        viewModelScope.launch {
            snapshots.collect { map ->
                val now = System.currentTimeMillis()
                map.forEach { (src, snap) ->
                    appendSeries(_speedSeries[src], now, snap.speedKmh)
                    appendSeries(_gMagnitudeSeries[src], now, snap.horizGMagnitude)
                    appendSeries(_headingSeries[src], now, snap.headingDeg)
                    appendSeries(_vertSpeedSeries[src], now, snap.verticalSpeedMps)
                }
            }
        }
    }

    /** Currently saved wheel speed calibration in % (range −5..+5). Exposed so
     *  the Compare tab can preview what new value an "apply from comparison"
     *  action would write. */
    val calibrationOffsetPct: StateFlow<Float> = settingsRepository.settings
        .map { it.speedCalibrationOffsetPct }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    /** Whether the wheel is connected right now — gates the "apply" button so
     *  the user can't write calibration with no wheel to write it to. */
    val wheelConnected: StateFlow<Boolean> = wheelRepository.connectionState
        .map { it == ConnectionState.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Compute the calibration % that would make the wheel's average reading
     * match the reference source's average over the rolling window, on top of
     * any calibration already in effect. Returns null if either input is too
     * small to be meaningful (we don't divide near zero).
     *
     * Math: displayed_wheel = raw * (1 + curPct/100). We want
     * raw * (1 + newPct/100) = ref → newPct = (ref·(1 + curPct/100)/wheel − 1)·100.
     */
    fun computeCalibrationPct(avgWheelKmh: Float, avgRefKmh: Float): Float? {
        if (avgWheelKmh < 1f || avgRefKmh < 1f) return null
        val curPct = calibrationOffsetPct.value
        val newPct = (avgRefKmh * (1f + curPct / 100f) / avgWheelKmh - 1f) * 100f
        return (kotlin.math.round(newPct * 10f) / 10f).coerceIn(-5f, 5f)
    }

    /** Persist a new wheel speed calibration. Same clamp/rounding rules as the
     *  Wheel parameters slider. */
    fun applyCalibrationPct(newPct: Float) {
        viewModelScope.launch {
            val rounded = (kotlin.math.round(newPct * 10f) / 10f).coerceIn(-5f, 5f)
            val current = settingsRepository.get()
            settingsRepository.update(current.copy(speedCalibrationOffsetPct = rounded))
        }
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
}

// Local helper for MutableStateFlow.update — keeps the call site tight.
private fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val current = value
        val next = transform(current)
        if (compareAndSet(current, next)) return
    }
}
