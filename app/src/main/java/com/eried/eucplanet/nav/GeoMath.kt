package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.ArrowDir
import com.eried.eucplanet.data.model.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical-geometry helpers for the Navigator. Distances use the haversine
 * formula; segment math uses a local equirectangular projection, which is
 * accurate to well under a meter over the short hops of a route polyline and
 * far cheaper than projecting every point onto the sphere.
 */
object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG = Math.PI / 180.0

    /** Great-circle distance between two points, in meters. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat) * DEG
        val dLng = (b.lng - a.lng) * DEG
        val la1 = a.lat * DEG
        val la2 = b.lat * DEG
        val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(la1) * cos(la2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * Initial bearing travelling from [a] to [b], degrees in 0..360
     * (0 = north, 90 = east).
     */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val la1 = a.lat * DEG
        val la2 = b.lat * DEG
        val dLng = (b.lng - a.lng) * DEG
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Signed difference `target - reference`, normalised to -180..180.
     * Positive means [target] is clockwise of [reference] — i.e. to the right.
     */
    fun relativeBearing(reference: Double, target: Double): Double {
        var d = (target - reference + 540.0) % 360.0 - 180.0
        if (d == -180.0) d = 180.0
        return d
    }

    /** Nearest-point result for [nearestOnPolyline]. */
    data class PolylineHit(
        /** Index of the polyline segment the nearest point falls on. */
        val segmentIndex: Int,
        /** Perpendicular distance from the query point to the polyline, meters. */
        val distanceM: Double,
        /** Cumulative distance from the polyline start to the nearest point, meters. */
        val alongM: Double
    )

    /**
     * Projects [p] onto a polyline. Returns which segment it lands on, how far
     * off the line it is, and how far along the line that nearest point sits —
     * the off-route check and the "distance to next turn" math both need this.
     */
    fun nearestOnPolyline(p: GeoPoint, line: List<GeoPoint>): PolylineHit? {
        if (line.size < 2) return null
        // Local planar projection centred on the query point.
        val lat0 = p.lat * DEG
        val mPerLng = EARTH_RADIUS_M * DEG * cos(lat0)
        val mPerLat = EARTH_RADIUS_M * DEG
        fun x(g: GeoPoint) = (g.lng - p.lng) * mPerLng
        fun y(g: GeoPoint) = (g.lat - p.lat) * mPerLat

        var best = PolylineHit(0, Double.MAX_VALUE, 0.0)
        var cumulative = 0.0
        for (i in 0 until line.size - 1) {
            val ax = x(line[i]); val ay = y(line[i])
            val bx = x(line[i + 1]); val by = y(line[i + 1])
            val dx = bx - ax; val dy = by - ay
            val segLen = sqrt(dx * dx + dy * dy)
            val t = if (segLen < 1e-9) 0.0
            else (((0.0 - ax) * dx + (0.0 - ay) * dy) / (segLen * segLen)).coerceIn(0.0, 1.0)
            val cx = ax + t * dx; val cy = ay + t * dy
            val dist = sqrt(cx * cx + cy * cy)
            // Accumulate the along-track distance from haversine segment lengths
            // so alongM stays consistent with polylineLengthM and the route
            // total — the planar projection is used only for the perpendicular
            // hit test, where its sub-meter error is harmless.
            val segHav = distanceM(line[i], line[i + 1])
            if (dist < best.distanceM) {
                best = PolylineHit(i, dist, cumulative + t * segHav)
            }
            cumulative += segHav
        }
        return best
    }

    /** Total length of a polyline, in meters. */
    fun polylineLengthM(line: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 0 until line.size - 1) total += distanceM(line[i], line[i + 1])
        return total
    }

    /**
     * Classifies a heading-relative bearing (-180..180, positive = right) into
     * the arrow glyph the popup should show. Used by Treasure Hunt, where the
     * "turn" is just the direction of the goal relative to travel.
     */
    fun arrowFor(relBearingDeg: Double): ArrowDir {
        val b = relBearingDeg
        val a = abs(b)
        return when {
            a <= 22.0 -> ArrowDir.STRAIGHT
            a >= 160.0 -> ArrowDir.REVERSE
            b > 0 -> when {
                a <= 60.0 -> ArrowDir.SLIGHT_RIGHT
                a <= 125.0 -> ArrowDir.RIGHT
                else -> ArrowDir.SHARP_RIGHT
            }
            else -> when {
                a <= 60.0 -> ArrowDir.SLIGHT_LEFT
                a <= 125.0 -> ArrowDir.LEFT
                else -> ArrowDir.SHARP_LEFT
            }
        }
    }
}
