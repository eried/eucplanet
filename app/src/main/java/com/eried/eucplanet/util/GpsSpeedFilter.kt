package com.eried.eucplanet.util

/**
 * Noise filter for a single GPS speed stream, in km/h.
 *
 * The GPS ground speed is the receiver's Doppler reading, which occasionally
 * throws a one-sample spike (multipath near buildings/trees, or a brief signal
 * drop and reacquire). A consumer that keeps a session MAX would latch that
 * spike forever - a ~40 mph ride showing a 49 mph "max". A median-of-3 medians a
 * lone spike (40, 49, 40 -> 40) back out while sustained real speed passes
 * straight through, at the cost of ~1 sample (about a second at 1 Hz GPS) of lag.
 *
 * Confidence gating (position / speed accuracy) is left to the caller because
 * the accuracy fields differ per source (Android [android.location.Location] vs
 * an external box's own sample). Not thread-safe: drive it from one source's
 * update callback.
 */
class GpsSpeedFilter {
    private val window = ArrayDeque<Float>()

    /** Feed one accepted sample (km/h); returns the median of the last up-to-3. */
    fun filter(speedKmh: Float): Float {
        window.addLast(speedKmh)
        while (window.size > 3) window.removeFirst()
        return median(window)
    }

    /** Drop the window, e.g. when the fix is lost, so a stale speed can't blend
     *  into the next fix's median. */
    fun reset() = window.clear()

    companion object {
        /** Median of up to three values: the middle element of the sorted set
         *  (the higher of two, the only one of one). */
        fun median(values: Collection<Float>): Float {
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }
}
