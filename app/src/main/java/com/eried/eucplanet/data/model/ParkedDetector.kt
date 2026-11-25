package com.eried.eucplanet.data.model

/**
 * Whether a connected wheel is parked: standing still for a while, or on the
 * charger. [GpsPowerPolicy] uses it to slow the GPS down for the hours a wheel
 * sits next to the phone with nobody on it.
 *
 * Parked ends at once, on the first frame that moves and on the frame the
 * charger comes off. A rider who unplugs is about to ride, so the still timer
 * starts again from there and does not count the hours on the charger.
 *
 * Fed from the telemetry collector and reset from the connection collector,
 * which are different threads, so every method takes the one lock.
 */
class ParkedDetector {

    private var stillSinceMs: Long? = null
    private var wasCharging = false

    /** A different connection starts from nothing. */
    @Synchronized
    fun reset() {
        stillSinceMs = null
        wasCharging = false
    }

    /**
     * One telemetry frame. [stillMs] is how long the wheel has to stand still
     * before it counts as parked; 0 or less switches the whole thing off.
     */
    @Synchronized
    fun update(nowMs: Long, moving: Boolean, charging: Boolean, stillMs: Long): Boolean {
        if (stillMs <= 0L) {
            reset()
            return false
        }
        val unplugged = wasCharging && !charging
        wasCharging = charging
        if (moving) {
            stillSinceMs = null
            return false
        }
        if (unplugged) stillSinceMs = nowMs
        val since = stillSinceMs ?: nowMs.also { stillSinceMs = it }
        // A clock that steps back must not read as a long stand.
        if (nowMs < since) {
            stillSinceMs = nowMs
            return charging
        }
        return charging || nowMs - since >= stillMs
    }
}
