package com.eried.eucplanet.hud.protocol

/**
 * The rider's direction of travel, kept through a stop.
 *
 * GPS only knows a heading while it moves. Passed on as it comes, the HUD
 * map swung back to north-up at every red light and round again on the way
 * out, and at walking pace the heading jitters enough to spin the map. So
 * a heading counts once the rider is really moving, and the last one that
 * counted stays until the next.
 */
class HeldHeading(private val minSpeedMs: Float = MIN_SPEED_MS) {
    private var held = Float.NaN

    /**
     * Feed the latest fix: [bearingDeg] null when it has none, [speedMs]
     * null when it has none. Returns the heading to show, NaN until the
     * first one arrives.
     */
    fun update(bearingDeg: Float?, speedMs: Float?): Float {
        if (bearingDeg != null && !bearingDeg.isNaN()) {
            val moving = speedMs == null || speedMs >= minSpeedMs
            // The very first heading is taken as it comes: better than north.
            if (moving || held.isNaN()) held = bearingDeg
        }
        return held
    }

    companion object {
        /** About 3 km/h: below it a phone's GPS heading is mostly noise. */
        const val MIN_SPEED_MS = 0.8f
    }
}
