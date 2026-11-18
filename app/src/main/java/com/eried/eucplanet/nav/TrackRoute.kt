package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.Maneuver
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.TurnType
import com.eried.eucplanet.data.model.Waypoint
import kotlin.math.abs

/**
 * A locked track: the line from a GPX file, ridden exactly as drawn. Its
 * stops are read-only and sit on the line in riding order: the start, the
 * file's named places that lie on it, and the end. Nothing here asks a
 * router; turns are read from the line's own bends.
 */
object TrackRoute {

    /** How close to the line a file's named place must be to count as on it. */
    const val PLACE_ON_TRACK_M = 50.0

    /** A place this close to either end is that end, not a stop of its own. */
    private const val END_CLEAR_M = 30.0

    /** A bend sharper than this is a turn worth a cue. */
    private const val TURN_MIN_DEG = 35.0

    /** How far either side of a vertex the bend is measured, so GPS wiggle
     *  in a recorded line does not read as a turn. */
    private const val TURN_ARM_M = 25.0

    /** Two bends closer than this are one turn (the sharper one). */
    private const val TURN_MERGE_M = 40.0

    /**
     * The file's named places that lie on [track], each once, in the order
     * the line passes them. Exports repeat places (BRouter wrote each of 170
     * places 24 times), and most are bookmarks nowhere near the ride.
     */
    fun placesOnTrack(places: List<Waypoint>, track: List<GeoPoint>, maxM: Double = PLACE_ON_TRACK_M): List<Waypoint> {
        if (track.size < 2) return emptyList()
        val total = GeoMath.polylineLengthM(track)
        val seen = HashSet<Pair<Long, Long>>()
        return places
            .filter { seen.add(Math.round(it.lat * 1e5) to Math.round(it.lng * 1e5)) }
            .mapNotNull { p ->
                val hit = GeoMath.nearestOnPolyline(p.point(), track) ?: return@mapNotNull null
                if (hit.distanceM > maxM) return@mapNotNull null
                if (hit.alongM < END_CLEAR_M || hit.alongM > total - END_CLEAR_M) return@mapNotNull null
                hit.alongM to p.copy(name = p.name.trim(), radiusM = null, passed = false)
            }
            .sortedBy { it.first }
            .map { it.second }
    }

    /** The locked stops: the track's start, its places, its end. */
    fun stops(track: List<GeoPoint>, places: List<Waypoint>): List<Waypoint> {
        if (track.size < 2) return emptyList()
        val a = track.first()
        val b = track.last()
        return listOf(Waypoint(a.lat, a.lng)) + placesOnTrack(places, track) + Waypoint(b.lat, b.lng)
    }

    /**
     * The route to draw and ride: the track itself through [stops]. Its
     * travel mode is STRAIGHT so the engine never re-routes off the line,
     * and its maneuvers are the line's bends, so guidance gives turn cues
     * instead of homing on the last stop.
     */
    fun route(name: String, track: List<GeoPoint>, stops: List<Waypoint>): NavRoute =
        NavRoute(
            name = name,
            waypoints = stops,
            travelMode = TravelMode.STRAIGHT,
            geometry = track,
            maneuvers = turns(track),
            totalDistanceM = GeoMath.polylineLengthM(track),
            track = track,
        )

    /**
     * The rest of the track for guidance: from [stops]'s first stop (where
     * the rider is, a stop just reached or the start) to the end, through
     * the stops after it. The first stop is the origin, the way every leg
     * starts at the rider.
     */
    fun leg(name: String, track: List<GeoPoint>, stops: List<Waypoint>): NavRoute {
        val from = stops.firstOrNull()?.point()
        // The start is the line's first point; a loop's start is also its
        // end, and the nearest point would be the wrong one.
        val startAt = when {
            from == null -> 0.0
            GeoMath.distanceM(from, track.first()) < 1.0 -> 0.0
            else -> GeoMath.nearestOnPolyline(from, track)?.alongM ?: 0.0
        }
        val slice = slice(track, startAt)
        return route(name, slice, stops).copy(track = track)
    }

    /** [track] from [fromM] along it to its end. */
    fun slice(track: List<GeoPoint>, fromM: Double): List<GeoPoint> {
        if (fromM <= 0.0 || track.size < 2) return track
        var acc = 0.0
        for (i in 0 until track.size - 1) {
            val seg = GeoMath.distanceM(track[i], track[i + 1])
            if (acc + seg >= fromM) {
                val t = if (seg > 0) (fromM - acc) / seg else 0.0
                val p = lerp(track[i], track[i + 1], t)
                return listOf(p) + track.subList(i + 1, track.size)
            }
            acc += seg
        }
        return track.takeLast(2)
    }

