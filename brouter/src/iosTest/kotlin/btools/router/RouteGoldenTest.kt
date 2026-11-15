package btools.router

import btools.util.BFiles
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Kotlin/Native build routes like the upstream Java: the answers the JVM
 * run (RouteEquivalenceTest) took from the Java reference for its first
 * routes, routed again here on the iOS simulator. Skips without the segment
 * or without that answer file (run :brouter:jvmTest first; allTests does).
 */
class RouteGoldenTest {

    @Test
    fun nativeRoutesLikeTheJavaReference() {
        val dir = RouteCases.segmentDir()
        if (dir == null) {
            println(RouteCases.skipMessage())
            return
        }
        if (!BFiles.isFile(TestPaths.GOLDEN)) {
            println("skipped: no ${TestPaths.GOLDEN} (run :brouter:jvmTest first)")
            return
        }
        val lines = BFiles.readBytes(TestPaths.GOLDEN).decodeToString().lines().filter { it.isNotEmpty() }
        if (lines.first() != RouteCases.segmentStamp(dir)) {
            println("skipped: ${TestPaths.GOLDEN} was made from another ${RouteCases.SEGMENT} build")
            return
        }
        val expected = lines.drop(1).associate { line ->
            val id = line.substringBefore(' ').toInt()
            val rest = line.substringAfter(' ')
            id to (rest.substringBefore(' ').toLong() to rest.substringAfter(' '))
        }
        val cases = RouteCases.cases(expected.keys.max() + 1).filter { it.id in expected }
        RouteCases.routeKotlin(RouteCases.cases(1, seed = 7).first(), dir)
        var javaMs = 0L
        var nativeMs = 0L
        val mismatches = ArrayList<String>()
        for (c in cases) {
            val (want, wantMs) = expected.getValue(c.id).let { it.second to it.first }
            val (got, ms) = RouteCases.routeKotlin(c, dir)
            javaMs += wantMs
            nativeMs += ms
            if (want != got) {
                mismatches += "$c\n${RouteCases.firstDifference(want, got)}"
                println("MISMATCH ${mismatches.last()}")
            }
        }
        println(
            "BRouter on iOS: ${cases.size} routes, ${mismatches.size} mismatches; " +
                "Kotlin/Native $nativeMs ms, Java on the JVM $javaMs ms",
        )
        assertEquals(0, mismatches.size, mismatches.take(5).joinToString("\n\n"))
    }
}
