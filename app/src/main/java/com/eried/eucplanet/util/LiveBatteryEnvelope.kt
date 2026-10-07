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
 * Three rules, and the shape they give the line is the whole point:
 *
 *  - **Each half minute is read at its lightest load.** Load moves a battery
 *    reading one way only, down, so the lightest-loaded moment in a bucket is
 *    the closest look at the resting level anyone gets without stopping. That
 *    is a high percentile of the bucket, not its middle. A median is a sagging
 *    number whenever the rider spent most of the half minute on the throttle,
 *    and reading it made the line dive on every launch.
 *  - **Down takes two half minutes that agree, and moves to the higher.** Once
 *    a drop is in, nothing takes it back (see the next rule), so a single
 *    bucket that happened to be all climb must not be allowed to write it.
 *    Two consecutive buckets both below the line is a resting level that
 *    really fell. The line moves to the higher of the two, the less sagged one,
 *    so an over-drop is the one mistake this never makes. The cost is a minute
 *    of lag on a real discharge, and a minute is nothing against a percentage
 *    that moves one point in that time.
 *  - **Never up while riding.** Regen on a descent and a sag letting go look
 *    the same from here, and the raw percentage already shows the rider
 *    whichever it was. This is the number that only goes one way, which is
 *    what lets an alarm on it fire once and stay fired. The one exception is
 *    a wheel on the charger, where the level genuinely rises and following it
 *    is the only honest thing to do.
 *
 * Pure and tickless: it is fed samples and asked for a value, so a test can
 * run a whole ride through it in a millisecond.
 */
class LiveBatteryEnvelope(
    private val bucketMs: Long = (BatteryEnvelope.BUCKET_S * 1000).toLong(),
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
    private var bucketCharging = false
    private var running = Float.NaN

    /** The previous bucket's level if it sat below the line, else NaN. */
    private var pendingDrop = Float.NaN

    /** The envelope now, or NaN before the first bucket has closed. */
    var value: Float = Float.NaN
        private set

    /**
     * Feed one battery reading. Returns the current envelope, NaN until the
     * first half minute of the ride has gone by.
     *
     * [charging] is whether the wheel says it is on the charger. A charging
     * pack is the one case where the resting level really rises, so while it
     * is set the line simply follows.
     *
     * A percentage of zero is dropped: every family leaves the field at zero
     * before the first real frame, and letting that into the bucket would
     * start every ride with an envelope at the bottom of the pack.
     */
    fun sample(nowMs: Long, batteryPercent: Float, charging: Boolean = false): Float {
        if (batteryPercent <= 0f || batteryPercent > 100f) return value
        if (!started) { bucketStartMs = nowMs; started = true }
        // A clock that jumped backwards (or a fresh connection) starts over
        // rather than holding a bucket open forever.
        if (nowMs < bucketStartMs) reset()
        bucket += batteryPercent
        bucketCharging = bucketCharging || charging
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
        bucketCharging = false
        running = Float.NaN
        pendingDrop = Float.NaN
        value = Float.NaN
    }

    private fun closeBucket() {
        if (bucket.isEmpty()) return
        val level = lightestLoad(bucket)
        val charging = bucketCharging
        bucket.clear()
        bucketCharging = false
        when {
            running.isNaN() -> running = level
            // On the charger the level really does rise. Follow it, both
            // ways, and forget any drop that was waiting: the pack it was
            // measured on is being refilled.
            charging -> { running = level; pendingDrop = Float.NaN }
            // Never up while riding. Whatever pushed a bucket above the line,
            // regen or a sag letting go, the raw percentage already shows it.
            level >= running -> pendingDrop = Float.NaN
            // Below the line twice in a row is a resting level that fell. Move
            // to the higher of the two: the less sagged reading, so the one
            // move this cannot undo is never an over-drop.
            !pendingDrop.isNaN() -> {
                running = maxOf(level, pendingDrop)
                pendingDrop = Float.NaN
            }
            // Below the line once. Could be a climb with no let-up; wait for
            // the next half minute to say.
            else -> pendingDrop = level
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
