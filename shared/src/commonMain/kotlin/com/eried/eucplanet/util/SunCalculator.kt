package com.eried.eucplanet.util

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise/sunset calculator using the NOAA solar equations — a faithful port of
 * the Android app's `SunCalculator`, with the JVM `Calendar`/`TimeZone` bits
 * moved behind [currentDateInfo] and `Math.toRadians/toDegrees` inlined so the
 * math is fully shared (Android + iOS produce identical times).
 */
object SunCalculator {

    sealed interface SunResult {
        data class Normal(val sunriseMillis: Long, val sunsetMillis: Long) : SunResult
        data object MidnightSun : SunResult
        data object PolarNight : SunResult
    }

    private fun rad(deg: Double) = deg * PI / 180.0
    private fun deg(rad: Double) = rad * 180.0 / PI

    fun calculateState(latitude: Double, longitude: Double, date: DateInfo = currentDateInfo()): SunResult {
        val year = date.year
        val month = date.month
        val day = date.day

        val n1 = floor(275.0 * month / 9.0)
        val n2 = floor((month + 9.0) / 12.0)
        val n3 = 1.0 + floor((year - 4.0 * floor(year / 4.0) + 2.0) / 3.0)
        val dayOfYear = n1 - n2 * n3 + day - 30

        val tzOffset = date.tzOffsetHours

        val cosH = computeCosH(dayOfYear, latitude, longitude)
        if (cosH > 1) return SunResult.PolarNight
        if (cosH < -1) return SunResult.MidnightSun

        val sunrise = calcSunTime(dayOfYear, latitude, longitude, tzOffset, true) ?: return SunResult.PolarNight
        val sunset = calcSunTime(dayOfYear, latitude, longitude, tzOffset, false) ?: return SunResult.PolarNight

        val base = date.localMidnightMillis
        return SunResult.Normal(
            sunriseMillis = base + (sunrise * 3600000).toLong(),
            sunsetMillis = base + (sunset * 3600000).toLong(),
        )
    }

    private fun computeCosH(dayOfYear: Double, latitude: Double, longitude: Double): Double {
        val zenith = 90.833
        val lngHour = longitude / 15.0
        val t = dayOfYear + (12.0 - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634
        l = ((l % 360) + 360) % 360
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        return (cos(rad(zenith)) - sinDec * sin(rad(latitude))) / (cosDec * cos(rad(latitude)))
    }

    private fun calcSunTime(
        dayOfYear: Double, latitude: Double, longitude: Double,
        tzOffset: Double, isSunrise: Boolean,
    ): Double? {
        val zenith = 90.833
        val lngHour = longitude / 15.0
        val t = if (isSunrise) dayOfYear + (6.0 - lngHour) / 24.0 else dayOfYear + (18.0 - lngHour) / 24.0
        val m = 0.9856 * t - 3.289

        var l = m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634
        l = ((l % 360) + 360) % 360

        var ra = deg(atan(0.91764 * tan(rad(l))))
        ra = ((ra % 360) + 360) % 360

        val lQuadrant = floor(l / 90.0) * 90.0
        val raQuadrant = floor(ra / 90.0) * 90.0
        ra += lQuadrant - raQuadrant
        ra /= 15.0

        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))

        val cosH = (cos(rad(zenith)) - sinDec * sin(rad(latitude))) / (cosDec * cos(rad(latitude)))
        if (cosH > 1 || cosH < -1) return null

        val h = if (isSunrise) 360 - deg(acos(cosH)) else deg(acos(cosH))
        val hHours = h / 15.0

        val localMeanTime = hHours + ra - 0.06571 * t - 6.622
        var utc = localMeanTime - lngHour
        utc = ((utc % 24) + 24) % 24
        return utc + tzOffset
    }
}
