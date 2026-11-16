package btools.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stopped run ends while it reads segments to match its stops, not only
 * in its search: on a slow phone the matching alone outlasted the app's time
 * limit, and the next route waited for it. Needs the segment (see
 * [RouteCases]); skips without it.
 */
class TerminateTest {

    private fun engine(dir: String): RoutingEngine {
        val c = RouteCases.cases(1).first()
        val rc = RoutingContext()
        rc.localFunction = RouteCases.profilePath("wheel")
        rc.keyValues = hashMapOf("turnInstructionMode" to "9")
        rc.memoryclass = 64
        val wps = c.points.mapIndexed { i, p ->
            val n = OsmNodeNamed()
            n.name = "via$i"
            n.ilon = RouteCases.toILon(p.second)
            n.ilat = RouteCases.toILat(p.first)
            n
        }.toMutableList()
        return RoutingEngine(null, null, dir, wps, rc).also { it.quite = true }
    }

    @Test
    fun aTerminatedRun_stopsWhileReadingSegments() {
        val dir = RouteCases.segmentDir()
        if (dir == null) {
            println(RouteCases.skipMessage())
            return
        }
        val e = engine(dir)
        e.terminate()
        e.doRun(RouteCases.MAX_MS)
        // BRouter keeps an empty track when it finds none.
        assertEquals(0, e.foundTrack?.nodes?.size ?: 0)
        assertTrue(e.errorMessage.orEmpty().contains("reading segments"), "error: ${e.errorMessage}")
    }

    @Test
    fun anUnstoppedRun_stillRoutes() {
        val dir = RouteCases.segmentDir() ?: return
        val e = engine(dir)
        e.doRun(RouteCases.MAX_MS)
        assertEquals(null, e.errorMessage)
        assertTrue((e.foundTrack?.nodes?.size ?: 0) >= 2)
    }
}
