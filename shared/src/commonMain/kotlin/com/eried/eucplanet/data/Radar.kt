package com.eried.eucplanet.data

/** What a radar adapter emits per car, before threat-level classification. */
data class DecodedThreat(
    val id: Int,
    val distanceM: Int,
    val approachSpeedKmh: Int,
)

/**
 * One vehicle currently tracked by a paired rear-view radar (Garmin Varia) —
 * port of Android's RadarThreat. Kept in `data` (not `data.model`) to dodge the
 * FQN collision with Android's own class.
 */
data class RadarThreat(
    val id: Int,
    val distanceM: Int,
    val approachSpeedKmh: Int,
    val threatLevel: ThreatLevel,
    val firstSeenMs: Long,
)

/** Locally-classified severity (Garmin's own level byte is NDA-gated). */
enum class ThreatLevel { NONE, APPROACHING, FAST_APPROACH }

enum class RadarVendor(val displayName: String) { VARIA("Garmin Varia") }
