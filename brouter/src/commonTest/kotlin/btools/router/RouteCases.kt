package btools.router

import btools.util.BFiles
import btools.util.currentTimeMillis
import btools.util.joinPath
import kotlin.random.Random

/**
 * The routes the equivalence tests ask for, on the segment E10_N55 (Oslo,
 * Gothenburg, Copenhagen, Malmo and the land between), and a route's answer as
 * one line of text, so the Java reference, the Kotlin copy on the JVM and the
 * Kotlin copy on iOS can be compared exactly.
 *
 * Fetch the segment once (65 MB, rebuilt weekly upstream):
 *   mkdir -p brouter/testdata/segments
 *   curl -o brouter/testdata/segments/E10_N55.rd5 https://brouter.de/brouter/segments4/E10_N55.rd5
 * or point BROUTER_SEGMENTS (environment) or -PbrouterSegments at a folder
 * holding it. Without it the routing tests skip.
 */
internal object RouteCases {

    const val SEGMENT = "E10_N55"

    /** The app's profiles and the switches BrouterMapping sends. */
    private val PROFILES = listOf(
        "wheel" to emptyMap(),
        "wheel" to mapOf("avoid_unsafe" to "1"),
        "wheel" to mapOf("allow_ferries" to "0"),
        "wheel" to mapOf("avoid_unpaved" to "1"),
        "hiking-mountain" to emptyMap(),
        "wheel" to mapOf("avoid_unsafe" to "1", "allow_ferries" to "0", "avoid_unpaved" to "1"),
        "hiking-mountain" to mapOf("allow_ferries" to "0"),
        // Not a number: both copies must fail the same way.
        "wheel" to mapOf("avoid_unsafe" to "true"),
    )

    /** Towns inside the square, lat to lon. */
    private val TOWNS = listOf(
        59.9139 to 10.7522, 57.7089 to 11.9746, 55.6761 to 12.5683, 55.6050 to 13.0038,
        56.1629 to 10.2039, 56.0465 to 12.6945, 56.6745 to 12.8578, 57.7826 to 14.1618,
        59.2181 to 10.9298, 59.7439 to 10.2045, 57.7210 to 12.9401, 55.4038 to 10.4024,
        55.6415 to 12.0803, 56.8790 to 14.8059, 59.3793 to 13.5036, 58.3498 to 11.9381,
        55.7047 to 13.1910, 58.2664 to 12.2867,
    )

    class Case(
        val id: Int,
        val profile: String,
        val params: Map<String, String>,
        val heading: Int?,
        /** lat, lon of each stop. */
        val points: List<Pair<Double, Double>>,
    ) {
        override fun toString(): String =
            "#$id $profile$params heading=$heading " + points.joinToString(" ") { "${it.first},${it.second}" }
    }

    /** [n] cases, the same on every platform (kotlin.random is seeded XorWow everywhere). */
    fun cases(n: Int, seed: Int = 20261115): List<Case> {
        val rnd = Random(seed)
        return (0 until n).map { id ->
            val town = TOWNS[id % TOWNS.size]
            val (profile, params) = PROFILES[(id / TOWNS.size + id) % PROFILES.size]
            val start = clamp(town.first + rnd.nextDouble(-0.05, 0.05), town.second + rnd.nextDouble(-0.09, 0.09))
            val stops = ArrayList<Pair<Double, Double>>()
            stops += start
            val legs = if (id % 7 == 3) 2 else 1
            var at = start
            repeat(legs) {
                // 1 to about 12 km away.
                val dLat = rnd.nextDouble(-0.1, 0.1)
                val dLon = rnd.nextDouble(-0.17, 0.17)
                val next = clamp(at.first + dLat, at.second + dLon)
                stops += next
                at = next
            }
            val heading = if (id % 3 == 1) rnd.nextInt(360) else null
            Case(id, profile, params, heading, stops)
        }
    }

    private fun clamp(lat: Double, lon: Double) =
        lat.coerceIn(55.02, 59.98) to lon.coerceIn(10.02, 14.98)

    fun toILon(lon: Double): Int = ((lon + 180.0) * 1_000_000.0 + 0.5).toInt()
    fun toILat(lat: Double): Int = ((lat + 90.0) * 1_000_000.0 + 0.5).toInt()

    /** The folder with the segment, or null (and why) when it is missing. */
    fun segmentDir(): String? =
        TestPaths.SEGMENTS.takeIf { BFiles.isFile(joinPath(it, "$SEGMENT.rd5")) }

