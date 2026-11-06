package com.eried.eucplanet.app

import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.platform.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Swift wrapper around CoreLocation (iosApp/LocationBridge.swift)
 * does when asked. A CLLocationManager delegate written in Kotlin was never
 * called back on the simulator while the same delegate in Swift was, so
 * Swift owns the manager and reports through [IosLocation.onFix].
 */
interface LocationSdk {
    /** Asks for permission the first time, then keeps the fixes coming. */
    fun start()
    fun stop()
    /** While a trip records, fixes keep coming with the screen off. */
    fun keepInBackground(on: Boolean)
}

/**
 * The phone's own GPS, as TripRepository's fused location client gives it
 * on Android: the freshest fix, and the last one known. Updates run while
 * the app is in front; a recording keeps them running in the background
 * (the location background mode in Info.plist).
 */
object IosLocation {
    var sdk: LocationSdk? = null

    private val _fix = MutableStateFlow<PhoneLocation?>(null)
    val fix: StateFlow<PhoneLocation?> = _fix.asStateFlow()
    private val _lastKnown = MutableStateFlow<PhoneLocation?>(null)
    val lastKnown: StateFlow<PhoneLocation?> = _lastKnown.asStateFlow()

    fun start() = sdk?.start() ?: Unit

    fun stop() {
        sdk?.stop()
        _fix.value = null
    }

    fun keepInBackground(on: Boolean) = sdk?.keepInBackground(on) ?: Unit

    // --- Called from Swift ---

    /**
     * One CLLocation. Negative accuracies, speed and course mean "not known",
     * as CoreLocation reports them; they map to Android's has* flags.
     */
    fun onFix(
        latitude: Double, longitude: Double, altitude: Double, verticalAccuracy: Double,
        speed: Double, course: Double, horizontalAccuracy: Double, timeMs: Long,
    ) {
        val fix = PhoneLocation(
            latitude = latitude,
            longitude = longitude,
            altitude = if (verticalAccuracy >= 0) altitude else 0.0,
            speed = if (speed >= 0) speed.toFloat() else 0f,
            bearing = if (course >= 0) course.toFloat() else 0f,
            accuracy = if (horizontalAccuracy >= 0) horizontalAccuracy.toFloat() else 0f,
            speedKnown = speed >= 0,
            bearingKnown = course >= 0,
            accuracyKnown = horizontalAccuracy >= 0,
            altitudeKnown = verticalAccuracy >= 0,
            time = timeMs,
        )
        if (_fix.value == null) Log.i("Location", "first fix, accuracy ${fix.accuracy} m")
        _fix.value = fix
        _lastKnown.value = fix
    }
}
