package com.eried.eucplanet.platform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Vincenty's inverse formula on WGS84, the method Android's Location uses. */
actual fun distanceAndBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): FloatArray {
    val maxIters = 20
    val phi1 = lat1 * PI / 180.0
    val phi2 = lat2 * PI / 180.0
    val a = 6378137.0
    val b = 6356752.3142
    val f = (a - b) / a
    val aSqMinusBSqOverBSq = (a * a - b * b) / (b * b)
    val l = (lng2 - lng1) * PI / 180.0
    var aA = 0.0
    val u1 = atan((1.0 - f) * tan(phi1))
    val u2 = atan((1.0 - f) * tan(phi2))
    val cosU1 = cos(u1)
    val cosU2 = cos(u2)
    val sinU1 = sin(u1)
    val sinU2 = sin(u2)
    val cosU1cosU2 = cosU1 * cosU2
    val sinU1sinU2 = sinU1 * sinU2
    var sigma = 0.0
    var deltaSigma = 0.0
    var cosLambda = 0.0
    var sinLambda = 0.0
    var lambda = l
    for (iter in 0 until maxIters) {
        val lambdaOrig = lambda
        cosLambda = cos(lambda)
        sinLambda = sin(lambda)
        val t1 = cosU2 * sinLambda
        val t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
        val sinSigma = sqrt(t1 * t1 + t2 * t2)
        val cosSigma = sinU1sinU2 + cosU1cosU2 * cosLambda
        sigma = atan2(sinSigma, cosSigma)
        val sinAlpha = if (sinSigma == 0.0) 0.0 else cosU1cosU2 * sinLambda / sinSigma
        val cosSqAlpha = 1.0 - sinAlpha * sinAlpha
        val cos2SM = if (cosSqAlpha == 0.0) 0.0 else cosSigma - 2.0 * sinU1sinU2 / cosSqAlpha
        val uSquared = cosSqAlpha * aSqMinusBSqOverBSq
        aA = 1 + (uSquared / 16384.0) * (4096.0 + uSquared * (-768 + uSquared * (320.0 - 175.0 * uSquared)))
        val bB = (uSquared / 1024.0) * (256.0 + uSquared * (-128.0 + uSquared * (74.0 - 47.0 * uSquared)))
        val cC = (f / 16.0) * cosSqAlpha * (4.0 + f * (4.0 - 3.0 * cosSqAlpha))
        val cos2SMSq = cos2SM * cos2SM
        deltaSigma = bB * sinSigma * (cos2SM + (bB / 4.0) * (cosSigma * (-1.0 + 2.0 * cos2SMSq) -
            (bB / 6.0) * cos2SM * (-3.0 + 4.0 * sinSigma * sinSigma) * (-3.0 + 4.0 * cos2SMSq)))
        lambda = l + (1.0 - cC) * f * sinAlpha *
            (sigma + cC * sinSigma * (cos2SM + cC * cosSigma * (-1.0 + 2.0 * cos2SM * cos2SM)))
        val delta = (lambda - lambdaOrig) / lambda
        if (abs(delta) < 1.0e-12) break
    }
    val distance = (b * aA * (sigma - deltaSigma)).toFloat()
    val bearing = (atan2(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda) * 180.0 / PI).toFloat()
    return floatArrayOf(distance, bearing)
}
