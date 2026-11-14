package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.nav.RouteSolver.End
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.random.Random
import com.eried.eucplanet.platform.System

/**
 * Optimize stop order: a GPX in whatever order its author saved it comes
 * back in the shortest riding order, under the rider's rules for where it
 * starts and ends.
 */
class RouteSolverTest {

    private fun points(n: Int, seed: Int) = Random(seed).let { r ->
        List(n) { GeoPoint(59.85 + r.nextDouble() * 0.2, 10.6 + r.nextDouble() * 0.4) }
    }

    /** Every order, for checking the solver against the true shortest. */
    private fun bruteForce(dist: Array<DoubleArray>, hasOrigin: Boolean, end: End): Double {
        val n = if (hasOrigin) dist.size - 1 else dist.size
        var best = Double.MAX_VALUE
        fun go(prefix: IntArray, left: List<Int>) {
            if (left.isEmpty()) { best = minOf(best, RouteSolver.cost(dist, hasOrigin, end, prefix)); return }
            for (k in left) go(prefix + k, left - k)
        }
        go(IntArray(0), (0 until n).toList())
        return best
    }

    private fun isPermutation(order: IntArray, n: Int) = order.sorted() == (0 until n).toList()

    @Test fun `stops along a line come back in line order`() {
        val line = List(6) { GeoPoint(60.0, 10.0 + it * 0.01) }
        val shuffled = listOf(3, 0, 5, 1, 4, 2).map { line[it] }
        val table = RoutingService.straightTable(shuffled)
        val order = RouteSolver.solve(table, hasOrigin = false, end = End.ANYWHERE)
        val solved = order.map { shuffled[it] }
        assertTrue(solved == line || solved == line.reversed())
    }

    @Test fun `the exact solver finds the true shortest under every rule`() {
        for (seed in 1..4) for (hasOrigin in listOf(false, true)) for (end in End.values()) {
            val table = RoutingService.straightTable(points(if (hasOrigin) 8 else 7, seed))
            val order = RouteSolver.solve(table, hasOrigin, end)
            val n = if (hasOrigin) table.size - 1 else table.size
            assertTrue(isPermutation(order, n))
            assertEquals(bruteForce(table, hasOrigin, end), RouteSolver.cost(table, hasOrigin, end, order), absoluteTolerance = 1e-6, message = "seed $seed origin $hasOrigin $end")
        }
    }

    @Test fun `a one-way table is priced in the direction it is ridden`() {
        // 0 -> 1 -> 2 is cheap, every other direction is dear.
        val table = arrayOf(
            doubleArrayOf(0.0, 1.0, 100.0),
            doubleArrayOf(100.0, 0.0, 1.0),
            doubleArrayOf(100.0, 100.0, 0.0),
        )
        assertContentEquals(intArrayOf(0, 1, 2), RouteSolver.solve(table, false, End.ANYWHERE))
    }

    @Test fun `with my position the route starts at the nearest end`() {
        val line = List(5) { GeoPoint(60.0, 10.0 + it * 0.01) }
        val me = GeoPoint(60.0, 10.05) // just past the last stop
        val table = RoutingService.straightTable(line + me)
        val order = RouteSolver.solve(table, hasOrigin = true, end = End.ANYWHERE)
        assertContentEquals(intArrayOf(4, 3, 2, 1, 0), order)
    }

    @Test fun `two stops and my position - the nearer one comes first`() {
        val far = GeoPoint(60.0, 10.10)
        val near = GeoPoint(60.0, 10.01)
        val me = GeoPoint(60.0, 10.0)
        val table = RoutingService.straightTable(listOf(far, near, me))
        assertContentEquals(intArrayOf(1, 0), RouteSolver.solve(table, hasOrigin = true, end = End.ANYWHERE))
        // Fixed end keeps the given last stop last, whatever it costs.
        assertContentEquals(intArrayOf(0, 1), RouteSolver.solve(table, hasOrigin = true, end = End.KEEP_LAST))
    }

    @Test fun `keep last stop leaves the last stop last`() {
        for (n in listOf(6, 20, 50)) {
            val table = RoutingService.straightTable(points(n, n))
            val order = RouteSolver.solve(table, false, End.KEEP_LAST)
            assertTrue(isPermutation(order, n))
            assertEquals(n - 1, order.last())
        }
    }

    @Test fun `the heuristic lands within a few percent of the true shortest`() {
        for (seed in 1..6) for (end in End.values()) {
            val table = RoutingService.straightTable(points(11, seed))
            val exact = RouteSolver.cost(table, false, end, RouteSolver.solve(table, false, end))
            val heur = RouteSolver.solve(table, false, end, exactUpTo = 0)
            assertTrue(isPermutation(heur, 11))
            val c = RouteSolver.cost(table, false, end, heur)
            assertTrue(c <= exact * 1.05, "seed $seed $end: $c vs $exact")
            if (end == End.KEEP_LAST) assertEquals(10, heur.last())
        }
    }

    @Test fun `the heuristic is never worse than the order it was given`() {
        for (seed in 1..5) {
            val table = RoutingService.straightTable(points(30, seed))
            val order = RouteSolver.solve(table, true, End.LOOP)
            val n = 29
            assertTrue(isPermutation(order, n))
            assertTrue(RouteSolver.cost(table, true, End.LOOP, order) <= RouteSolver.cost(table, true, End.LOOP, IntArray(n) { it }))
        }
    }

    @Test fun `fifty scrambled stops solve fast and much shorter`() {
        val pts = points(50, 42)
        val table = RoutingService.straightTable(pts)
        val t0 = System.nanoTime()
        val order = RouteSolver.solve(table, false, End.ANYWHERE)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(isPermutation(order, 50))
        val before = RouteSolver.cost(table, false, End.ANYWHERE, IntArray(50) { it })
        val after = RouteSolver.cost(table, false, End.ANYWHERE, order)
        assertTrue(after < before * 0.5, "before $before after $after")
        assertTrue(ms < 3000, "took $ms ms")
    }

    @Test fun `an OSRM table reads into metres - and an unroutable pair gets the padded straight line`() {
        val pts = listOf(GeoPoint(59.91, 10.75), GeoPoint(59.93, 10.80))
        val t = RoutingService.parseOsrmTable("""{"code":"Ok","distances":[[0,4549.4],[null,0]]}""", pts)
        assertNotNull(t)
        assertEquals(4549.4, t!![0][1], absoluteTolerance = 1e-9)
        assertTrue(t[1][0] > GeoMath.distanceM(pts[1], pts[0]))
        assertNull(RoutingService.parseOsrmTable("""{"code":"TooBig"}""", pts))
        assertNull(RoutingService.parseOsrmTable("not json", pts))
    }
}
