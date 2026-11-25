package com.eried.eucplanet.data.model

import kotlin.math.abs

/**
 * Whether a phone motion reading is worth publishing with the wheel data.
 *
 * The reading is merged into the wheel data about eight times a second, and
 * every merge wakes everything that watches the wheel: the service's whole
 * per-update pass and the dashboard. A phone lying beside a charging wheel
 * reports the same hundredth of a g for hours, and each of those merges
 * changed nothing anyone could see.
 *
 * A reading is published when it differs from the one last published by
 * [minChangeG] on any axis, so a phone that starts to move is seen at once and
 * a slow drift is seen as soon as it adds up. And one is published every
 * [keepAliveMs] whatever it says, so nothing that waits on these updates can
 * be left waiting for longer than that.
 */
class ImuPublishGate(
    private val minChangeG: Float = MIN_CHANGE_G,
    private val keepAliveMs: Long = KEEP_ALIVE_MS,
) {
    private var lastPublishMs = Long.MIN_VALUE / 2

    /**
     * [shownG], [shownX], [shownY] are what the wheel data holds now; the
     * other three are the new reading.
     */
    fun shouldPublish(
        nowMs: Long,
        shownG: Float, shownX: Float, shownY: Float,
        g: Float, x: Float, y: Float,
    ): Boolean {
        val moved = abs(g - shownG) >= minChangeG ||
            abs(x - shownX) >= minChangeG ||
            abs(y - shownY) >= minChangeG
        // nowMs < lastPublishMs: the clock stepped back, do not wait it out.
        val due = nowMs - lastPublishMs >= keepAliveMs || nowMs < lastPublishMs
        if (!moved && !due) return false
        lastPublishMs = nowMs
        return true
    }

    companion object {
        /** A hundredth of a g: what the G-force tile shows. */
        const val MIN_CHANGE_G = 0.01f
        const val KEEP_ALIVE_MS = 1_000L
    }
}
