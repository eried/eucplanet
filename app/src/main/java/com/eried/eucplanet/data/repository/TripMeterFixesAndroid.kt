package com.eried.eucplanet.data.repository

import android.location.Location
import com.eried.eucplanet.ui.dashboard.toPhoneLocation

/**
 * The trip meter's fixes from [TripRepository], through a Lazy so the meter
 * never forms a DI cycle. Distance is Location's own ellipsoidal one, the
 * same computation `Location.distanceTo` does.
 */
class AndroidTripMeterFixes(private val trips: Lazy<TripRepository>) : TripMeterFixes {
    override fun currentFix(): PhoneLocation? = trips.value.currentLocation.value?.toPhoneLocation()

    override fun distanceMeters(from: PhoneLocation, to: PhoneLocation): Float {
        val out = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, out)
        return out[0]
    }
}
