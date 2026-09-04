package com.eried.eucplanet.util

/**
 * The battery envelope, computed as the ride happens.
 *
 * [BatteryEnvelope] does this over a finished trip, with the whole array in
 * hand and both endpoints known. A rider on the wheel has neither, and the
 * number they are shown meanwhile is the raw percentage, which on an 84 V pack
 * swings several points every time they accelerate and hands it back when they
 * coast. That makes a low-battery alarm on the raw value either useless or a
 * liar: set it tight and it fires on a hill, set it loose and it fires too
 * late.
 *
 * Half-minute buckets, walked with hysteresis. Each bucket reports the
 * reading at its LIGHTEST load, because load moves a battery reading one way
 * only: down. The lightest-loaded moment in a half minute is the closest look
 * anyone gets at the resting level without stopping.
 *
 * That is the part a median got wrong. Reduced to its middle value, a half
 * minute the rider spent accelerating reports a sagging number, and the walk
 * below believes every fall immediately, so the line dived on load and climbed
 * back a minute later once the sag let go. A rider watching a pack whose
 * charge had not moved saw it swing ten points, which is the swing this exists
 * to remove.
 *
 * It steps DOWN freely, because a resting level that has really dropped does
 * not come back, and it only steps UP when two consecutive buckets agree the
 * rise is real, which is what separates a regen descent from a sag letting
 * go.
 *
 * Pure and tickless: it is fed samples and asked for a value, so a test can
 * run a whole ride through it in a millisecond.
 */
class LiveBatteryEnvelope(
    private val bucketMs: Long = (BatteryEnvelope.BUCKET_S * 1000).toLong(),
    private val riseHysteresis: Float = 1.5f,
) {

    private var bucketStartMs = 0L

    /**
     * Whether a bucket is open.
     *
     * A separate flag rather than treating a zero start as "not started": a
     * caller whose clock legitimately reads 0 then re-opened the bucket on
     * every sample and it never closed, so the envelope stayed NaN forever.
     */
    private var started = false
    private val bucket = ArrayList<Float>()
    private var running = Float.NaN
    private var pendingRise = Float.NaN

    /** The envelope now, or NaN before the first bucket has closed. */
    var value: Float = Float.NaN
        private set

    /**
     * Feed one battery reading. Returns the current envelope, NaN until the
     * first half minute of the ride has gone by.
     *
     * A percentage of zero is dropped: every family leaves the field at zero
     * before the first real frame, and letting that into the median would
     * start every ride with an envelope at the bottom of the pack.
     */
    fun sample(nowMs: Long, batteryPercent: Float): Float {
        if (batteryPercent <= 0f || batteryPercent > 100f) return value
        if (!started) { bucketStartMs = nowMs; started = true }
        // A clock that jumped backwards (or a fresh connection) starts over
        // rather than holding a bucket open forever.
        if (nowMs < bucketStartMs) reset()
        bucket += batteryPercent
        if (nowMs - bucketStartMs < bucketMs) return value
        closeBucket()
        bucketStartMs = nowMs
        return value
    }

    /** Forget the ride so far: a new wheel is a new pack. */
    fun reset() {
        bucketStartMs = 0L
        started = false
        bucket.clear()
        running = Float.NaN
        pendingRise = Float.NaN
        value = Float.NaN
    }

    private fun closeBucket() {
        if (bucket.isEmpty()) return
        val level = lightestLoad(bucket)
        bucket.clear()
        when {
            running.isNaN() -> running = level
            // Down is always believed. A RESTING level that really fell does
            // not come back, and waiting for confirmation would only make the
            // alarm late, which is the one thing a low-battery warning cannot
            // be. This is only safe because [level] is a lightest-load
            // reading; on a median it made every hard launch look like a
            // discharge.
            level <= running -> { running = level; pendingRise = Float.NaN }
            // Up needs two buckets that agree, which is what tells a genuine
            // regen descent from a sag letting go.
            !pendingRise.isNaN() && level >= running + riseHysteresis -> {
                running = minOf(level, pendingRise)
                pendingRise = Float.NaN
            }
            level >= running + riseHysteresis -> pendingRise = level
            else -> pendingRise = Float.NaN
        }
        value = (running * 10f).toInt() / 10f
    }

    /**
     * The bucket's reading at its lightest load: the 90th percentile.
     *
     * Not the maximum, so one spurious frame cannot set the level for a whole
     * half minute, and not the middle, which is a sagging number whenever the
     * rider spent that half minute working the wheel.
     */
    private fun lightestLoad(samples: List<Float>): Float {
        val sorted = samples.sorted()
        val i = ((sorted.size - 1) * 9) / 10
        return sorted[i]
    }
}