    /**
     * The line's turns, as maneuvers with their distance from its start, and
     * an arrival at its end. A bend is measured between the points [TURN_ARM_M]
     * before and after each vertex; of bends closer than [TURN_MERGE_M], the
     * sharpest stands for the turn.
     */
    fun turns(track: List<GeoPoint>): List<Maneuver> {
        if (track.size < 2) return emptyList()
        val cum = DoubleArray(track.size)
        for (i in 1 until track.size) cum[i] = cum[i - 1] + GeoMath.distanceM(track[i - 1], track[i])
        val total = cum.last()
        data class Bend(val i: Int, val deg: Double)
        val bends = ArrayList<Bend>()
        for (i in 1 until track.size - 1) {
            if (cum[i] < TURN_ARM_M || cum[i] > total - TURN_ARM_M) continue
            val before = at(track, cum, cum[i] - TURN_ARM_M)
            val after = at(track, cum, cum[i] + TURN_ARM_M)
            val d = turnDeg(GeoMath.bearingDeg(before, track[i]), GeoMath.bearingDeg(track[i], after))
            if (abs(d) >= TURN_MIN_DEG) bends += Bend(i, d)
        }
        val kept = ArrayList<Bend>()
        for (b in bends.sortedByDescending { abs(it.deg) }) {
            if (kept.none { abs(cum[it.i] - cum[b.i]) < TURN_MERGE_M }) kept += b
        }
        return kept.sortedBy { it.i }.map {
            Maneuver(track[it.i], turnType(it.deg), "", "", cum[it.i])
        } + Maneuver(track.last(), TurnType.ARRIVE, "", "", total)
    }

    /** Signed change of direction in degrees, right positive, in (-180, 180]. */
    internal fun turnDeg(inDeg: Double, outDeg: Double): Double {
        var d = (outDeg - inDeg) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    internal fun turnType(deg: Double): TurnType {
        val a = abs(deg)
        val right = deg > 0
        return when {
            a >= 165 -> TurnType.UTURN
            a >= 125 -> if (right) TurnType.SHARP_RIGHT else TurnType.SHARP_LEFT
            a >= 60 -> if (right) TurnType.RIGHT else TurnType.LEFT
            else -> if (right) TurnType.SLIGHT_RIGHT else TurnType.SLIGHT_LEFT
        }
    }

    private fun at(track: List<GeoPoint>, cum: DoubleArray, m: Double): GeoPoint {
        if (m <= 0.0) return track.first()
        if (m >= cum.last()) return track.last()
        var i = java.util.Arrays.binarySearch(cum, m)
        if (i >= 0) return track[i]
        i = -i - 2
        val seg = cum[i + 1] - cum[i]
        return lerp(track[i], track[i + 1], if (seg > 0) (m - cum[i]) / seg else 0.0)
    }

    private fun lerp(a: GeoPoint, b: GeoPoint, t: Double) =
        GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)

    /**
     * How far each stop sits along [line], first stop at 0. Measured in
     * order, so a loop's end is not found at its start.
     */
    fun alongM(stops: List<GeoPoint>, line: List<GeoPoint>): List<Double> {
        if (stops.isEmpty()) return emptyList()
        var acc = 0.0
        return listOf(0.0) + RouteLegs.metres(stops, line).map { acc += it; acc }
    }

    /**
     * Where the rider is along a line that may cross itself or come back to
     * its start. The nearest point of the whole line would jump to the other
     * pass of a crossing (or the end of a loop, at its start), so this looks
     * just behind and ahead of the last place found, and searches the whole
     * line only when the rider is far from that stretch.
     */
    class Progress(private val line: List<GeoPoint>) {
        private val cum = DoubleArray(line.size).also {
            for (i in 1 until line.size) it[i] = it[i - 1] + GeoMath.distanceM(line[i - 1], line[i])
        }
        private var atM = 0.0

        fun locate(p: GeoPoint): GeoMath.PolylineHit? {
            if (line.size < 2) return null
            // The stretch being ridden wins while the rider is on it: where a
            // track crosses or doubles back on itself, a later pass can be a
            // hair nearer, and jumping to it skips cues and says "arrived".
            nearest(p, atM - STAY_BACK_M, atM + STAY_AHEAD_M)
                ?.takeIf { it.distanceM <= STAY_M }
                ?.let { atM = it.alongM; return it }
            val lo = indexAt(atM - BACK_M)
            val hi = maxOf(indexAt(atM + AHEAD_M) + 1, lo + 1).coerceAtMost(line.lastIndex)
            val near = GeoMath.nearestOnPolyline(p, line.subList(lo, hi + 1))
                ?.let { GeoMath.PolylineHit(it.segmentIndex + lo, it.distanceM, it.alongM + cum[lo]) }
            val hit = if (near == null || near.distanceM > LOST_M) {
                GeoMath.nearestOnPolyline(p, line)?.takeIf { near == null || it.distanceM < near.distanceM } ?: near
            } else near
            if (hit != null) atM = hit.alongM
            return hit
        }

