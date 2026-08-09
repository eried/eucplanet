package com.eried.eucplanet.util

/**
 * Shared smoothing for noisy wheel telemetry.
 *
 * Battery percent, current and PWM all swing hard at the ~1 Hz sample rate:
 * battery sags under load and springs back, current is close to noise while
 * riding, and PWM peaks are momentary. The raw series is honest but unreadable,
 * and a peak value is a poor thing to warn a rider about.
 *
 * Used by the Trip Details smoothed graphs and by the periodic voice reports,
 * so both describe the wheel the same way.
 *
 * Kept free of Android so it can be unit-tested directly.
 */
object Smoothing {

    /**
     * Centred moving average over [window] samples.
     *
     * NaN marks "no reading" throughout this app (a CSV column the trip predates,
     * or an empty cell), so NaN inputs are skipped rather than poisoning the
     * average, and a window with nothing in it stays NaN so the chart breaks the
     * line instead of drawing a false zero.
     *
     * The window shrinks at the ends rather than padding, so the curve starts and
     * finishes on real data instead of easing out of nowhere.
     *
     * @param window samples across the whole window. Values below 2 return the
     *   input untouched.
     */
    fun movingAverage(values: List<Float>, window: Int): List<Float> {
        if (window < 2 || values.size < 2) return values
        val half = window / 2
        return List(values.size) { i ->
            var sum = 0.0
            var n = 0
            val from = (i - half).coerceAtLeast(0)
            val to = (i + half).coerceAtMost(values.lastIndex)
            for (j in from..to) {
                val v = values[j]
                if (!v.isNaN()) { sum += v; n++ }
            }
            if (n == 0) Float.NaN else (sum / n).toFloat()
        }
    }

    /**
     * Average of the samples inside a trailing time window, newest last.
     *
     * For live use, where "what has the wheel been doing for the last few
     * seconds" is the useful question and an instantaneous peak is not. Samples
     * arrive irregularly over BLE, so the window is by timestamp, not by count.
     *
     * @param samples (timestampMs, value) in ascending time order
     * @param nowMs end of the window
     * @param windowMs how far back to average
     * @return the mean, or null when the window holds no usable sample
     */
    fun trailingAverage(
        samples: List<Pair<Long, Float>>,
        nowMs: Long,
        windowMs: Long,
    ): Float? {
        if (windowMs <= 0L) return null
        val cutoff = nowMs - windowMs
        var sum = 0.0
        var n = 0
        for ((t, v) in samples) {
            if (t < cutoff || t > nowMs) continue
            if (v.isNaN()) continue
            sum += v; n++
        }
        return if (n == 0) null else (sum / n).toFloat()
    }
}
