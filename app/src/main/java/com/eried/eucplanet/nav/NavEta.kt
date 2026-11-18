package com.eried.eucplanet.nav

/**
 * Arrival time for the map's navigator view. The speed starts at the rider's
 * cruising-speed setting and slides to their own moving average over the
 * first ten minutes of riding, never above 80% of tiltback, never below a
 * crawl that would put the arrival days away.
 */
object NavEta {
    const val FLOOR_KMH = 5.0
    const val BLEND_FULL_S = 600.0
    const val TILTBACK_SHARE = 0.8

    fun speedKmh(settingKmh: Double, movingAvgKmh: Double?, movingSeconds: Double, tiltbackKmh: Double): Double {
        val w = if (movingAvgKmh == null) 0.0 else (movingSeconds / BLEND_FULL_S).coerceIn(0.0, 1.0)
        var speed = settingKmh * (1 - w) + (movingAvgKmh ?: 0.0) * w
        if (tiltbackKmh > 0) speed = minOf(speed, tiltbackKmh * TILTBACK_SHARE)
        return maxOf(speed, FLOOR_KMH)
    }

    fun etaEpochMs(nowMs: Long, remainingM: Double, speedKmh: Double): Long =
        nowMs + (remainingM / (speedKmh / 3.6) * 1000.0).toLong()
}

/** Average speed over the time actually spent moving; stops do not count. */
class MovingAverage {
    private var lastMs = -1L
    private var distKm = 0.0
    var movingSeconds = 0.0
        private set
    val avgKmh: Double? get() = if (movingSeconds > 0) distKm / (movingSeconds / 3600.0) else null

    fun add(timeMs: Long, speedKmh: Double, movingKmh: Double) {
        val prev = lastMs
        lastMs = timeMs
        if (prev < 0 || speedKmh <= movingKmh) return
        // A long gap (GPS lost, app paused) is not riding time we saw.
        val dtS = ((timeMs - prev) / 1000.0).coerceIn(0.0, MAX_STEP_S)
        movingSeconds += dtS
        distKm += speedKmh * dtS / 3600.0
    }

    fun reset() { lastMs = -1L; distKm = 0.0; movingSeconds = 0.0 }

    private companion object { const val MAX_STEP_S = 5.0 }
}

/** How fast the straight-line distance to a goal shrinks, for Treasure hunt and Homing. */
class ClosingSpeed(private val windowMs: Long = 120_000L, private val minSpanMs: Long = 20_000L) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(timeMs: Long, distM: Double) {
        samples.addLast(timeMs to distM)
        while (samples.isNotEmpty() && timeMs - samples.first().first > windowMs) samples.removeFirst()
    }

    fun kmh(): Double? {
        if (samples.size < 2) return null
        val (t0, d0) = samples.first()
        val (t1, d1) = samples.last()
        val spanMs = t1 - t0
        if (spanMs < minSpanMs) return null
        val kmh = (d0 - d1) / (spanMs / 1000.0) * 3.6
        return kmh.takeIf { it >= NavEta.FLOOR_KMH }
    }

    fun reset() = samples.clear()
}
