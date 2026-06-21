package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.TripBackup
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
class TripRecorder(private val fileStore: FileStore = createFileStore(), seedDemo: Boolean = false) {
    // Seed sample trips only where there can be no real ones (the Simulator), so a
    // real device shows only the rider's own recordings — like Android.
    private val _trips = MutableStateFlow(if (seedDemo) seedTrips else emptyList())
    val trips: StateFlow<List<TripSummary>> = _trips.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val samples = ArrayList<WheelData>()
    private var startMs = 0L
    private var rideCount = 0
    private var nextId = 1L

    /** Invoked when a ride is saved, with everything EucStats needs to upload it
     *  (the trip id for status callbacks, start/end epoch ms, sample count, CSV
     *  text). Set by the app layer; null = no online backup. Kept out of the
     *  recorder so it stays platform-free. */
    var onTripSaved: ((tripId: Long, startMs: Long, endMs: Long, sampleCount: Int, csv: String) -> Unit)? = null

    /** Update a trip's online-backup state (called by the app as uploads resolve). */
    fun setBackup(tripId: Long, status: TripBackup) {
        _trips.value = _trips.value.map { if (it.id == tripId) it.copy(backup = status) else it }
    }

    /** Rebuild the upload CSV for a trip from its samples (for manual re-sync / retry). */
    fun csvFor(trip: TripSummary): String = buildCsv(trip.samples)

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
        if (samples.size < 2) {
            samples.clear()
            return
        }
        val endMs = nowEpochMillis()
        val durationMin = ((endMs - startMs) / 60_000L).toInt().coerceAtLeast(1)
        val speeds = samples.map { it.speed }
        val distance = (samples.last().tripDistance - samples.first().tripDistance).coerceAtLeast(0f)
        rideCount += 1
        val csv = buildCsv(samples)
        val sampleCount = samples.size
        val csvPath = fileStore.writeText("euc_trip_$rideCount.csv", csv)
        val tripId = nextId++
        val trip = TripSummary(
            date = "Ride $rideCount · just now",
            distanceKm = distance,
            durationMin = durationMin,
            avgKmh = if (speeds.isNotEmpty()) (speeds.sum() / speeds.size) else 0f,
            maxKmh = speeds.maxOrNull() ?: 0f,
            gpsLock = samples.any { it.latitude != 0.0 || it.longitude != 0.0 },
            backup = TripBackup.Off,
            id = tripId,
            csvPath = csvPath,
            samples = samples.toList(),
        )
        _trips.value = listOf(trip) + _trips.value
        samples.clear()
        // Hand the raw ride to the app layer for online backup (EucStats), if wired.
        onTripSaved?.invoke(tripId, startMs, endMs, sampleCount, csv)
    }

    /** DarknessBot-compatible-ish CSV of the ride samples. */
    private fun buildCsv(s: List<WheelData>): String {
        val sb = StringBuilder()
        sb.append("t_s,speed_kmh,voltage_v,current_a,power_w,battery_pct,distance_km,pwm_pct,temp_c\n")
        val t0 = s.firstOrNull()?.timestamp ?: 0L
        for (w in s) {
            val t = (w.timestamp - t0) / 1000.0
            sb.append(t).append(',')
                .append(w.speed).append(',')
                .append(w.voltage).append(',')
                .append(w.current).append(',')
                .append(w.motorPower).append(',')
                .append(w.batteryPercent).append(',')
                .append(w.tripDistance).append(',')
                .append(w.pwm).append(',')
                .append(w.maxTemperature).append('\n')
        }
        return sb.toString()
    }

    private companion object {
        val seedTrips = listOf(
            TripSummary("Jun 9 · 18:42", 12.4f, 31, 24.1f, 41.6f, gpsLock = true, backup = TripBackup.Uploaded, id = -1L),
            TripSummary("Jun 8 · 08:15", 6.1f, 17, 21.7f, 38.2f, gpsLock = true, backup = TripBackup.Off, id = -2L),
            TripSummary("Jun 6 · 14:03", 28.9f, 74, 26.4f, 47.0f, gpsLock = false, backup = TripBackup.Failed, id = -3L),
        )
    }
}
