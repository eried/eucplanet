package com.eried.eucplanet.hud.protocol

/**
 * Paces the HUD's own WiFi scan requests while it is off the air.
 *
 * Android rejoins a saved network only when one of its own scans sees it, and
 * a disconnected radio scans on a backed-off schedule (20, 40, 80, 160 s with
 * the screen on, up to three minutes with it off). The 2026-09-11 capture is
 * that schedule end to end: the phone's hotspot came up at 12:21:05, the HUD's
 * first beacon landed at 12:22:20, and the phone paired 125 ms after it. An
 * app-requested scan feeds the same auto-join, so asking every half minute
 * bounds the wait at about that instead of at the schedule's tail.
 *
 * The OS serves `WifiManager.startScan()` to a foreground app at most four
 * times in any two-minute window and silently refuses the rest, so the
 * interval stays above 30 s. Nothing else is rate limited here: the first
 * request after [reset] is always granted, which is the one that matters
 * when the radio has just dropped.
 */
class RescanPacer(private val minIntervalMs: Long = FOREGROUND_SAFE_INTERVAL_MS) {

    private var lastRequestMs: Long? = null

    /** True, and the request is recorded, when enough time has passed since
     *  the last granted request. False means: not now, ask again later. */
    fun claim(nowMs: Long): Boolean {
        val last = lastRequestMs
        if (last != null && nowMs - last < minIntervalMs) return false
        lastRequestMs = nowMs
        return true
    }

    /** Back on the air: the next off-air episode may scan at once. */
    fun reset() {
        lastRequestMs = null
    }

    companion object {
        /** Four scans per 120 s is the foreground budget; 35 s keeps a margin. */
        const val FOREGROUND_SAFE_INTERVAL_MS: Long = 35_000L
    }
}
