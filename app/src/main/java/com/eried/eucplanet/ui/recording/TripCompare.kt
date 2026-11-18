package com.eried.eucplanet.ui.recording

import com.eried.eucplanet.ui.studio.formatReplayClock
import com.eried.eucplanet.ui.studio.parseReplayClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Comparing the open trip (A) with another one (B), as eucviewer's inspector does.
 *
 * Everything is on A's clock: B sits at `offset` ms into A, so B's own elapsed
 * time `tb` lands at `tb + offset`. A positive offset means B started later in
 * A's ride. B never joins A's point list; it is fitted onto A's samples so the
 * charts can draw it like any other overlay, index for index.
 *
 * Kept free of Compose and Android so it can be unit-tested directly.
 */
object TripCompare {

    /** A gap in B longer than this breaks B's line instead of bridging it. */
    const val MAX_GAP_MS = 5_000L

    /** Auto never trusts an overlap shorter than this many 1 s steps. */
    private const val MIN_OVERLAP_S = 10

    /**
     * B's value at each of A's sample times, NaN where B has nothing to say:
     * before B starts, after it ends, inside a gap, or where B itself is NaN.
     *
     * [aMs] and [bMs] are each trip's elapsed ms from its own first sample, in
     * order. Linear between B's two neighbouring samples.
     */
    fun fit(aMs: LongArray, bMs: LongArray, bValues: FloatArray, offsetMs: Long): FloatArray {
        val out = FloatArray(aMs.size) { Float.NaN }
        if (bMs.size < 2 || bValues.size != bMs.size) return out
        var j = 0
        for (i in aMs.indices) {
            val t = aMs[i] - offsetMs
            if (t < bMs.first() || t > bMs.last()) continue
            // A's times only grow, so B's cursor only moves forward.
            while (j < bMs.size - 2 && bMs[j + 1] < t) j++
            val t0 = bMs[j]
            val t1 = bMs[j + 1]
            val v0 = bValues[j]
            val v1 = bValues[j + 1]
            if (t1 - t0 > MAX_GAP_MS) {
                // Across a gap only a moment that lands on a real sample counts.
                out[i] = if (t == t0) v0 else if (t == t1) v1 else Float.NaN
                continue
            }
            out[i] = when {
                v0.isNaN() && v1.isNaN() -> Float.NaN
                v0.isNaN() -> v1
                v1.isNaN() -> v0
                t1 == t0 -> v0
                else -> v0 + (v1 - v0) * ((t - t0).toFloat() / (t1 - t0))
            }
        }
        return out
    }

