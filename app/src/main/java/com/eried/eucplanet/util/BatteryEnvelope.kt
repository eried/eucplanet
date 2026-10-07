package com.eried.eucplanet.util

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A smoothed, low-resolution battery line that follows what the rider is
 * doing instead of the load-driven voltage sag. Raw battery % bounces (sags
 * under acceleration, recovers coasting or stopped) but the true charge only
 * trends one way: the envelope steps DOWN while riding, stays FLAT while
 * stopped, and steps UP on a sustained regen descent.
 *
 * Primary model (trips that carry a current column): coulomb counting
 * anchored to the real battery. The measured start-to-end battery drop is
 * warped along the cumulative charge curve, so the endpoints hit the real
 * battery exactly and the shape between them follows energy use. No absolute
 * pack capacity is estimated, and no monotonic clamp is needed - drive makes
 * the integral rise (envelope steps down), a stop holds it flat, regen dips
 * it (envelope steps up).
 *
 * Fallback model (battery-only logs): 30 s bucket medians walked with
 * hysteresis - steps down freely, holds through a single sag-recovery spike,
 * and climbs only when two consecutive buckets confirm a real rise.
 */
object BatteryEnvelope {

    /** Envelope resolution: one latched value per 30 s of ride. */
    const val BUCKET_S = 30f

    /** Two consecutive buckets must sit this far above the running value
     *  before the battery-only walk accepts a rise as real. */
    private const val RISE_HYSTERESIS = 1.5f

    /**
     * Per-sample envelope (%), rounded to 0.1. [tSec] is elapsed seconds per
     * sample, [battery] the raw battery %, [current] signed amps (positive =
     * drive, negative = regen; NaN = not recorded). Empty input gives an
     * empty result.
     */
    fun compute(tSec: FloatArray, battery: FloatArray, current: FloatArray): FloatArray {
        val n = tSec.size
        if (n == 0 || battery.size != n || current.size != n) return FloatArray(0)
        val hasCurrent = current.any { !it.isNaN() && it != 0f }
        if (hasCurrent) {
            val primary = coulombWarped(tSec, battery, current)
            if (primary != null) return primary
        }
        return batteryOnly(tSec, battery)
    }

    /** Null when the anchors or the integral are unusable; the caller then
     *  falls back to the battery-only walk. */
    private fun coulombWarped(tSec: FloatArray, battery: FloatArray, current: FloatArray): FloatArray? {
        val n = tSec.size
        val t0 = tSec[0]
        val tEnd = tSec[n - 1]
        // Endpoint anchors: median of the battery readings in the first and
        // last 30 s, so one noisy sample cannot skew them. Zero readings are
        // "no data yet" (connecting), not a measurement.
        val startWindow = ArrayList<Float>()
        val endWindow = ArrayList<Float>()
        for (i in 0 until n) {
            val b = battery[i]
            if (b <= 0f || b.isNaN()) continue
            if (tSec[i] - t0 <= BUCKET_S) startWindow.add(b)
            if (tEnd - tSec[i] <= BUCKET_S) endWindow.add(b)
        }
        if (startWindow.isEmpty() || endWindow.isEmpty()) return null
        val battStart = median(startWindow)
        val battEnd = median(endWindow)

        // Net charge, trapezoidal, amp-seconds. A NaN current sample (row
        // predating the column, or a blank cell) integrates as zero draw.
        val cum = FloatArray(n)
        for (i in 1 until n) {
            val cPrev = current[i - 1].takeIf { !it.isNaN() } ?: 0f
            val cHere = current[i].takeIf { !it.isNaN() } ?: 0f
            cum[i] = cum[i - 1] + (cPrev + cHere) / 2f * (tSec[i] - tSec[i - 1])
        }
        val total = cum[n - 1]
        // The warp divides by the net charge, so it needs that number to be
        // meaningfully positive. A trip whose regen nearly cancels its drive
        // leaves a tiny (or negative) total, and dividing by it amplifies the
        // intermediate swings into nonsense - the battery-only walk reads
        // better there.
        val maxAbs = cum.maxOf { abs(it) }
        if (total < 1f || total < 0.05f * maxAbs) return null

        // Warp the measured drop along the consumption curve, then latch one
        // value per 30 s bucket: each bucket holds its opening value.
        val out = FloatArray(n)
        var bucket = -1
        var latched = battStart
        for (i in 0 until n) {
            val k = ((tSec[i] - t0) / BUCKET_S).toInt()
            if (k > bucket) {
                bucket = k
                latched = battStart + (battEnd - battStart) * (cum[i] / total)
            }
            out[i] = round1(latched)
        }
        return out
    }

    private fun batteryOnly(tSec: FloatArray, battery: FloatArray): FloatArray {
        val n = tSec.size
        val t0 = tSec[0]
        // 30 s bucket medians of the valid readings.
        val byBucket = LinkedHashMap<Int, ArrayList<Float>>()
        val bucketOf = IntArray(n)
        for (i in 0 until n) {
            val k = ((tSec[i] - t0) / BUCKET_S).toInt()
            bucketOf[i] = k
            val b = battery[i]
            if (b > 0f && !b.isNaN()) byBucket.getOrPut(k) { ArrayList() }.add(b)
        }
        val medians = byBucket.mapValues { median(it.value) }
        val keys = medians.keys.sorted()
        if (keys.isEmpty()) return FloatArray(n)

        // Walk: down freely, up only when two consecutive buckets agree the
        // rise is real, otherwise hold - so a single sag-recovery spike
        // cannot drag the line up.
        val socOf = HashMap<Int, Float>()
        var soc = medians.getValue(keys.first())
        for ((idx, k) in keys.withIndex()) {
            val m = medians.getValue(k)
            val next = keys.getOrNull(idx + 1)?.let { medians.getValue(it) }
            soc = when {
                m < soc -> m
                m > soc + RISE_HYSTERESIS && next != null && next > soc + RISE_HYSTERESIS -> m
                else -> soc
            }
            socOf[k] = soc
        }
        // Buckets with no valid reading carry the previous bucket's value.
        val out = FloatArray(n)
        var last = medians.getValue(keys.first())
        var maxSeen = -1
        for (i in 0 until n) {
            val k = bucketOf[i]
            if (k > maxSeen) {
                maxSeen = k
                socOf[k]?.let { last = it }
            }
            out[i] = round1(socOf[k] ?: last)
        }
        return out
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2f
    }

    private fun round1(v: Float): Float = (v * 10f).roundToInt() / 10f
}
