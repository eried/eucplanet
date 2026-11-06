package com.eried.eucplanet.service

import com.eried.eucplanet.platform.Locale
import com.eried.eucplanet.platform.format
import kotlin.math.roundToInt
import kotlin.math.sign

// The speed curves the volume, media rate and engine sound follow, and the
// editors that draw them.

/**
 * Ceiling for the auto-volume boost multiplier. Single source of truth shared by the
 * curve editor's Y axis and [pchipInterpolate]'s output clamp, so the draggable range
 * and the applied boost can never drift apart.
 */
const val AUTO_VOLUME_MAX_MULTIPLIER = 3f

/** Curve format: "speed:multiplier,speed:multiplier,..." e.g. "0:0,25:0.6,50:1.2,75:2.0". */
fun parseVolumeCurve(raw: String): List<Pair<Float, Float>> {
    return raw.split(",").mapNotNull { pair ->
        val parts = pair.trim().split(":")
        if (parts.size == 2) {
            val speed = parts[0].toFloatOrNull()
            val mult = parts[1].toFloatOrNull()
            if (speed != null && mult != null) speed to mult else null
        } else null
    }.sortedBy { it.first }
}

fun encodeVolumeCurve(points: List<Pair<Float, Float>>): String {
    return points.sortedBy { it.first }
        .joinToString(",") { (s, m) -> "${s.roundToInt()}:${"%.2f".format(Locale.US, m)}" }
}

/**
 * PCHIP (Piecewise Cubic Hermite Interpolating Polynomial, Fritsch–Carlson 1980).
 * Smooth like a spline but never overshoots between monotonic control points.
 * Returns multiplier value at the given speed; clamps to [0, AUTO_VOLUME_MAX_MULTIPLIER].
 */
fun pchipInterpolate(points: List<Pair<Float, Float>>, x: Float): Float {
    if (points.isEmpty()) return 0f
    if (points.size == 1) return points[0].second.coerceIn(0f, AUTO_VOLUME_MAX_MULTIPLIER)
    val sorted = points.sortedBy { it.first }
    if (x <= sorted.first().first) return sorted.first().second.coerceIn(0f, AUTO_VOLUME_MAX_MULTIPLIER)
    if (x >= sorted.last().first) return sorted.last().second.coerceIn(0f, AUTO_VOLUME_MAX_MULTIPLIER)

    val n = sorted.size
    // Slopes of each segment
    val h = FloatArray(n - 1)
    val d = FloatArray(n - 1)
    for (i in 0 until n - 1) {
        h[i] = sorted[i + 1].first - sorted[i].first
        d[i] = (sorted[i + 1].second - sorted[i].second) / h[i]
    }

    // Tangents at each control point (Fritsch–Carlson)
    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) {
        m[i] = if (d[i - 1].sign != d[i].sign || d[i - 1] == 0f || d[i] == 0f) {
            0f
        } else {
            (d[i - 1] + d[i]) / 2f
        }
    }
    // Monotonicity-preserving tangent clipping
    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
        } else {
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
    }

    // Find segment
    var k = 0
    for (i in 0 until n - 1) {
        if (x >= sorted[i].first && x <= sorted[i + 1].first) {
            k = i
            break
        }
    }
    val hk = h[k]
    val t = (x - sorted[k].first) / hk
    val t2 = t * t
    val t3 = t2 * t
    val h00 = 2f * t3 - 3f * t2 + 1f
    val h10 = t3 - 2f * t2 + t
    val h01 = -2f * t3 + 3f * t2
    val h11 = t3 - t2
    val y = h00 * sorted[k].second +
            h10 * hk * m[k] +
            h01 * sorted[k + 1].second +
            h11 * hk * m[k + 1]
    return y.coerceIn(0f, AUTO_VOLUME_MAX_MULTIPLIER)
}
