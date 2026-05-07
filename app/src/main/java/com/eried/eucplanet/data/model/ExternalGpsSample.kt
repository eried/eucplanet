package com.eried.eucplanet.data.model

/**
 * One position+speed sample from an external BLE GPS box (RaceBox, etc.).
 * Augments — never replaces — the phone GPS and the wheel's onboard GPS in
 * the trip log: both stay in their existing CSV columns and graph layers,
 * the external sample lands in dedicated trailing columns.
 */
data class ExternalGpsSample(
    val source: ExternalGpsSource,
    val speedKmh: Float,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Float,
    /** Horizontal accuracy in metres, or 0 if the device doesn't report it. */
    val accuracyMeters: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ExternalGpsSource(val displayName: String) {
    RACEBOX("RaceBox")
    // Future entries (Draggy, VBox, Garmin Catalyst, ...) plug in here as
    // their protocols become public. Adapter implementations live alongside
    // RaceBoxAdapter under ble/gps/.
}
