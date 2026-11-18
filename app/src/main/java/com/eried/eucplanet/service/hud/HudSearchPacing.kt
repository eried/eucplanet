package com.eried.eucplanet.service.hud

/**
 * How hard the dial loop searches for a HUD that is not answering.
 *
 * A rider is waiting when a wheel is connected or the app is on screen: then
 * the search repeats every 2 s for the sprint window and every 5 s after. With
 * nobody waiting it repeats only every idle interval, because searching every
 * few seconds all day kept about half a core busy. The subnet sweep, by far
 * the costliest channel, runs at most once per sweep interval either way; a
 * last-sweep time of 0 means "sweep on the next search".
 */
internal object HudSearchPacing {

    fun waitMs(attending: Boolean, sinceStartMs: Long, sprintMs: Long, idleMs: Long): Long = when {
        !attending -> idleMs
        sinceStartMs < sprintMs -> 2_000L
        else -> 5_000L
    }

    fun sweepDue(nowMs: Long, lastSweepMs: Long, intervalMs: Long): Boolean =
        lastSweepMs == 0L || nowMs - lastSweepMs >= intervalMs
}
