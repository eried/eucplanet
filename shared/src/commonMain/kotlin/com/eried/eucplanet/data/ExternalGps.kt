package com.eried.eucplanet.data

import com.eried.eucplanet.util.nowEpochMillis

/**
 * One position+speed sample from an external BLE GPS box (RaceBox, etc.) —
 * port of Android's ExternalGpsSample. Kept in the `data` package (not
 * `data.model`) to dodge the FQN collision with Android's own class.
 */
data class ExternalGpsSample(
    val source: ExternalGpsSource,
    val speedKmh: Float,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Float,
    val accuracyMeters: Float = 0f,
    val accelXG: Float? = null,
    val accelYG: Float? = null,
    val accelZG: Float? = null,
    /** Heading of motion in degrees [0..360), or null if unknown. */
    val headingDeg: Float? = null,
    /** Vertical speed in m/s (positive up), or null if unknown. */
    val verticalSpeedMps: Float? = null,
    val numSatellites: Int? = null,
    val timestamp: Long = nowEpochMillis(),
)

enum class ExternalGpsSource(val displayName: String) {
    RACEBOX("RaceBox"),
}

/**
 * Per-vendor adapter for an external BLE GPS box: advertisement matching + decoding
 * the raw notification stream into [ExternalGpsSample]s. Mirrors WheelAdapter.
 */
interface ExternalGpsAdapter {
    val source: ExternalGpsSource
    fun matches(deviceName: String): Boolean
    fun decode(notification: ByteArray): ExternalGpsSample?
    /** Post-subscribe RX writes (RaceBox MGA-INI assist); empty = none. */
    fun initCommands(
        timeUtcMillis: Long,
        lastKnownLat: Double?,
        lastKnownLon: Double?,
        lastKnownAccM: Float?,
    ): List<ByteArray> = emptyList()
}
