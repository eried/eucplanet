package com.eried.eucplanet.hud.protocol

import kotlin.math.pow
import kotlin.math.round

/** Shared WGS84 Web Mercator math for raster tile coordinates. */
object WebMercator {
    private const val MAX_LATITUDE = 85.05112878

    private fun worldSize(zoom: Int): Double = 2.0.pow(zoom.coerceIn(0, 30).toDouble())

    /** Unwrapped fractional XYZ tile X coordinate. */
    fun tileX(lon: Double, zoom: Int): Double =
        (lon + 180.0) / 360.0 * worldSize(zoom)

    /** Clamped fractional XYZ tile Y coordinate in [0, 2^zoom). */
    fun tileY(lat: Double, zoom: Int): Double {
        val n = worldSize(zoom)
        val clampedLat = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val sine = kotlin.math.sin(Math.toRadians(clampedLat))
        val y = (0.5 - kotlin.math.ln((1.0 + sine) / (1.0 - sine)) / (4.0 * Math.PI)) * n
        return y.coerceIn(0.0, Math.nextDown(n))
    }

    /** Positive modulo for an integer tile X coordinate. */
    fun wrapX(x: Int, zoom: Int): Int {
        val width = worldSize(zoom).toInt()
        return ((x % width) + width) % width
    }

    /** Shift [x] by whole worlds so it is nearest to [centerX]. */
    fun nearestWorldX(x: Double, centerX: Double, zoom: Int): Double {
        val n = worldSize(zoom)
        return x + round((centerX - x) / n) * n
    }
}
