package com.eried.eucplanet.data.repository

/**
 * One phone location fix, with the members of android.location.Location that
 * shared code reads, under the same names, so a moved caller keeps
 * `loc.hasSpeed()` / `loc.speed`. A missing value reads as Location's own
 * default (0) and its has* check is false.
 */
data class PhoneLocation(
    val latitude: Double,
    val longitude: Double,
    /** Metres above WGS84; 0.0 when the fix has none, as on Android. */
    val altitude: Double = 0.0,
    /** m/s. */
    val speed: Float = 0f,
    /** Degrees east of true north. */
    val bearing: Float = 0f,
    /** Metres, 68% radius. */
    val accuracy: Float = 0f,
    private val speedKnown: Boolean = false,
    private val bearingKnown: Boolean = false,
    private val accuracyKnown: Boolean = false,
    private val altitudeKnown: Boolean = false,
    /** Wall-clock ms of the fix. */
    val time: Long = 0L,
) {
    fun hasSpeed(): Boolean = speedKnown
    fun hasBearing(): Boolean = bearingKnown
    fun hasAccuracy(): Boolean = accuracyKnown
    fun hasAltitude(): Boolean = altitudeKnown
}
