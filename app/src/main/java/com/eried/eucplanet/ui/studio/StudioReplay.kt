package com.eried.eucplanet.ui.studio

import com.eried.eucplanet.data.model.WheelData
import java.text.SimpleDateFormat
import java.util.Locale

/** A studio session is either live cameras (record now) or a trip replay. */
enum class StudioMode { LIVE, REPLAY }

/** One parsed row of a trip CSV, as telemetry the studio overlays can render. */
data class ReplaySample(val offsetMs: Long, val data: WheelData)

/**
 * A recorded trip parsed into a scrubbable telemetry timeline. Pitch and roll
 * are not stored in trip CSVs, so those gauges read 0 during replay.
 */
class ReplayTrip(val samples: List<ReplaySample>) {

    /** Total trip length in milliseconds (0 if the trip had no usable rows). */
    val durationMs: Long = samples.lastOrNull()?.offsetMs ?: 0L

    /** Telemetry at [offsetMs] into the trip — the most recent sample at/before it. */
    fun dataAt(offsetMs: Long): WheelData {
        if (samples.isEmpty()) return WheelData()
        val t = offsetMs.coerceIn(0L, durationMs)
        var lo = 0
        var hi = samples.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (samples[mid].offsetMs <= t) lo = mid else hi = mid - 1
        }
        return samples[lo].data
    }
}

/**
 * Parses a DarknessBot-format trip CSV (see [com.eried.eucplanet.util.CsvWriter])
 * into a [ReplayTrip]. Columns:
 * `Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,`
 * `Total mileage,GPS speed,Ext GPS speed,Current,PWM`.
 */
fun parseTripCsv(text: String): ReplayTrip {
    // Tolerate a few date layouts so a trip recorded by any app/version still
    // replays — strictness here was making GPS-only trips look "empty".
    val fmts = listOf(
        "dd.MM.yyyy HH:mm:ss.SSS", "dd.MM.yyyy HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss"
    ).map { SimpleDateFormat(it, Locale.US) }
    val out = ArrayList<ReplaySample>()
    var firstMs = -1L
    var firstMileage = Float.NaN
    text.lineSequence().drop(1).forEach { line ->
        if (line.isBlank()) return@forEach
        val c = line.split(',')
        if (c.size < 2) return@forEach
        // Use whatever columns the row has; missing telemetry just reads 0.
        fun col(i: Int) = c.getOrNull(i)?.trim()?.toFloatOrNull() ?: 0f
        val ms = fmts.firstNotNullOfOrNull {
            runCatching { it.parse(c[0].trim())?.time }.getOrNull()
        } ?: return@forEach
        if (firstMs < 0L) firstMs = ms
        val voltage = col(2)
        val current = col(11)
        val mileage = col(8)
        if (firstMileage.isNaN()) firstMileage = mileage
        out += ReplaySample(
            offsetMs = (ms - firstMs).coerceAtLeast(0L),
            data = WheelData(
                speed = col(1),
                voltage = voltage,
                current = current,
                maxTemperature = col(3),
                batteryPercent = col(4).toInt(),
                pwm = col(12),
                totalDistance = mileage,
                tripDistance = (mileage - firstMileage).coerceAtLeast(0f),
                motorPower = (voltage * current).toInt()
            )
        )
    }
    return ReplayTrip(out)
}

/** mm:ss for a millisecond offset, used by the replay timeline labels. */
fun formatReplayClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
