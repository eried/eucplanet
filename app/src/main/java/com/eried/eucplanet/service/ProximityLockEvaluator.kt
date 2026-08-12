package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.ProximityLockSettings

/**
 * Decides when the Bluetooth-signal proximity automation should lock or unlock
 * the wheel.
 *
 * Pure state machine, no Android and no repositories, so the awkward cases can
 * be unit tested instead of ridden. [AutomationManager] feeds it a reading per
 * telemetry tick and carries out whatever it returns.
 *
 * The rules:
 *  - Lock when the signal fades to/below [ProximityLockSettings.lockBelowDbm]:
 *    the rider is walking away, still just in range.
 *  - Unlock when the signal comes back to/above
 *    [ProximityLockSettings.unlockAboveDbm], but only if the rider actually
 *    went away first (see [unlockArmed]).
 *  - A reading has to hold past its line for [HOLD_MS] before either fires,
 *    because RSSI is noisy, and the two lines are always [MIN_GAP_DBM] apart
 *    so they cannot chase each other.
 */
class ProximityLockEvaluator {

    enum class Action { NONE, LOCK, UNLOCK }

    /**
     * Whether an unlock is allowed to fire at all.
     *
     * This is the fix for a wheel that locked and then immediately unlocked
     * itself. Arming used to be implicit - locked wheel plus strong signal was
     * the whole test - so locking by hand while standing next to the wheel met
     * it the instant the lock landed, and the automation undid the rider's own
     * lock a few seconds later. It looked like the app sending lock-on then
     * lock-off. Now an unlock has to be earned by the signal genuinely fading
     * first, which is the only thing that means "the rider left".
     *
     * Only reachable on a family that reports its lock state back, which is why
     * it went unnoticed until InMotion V5/V8/V10 started reporting theirs.
     */
    private var unlockArmed: Boolean = false

    // When each condition first became true, or null while it is not met.
    // Null rather than the 0L the rest of the app uses as a "no timestamp"
    // sentinel: a caller is free to count from 0, and a sentinel that is also a
    // legal reading silently never expires.
    private var lockCandidateSinceMs: Long? = null
    private var unlockCandidateSinceMs: Long? = null

    /**
     * One reading. [rssiDbm] of 0 means the link has not reported a signal yet
     * and is ignored without disturbing any hold in progress. [locked] is the
     * wheel's current state as the app understands it.
     */
    fun evaluate(
        nowMs: Long,
        rssiDbm: Int,
        locked: Boolean,
        settings: ProximityLockSettings
    ): Action {
        if (rssiDbm == 0) return Action.NONE

        val lockAt = settings.lockBelowDbm
        // Unlock line is always kept stronger than the lock line, whatever the
        // rider typed, so a dead-band exists.
        val unlockAt = maxOf(settings.unlockAboveDbm, lockAt + MIN_GAP_DBM)

        // An unlocked wheel has nothing to come back to, so the next unlock has
        // to earn its arming again.
        if (!locked) unlockArmed = false
        // Locked and the signal has gone weak: the rider walked away, so a
        // later return is a real return.
        if (locked && rssiDbm <= lockAt) unlockArmed = true

        var action = Action.NONE

        // Walking away: weak signal, currently unlocked -> lock.
        if (settings.lockEnabled && !locked && rssiDbm <= lockAt) {
            val since = lockCandidateSinceMs ?: nowMs.also { lockCandidateSinceMs = it }
            if (nowMs - since >= HOLD_MS) {
                lockCandidateSinceMs = null
                // We are locking because the rider left, so coming back should
                // unlock: arm here rather than waiting for another weak reading
                // the rider may already be walking out of.
                unlockArmed = true
                action = Action.LOCK
            }
        } else lockCandidateSinceMs = null

        // Returning: strong signal and a locked wheel -> unlock.
        //
        // lockEnabled is required as well: unlocking only ever reverses this
        // feature's own lock, and the settings screen presents it that way, so
        // a stale unlockEnabled left over from switching the feature off must
        // not keep acting on its own.
        //
        // Whether the rider had to walk away first is theirs to choose; see
        // [ProximityLockSettings.unlockWhen]. In NEAR the signal speaks for
        // itself, which is symmetric with the lock half above.
        val needsWalkAway = settings.unlockWhen != ProximityLockSettings.UNLOCK_WHEN_NEAR
        if (settings.lockEnabled && settings.unlockEnabled &&
            locked && (unlockArmed || !needsWalkAway) && rssiDbm >= unlockAt
        ) {
            val since = unlockCandidateSinceMs ?: nowMs.also { unlockCandidateSinceMs = it }
            if (nowMs - since >= HOLD_MS) {
                unlockCandidateSinceMs = null
                unlockArmed = false
                action = Action.UNLOCK
            }
        } else unlockCandidateSinceMs = null

        return action
    }

    /**
     * The link dropped. Clears the holds, which mean nothing without a live
     * signal, but deliberately KEEPS [unlockArmed].
     *
     * Walking far enough away drops the connection, and that is the feature's
     * whole point: park, walk off, the wheel locks, the link dies a few steps
     * later, and the rider comes back to a wheel that should unlock itself.
     * Clearing the arming here would leave that wheel locked forever, breaking
     * the case the feature exists for while looking like a safe thing to do.
     * The arming survives because it records something the link cannot undo -
     * that the rider genuinely walked away from a locked wheel.
     */
    fun onLinkLost() {
        lockCandidateSinceMs = null
        unlockCandidateSinceMs = null
    }

    /**
     * Clear everything, for Stop All and for the feature being switched off.
     *
     * Arming lives in memory only, so it is also lost if the app is killed
     * between the walk away and the return. That fails closed - the wheel
     * stays locked and the rider unlocks it by hand - which is the right
     * direction for a theft deterrent to fail in.
     */
    fun reset() {
        unlockArmed = false
        onLinkLost()
    }

    companion object {
        /**
         * RSSI must hold past its threshold this long before we act. Kept equal
         * to the media-control hold so both automations feel consistent; the
         * dead-band is what actually prevents flip-flop.
         */
        const val HOLD_MS = 3000L

        /**
         * Minimum dead-band (dBm) kept between the lock and unlock thresholds.
         * Small, so the two can sit close for a snappy transition; the hold
         * soaks up the jitter that such a tight gap would otherwise let through.
         */
        const val MIN_GAP_DBM = 2
    }
}
