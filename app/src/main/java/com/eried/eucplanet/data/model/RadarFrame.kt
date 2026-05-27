package com.eried.eucplanet.data.model

/**
 * One snapshot of the rear-view radar state. The radar emits ~1 Hz; the
 * repository tracks the latest frame and feeds it to the dashboard overlay,
 * the alarm engine, and any downstream consumer that wants threats.
 *
 * Empty [threats] means the radar reports the lane is clear, NOT that the
 * stream has stalled. Use [timestamp] freshness to distinguish.
 */
data class RadarFrame(
    val vendor: RadarVendor,
    val threats: List<RadarThreat>,
    /** Device battery percentage if the radar publishes it, null otherwise. */
    val batteryPercent: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Closest threat in this frame, or null when the lane is clear. */
    val closest: RadarThreat? get() = threats.minByOrNull { it.distanceM }

    /** Highest severity present in this frame, falling back to NONE. */
    val maxLevel: ThreatLevel
        get() = threats.maxByOrNull { it.threatLevel.ordinal }?.threatLevel ?: ThreatLevel.NONE
}
