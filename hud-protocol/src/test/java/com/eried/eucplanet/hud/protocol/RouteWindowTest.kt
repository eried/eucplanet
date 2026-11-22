package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route piece the phone sends for the HUD map: it has to survive the
 * trip as a string, stay small however long the route is, and always hold
 * the part of the line the rider is on.
 */
class RouteWindowTest {

    /** A straight line north from (59.9, 10.7), one point every [stepM] metres. */
    private fun northLine(points: Int, stepM: Double): DoubleArray {
        val out = DoubleArray(points * 2)
        for (i in 0 until points) {
            out[2 * i] = 59.9 + i * stepM / 111_195.0
            out[2 * i + 1] = 10.7
        }
        return out
    }

    @Test fun `a line comes back the same to a metre`() {
        val line = doubleArrayOf(59.91234, 10.75678, 59.91300, 10.75700, -33.45001, -70.66002, 0.0, 0.0)
        val back = RouteWindow.decode(RouteWindow.encode(line))
        assertEquals(line.size, back.size)
        for (i in line.indices) assertEquals(line[i], back[i], 0.00001)
    }

    @Test fun `a known encoded line decodes to its published points`() {
        // The reference example of the encoded-polyline format.
        val pts = RouteWindow.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(listOf(38.5, -120.2, 40.7, -120.95, 43.252, -126.453), pts.toList())
    }

    @Test fun `no route and a one point route send nothing`() {
        assertEquals("", RouteWindow.encodeAround(DoubleArray(0), 59.9, 10.7))
        assertEquals("", RouteWindow.encodeAround(doubleArrayOf(59.9, 10.7), 59.9, 10.7))
    }

    @Test fun `a cut off or foreign string draws no route`() {
        val good = RouteWindow.encode(northLine(10, 20.0))
        assertEquals(0, RouteWindow.decode(good.dropLast(1) + "\u007f").size)
        assertEquals(0, RouteWindow.decode("not a route!").size)
        assertEquals(0, RouteWindow.decode("").size)
    }

    @Test fun `a long route stays small and covers the rider`() {
        // 200 km at 10 m a point, rider 50 km in.
        val route = northLine(20_000, 10.0)
        val riderLat = route[2 * 5_000]
        val w = RouteWindow.slice(route, riderLat, 10.7)
        val n = w.size / 2
        assertTrue("points $n", n <= RouteWindow.NEAR_MAX_POINTS + RouteWindow.FAR_MAX_POINTS)
        // Starts behind the rider and runs on ahead, in order.
        assertTrue(w[0] < riderLat)
        assertTrue(w[w.size - 2] > riderLat)
        for (i in 1 until n) assertTrue(w[2 * i] > w[2 * (i - 1)])
        val behindM = (riderLat - w[0]) * 111_195.0
        val aheadM = (w[w.size - 2] - riderLat) * 111_195.0
        assertEquals(RouteWindow.BEHIND_M, behindM, 15.0)
        assertEquals(RouteWindow.FAR_M, aheadM, 15.0)
        assertTrue(RouteWindow.encode(w).length < 600)
    }

    @Test fun `close by the line keeps its shape`() {
        // A right-angle corner 100 m ahead must still be a corner.
        val route = doubleArrayOf(
            59.9000, 10.7000,
            59.9009, 10.7000,
            59.9009, 10.7030,
        )
        val w = RouteWindow.slice(route, 59.9000, 10.7000)
        assertEquals(route.toList(), w.toList())
    }

    @Test fun `the end of the route is the end of the line`() {
        val route = northLine(50, 10.0)
        val w = RouteWindow.slice(route, route[2 * 45], 10.7)
        assertEquals(route[route.size - 2], w[w.size - 2], 1e-9)
    }
}
