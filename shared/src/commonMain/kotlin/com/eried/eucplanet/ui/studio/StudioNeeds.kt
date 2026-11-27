package com.eried.eucplanet.ui.studio

import com.eried.eucplanet.hud.protocol.OverlayElement
import com.eried.eucplanet.hud.protocol.OverlayElementType

/**
 * What a preset needs kept running for it.
 *
 * The Studio ran two things for every preset: a tick on each display frame of
 * a recording, which recomposed the whole screen sixty times a second, and a
 * G-force trail rebuilt on each motion reading, fifty times a second. The tick
 * is only for a stopwatch clock and the trail only for a G-force element.
 */
object StudioNeeds {
    fun stopwatchTick(elements: List<OverlayElement>): Boolean =
        elements.any { it.type == OverlayElementType.CLOCK && it.clockStyle == "STOPWATCH" }

    fun gForceTrail(elements: List<OverlayElement>): Boolean =
        elements.any { it.type == OverlayElementType.G_FORCE }
}
