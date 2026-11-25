package com.eried.eucplanet.nav

/**
 * Makes guidance updates and Stop take turns.
 *
 * Guidance publishes its state from the GPS collector, about once a second,
 * as "read the state, change a few fields, write it back". Stop cleared the
 * state from the rider's thread. A fix being worked on in the same instant
 * could read the state before Stop and write it after, which put the old
 * turn and the "navigating" flag back with nothing running to clear them
 * again: the popup, or the arrow on the watch and the HUD, stayed.
 *
 * Every write goes through here. One lock, held for the write alone, never
 * across a wait. After [end] the guidance writes are dropped.
 */
class GuidanceGate {
    private var counter = 0
    private var session = 0   // 0 = stopped

    /** A new guidance session. Returns its id, for [ifSession]. */
    @Synchronized
    fun begin(): Int {
        counter++
        session = counter
        return counter
    }

    /** Stop: runs [clear] and drops every guidance write that comes later. */
    @Synchronized
    fun end(clear: () -> Unit) {
        session = 0
        clear()
    }

    /** A write by guidance itself. Dropped, returning false, once stopped. */
    @Synchronized
    fun ifGuiding(write: () -> Unit): Boolean {
        if (session == 0) return false
        write()
        return true
    }

    /**
     * A write by the start of session [id]. Dropped when that session was
     * stopped, or replaced by a newer one, while it was still loading.
     */
    @Synchronized
    fun ifSession(id: Int, write: () -> Unit): Boolean {
        if (session != id) return false
        write()
        return true
    }

    /** A write that is not guidance (the rider minimising the popup). */
    @Synchronized
    fun always(write: () -> Unit) = write()
}
