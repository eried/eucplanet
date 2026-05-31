package com.eried.eucplanet.data.model

/**
 * One vehicle currently tracked by a paired rear-view radar (Garmin Varia
 * today; the [RadarVendor] enum is the extension point for other devices).
 *
 * The radar publishes a list of these on each notify frame. Each car keeps a
 * stable [id] across frames until the radar drops it (out of range, lost
 * track), so the repository can compute a closing rate and derive
 * [ThreatLevel] without trusting any vendor-specific level byte.
 */
data class RadarThreat(
    /** Vendor track-id, stable while the car is in view. */
    val id: Int,
    /** Range from the rear of the rider, in metres. */
    val distanceM: Int,
    /** Approach speed in km/h. Positive = closing. */
    val approachSpeedKmh: Int,
    /** Locally-classified severity. See [ThreatLevel]. */
    val threatLevel: ThreatLevel,
    /** Wall-clock when this threat was first seen this session, in ms. */
    val firstSeenMs: Long
)

/**
 * Locally-classified severity. Derived from distance + closing rate so the
 * classification is independent of whatever Garmin chose to put in their
 * vendor-private level byte (which is currently NDA-gated and not in the
 * `…3203` notify channel any public client reads).
 */
enum class ThreatLevel {
    /** Tracked but distant or moving away. UI shows the dot, no alarm. */
    NONE,
    /** Closing at a normal pace. UI dot turns amber. */
    APPROACHING,
    /** Closing fast or already close. UI dot turns red, alarms fire. */
    FAST_APPROACH
}

/** Which radar product family the paired device belongs to. */
enum class RadarVendor(val displayName: String) {
    VARIA("Garmin Varia")
    // Future: MAGICSHINE_SEEMEE, BSAFE, etc. when their BLE protocols become public.
}
