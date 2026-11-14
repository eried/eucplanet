package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint

/**
 * Decides whether the rider's current position is close enough to the first
 * stop to be used as the route origin. Past [maxKm] the Route Builder hides the
 * you-to-first-stop line and refuses to start navigation, while still letting
 * the rider plan stop-to-stop.
 */
object NavStartGuard {
    fun originWithinStart(rider: GeoPoint, firstStop: GeoPoint, maxKm: Int): Boolean =
        GeoMath.distanceM(rider, firstStop) <= maxKm * 1000.0
}
