package com.eried.eucplanet.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One GPS fix. Unknown speed/bearing are negative; unknown altitude is NaN. */
data class GpsFix(
    val lat: Double,
    val lng: Double,
    val speedKmh: Float,
    val bearingDeg: Float,
    val altitudeM: Float,
    val timestamp: Long,
)

/**
 * Phone GPS seam. The iOS app (Swift `LocationBridge`, CoreLocation) sets
 * [nativeStart]/[nativeStop] at launch and pushes fixes via [update]; Android
 * leaves the hooks null (the Android app has its own location). Pure-Kotlin
 * consumers (auto-lights, HUD GPS, trip track) just observe [location].
 */
object LocationService {
    private val _location = MutableStateFlow<GpsFix?>(null)
    val location: StateFlow<GpsFix?> = _location

    var nativeStart: (() -> Unit)? = null
    var nativeStop: (() -> Unit)? = null

    fun start() { nativeStart?.invoke() }
    fun stop() { nativeStop?.invoke() }

    /** Called by the platform when a fresh fix arrives. */
    fun update(lat: Double, lng: Double, speedKmh: Float, bearingDeg: Float, altitudeM: Float, timestamp: Long) {
        _location.value = GpsFix(lat, lng, speedKmh, bearingDeg, altitudeM, timestamp)
    }

    /** Platform lost the fix / permission denied. */
    fun clear() { _location.value = null }
}
