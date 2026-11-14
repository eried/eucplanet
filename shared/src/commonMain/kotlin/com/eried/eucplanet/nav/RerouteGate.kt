package com.eried.eucplanet.nav

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * When a reroute may start, and whether it is spoken. A failed reroute (no
 * signal and nothing offline that covers it, or a mode that never routes
 * offline) waits before the next try, twice as long each time up to a
 * minute, and only the first try of such a run says "Rerouting". Without
 * this every GPS fix started a new try and the rider heard it over and over.
 */
class RerouteGate {

    private companion object {
        const val MAX_WAIT_MS = 60_000L
    }

    private val lock = SynchronizedObject()
    private var failures = 0
    private var nextTryMs = 0L

    fun mayTry(now: Long): Boolean = synchronized(lock) { now >= nextTryMs }

    /** True for the first try since the last success or reset. */
    fun speak(): Boolean = synchronized(lock) { failures == 0 }

    /** A try failed at [now]; the next waits [delayMs], doubled per failure in a row. */
    fun failed(now: Long, delayMs: Long): Unit = synchronized(lock) {
        val wait = (delayMs shl failures.coerceAtMost(4)).coerceAtMost(MAX_WAIT_MS)
        failures++
        nextTryMs = now + wait
    }

    /** A reroute worked, the rider is back on the route, or the session restarted. */
    fun reset(): Unit = synchronized(lock) {
        failures = 0
        nextTryMs = 0L
    }
}
