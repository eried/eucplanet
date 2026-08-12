package com.eried.eucplanet.util

/**
 * Whether the rider still needs telling that the proximity automation may
 * override a lock they just made by hand.
 *
 * The warning is worth saying once. Saying it on every single tap of the lock
 * button turns a useful heads-up into noise the rider reads past, and it lands
 * at the exact moment they are watching the wheel rather than the phone.
 *
 * Deliberately process-scoped and not stored: a rider who has seen it does not
 * need it again this session, and one who comes back tomorrow has probably
 * forgotten. [rearm] puts it back when the automation is switched on again,
 * since that is the moment the warning is worth repeating - the behaviour they
 * are being warned about has just changed.
 *
 * A plain object for the same reason as [PipHost]: the warning is raised on the
 * dashboard and re-armed from the settings screen, and a singleton is the
 * smallest thing that spans both.
 */
object AutoLockNotice {

    @Volatile
    private var pending = true

    /**
     * True at most once per arming. Consumes the notice, so call it only when
     * about to actually show the warning.
     */
    fun consume(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }

    /** Say it again: the automation was just switched on. */
    fun rearm() {
        pending = true
    }
}
