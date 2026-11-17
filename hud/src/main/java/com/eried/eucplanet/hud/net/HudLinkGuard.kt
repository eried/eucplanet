package com.eried.eucplanet.hud.net

/**
 * One phone at a time.
 *
 * The HUD draws whatever the last frame said. Two phones dialing the same
 * HUD both got in, and their frames took turns on the screen, which the
 * rider saw as flickering. A second phone is turned away while the first is
 * still talking; the same phone dialing again (a fast reconnect) takes over.
 */
object HudLinkGuard {

    /** A linked phone counts as talking this long after its last frame.
     *  Past it the link is taken for dead and another phone may come in. */
    const val TALKING_MS = 3_000L

    /**
     * Whether a phone dialing from [newAddress] may link. [linkedAddress] is
     * the phone already linked, or null; [sinceLastFrameMs] is how long ago
     * it last sent a frame.
     */
    fun admits(linkedAddress: String?, sinceLastFrameMs: Long, newAddress: String): Boolean =
        linkedAddress == null || linkedAddress == newAddress || sinceLastFrameMs > TALKING_MS
}
