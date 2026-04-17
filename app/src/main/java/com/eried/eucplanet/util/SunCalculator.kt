package com.eried.eucplanet.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Simple sunrise/sunset calculator using the NOAA solar equations.
 * Returns times as epoch millis for the given date and location.
 */
object SunCalculator {

    data class SunTimes(val sunriseMillis: Long, val sunsetMillis: Long)

    sealed interface SunResult {
        data class Normal(val sunriseMillis: Long, val sunsetMillis: Long) : SunResult
        object MidnightSun : SunResult
        object PolarNight : SunResult
    }

    /**
     * Calculate sunrise and sunset for today at the given lat/lon.
     * Returns null if the sun never rises or sets (polar regions).
     */
    fun calculate(latitude: Double, longitude: Double, timeZone: TimeZone = TimeZone.getDefault()): SunTimes? {
        return when (val r = calculateState(latitude, longitude, timeZone)) {
            is SunResult.Normal -> SunTimes(r.sunriseMillis, r.sunsetMillis)
            else -> null
        }
    }

    fun calculateState(latitude: Double, longitude: Double, timeZone: TimeZone = TimeZone.getDefault()): SunResult {
        val cal = Calendar.getInstance(timeZone)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)

        val n1 = floor(275.0 * month / 9.0)
        val n2 = floor((month + 9.0) / 12.0)
        val n3 = 1.0 + floor((year - 4.0 * floor(year / 4.0) + 2.0) / 3.0)
        val dayOfYear = n1 - n2 * n3 + day - 30

        val tzOffset = timeZone.getOffset(cal.timeInMillis) / 3600000.0

        val cosH = computeCosH(dayOfYear, latitude, longitude)
        if (cosH > 1) return SunResult.PolarNight
        if (cosH < -1) return SunResult.MidnightSun

        val sunrise = calcSunTime(dayOfYear, latitude, longitude, tzOffset, true) ?: return SunResult.PolarNight
        val sunset = calcSunTime(dayOfYear, latitude, longitude, tzOffset, false) ?: return SunResult.PolarNight

        val base = Calendar.getInstance(timeZone).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return SunResult.Normal(
            sunriseMillis = base + (sunrise * 3600000).toLong(),
            sunsetMillis = base + (sunset * 3600000).toLong()
        )
    }

    private fun computeCosH(dayOfYear: Double, latitude: Double, longitude: Double): Double {
        val zenith = 90.833
        val lngHour = longitude / 15.0
        val t = dayOfYear + (12.0 - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
        l = ((l % 360) + 360) % 360
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))
        return (cos(Math.toRadians(zenith)) - sinDec * sin(Math.toRadians(latitude))) /
                (cosDec * cos(Math.toRadians(latitude)))
    }

    private fun calcSunTime(
        dayOfYear: Double, latitude: Double, longitude: Double,
        tzOffset: Double, isSunrise: Boolean
    ): Double? {
        val zenith = 90.833 // official zenith for sunrise/sunset

        // Approximate time
        val lngHour = longitude / 15.0
        val t = if (isSunrise) {
            dayOfYear + (6.0 - lngHour) / 24.0
        } else {
            dayOfYear + (18.0 - lngHour) / 24.0
        }

        // Sun's mean anomaly
        val m = 0.9856 * t - 3.289

        // Sun's true longitude
        var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
        l = ((l % 360) + 360) % 360

        // Sun's right ascension
        var ra = Math.toDegrees(kotlin.math.atan(0.91764 * tan(Math.toRadians(l))))
        ra = ((ra % 360) + 360) % 360

        // RA in same quadrant as L
        val lQuadrant = floor(l / 90.0) * 90.0
        val raQuadrant = floor(ra / 90.0) * 90.0
        ra += lQuadrant - raQuadrant
        ra /= 15.0

        // Sun's declination
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))

        // Local hour angle
        val cosH = (cos(Math.toRadians(zenith)) - sinDec * sin(Math.toRadians(latitude))) /
                (cosDec * cos(Math.toRadians(latitude)))

        if (cosH > 1 || cosH < -1) return null // no sunrise/sunset

        val h = if (isSunrise) {
            360 - Math.toDegrees(acos(cosH))
        } else {
            Math.toDegrees(acos(cosH))
        }
        val hHours = h / 15.0

        // Local mean time
        val localMeanTime = hHours + ra - 0.06571 * t - 6.622

        // UTC
        var utc = localMeanTime - lngHour
        utc = ((utc % 24) + 24) % 24

        return utc + tzOffset
    }
}
