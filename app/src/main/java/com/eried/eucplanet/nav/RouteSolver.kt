package com.eried.eucplanet.nav

/**
 * Puts a route's stops in the shortest riding order.
 *
 * A GPX from somewhere else comes in whatever order its author saved it,
 * which is rarely the order you would ride it. This takes a distance table
 * and returns the order with the smallest total.
 *
 * Up to [EXACT_MAX] stops it tries every order (Held-Karp), so the answer is
 * the shortest there is. Above that it builds a route from several starts and
 * improves each one until no single move helps: reversing a stretch (2-opt,
 * which removes crossings) and moving a run of 1 to 3 stops elsewhere (Or-opt).
 * For the 50 stops a route can hold that lands within a few percent of the
 * shortest in well under a second.
 *
 * Distances may be one-way (a road table is not symmetric), so every move is
 * priced by recomputing the whole route, never by a symmetric shortcut.
 */
object RouteSolver {

    enum class End {
        /** The route stops at whichever stop is last; the shortest one-way path. */
        ANYWHERE,
        /** The route comes back to where it started. */
        LOOP,
        /** The current last stop stays last, the rest are reordered. */
        KEEP_LAST,
    }

    const val EXACT_MAX = 13
    private const val INF = Double.MAX_VALUE / 4
    private const val HEURISTIC_STARTS = 8

    /**
     * [dist] covers the stops, plus the rider as the last row and column when
     * [hasOrigin]. Returns the stops' indices in riding order, a permutation of
     * 0 until stop count. [exactUpTo] is for tests, to run the heuristic on
     * a size the exact solver can check.
     */
    fun solve(dist: Array<DoubleArray>, hasOrigin: Boolean, end: End, exactUpTo: Int = EXACT_MAX): IntArray {
        val p = Problem(dist, hasOrigin, end)
        if (p.n <= 2) return trivial(p)
        return if (p.n <= exactUpTo) exact(p) else heuristic(p)
    }

    /** Total length of [order] under the same rules [solve] uses. */
    fun cost(dist: Array<DoubleArray>, hasOrigin: Boolean, end: End, order: IntArray): Double =
        Problem(dist, hasOrigin, end).cost(order)

    /**
     * The route as a chain over the stops: [start] is what it costs to begin
     * at a stop, [finish] what it costs to end at one. Every rule (origin,
     * loop, fixed last stop) is one of those two vectors, so the search
     * itself never needs to know which rule it is solving.
     */
    private class Problem(val dist: Array<DoubleArray>, hasOrigin: Boolean, end: End) {
        val n = if (hasOrigin) dist.size - 1 else dist.size
        val start = DoubleArray(n)
        val finish = DoubleArray(n)
        /** The stop that must stay last, or -1. */
        val fixedLast = if (end == End.KEEP_LAST) n - 1 else -1

        init {
            val o = n // the origin's row, when there is one
            for (j in 0 until n) {
                start[j] = when {
                    hasOrigin -> dist[o][j]
                    // A loop costs the same from any stop, so pin the first
                    // one rather than search n rotations of each order.
                    end == End.LOOP -> if (j == 0) 0.0 else INF
                    else -> 0.0
                }
                finish[j] = when (end) {
                    End.ANYWHERE -> 0.0
                    End.LOOP -> if (hasOrigin) dist[j][o] else dist[j][0]
                    End.KEEP_LAST -> if (j == n - 1) 0.0 else INF
                }
            }
            // A fixed last stop may not also be the first when there are others.
            if (end == End.KEEP_LAST && n > 1) start[n - 1] = INF
        }

        fun cost(order: IntArray): Double {
            if (order.isEmpty()) return 0.0
            var c = start[order[0]]
            for (i in 1 until order.size) c += dist[order[i - 1]][order[i]]
            return c + finish[order[order.size - 1]]
        }
    }

    private fun trivial(p: Problem): IntArray {
        val a = IntArray(p.n) { it }
        val b = a.reversedArray()
        return if (p.cost(b) < p.cost(a)) b else a
    }

