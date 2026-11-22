package com.eried.eucplanet.hud.protocol

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * The piece of the route the HUD map draws, small enough to ride along in
 * every state frame.
 *
 * A whole route can be tens of thousands of points and the state goes out
 * several times a second, so the phone sends only what a HUD map can show:
 * a little behind the rider and a few kilometres ahead, in full detail close
 * by and thinned out further on. The window is re-cut around the rider on
 * each frame, so the line never runs out as the ride goes on.
 *
 * Points travel as one short string (the common encoded-polyline form, five
 * decimals, about a metre), which costs four or five characters a point.
 * Coordinates are flat arrays, `[lat0, lng0, lat1, lng1, ...]`.
 */
object RouteWindow {
    /** Route kept behind the rider, so the line reaches under the marker. */
    const val BEHIND_M = 300.0
    /** Kept in full detail ahead: what the map shows at riding zoom. */
    const val NEAR_M = 1_200.0
    /** Kept thinned out ahead: what the map shows zoomed far out. */
    const val FAR_M = 10_000.0
    const val NEAR_MAX_POINTS = 70
    const val FAR_MAX_POINTS = 40

    /**
     * Cut [route] around the rider at ([lat], [lng]) and encode it. Empty
     * when there is no line to draw.
     */
    fun encodeAround(route: DoubleArray, lat: Double, lng: Double): String =
        encode(slice(route, lat, lng))

    /** The window of [route] around the rider, thinned to the point caps. */
    fun slice(route: DoubleArray, lat: Double, lng: Double): DoubleArray {
        val n = route.size / 2
        if (n < 2) return DoubleArray(0)

        var nearest = 0
        var best = Double.MAX_VALUE
        for (i in 0 until n) {
            val d = distM(lat, lng, route[2 * i], route[2 * i + 1])
            if (d < best) { best = d; nearest = i }
        }

        var first = nearest
        var behind = 0.0
        while (first > 0 && behind < BEHIND_M) {
            behind += segM(route, first - 1)
            first--
        }
        var nearEnd = nearest
        var ahead = 0.0
        while (nearEnd < n - 1 && ahead < NEAR_M) {
            ahead += segM(route, nearEnd)
            nearEnd++
        }
        var last = nearEnd
        while (last < n - 1 && ahead < FAR_M) {
            ahead += segM(route, last)
            last++
        }

        val near = thin(route, first, nearEnd, NEAR_MAX_POINTS, startTolM = 1.5)
        if (last == nearEnd) return near
        val far = thin(route, nearEnd, last, FAR_MAX_POINTS, startTolM = 10.0)
        // The far part starts on the point the near part ends on.
        return near + far.copyOfRange(2, far.size)
    }

    /**
     * Points [from]..[to] of [route] with the ones that add no shape taken
     * out: a point goes when it lies within a tolerance of the straight line
     * between its kept neighbours, so a straight road costs two points and a
     * corner stays a corner. The tolerance doubles until at most [maxPoints]
     * are left. Both ends always stay.
     */
    private fun thin(route: DoubleArray, from: Int, to: Int, maxPoints: Int, startTolM: Double): DoubleArray {
        var tol = startTolM
        while (true) {
            val keep = BooleanArray(to - from + 1)
            keep[0] = true
            keep[to - from] = true
            val stack = ArrayDeque<Int>()
            stack.addLast(from); stack.addLast(to)
            while (stack.isNotEmpty()) {
                val b = stack.removeLast()
                val a = stack.removeLast()
                var worst = -1
                var worstM = tol
                for (i in a + 1 until b) {
                    val d = offLineM(route, i, a, b)
                    if (d > worstM) { worstM = d; worst = i }
                }
                if (worst >= 0) {
                    keep[worst - from] = true
                    stack.addLast(a); stack.addLast(worst)
                    stack.addLast(worst); stack.addLast(b)
                }
            }
            val count = keep.count { it }
            if (count <= maxPoints || tol > 5_000.0) {
                val out = DoubleArray(count * 2)
                var j = 0
                for (i in from..to) if (keep[i - from]) {
                    out[j++] = route[2 * i]
                    out[j++] = route[2 * i + 1]
                }
                return out
            }
            tol *= 2.0
        }
    }

    /** How far point [i] lies from the straight line [a]..[b], in metres. */
    private fun offLineM(route: DoubleArray, i: Int, a: Int, b: Int): Double {
        val lat0 = route[2 * a]
        val k = cos(Math.toRadians(lat0)) * 111_195.0
        val bx = (route[2 * b + 1] - route[2 * a + 1]) * k
        val by = (route[2 * b] - lat0) * 111_195.0
        val px = (route[2 * i + 1] - route[2 * a + 1]) * k
        val py = (route[2 * i] - lat0) * 111_195.0
        val len2 = bx * bx + by * by
        if (len2 == 0.0) return hypot(px, py)
        val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by)
    }

    private fun segM(route: DoubleArray, i: Int): Double =
        distM(route[2 * i], route[2 * i + 1], route[2 * i + 2], route[2 * i + 3])

    /** Flat-earth distance in metres, plenty for points a few km apart. */
    private fun distM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val x = Math.toRadians(lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2.0))
        val y = Math.toRadians(lat2 - lat1)
        return hypot(x, y) * 6_371_000.0
    }

    /** Encoded-polyline form of flat [points], five decimals. */
    fun encode(points: DoubleArray): String {
        if (points.size < 4) return ""
        val sb = StringBuilder(points.size * 3)
        var prevLat = 0L
        var prevLng = 0L
        for (i in 0 until points.size / 2) {
            val lat = (points[2 * i] * 1e5).roundToLong()
            val lng = (points[2 * i + 1] * 1e5).roundToLong()
            put(sb, lat - prevLat)
            put(sb, lng - prevLng)
            prevLat = lat
            prevLng = lng
        }
        return sb.toString()
    }

    private fun put(sb: StringBuilder, delta: Long) {
        var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (v >= 0x20) {
            sb.append((((v and 0x1f) or 0x20) + 63).toInt().toChar())
            v = v shr 5
        }
        sb.append((v + 63).toInt().toChar())
    }

    /**
     * Flat points from [encoded]. Anything that does not read as a line,
     * a cut-off string included, gives an empty array: the HUD then draws
     * no route rather than a wrong one.
     */
    fun decode(encoded: String): DoubleArray {
        if (encoded.isEmpty()) return DoubleArray(0)
        val out = ArrayList<Double>(encoded.length / 2)
        var i = 0
        var lat = 0L
        var lng = 0L
        while (i < encoded.length) {
            for (axis in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    if (i >= encoded.length || shift > 60) return DoubleArray(0)
                    val b = encoded[i++].code - 63
                    if (b < 0 || b > 0x3f) return DoubleArray(0)
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (axis == 0) lat += delta else lng += delta
            }
            val la = lat / 1e5
            val lo = lng / 1e5
            if (la !in -90.0..90.0 || lo !in -180.0..180.0) return DoubleArray(0)
            out.add(la)
            out.add(lo)
        }
        return if (out.size >= 4) out.toDoubleArray() else DoubleArray(0)
    }
}