        private fun nearest(p: GeoPoint, fromM: Double, toM: Double): GeoMath.PolylineHit? {
            val lo = indexAt(fromM)
            val hi = maxOf(indexAt(toM) + 1, lo + 1).coerceAtMost(line.lastIndex)
            return GeoMath.nearestOnPolyline(p, line.subList(lo, hi + 1))
                ?.let { GeoMath.PolylineHit(it.segmentIndex + lo, it.distanceM, it.alongM + cum[lo]) }
        }

        private fun indexAt(m: Double): Int {
            if (m <= 0.0) return 0
            val i = java.util.Arrays.binarySearch(cum, m)
            return (if (i >= 0) i else -i - 2).coerceIn(0, line.lastIndex)
        }

        private companion object {
            /** The stretch kept while the rider is within [STAY_M] of it. */
            const val STAY_BACK_M = 20.0
            const val STAY_AHEAD_M = 60.0
            const val STAY_M = 25.0
            const val BACK_M = 100.0
            const val AHEAD_M = 1500.0
            const val LOST_M = 300.0
        }
    }

    /**
     * Unlocking a track: which of its points become stops so a line through
     * them keeps its shape. Each step adds the point furthest from the line
     * so far, the one that keeps the most shape, so any count is the best
     * step-by-step pick, and [maxOffM] says how far the line can stray.
     * [keep] are track indices that are stops anyway (the places on it).
     */
    class Simplifier(private val track: List<GeoPoint>, keep: Collection<Int> = emptyList()) {
        private val base: List<Int> = (keep + 0 + track.lastIndex).filter { it in track.indices }.distinct().sorted()
        /** Points in the order they are added after [base]. */
        private val order = ArrayList<Int>()
        /** The worst stray with the base plus the first n added: [devAfter][n]. */
        private val devAfter = ArrayList<Double>()

        init {
            // Segments between chosen points, each with its furthest point.
            data class Seg(val a: Int, val b: Int, val far: Int, val dev: Double)
            fun seg(a: Int, b: Int): Seg {
                var far = -1
                var dev = 0.0
                if (b - a > 1) {
                    val ab = listOf(track[a], track[b])
                    for (i in a + 1 until b) {
                        val d = GeoMath.nearestOnPolyline(track[i], ab)?.distanceM ?: 0.0
                        if (d > dev) { dev = d; far = i }
                    }
                }
                return Seg(a, b, far, dev)
            }
            val segs = java.util.PriorityQueue<Seg>(compareByDescending { it.dev })
            base.zipWithNext { a, b -> segs += seg(a, b) }
            devAfter += segs.peek()?.dev ?: 0.0
            while (order.size < MAX_ADDED) {
                val top = segs.poll() ?: break
                if (top.far < 0 || top.dev <= 0.0) break
                order += top.far
                segs += seg(top.a, top.far)
                segs += seg(top.far, top.b)
                devAfter += segs.peek()?.dev ?: 0.0
            }
        }

        /** The points that are stops whatever the count. */
        val fixedCount: Int get() = base.size

        /** The most points worth adding: past this the line is the track. */
        val maxAdded: Int get() = order.size

        /** Track indices of the stops with [added] points on top of the fixed ones. */
        fun pick(added: Int): List<Int> =
            (base + order.take(added.coerceIn(0, order.size))).sorted()

        /** How far the line through [pick] strays from the track at most, metres. */
        fun maxOffM(added: Int): Double = devAfter[added.coerceIn(0, devAfter.lastIndex)]

        /** The fewest added points that keep the line within [m] of the
         *  track, or [cap] when even that many cannot. */
        fun fewestWithin(m: Double, cap: Int): Int {
            val top = cap.coerceIn(0, order.size)
            for (n in 0..top) if (devAfter[n] <= m) return n
            return top
        }

        private companion object {
            const val MAX_ADDED = 200
        }
    }

    /** Within this of the point a locked track resumes from, guidance follows
     *  the line at once; further, it takes the rider there first. */
    const val JOIN_M = 100.0

    /**
     * How a locked track starts from [me]. It resumes from the last stop
     * reached, or its start. Already there ([leg] set), the rider follows the
     * line from it and it counts as reached. Further away, [leadInTo] is the
     * point to ride to first.
     */
    data class Start(val stops: List<Waypoint>, val leg: NavRoute?, val leadInTo: Waypoint?)

    fun start(name: String, track: List<GeoPoint>, dests: List<Waypoint>, me: GeoPoint, nearM: Double): Start? {
        if (dests.none { !it.passed }) return null
        val anchorIdx = dests.indexOfLast { it.passed }.takeIf { it >= 0 } ?: 0
        val anchor = dests[anchorIdx].copy(passed = false)
        if (GeoMath.distanceM(me, anchor.point()) > nearM) return Start(dests, null, anchor)
        val stops = dests.mapIndexed { i, w -> if (i <= anchorIdx) w.copy(passed = true) else w }
        return Start(stops, leg(name, track, listOf(anchor) + stops.drop(anchorIdx + 1)), null)
    }
}
