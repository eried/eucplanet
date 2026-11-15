package btools.router

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Kotlin BRouter routes exactly like the upstream Java it was converted
 * from: the same track points, elevations, distance, ascend, cost and turn
 * hints, or the same error, on a few hundred routes through each of the app's
 * profiles and switches. Also writes the Java's answers for the first
 * [IOS_CASES] routes, which the iOS run (RouteGoldenTest) checks the Kotlin/Native
 * build against, and prints the time each copy took.
 *
 * Needs the segment (see [RouteCases]); skips without it. BROUTER_CASES sets
 * how many routes (default 300).
 */
class RouteEquivalenceTest {

    @Test
    fun kotlinRoutesLikeTheJavaReference() {
        val dir = RouteCases.segmentDir()
        if (dir == null) println(RouteCases.skipMessage())
        assumeTrue(RouteCases.skipMessage(), dir != null)
        dir!!
        val n = System.getenv("BROUTER_CASES")?.toIntOrNull() ?: 300
        val cases = RouteCases.cases(n)

        // Warm both up on routes that are not timed, so the JIT does not count.
        for (c in RouteCases.cases(6, seed = 7)) {
            routeJava(c, dir)
            RouteCases.routeKotlin(c, dir)
        }

        var javaMs = 0L
        var kotlinMs = 0L
        var routed = 0
        val mismatches = ArrayList<String>()
        val golden = StringBuilder().append(RouteCases.segmentStamp(dir)).append('\n')
        for (c in cases) {
            // Alternate which copy goes first, so disk caches favour neither.
            val (j, k) = if (c.id % 2 == 0) {
                val j = routeJava(c, dir)
                j to RouteCases.routeKotlin(c, dir)
            } else {
                val k = RouteCases.routeKotlin(c, dir)
                routeJava(c, dir) to k
            }
            javaMs += j.second
            kotlinMs += k.second
            if (j.first.startsWith("ok")) routed++
            if (j.first != k.first) mismatches += "$c\n${RouteCases.firstDifference(j.first, k.first)}"
            if (c.id < IOS_CASES) golden.append(c.id).append(' ').append(j.second).append(' ').append(j.first).append('\n')
        }
        File(TestPaths.GOLDEN).apply { parentFile.mkdirs() }.writeText(golden.toString())
        println(
            "BRouter equivalence: ${cases.size} routes ($routed routed, ${cases.size - routed} errors), " +
                "${mismatches.size} mismatches; Java ${javaMs} ms, Kotlin ${kotlinMs} ms " +
                "(${"%.2f".format(kotlinMs.toDouble() / javaMs.coerceAtLeast(1))}x)",
        )
        assertEquals(mismatches.take(5).joinToString("\n\n"), 0, mismatches.size)
        // Most routes must actually route, or the test proves little.
        assertEquals("too few routes found: $routed of ${cases.size}", true, routed * 10 >= cases.size * 6)
    }

    /** The upstream Java's answer, built exactly as RouteCases.routeKotlin builds the Kotlin one. */
    private fun routeJava(c: RouteCases.Case, segmentDir: String): Pair<String, Long> {
        val rc = reference.btools.router.RoutingContext()
        rc.localFunction = RouteCases.profilePath(c.profile)
        rc.keyValues = HashMap(c.params + ("turnInstructionMode" to "9"))
        rc.memoryclass = 64
        if (c.heading != null) {
            rc.startDirection = c.heading
            rc.forceUseStartDirection = true
        }
        val wps = c.points.mapIndexed { i, p ->
            reference.btools.router.OsmNodeNamed().apply {
                name = "via$i"
                ilon = RouteCases.toILon(p.second)
                ilat = RouteCases.toILat(p.first)
            }
        }
        val t0 = System.currentTimeMillis()
        val engine = try {
            reference.btools.router.RoutingEngine(null, null, File(segmentDir), wps, rc)
        } catch (e: Exception) {
            return RouteCases.describe("throws " + e.message, 0, 0, 0, 0, 0, IntArray(0), emptyList()) to 0L
        }
        engine.quite = true
        engine.doRun(RouteCases.MAX_MS)
        val ms = System.currentTimeMillis() - t0
        val track = engine.foundTrack
        val error = engine.errorMessage
        if (error != null || track == null) {
            return RouteCases.describe(error ?: "no track", 0, 0, 0, 0, 0, IntArray(0), emptyList()) to ms
        }
        val pts = IntArray(track.nodes.size * 3)
        track.nodes.forEachIndexed { i, nd ->
            pts[i * 3] = nd.iLon
            pts[i * 3 + 1] = nd.iLat
            pts[i * 3 + 2] = nd.sElev.toInt()
        }
        val hints = track.voiceHints?.list.orEmpty().map {
            RouteCases.hint(it.command, it.indexInTrack, it.exitNumber, it.angle, it.distanceToNext)
        }
        return RouteCases.describe(null, track.distance, track.ascend, track.plainAscend, track.cost, track.energy, pts, hints) to ms
    }

    companion object {
        const val IOS_CASES = 40
    }
}
