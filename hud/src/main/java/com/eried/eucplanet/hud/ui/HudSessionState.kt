package com.eried.eucplanet.hud.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eried.eucplanet.hud.protocol.HudState

/**
 * Per-ride accumulators the HUD screens read from. Lives at the activity
 * level (held by `HudActivity`) so the data keeps growing whether or not
 * the screen that USES it is currently composed.
 *
 * Why this exists: every HUD screen composable only runs when its enum
 * is `controller.current`. If a screen kept its rolling buffer in a
 * `remember { ArrayDeque() }`, the buffer would reset every time the
 * rider switched away and back -- the PowerScreen sparkline would
 * start empty every visit, the TripStats "session max" would mean
 * "since I last looked at this screen" instead of "since pair." Lifting
 * the accumulators here makes them survive screen switches and only
 * reset on HUD reboot, which is the rider-expected meaning of "session."
 *
 * The fields are backed by Compose `mutableStateOf` so any screen that
 * reads them auto-recomposes on update. The rolling buffer is a plain
 * `ArrayDeque` (mutating it doesn't trip Compose's snapshot system);
 * we expose a `tick` counter alongside that bumps every push, and the
 * screens that draw from the buffer read `tick` to register a Compose
 * dependency so their `Canvas` re-runs on every new sample.
 *
 * Wire frames are routed in via [ingest] from a `HudActivity` lifecycle
 * collector. The phone keeps sending every field on every frame
 * regardless of which screen is active, so the accumulators see the
 * full stream even when their consumer is off-screen.
 */
class HudSessionState {

    // --- PowerScreen ---------------------------------------------------

    /** 300-sample rolling buffer of instantaneous watts (60 s at 5 Hz). */
    val wattsBuffer: java.util.ArrayDeque<Float> = java.util.ArrayDeque(300)

    /** Bumps every time [wattsBuffer] gets a new sample. Compose
     *  composables that draw the buffer read this so Canvas re-runs. */
    var wattsTick: Int by mutableStateOf(0)
        private set

    /** Trailing average over the buffer, kept incrementally so the
     *  PowerScreen range-estimate doesn't have to walk 300 entries each
     *  frame. NaN when there are zero samples. */
    var avgWatts: Float by mutableStateOf(Float.NaN)
        private set

    // --- TripStatsScreen -----------------------------------------------

    /** epoch ms of the first frame received this session, 0 = none yet. */
    var firstFrameMs: Long by mutableStateOf(0L)
        private set
    /** Highest speed seen this session, in km/h. */
    var maxSpeedKmh: Float by mutableStateOf(0f)
        private set
    /** Wheel-reported trip km at session start. NaN until first frame. */
    var tripStartKm: Float by mutableStateOf(Float.NaN)
        private set
    /** Running sum of speed samples for the average. */
    var sumSpeedKmh: Float by mutableStateOf(0f)
        private set
    /** Sample count, denominator of the average. */
    var speedSamples: Int by mutableStateOf(0)
        private set

    /** Average speed over the session, in km/h. NaN when no samples
     *  have been seen yet. */
    val avgSpeedKmh: Float
        get() = if (speedSamples == 0) Float.NaN else sumSpeedKmh / speedSamples

    // --- SafetyScreen --------------------------------------------------

    /** Highest voltage seen this session. The sag tile compares this to
     *  the current voltage to detect under-load drops. Resets on HUD
     *  reboot only. */
    var maxVoltage: Float by mutableStateOf(0f)
        private set

    // -------------------------------------------------------------------

    /** Fold a freshly received wire frame into the session accumulators.
     *  Called from `HudActivity` on every accepted frame from
     *  `server.state.collect`. Pure mutation, no IO, no allocation
     *  except the deque's amortised growth. */
    fun ingest(hud: HudState) {
        // First-frame timestamp anchors the session duration. Use the
        // wire ts when we have it (matches what the rider's phone is
        // emitting) else fall back to the local clock.
        val ts = if (hud.timestampMs > 0L) hud.timestampMs
            else System.currentTimeMillis()
        if (firstFrameMs == 0L) firstFrameMs = ts

        // Speed-derived counters.
        val s = hud.speedKmh
        if (s.isFinite()) {
            if (s > maxSpeedKmh) maxSpeedKmh = s
            sumSpeedKmh += s
            speedSamples += 1
        }

        // Trip-distance anchor for "distance ridden this session".
        if (tripStartKm.isNaN() && hud.tripKm.isFinite()) tripStartKm = hud.tripKm

        // Safety-screen sag baseline. Track the session high, not the
        // running peak -- a transient spike (e.g. regen burst) should
        // bump the baseline so subsequent sag is measured from there.
        if (hud.voltage.isFinite() && hud.voltage > maxVoltage) {
            maxVoltage = hud.voltage
        }

        // PowerScreen rolling buffer + incremental average.
        if (hud.voltage.isFinite() && hud.current.isFinite()) {
            val w = hud.voltage * hud.current
            // Update average incrementally: cheaper than walking the
            // buffer every frame, and the buffer is bounded at 300 so
            // arithmetic precision drift is well under display
            // resolution (we round to whole watts in the UI).
            val n = wattsBuffer.size
            avgWatts = if (n == 0) {
                w
            } else if (n < 300) {
                ((avgWatts * n) + w) / (n + 1)
            } else {
                // Buffer is full: a push will evict the oldest sample.
                val evicted = wattsBuffer.first()
                ((avgWatts * n) - evicted + w) / n
            }
            wattsBuffer.addLast(w)
            while (wattsBuffer.size > 300) wattsBuffer.removeFirst()
            // Overflow-safe tick. Compose treats any value change as
            // a recomposition trigger; we just need it to be different
            // from the previous read.
            wattsTick = (wattsTick + 1) and Int.MAX_VALUE
        }
    }
}
