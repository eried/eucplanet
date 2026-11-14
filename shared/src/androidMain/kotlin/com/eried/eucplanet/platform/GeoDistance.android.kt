package com.eried.eucplanet.platform

actual fun distanceAndBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): FloatArray =
    FloatArray(2).also { android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, it) }
