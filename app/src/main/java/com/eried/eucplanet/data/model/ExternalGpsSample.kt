package com.eried.eucplanet.data.model

/**
 * One position+speed sample from an external BLE GPS box (RaceBox, etc.).
 * Augments, never replaces, the phone GPS and the wheel's onboard GPS in
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
    /** Linear acceleration in g, or null if the device doesn't report it.
     *  RaceBox Mini/S/Pro stream accel + gyro alongside the PVT message
     *  on their custom 0xFF/0x01 frame; basic GPS receivers (Garmin etc.)
     *  won't fill these in. */
    val accelXG: Float? = null,
    val accelYG: Float? = null,
    val accelZG: Float? = null,
    /** Heading of motion in degrees [0..360), or null if unknown. Both
     *  standard NAV-PVT and the extended frame report this at offset 64
     *  as int32 deg × 1e-5. */
    val headingDeg: Float? = null,
    /** Vertical speed in m/s (positive up), or null if unknown. Derived
     *  from the NED down-velocity (offset 56, int32 mm/s, +down → flip). */
    val verticalSpeedMps: Float? = null,
    /** Number of satellites used in the fix, or null if unknown. Useful as
     *  a per-source quality indicator. */
    val numSatellites: Int? = null,
    /** Internal battery of the GPS box in percent [0..100], or null if the
     *  device doesn't report it. RaceBox carries it in the extended frame's
     *  batteryStatus byte; Dragy exposes it on a separate device-status
     *  characteristic the connection manager polls. Lets the rider see the box
     *  draining before it dies mid-ride. */
    val batteryPercent: Int? = null,
    /** True when the box reports it is charging, false when on battery, null
     *  when unknown or not reported (Dragy's status byte is percent-only). */
    val charging: Boolean? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ExternalGpsSource(val displayName: String) {
    RACEBOX("RaceBox"),
    DRAGY("Dragy")
    // Future entries (VBox, Garmin Catalyst, ...) plug in here as their
    // protocols become public. Adapter implementations live alongside
    // RaceBoxAdapter under ble/gps/.
}
