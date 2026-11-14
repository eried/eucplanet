package com.eried.eucplanet.platform

/**
 * The distance in metres and the initial bearing in degrees from
 * ([lat1], [lng1]) to ([lat2], [lng2]) on the WGS84 ellipsoid, as
 * `android.location.Location.distanceTo` and `bearingTo` give them:
 * `[distance, bearing]`. Android asks `Location.distanceBetween` itself.
 */
expect fun distanceAndBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): FloatArray
