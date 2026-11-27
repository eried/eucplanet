package com.eried.eucplanet.hud.protocol

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Phone side of "the custom overlay travels when it changes".
 *
 * One per connection, so a new connection starts by sending the overlay.
 * Not thread-safe: used by the one loop that sends that connection's frames.
 */
class OverlaySender(private val resendMs: Long = RESEND_MS) {
    private var sent: String? = null
    private var sentAtMs = 0L

    /**
     * [state] as it should go out to a HUD that reported [hudMinor]: with its
     * overlay, or without it and marked [HudState.customOverlaySame].
     */
    fun stamp(state: HudState, hudMinor: Int, nowMs: Long): HudState {
        // A HUD that does not know the flag gets the overlay in every frame,
        // as it always has. So does a HUD whose greeting has not arrived yet.
        if (hudMinor < MIN_HUD_MINOR) {
            sent = null
            return state
        }
        val overlay = state.customOverlayJson
        // No overlay is said outright, never as "same as before".
        if (overlay.isEmpty()) {
            sent = null
            return state
        }
        val due = sent != overlay || nowMs - sentAtMs >= resendMs || nowMs < sentAtMs
        if (due) {
            sent = overlay
            sentAtMs = nowMs
            return state
        }
        return state.copy(customOverlayJson = "", customOverlaySame = true)
    }

    companion object {
        /** The HUD's PROTOCOL_MINOR from which it understands the flag. */
        const val MIN_HUD_MINOR = 28
        /** The overlay is sent again this often whatever happened, so a HUD
         *  that somehow lost it is without it for ten seconds at most. */
        const val RESEND_MS = 10_000L
    }
}

/** HUD side: puts the overlay back into a frame that left it out. */
class OverlayKeeper {
    // Replaces @Synchronized, which does nothing on the iPhone.
    private val lock = SynchronizedObject()
    private var last = ""

    fun restore(state: HudState): HudState = synchronized(lock) {
        if (state.customOverlaySame) {
            state.copy(customOverlayJson = last, customOverlaySame = false)
        } else {
            last = state.customOverlayJson
            state
        }
    }
}
