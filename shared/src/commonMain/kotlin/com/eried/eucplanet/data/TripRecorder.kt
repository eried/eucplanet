package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.TripSummary
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.nowEpochMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records a ride: while [recording], [sample] accumulates telemetry; [stop]
 * summarises it into a [TripSummary] and prepends it to [trips]. In-memory for
 * now (DarknessBot-compatible CSV export + persistence land with the storage
 * actuals); the shape is the seam the Recordings screen reads. Seeded with a few
 * representative trips so the list isn't empty before the first ride.
 */
class TripRecorder {
    private val _trips = MutableStateFlow(seedTrips)
    val trips: StateFlow<List<TripSummary>> = _trips.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val samples = ArrayList<WheelData>()
    private var startMs = 0L
    private var rideCount = 0

    fun toggle() {
        if (_recording.value) stop() else start()
    }

    fun start() {
        samples.clear()
        startMs = nowEpochMillis()
        _recording.value = true
    }

    /** Feed one telemetry frame; ignored when not recording. */
    fun sample(d: WheelData) {
        if (_recording.value) samples.add(d)
    }

    fun stop() {
        _recording.value = false
        if (samples.size < 2) return
        val durationMin = ((nowEpochMillis() - startMs) / 60_000L).toInt().coerceAtLeast(1)
        val speeds = samples.map { it.speed }
        val distance = (samples.last().tripDistance - samples.first().tripDistance).coerceAtLeast(0f)
        rideCount += 1
        val trip = TripSummary(
            date = "Ride $rideCount · just now",
            distanceKm = distance,
            durationMin = durationMin,
            avgKmh = if (speeds.isNotEmpty()) (speeds.sum() / speeds.size) else 0f,
            maxKmh = speeds.maxOrNull() ?: 0f,
            gpsLock = false,
            synced = false,
        )
        _trips.value = listOf(trip) + _trips.value
        samples.clear()
    }

    private companion object {
        val seedTrips = listOf(
            TripSummary("Jun 9 · 18:42", 12.4f, 31, 24.1f, 41.6f, gpsLock = true, synced = true),
            TripSummary("Jun 8 · 08:15", 6.1f, 17, 21.7f, 38.2f, gpsLock = true, synced = false),
            TripSummary("Jun 6 · 14:03", 28.9f, 74, 26.4f, 47.0f, gpsLock = false, synced = true),
        )
    }
}