    fun skipMessage(): String =
        "skipped: no $SEGMENT.rd5 in ${TestPaths.SEGMENTS} (curl -o ${TestPaths.SEGMENTS}/$SEGMENT.rd5 " +
            "https://brouter.de/brouter/segments4/$SEGMENT.rd5, or set BROUTER_SEGMENTS)"

    /** What a segment is, so an answer file made from another build is not used. */
    fun segmentStamp(dir: String): String {
        val p = joinPath(dir, "$SEGMENT.rd5")
        return "$SEGMENT ${BFiles.length(p)} ${BFiles.lastModified(p)}"
    }

    fun profilePath(profile: String): String = joinPath(TestPaths.PROFILES, "$profile.brf")

    /** One answer as text: the error, or every track point, the totals and each hint, exactly. */
    fun describe(
        error: String?,
        distance: Int, ascend: Int, plainAscend: Int, cost: Int, energy: Int,
        points: IntArray /* ilon, ilat, selev per point */,
        hints: List<String>,
    ): String = buildString {
        if (error != null) {
            append("error ").append(error)
            return@buildString
        }
        append("ok d=").append(distance).append(" a=").append(ascend).append(" pa=").append(plainAscend)
            .append(" c=").append(cost).append(" e=").append(energy).append(" n=").append(points.size / 3)
        append(" pts=")
        var i = 0
        while (i < points.size) {
            if (i > 0) append(';')
            append(points[i]).append(',').append(points[i + 1]).append(',').append(points[i + 2])
            i += 3
        }
        append(" hints=").append(hints.joinToString(";"))
    }

    /** One hint as text: command, track index, roundabout exit, angle and distance bits. */
    fun hint(cmd: Int, index: Int, exit: Int, angle: Float, distanceToNext: Double): String =
        "$cmd,$index,$exit,${angle.toRawBits()},${distanceToNext.toRawBits()}"

    /** The Kotlin router's answer to [c], and how long it took in ms. */
    fun routeKotlin(c: Case, segmentDir: String): Pair<String, Long> {
        val rc = RoutingContext()
        rc.localFunction = profilePath(c.profile)
        rc.keyValues = HashMap(c.params + ("turnInstructionMode" to "9"))
        rc.memoryclass = 64
        if (c.heading != null) {
            rc.startDirection = c.heading
            rc.forceUseStartDirection = true
        }
        val wps = c.points.mapIndexed { i, p ->
            val n = OsmNodeNamed()
            n.name = "via$i"
            n.ilon = toILon(p.second)
            n.ilat = toILat(p.first)
            n
        }.toMutableList()
        val t0 = currentTimeMillis()
        val engine = try {
            RoutingEngine(null, null, segmentDir, wps, rc)
        } catch (e: Exception) {
            return RouteCases.describe("throws " + e.message, 0, 0, 0, 0, 0, IntArray(0), emptyList()) to 0L
        }
        engine.quite = true
        engine.doRun(MAX_MS)
        val ms = currentTimeMillis() - t0
        val track = engine.foundTrack
        val error = engine.errorMessage
        if (error != null || track == null) return describe(error ?: "no track", 0, 0, 0, 0, 0, IntArray(0), emptyList()) to ms
        val pts = IntArray(track.nodes.size * 3)
        track.nodes.forEachIndexed { i, n ->
            pts[i * 3] = n.getILon()
            pts[i * 3 + 1] = n.getILat()
            pts[i * 3 + 2] = n.getSElev().toInt()
        }
        val hints = track.voiceHints?.getList().orEmpty().map {
            hint(it.getCommand(), it.getIndexInTrack(), it.getExitNumber(), it.getAngle(), it.getDistanceToNext())
        }
        return describe(null, track.distance, track.ascend, track.plainAscend, track.cost, track.energy, pts, hints) to ms
    }

    /** The app's own budget is 20 s; the tests give more so a slow CI run does not change an answer. */
    const val MAX_MS = 120_000L

    /** The first line where two answers part, for a failure message. */
    fun firstDifference(a: String, b: String): String {
        val i = a.zip(b).indexOfFirst { (x, y) -> x != y }.let { if (it < 0) minOf(a.length, b.length) else it }
        val from = (i - 60).coerceAtLeast(0)
        return "at char $i:\n  expected ...${a.substring(from, minOf(a.length, i + 80))}\n  actual   ...${b.substring(from, minOf(b.length, i + 80))}"
    }
}
