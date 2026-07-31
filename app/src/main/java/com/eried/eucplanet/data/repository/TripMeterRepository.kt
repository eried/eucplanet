package com.eried.eucplanet.data.repository

import android.location.Location
import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.TripMeterAccumulator
import com.eried.eucplanet.data.model.TripMeterState
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.store.TripMeterStore
import com.eried.eucplanet.util.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Car-odometer-style running trip meter, independent of the recording feature.
 *
 * Counts distance while a wheel is CONNECTED (not just while recording),
 * persists across app restarts / wheel power-downs, and is cleared only by a
 * manual [reset] or by Stop All (which also calls [reset]). Reuses the recorder's
 * per-tick distance approach (GPS-primary, wheel-odometer fallback) but never
 * touches the recorder's state, so counting keeps running with no trip active.
 *
 * At each 10 km / 10 mi boundary (following the rider's distance unit) it appends
 * a split via the pure [TripMeterAccumulator]. State is a single JSON blob in
 * [TripMeterStore]. WheelRepository injects TripRepository through dagger.Lazy to
 * break a DI cycle; we do the same for both so this repository can never form one.
 */
@Singleton
class TripMeterRepository @Inject constructor(
    private val store: TripMeterStore,
    private val wheelRepositoryLazy: dagger.Lazy<WheelRepository>,
    private val tripRepositoryLazy: dagger.Lazy<TripRepository>,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        private const val TAG = "TripMeterRepo"
        // Tick cadence while connected. Distance still comes from whatever GPS /
        // wheel-odometer delta accrued since the last tick, so a slow (idle-tier)
        // GPS stream just produces a larger delta on the tick a fresh fix lands.
        private const val TICK_INTERVAL_MS = 1000L
        // Persist at most this often for the running total (splits force a write).
        private const val PERSIST_INTERVAL_MS = 15_000L
        // GPS credibility gate, identical to the recorder's: skip jittery / huge jumps.
        private const val GPS_MIN_STEP_M = 0.5f
        private const val GPS_MAX_STEP_M = 200f
        private const val GPS_MAX_ACCURACY_M = 25f
        // Wheel-odometer fallback: accept only a small forward delta so a
        // power-cycle reset (negative) or a spurious jump can't inflate the total.
        private const val WHEEL_MAX_STEP_KM = 0.2f
        // Below this speed the wheel is treated as parked: distance may still be
        // logged (a slow GPS creep), but active time isn't, so avg speed stays sane.
        private const val MOVING_SPEED_KMH = 1f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val accumulator = TripMeterAccumulator()

    private val _state = MutableStateFlow(TripMeterState())
    val state: StateFlow<TripMeterState> = _state.asStateFlow()

    /** Convenience for the HUD / Overlay Studio: current total distance in km. */
    val distanceKm: Float get() = _state.value.distanceKm

    // Per-connection tick state, reset on each (re)connect.
    private var lastGpsPoint: Location? = null
    private var lastWheelOdoKm: Float = 0f
    private var lastTickMs: Long = 0L
    private var lastPersistMs: Long = 0L

    init {
        // Restore the persisted meter first, then seed the accumulator from it.
        scope.launch {
            val restored = runCatching { store.load() }.getOrDefault(TripMeterState())
            accumulator.intervalKm = intervalKmFor(settingsRepository.get())
            accumulator.restore(restored)
            _state.value = accumulator.snapshot()
        }
        // Keep the split step in sync with the rider's distance unit.
        scope.launch {
            settingsRepository.settings.collect { accumulator.intervalKm = intervalKmFor(it) }
        }
        // Accumulate only while a wheel is connected; collectLatest cancels the
        // loop the moment the link drops (and flushes the total to storage).
        scope.launch {
            wheelRepository().connectionState.collectLatest { connState ->
                if (connState == ConnectionState.CONNECTED) {
                    runAccumulationLoop()
                } else {
                    resetTickState()
                    persist(_state.value)
                }
            }
        }
    }

    private fun wheelRepository(): WheelRepository = wheelRepositoryLazy.get()
    private fun tripRepository(): TripRepository = tripRepositoryLazy.get()

    /** The split step in km: 10 in the rider's distance unit, stored as km. */
    private fun intervalKmFor(s: com.eried.eucplanet.data.model.AppSettings): Float =
        Units.distanceToKm(10f, Units.effectiveDistanceUnit(s))

    private fun resetTickState() {
        lastGpsPoint = null
        lastWheelOdoKm = 0f
        lastTickMs = 0L
    }

    private suspend fun runAccumulationLoop() {
        resetTickState()
        Log.i(TAG, "Trip meter accumulating (connected)")
        while (true) {
            val wheel = wheelRepository().wheelData.value
            val loc = tripRepository().currentLocation.value
            val now = System.currentTimeMillis()

            val dKm = perTickDistanceKm(loc, wheel)
            // Wall time since the last tick, bounded so a long pause / clock jump
            // can't dump a phantom bucket of active time in one go.
            val dt = if (lastTickMs == 0L) 0L else (now - lastTickMs).coerceIn(0L, 5_000L)
            lastTickMs = now
            val speed = abs(wheel.speed)
            val moving = speed > MOVING_SPEED_KMH || dKm > 0f
            val dtActive = if (moving) dt else 0L

            val before = _state.value.splits.size
            accumulator.onTick(dKm, dtActive, speed, wheel.batteryPercent, now)
            val snapshot = accumulator.snapshot()
            _state.value = snapshot

            val splitAdded = snapshot.splits.size != before
            if (splitAdded || now - lastPersistMs >= PERSIST_INTERVAL_MS) {
                lastPersistMs = now
                persist(snapshot)
            }
            delay(TICK_INTERVAL_MS)
        }
    }

    /**
     * Per-tick distance in km. GPS-primary: a credible fix pair (accuracy
     * <= 25 m, step in 0.5..200 m). When GPS gives nothing usable this tick, fall
     * back to the wheel's lifetime odometer delta (small forward steps only).
     * Mirrors the recorder's source order without depending on its state.
     */
    private fun perTickDistanceKm(loc: Location?, wheel: WheelData): Float {
        if (loc != null) {
            val prev = lastGpsPoint
            val gpsKm = if (prev != null && loc.accuracy <= GPS_MAX_ACCURACY_M) {
                val stepM = prev.distanceTo(loc)
                if (stepM in GPS_MIN_STEP_M..GPS_MAX_STEP_M) stepM / 1000f else 0f
            } else 0f
            lastGpsPoint = loc
            if (gpsKm > 0f) {
                // Keep the odometer reference current so a later GPS gap doesn't
                // double-count the same ground via the fallback.
                if (wheel.totalDistance > 0f) lastWheelOdoKm = wheel.totalDistance
                return gpsKm
            }
        }
        // Wheel-odometer fallback.
        val odo = wheel.totalDistance
        if (odo > 0f) {
            val ref = lastWheelOdoKm
            lastWheelOdoKm = odo
            if (ref > 0f) {
                val d = odo - ref
                if (d > 0f && d <= WHEEL_MAX_STEP_KM) return d
            }
        }
        return 0f
    }

    private fun persist(snapshot: TripMeterState) {
        scope.launch { runCatching { store.save(snapshot) } }
    }

    /** Zero the total, clear the split log, and persist (fire-and-forget). Backs
     *  the detail-view Reset button. */
    fun reset() {
        val snapshot = clearInMemory()
        persist(snapshot)
        Log.i(TAG, "Trip meter reset")
    }

    /**
     * Reset and BLOCK until the cleared state is written to storage. The Stop All
     * teardown SIGKILLs the process the moment cleanup finishes, so the ordinary
     * fire-and-forget write in [reset] could lose the wipe to the kill. Callers on
     * that path use this so the next launch is guaranteed to start fresh.
     */
    suspend fun resetAndPersist() {
        val snapshot = clearInMemory()
        runCatching { store.save(snapshot) }
        Log.i(TAG, "Trip meter reset (persisted)")
    }

    private fun clearInMemory(): TripMeterState {
        accumulator.reset()
        resetTickState()
        val snapshot = accumulator.snapshot()
        _state.value = snapshot
        lastPersistMs = System.currentTimeMillis()
        return snapshot
    }
}
