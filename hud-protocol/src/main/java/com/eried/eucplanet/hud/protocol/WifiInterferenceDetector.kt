package com.eried.eucplanet.hud.protocol

/**
 * Phone-side detector for "my own home/other Wi-Fi is interrupting the HUD".
 *
 * On a single-radio phone, every time the home-Wi-Fi STA changes state while
 * the hotspot is up, the shared radio re-tunes and the live HUD link drops.
 * The HUD only knows the hotspot, so the PHONE must detect this and tell it.
 *
 * The fingerprint: a HUD link drop that lands within [correlationWindowMs] of
 * one of the phone's OWN Wi-Fi STA transitions (join / weaken / leave). A drop
 * with no nearby Wi-Fi event (rode out of hotspot range, HUD glitch) is NOT
 * counted, which is what keeps the advisory from false-firing. We require
 * [minCorrelatedDrops] within [rollingWindowMs] before raising the advisory,
 * and auto-clear it once the link has been stable for [clearAfterStableMs].
 *
 * Pure: the caller passes the clock ([nowMs]) so this is unit-testable and has
 * no Android dependency. Not thread-safe; call from a single coroutine/thread.
 */
class WifiInterferenceDetector(
    private val correlationWindowMs: Long = 20_000L,
    private val minCorrelatedDrops: Int = 2,
    private val rollingWindowMs: Long = 5 * 60_000L,
    private val clearAfterStableMs: Long = 3 * 60_000L,
) {
    private var lastStaTransitionMs: Long = 0L
    private val correlatedDropTimes = ArrayDeque<Long>()

    /** True while we believe the phone's Wi-Fi is the thing dropping the HUD. */
    var advisoryActive: Boolean = false
        private set

    /** The phone's home/other Wi-Fi STA just changed state (onAvailable /
     *  onLosing / onLost of an internet-capable Wi-Fi network). */
    fun onStaTransition(nowMs: Long) {
        lastStaTransitionMs = nowMs
    }

    /** Record an established-link HUD drop. Returns true if it correlated with a
     *  recent STA transition (i.e. it looks like channel-follow, not a plain
     *  out-of-range drop). */
    fun onHudDrop(nowMs: Long): Boolean {
        val correlated = lastStaTransitionMs != 0L &&
            nowMs - lastStaTransitionMs in 0..correlationWindowMs
        if (correlated) {
            correlatedDropTimes.addLast(nowMs)
            prune(nowMs)
            if (correlatedDropTimes.size >= minCorrelatedDrops) advisoryActive = true
        }
        return correlated
    }

    /** Call on a healthy/stable tick. Clears the advisory once the link has gone
     *  [clearAfterStableMs] with no fresh correlated drop. */
    fun onStableTick(nowMs: Long) {
        prune(nowMs)
        val lastDrop = correlatedDropTimes.lastOrNull()
        if (advisoryActive && (lastDrop == null || nowMs - lastDrop >= clearAfterStableMs)) {
            advisoryActive = false
            correlatedDropTimes.clear()
        }
    }

    private fun prune(nowMs: Long) {
        while (correlatedDropTimes.isNotEmpty() &&
            nowMs - correlatedDropTimes.first() > rollingWindowMs) {
            correlatedDropTimes.removeFirst()
        }
    }
}