    /** Held-Karp: the shortest chain over every subset, O(n^2 2^n). */
    private fun exact(p: Problem): IntArray {
        val n = p.n
        val full = (1 shl n) - 1
        val dp = Array(1 shl n) { DoubleArray(n) { INF } }
        val parent = Array(1 shl n) { IntArray(n) { -1 } }
        for (j in 0 until n) dp[1 shl j][j] = p.start[j]
        for (mask in 1..full) {
            for (j in 0 until n) {
                val here = dp[mask][j]
                if (here >= INF || mask and (1 shl j) == 0) continue
                for (k in 0 until n) {
                    if (mask and (1 shl k) != 0) continue
                    val next = mask or (1 shl k)
                    val c = here + p.dist[j][k]
                    if (c < dp[next][k]) { dp[next][k] = c; parent[next][k] = j }
                }
            }
        }
        var last = 0
        var best = INF
        for (j in 0 until n) {
            val c = dp[full][j] + p.finish[j]
            if (c < best) { best = c; last = j }
        }
        val order = IntArray(n)
        var mask = full
        var j = last
        for (i in n - 1 downTo 0) {
            order[i] = j
            val pj = parent[mask][j]
            mask = mask xor (1 shl j)
            j = pj
        }
        return order
    }

    private fun heuristic(p: Problem): IntArray {
        val n = p.n
        // Starts: the current order, then nearest-neighbour chains from the
        // cheapest first stops. Seeding with the current order means the
        // answer is never worse than what the rider already has.
        val firsts = (0 until n).filter { p.start[it] < INF }.sortedBy { p.start[it] }
        val seeds = mutableListOf(IntArray(n) { it })
        for (f in firsts.take(HEURISTIC_STARTS)) seeds += nearestNeighbour(p, f)
        var best = seeds[0]
        var bestCost = INF
        for (s in seeds) {
            val improved = improve(p, s)
            val c = p.cost(improved)
            if (c < bestCost) { bestCost = c; best = improved }
        }
        return best
    }

    private fun nearestNeighbour(p: Problem, first: Int): IntArray {
        val n = p.n
        val used = BooleanArray(n)
        val order = IntArray(n)
        order[0] = first; used[first] = true
        for (i in 1 until n) {
            val from = order[i - 1]
            var pick = -1
            for (k in 0 until n) {
                if (used[k]) continue
                // A fixed last stop waits until it is the only one left.
                if (k == p.fixedLast && i < n - 1) continue
                if (pick < 0 || p.dist[from][k] < p.dist[from][pick]) pick = k
            }
            order[i] = pick; used[pick] = true
        }
        return order
    }

    /** 2-opt and Or-opt until neither finds a shorter route. */
    private fun improve(p: Problem, seed: IntArray): IntArray {
        var order = seed.copyOf()
        var cur = p.cost(order)
        val n = order.size
        var changed = true
        while (changed) {
            changed = false
            // 2-opt: reverse order[i..k].
            for (i in 0 until n - 1) {
                for (k in i + 1 until n) {
                    val cand = order.copyOf()
                    var a = i; var b = k
                    while (a < b) { val t = cand[a]; cand[a] = cand[b]; cand[b] = t; a++; b-- }
                    val c = p.cost(cand)
                    if (c < cur - 1e-6) { order = cand; cur = c; changed = true }
                }
            }
            // Or-opt: move a run of 1..3 stops to another position.
            for (len in 1..3) {
                if (len >= n) break
                for (i in 0..n - len) {
                    val run = order.copyOfRange(i, i + len)
                    val rest = order.filterIndexed { idx, _ -> idx < i || idx >= i + len }
                    for (at in 0..rest.size) {
                        if (at == i) continue
                        val cand = IntArray(n)
                        var w = 0
                        for (x in 0 until at) cand[w++] = rest[x]
                        for (r in run) cand[w++] = r
                        for (x in at until rest.size) cand[w++] = rest[x]
                        val c = p.cost(cand)
                        if (c < cur - 1e-6) { order = cand; cur = c; changed = true; break }
                    }
                    if (changed) break
                }
                if (changed) break
            }
        }
        return order
    }
}