    /**
     * Index into B of the sample nearest A's moment [aAtMs], or null when B
     * was not running then. Used for B's map marker.
     */
    fun nearestIndex(bMs: LongArray, aAtMs: Long, offsetMs: Long): Int? {
        if (bMs.isEmpty()) return null
        val t = aAtMs - offsetMs
        if (t < bMs.first() || t > bMs.last()) return null
        var lo = 0
        var hi = bMs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (bMs[mid] < t) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && t - bMs[lo - 1] < bMs[lo] - t) lo - 1 else lo
    }

    /**
     * The stretch of A's clock both trips cover, or null when they never
     * overlap. This is what Apply trims to.
     */
    fun overlapMs(aDurationMs: Long, bDurationMs: Long, offsetMs: Long): LongRange? {
        val start = max(0L, offsetMs)
        val end = min(aDurationMs, offsetMs + bDurationMs)
        return if (end > start) start..end else null
    }

    /**
     * The offset that best lines B up with A on one metric.
     *
     * Both trips go onto a 1 s grid (each second holds the last sample at or
     * before it). Every lag is scored by the correlation of the two stretches
     * that overlap there, times how much of the shorter trip that overlap is,
     * so two trips touching at their ends cannot win on a few lucky seconds.
     * Long rides are first searched in coarser steps, then every second
     * around the best one. Null when either trip is too short or flat.
     */
    fun autoOffsetMs(aMs: LongArray, aValues: FloatArray, bMs: LongArray, bValues: FloatArray): Long? {
        val a = grid(aMs, aValues)
        val b = grid(bMs, bValues)
        val minLen = min(a.size, b.size)
        if (minLen < MIN_OVERLAP_S || isFlat(a) || isFlat(b)) return null
        // lag = where B's first second lands on A's grid.
        val lo = -(0.9 * b.size).toInt()
        val hi = (0.9 * a.size).toInt()
        val pa = Prefix(a)
        val pb = Prefix(b)

        fun score(lag: Int): Double {
            val from = max(0, lag)
            val to = min(a.size, lag + b.size)
            val n = to - from
            if (n < MIN_OVERLAP_S) return Double.NEGATIVE_INFINITY
            val ma = pa.sum(from, to) / n
            val mb = pb.sum(from - lag, to - lag) / n
            val va = pa.sq(from, to) / n - ma * ma
            val vb = pb.sq(from - lag, to - lag) / n - mb * mb
            if (va < 1e-9 || vb < 1e-9) return Double.NEGATIVE_INFINITY
            var cross = 0.0
            for (i in from until to) cross += a[i] * b[i - lag]
            val r = (cross / n - ma * mb) / sqrt(va * vb)
            return r * min(1.0, n.toDouble() / minLen)
        }

        // About 1.5e8 multiply-adds at most, well under a second on a phone.
        val work = (hi - lo + 1).toDouble() * minLen
        val step = max(1, kotlin.math.ceil(work / MAX_WORK).toInt())
        var best = Double.NEGATIVE_INFINITY
        var bestLag = 0
        var lag = lo
        while (lag <= hi) {
            val s = score(lag)
            if (s > best) { best = s; bestLag = lag }
            lag += step
        }
        if (best == Double.NEGATIVE_INFINITY) return null
        if (step > 1) {
            val coarse = bestLag
            for (l in max(lo, coarse - step)..min(hi, coarse + step)) {
                val s = score(l)
                if (s > best) { best = s; bestLag = l }
            }
        }
        return bestLag * 1000L
    }

    private const val MAX_WORK = 1.5e8

    private fun isFlat(v: DoubleArray): Boolean {
        if (v.isEmpty()) return true
        val first = v[0]
        return v.all { abs(it - first) < 1e-9 }
    }

    /** Running sums so each lag's mean and variance cost nothing. */
    private class Prefix(v: DoubleArray) {
        private val s = DoubleArray(v.size + 1)
        private val q = DoubleArray(v.size + 1)
        init {
            for (i in v.indices) {
                s[i + 1] = s[i] + v[i]
                q[i + 1] = q[i] + v[i] * v[i]
            }
        }
        fun sum(from: Int, to: Int) = s[to] - s[from]
        fun sq(from: Int, to: Int) = q[to] - q[from]
    }

    /** Sample-and-hold onto whole seconds. NaN rows hold the previous value. */
    internal fun grid(ms: LongArray, values: FloatArray): DoubleArray {
        if (ms.isEmpty() || values.size != ms.size) return DoubleArray(0)
        val seconds = (ms.last() / 1000).toInt() + 1
        val out = DoubleArray(seconds)
        var j = 0
        var held = values.firstOrNull { !it.isNaN() }?.toDouble() ?: 0.0
        for (s in 0 until seconds) {
            val t = s * 1000L
            while (j < ms.size && ms[j] <= t) {
                if (!values[j].isNaN()) held = values[j].toDouble()
                j++
            }
            out[s] = held
        }
        return out
    }

    /** "+36:18", "-1:05", "+0:00". Minutes may pass 59, like the trim clock. */
    fun formatOffset(ms: Long): String =
        (if (ms < 0) "-" else "+") + formatReplayClock(abs(ms))

    /**
     * The inverse of [formatOffset], also taking plain seconds and H:MM:SS,
     * with or without a sign. Null when it isn't a clock.
     */
    fun parseOffset(text: String): Long? {
        val t = text.trim().replace('−', '-')
        if (t.isEmpty()) return null
        val negative = t.startsWith("-")
        val body = t.removePrefix("-").removePrefix("+")
        val ms = parseReplayClock(body) ?: return null
        return if (negative) -ms else ms
    }

    /**
     * What the offset field's number keyboard types, timer style like the trim
     * dialog: the last two digits are seconds, the rest minutes, so "3618" is
     * +36:18. A leading minus makes it negative. Seconds, or null.
     */
    fun parseTypedSeconds(raw: String): Int? {
        val negative = raw.trim().startsWith("-")
        val digits = raw.filter { it.isDigit() }.takeLast(6)
        if (digits.isEmpty()) return null
        val secs = digits.takeLast(2).toInt()
        val mins = digits.dropLast(2).ifEmpty { "0" }.toInt()
        val total = mins * 60 + secs
        return if (negative) -total else total
    }

    /** Seconds, rounded, for the offset stepper. */
    fun toSeconds(ms: Long): Long = (ms / 1000.0).roundToLong()
}
