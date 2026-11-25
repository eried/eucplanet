package com.eried.eucplanet.service

import com.eried.eucplanet.hud.protocol.OverlayElementType

/**
 * How often the floating overlay's history takes a sample, from what is on the
 * overlay.
 *
 * It took one five times a second and handed the overlay a fresh copy of six
 * minutes of them each time, for every preset, though only three kinds of
 * element ever look at it. The G-force trail is drawn from it and needs every
 * sample to stay smooth. A graph or a map trace reads the same at one point a
 * second. Anything else needs none.
 */
object OverlayHistoryNeed {
    /** The overlay's own push rate: the trail keeps it. */
    const val TRAIL_MS = 200L
    const val GRAPH_MS = 1_000L
    const val NONE = 0L

    fun sampleIntervalMs(types: Collection<OverlayElementType>): Long = when {
        OverlayElementType.G_FORCE in types -> TRAIL_MS
        OverlayElementType.DATA_GRAPH in types || OverlayElementType.MAP in types -> GRAPH_MS
        else -> NONE
    }
}
