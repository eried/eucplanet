package com.eried.eucplanet.weather

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Just enough solar math for the ridability score: the sun's elevation at a
 * time and place (simplified Meeus, a couple of arc-minutes of error, which
 * is nothing against hourly forecast buckets), and the "golden hour" test
 * the rider preferences use - the low-sun window around sunrise and sunset.
 */
object SunCalc {

    fun elevationDeg(timeMs: Long, lat: Double, lon: Double): Double {
        val d = timeMs / 86400000.0 - 10957.5  // days since J2000.0
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
        val q = (280.459 + 0.98564736 * d) % 360.0
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmstHours = (18.697374558 + 24.06570982441908 * d) % 24.0
        val lstRad = Math.toRadians(((gmstHours + lon / 15.0) % 24.0) * 15.0)
        val ha = lstRad - ra
        val latR = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(latR) * sin(dec) + cos(latR) * cos(dec) * cos(ha)))
    }

    /** The golden window: sun between -4 and +8 degrees - sunrise and sunset
     *  light, hourly samples catch one or two hours at each transition. */
    fun isGolden(timeMs: Long, lat: Double, lon: Double): Boolean {
        val el = elevationDeg(timeMs, lat, lon)
        return el > -4.0 && el < 8.0
    }
}
