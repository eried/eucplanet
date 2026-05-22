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
class ReplayTrip(
    val samples: List<ReplaySample>,
    /** Wall-clock epoch of offset 0 — lets the clock overlay show the real time. */
    val startEpochMs: Long = 0L
) {

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
 * Parses a trip CSV into a [ReplayTrip]. **Header-driven**: each telemetry
 * column is located by name, so it works for native recordings *and* imported
 * CSVs (DarknessBot / EUC World / WheelLog) whose column order and date format
 * differ. Several date layouts are accepted, including ISO `T`-separated stamps
 * with sub-millisecond precision.
 */
fun parseTripCsv(text: String): ReplayTrip {
    val fmts = listOf(
        "dd.MM.yyyy HH:mm:ss.SSS", "dd.MM.yyyy HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss"
    ).map { SimpleDateFormat(it, Locale.US) }
    val lines = text.lineSequence().iterator()
    if (!lines.hasNext()) return ReplayTrip(emptyList())
    val header = lines.next().split(',').map { it.trim().lowercase(Locale.US) }
    fun idx(vararg names: String): Int =
        names.firstNotNullOfOrNull { header.indexOf(it).takeIf { i -> i >= 0 } } ?: -1
    val iDate = idx("date")
    if (iDate < 0) return ReplayTrip(emptyList())
    val iSpeed = idx("speed")
    val iVoltage = idx("voltage")
    val iCurrent = idx("current")
    val iPwm = idx("pwm")
    val iTemp = idx("temperature")
    val iBattery = idx("battery level", "battery")
    val iMileage = idx("total mileage", "mileage", "distance")
    val iGForce = idx("g-force", "gforce")
    val iAccelX = idx("g-force x")
    val iAccelY = idx("g-force y")

    val out = ArrayList<ReplaySample>()
    var firstMs = -1L
    var firstMileage = Float.NaN
    // Trim sub-millisecond precision (e.g. .000000 micros) WITHOUT touching a
    // 4-digit year like .2026 — only 5+ digits after the dot are trimmed.
    val subMs = Regex("(\\.\\d{3})\\d{2,}")
    lines.forEach { line ->
        if (line.isBlank()) return@forEach
        val c = line.split(',')
        fun num(i: Int) = if (i in c.indices) c[i].trim().toFloatOrNull() ?: 0f else 0f
        val rawDate = c.getOrNull(iDate)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
        val ms = fmts.firstNotNullOfOrNull {
            runCatching { it.parse(subMs.replace(rawDate, "$1"))?.time }.getOrNull()
        } ?: return@forEach
        if (firstMs < 0L) firstMs = ms
        val voltage = num(iVoltage)
        val current = num(iCurrent)
        val mileage = num(iMileage)
        if (firstMileage.isNaN()) firstMileage = mileage
        out += ReplaySample(
            offsetMs = (ms - firstMs).coerceAtLeast(0L),
            data = WheelData(
                speed = num(iSpeed),
                voltage = voltage,
                current = current,
                maxTemperature = num(iTemp),
                batteryPercent = num(iBattery).toInt(),
                pwm = num(iPwm),
                totalDistance = mileage,
                tripDistance = (mileage - firstMileage).coerceAtLeast(0f),
                motorPower = (voltage * current).toInt(),
                gForce = num(iGForce),
                accelX = num(iAccelX),
                accelY = num(iAccelY)
            )
        )
    }
    return ReplayTrip(out, if (firstMs >= 0L) firstMs else 0L)
}

/** mm:ss for a millisecond offset, used by the replay timeline labels. */
fun formatReplayClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
