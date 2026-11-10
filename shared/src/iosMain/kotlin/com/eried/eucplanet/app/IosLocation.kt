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
    /**
     * How hard the GPS works, in CoreLocation's terms: the desired accuracy
     * (kCLLocationAccuracy*, metres) and the distance filter (metres, -1 none).
     */
    fun setPower(desiredAccuracy: Double, distanceFilter: Double)
    /** While a trip records, fixes keep coming with the screen off. */
    fun keepInBackground(on: Boolean)
    /** Asks for Always, which iOS shows once, as an upgrade from while in use. */
    fun requestAlways()
}

/**
 * The phone's own GPS, as TripRepository's fused location client gives it
 * on Android: the freshest fix, and the last one known. How hard it works,
 * and whether it keeps going in the background (the location background
 * mode in Info.plist), is Android's GPS power tiers ([IosGpsPower]).
 */
object IosLocation {
    var sdk: LocationSdk? = null

    private val _fix = MutableStateFlow<PhoneLocation?>(null)
    val fix: StateFlow<PhoneLocation?> = _fix.asStateFlow()
    private val _lastKnown = MutableStateFlow<PhoneLocation?>(null)
    val lastKnown: StateFlow<PhoneLocation?> = _lastKnown.asStateFlow()
    private val _authorized = MutableStateFlow(false)
    /** The rider allowed location, while in use or always. */
    val authorized: StateFlow<Boolean> = _authorized.asStateFlow()
    private val _access = MutableStateFlow(LocationAccess.NOT_ASKED)
    /** The app's location grant, finer than [authorized]. */
    val access: StateFlow<LocationAccess> = _access.asStateFlow()
    private val _servicesOn = MutableStateFlow(true)
    /** Location Services on for the whole phone (iPhone Settings -> Privacy). */
    val servicesOn: StateFlow<Boolean> = _servicesOn.asStateFlow()

    /** A consumer wants positions; [IosGpsPower] picks the tier. */
    fun start() = IosGpsPower.request()

    /** Stops the GPS until the next [start], as Android's Stop all. */
    fun stop() = IosGpsPower.release()

    internal fun startUpdates(desiredAccuracy: Double, distanceFilter: Double) {
        sdk?.setPower(desiredAccuracy, distanceFilter)
        sdk?.start()
    }

    /** Released: the last position goes too, so the fix icon reads honestly, as on Android. */
    internal fun stopUpdates() {
        sdk?.stop()
        _fix.value = null
    }

    fun keepInBackground(on: Boolean) = sdk?.keepInBackground(on) ?: Unit

    fun requestAlways() = sdk?.requestAlways() ?: Unit

    // --- Called from Swift ---

    /** At start and whenever the rider changes the app's location access. */
    fun onAuthorization(granted: Boolean) {
        _authorized.value = granted
    }

    /**
     * The grant as CLAuthorizationStatus numbers it (0 not asked, 1 restricted,
     * 2 denied, 3 always, 4 while in use), and whether Location Services is on
     * for the phone. Reported with [onAuthorization], whenever either changes.
     */
    fun onAccess(status: Int, servicesEnabled: Boolean) {
        _access.value = when (status) {
            0 -> LocationAccess.NOT_ASKED
            3 -> LocationAccess.ALWAYS
            4 -> LocationAccess.WHILE_IN_USE
            else -> LocationAccess.DENIED
        }
        _servicesOn.value = servicesEnabled
    }

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

/** The app's location grant on the iPhone. Restricted (parental controls) counts as denied. */
enum class LocationAccess { NOT_ASKED, DENIED, WHILE_IN_USE, ALWAYS }
