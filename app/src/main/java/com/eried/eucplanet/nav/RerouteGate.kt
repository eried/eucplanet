package com.eried.eucplanet.nav

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

    private var failures = 0
    private var nextTryMs = 0L

    @Synchronized
    fun mayTry(now: Long): Boolean = now >= nextTryMs

    /** True for the first try since the last success or reset. */
    @Synchronized
    fun speak(): Boolean = failures == 0

    /** A try failed at [now]; the next waits [delayMs], doubled per failure in a row. */
    @Synchronized
    fun failed(now: Long, delayMs: Long) {
        val wait = (delayMs shl failures.coerceAtMost(4)).coerceAtMost(MAX_WAIT_MS)
        failures++
        nextTryMs = now + wait
    }

    /** A reroute worked, the rider is back on the route, or the session restarted. */
    @Synchronized
    fun reset() {
        failures = 0
        nextTryMs = 0L
    }
}
