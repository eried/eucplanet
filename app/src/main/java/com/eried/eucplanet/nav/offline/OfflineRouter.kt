package com.eried.eucplanet.nav.offline

import android.content.Context
import android.util.Log
import btools.router.OsmNodeNamed
import btools.router.ProfileCache
import btools.router.RoutingContext
import btools.router.RoutingEngine
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.RouteAvoidances
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Routes on the phone from downloaded BRouter tiles. One route at a time:
 * BRouter keeps its profile in a static cache. Gives null on any failure or
 * after [timeoutMs], and the caller moves on to the next router.
 *
 * [engine] does the routing; the app's one is [brouterEngine], tests pass a fake.
 * A route that runs out of time or whose caller gives up is stopped, so the
 * next one does not spend its own budget waiting for it.
 *
 * The first run after a start is the slow one: BRouter rates every kind of
 * road it meets by running the profile on its tags and keeps the ratings,
 * so the first route in a town pays for all of them. [warmUp] does that on
 * a short route near the rider before they ask.
 */
class OfflineRouter(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val engine: Engine,
) {
    /** One routing run: [maxMs] to find the route, and [stop] to end it early. */
    fun interface Engine {
        fun run(req: Request, maxMs: Long, stop: EngineStop): EngineResult?
    }

    /**
     * Ends a run from another thread. The engine says how with [onStop];
     * [stop] calls that, at once if the run has already said it, or as soon
     * as it does.
     */
    class EngineStop {
        private val lock = Any()
        private var action: (() -> Unit)? = null
        private var stopped = false

        val isStopped: Boolean get() = synchronized(lock) { stopped }

        fun onStop(action: () -> Unit) {
            val now = synchronized(lock) {
                this.action = action
                stopped
            }
            if (now) action()
        }

        fun stop() {
            val run = synchronized(lock) {
                if (stopped) return
                stopped = true
                action
            }
            run?.invoke()
        }
    }

    /** What BRouter is asked: profile name, profile switches, start heading
     *  (whole degrees, null when unknown) and the stops in order. */
    data class Request(
        val profile: String,
        val params: Map<String, String>,
        val headingDeg: Int?,
        val points: List<GeoPoint>,
    )

    data class EngineResult(
        val points: List<GeoPoint>,
        val hints: List<BrouterMapping.Hint>,
        val distanceM: Double,
    )

    private val oneAtATime = Mutex()

    /** A warm-up run: [stop] ends it, [done] once it has. */
    private class WarmUp {
        val stop = EngineStop()
        val done = CompletableDeferred<Unit>()
    }

    private val state = Any()

    /** Routes waiting for the engine or on it; no warm-up starts while there is one. */
    private var routesWanting = 0

    private var warming: WarmUp? = null

    /** The profile and switches of the last run that finished: BRouter keeps
     *  that one profile read, so a warm-up for it has nothing left to do. */
    private var readyFor: Pair<String, Map<String, String>>? = null

    suspend fun route(
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        avoid: RouteAvoidances,
        heading: Double?,
    ): NavRoute? {
        if (waypoints.size < 2) return null
        val profile = BrouterMapping.profileFor(mode) ?: return null
        val req = Request(
            profile = profile,
            params = BrouterMapping.avoidParams(avoid),
            headingDeg = heading?.let { ((Math.round(it) % 360 + 360) % 360).toInt() },
            points = waypoints.map { GeoPoint(it.lat, it.lng) },
        )
        val (warm, budget) = synchronized(state) {
            routesWanting++
            // The first run with a profile rates every road type it meets,
            // which the next ones reuse: when no warm-up got to do that (a
            // route right at start), it gets twice the time.
            warming to if (readyFor == (req.profile to req.params)) timeoutMs else timeoutMs * COLD_BUDGET_FACTOR
        }
        try {
            if (warm != null) {
                // The rider's route goes first: the warm-up is stopped. What it
                // already did (the profile read, the roads rated) this route
                // does not do again, so waiting for it is not counted.
                warm.stop.stop()
                withTimeoutOrNull(timeoutMs) { warm.done.await() }
            }
            // The budget covers waiting for a route already running: a reroute
            // queued behind a slow one gives up rather than wait twice as long.
            val start = System.currentTimeMillis()
            return withTimeoutOrNull(budget) {
                oneAtATime.withLock {
                    val left = (budget - (System.currentTimeMillis() - start)).coerceAtLeast(1)
                    try {
                        runEngine(req, left, EngineStop())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "offline route failed: ${e.message}")
                        null
                    }
                }
            }?.takeIf { it.points.size >= 2 }?.let {
                BrouterMapping.toNavRoute(name, waypoints, mode, it.points, it.hints, it.distanceM)
            }
        } finally {
            synchronized(state) { routesWanting-- }
        }
    }

    /**
     * Gets the engine ready for [mode] and [avoid] with a short route in a
     * downloaded square of [store] (at [near] when one has it), so the
     * rider's first route does not spend its budget reading BRouter's
     * profile and rating the roads. Skipped when there is no square, when a
     * route is running or waiting, or when the engine already ran with that
     * profile and those switches. A route asked for meanwhile stops it. True
     * when it ran to the end.
     */
    suspend fun warmUp(store: OfflineTileStore, near: GeoPoint?, mode: TravelMode, avoid: RouteAvoidances): Boolean {
        val profile = BrouterMapping.profileFor(mode) ?: return false
        val points = runInterruptible(Dispatchers.IO) {
            runCatching { warmUpPoints(store.downloaded(), near) }.getOrNull()
        } ?: return false
        val req = Request(profile, BrouterMapping.avoidParams(avoid), null, points)
        val w = WarmUp()
        synchronized(state) {
            if (readyFor == (req.profile to req.params) || routesWanting > 0 || warming != null) return false
            if (!oneAtATime.tryLock()) return false
            warming = w
        }
        try {
            val t0 = System.currentTimeMillis()
            runEngine(req, WARM_UP_MS, w.stop)
            Log.i(TAG, "warm-up for ${req.profile} ${if (w.stop.isStopped) "stopped" else "done"} after ${System.currentTimeMillis() - t0} ms")
            return !w.stop.isStopped
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "warm-up failed: ${e.message}")
            return false
        } finally {
            synchronized(state) { warming = null }
            oneAtATime.unlock()
            w.done.complete(Unit)
        }
    }

    /**
     * One engine run, on the caller's lock. The run is blocking and BRouter
     * stretches its own time limit, so a timeout or a cancelled caller stops
     * it here; it returns once the run has ended.
     */
    private suspend fun runEngine(req: Request, maxMs: Long, stop: EngineStop): EngineResult? = coroutineScope {
        val run = async { runInterruptible(Dispatchers.IO) { engine.run(req, maxMs, stop) } }
        val result = try {
            run.await()
        } catch (e: CancellationException) {
            stop.stop()
            throw e
        }
        if (!stop.isStopped) synchronized(state) { readyFor = req.profile to req.params }
        result
    }

    companion object {
        private const val TAG = "OfflineRouter"
        const val TIMEOUT_MS = 20_000L

        /** How many times [TIMEOUT_MS] the first run with a profile gets. */
        const val COLD_BUDGET_FACTOR = 2

        /** BRouter's limit for a warm-up: long enough for a slow phone's
         *  first run, and a route asked for meanwhile stops it anyway. */
        const val WARM_UP_MS = 60_000L

        /** How far apart the warm-up's two stops are, in degrees of latitude
         *  (about 2 km): a route long enough that BRouter rates the common
         *  road types near the rider. */
        private const val WARM_UP_STEP_DEG = 0.02

        /**
         * The warm-up's two stops: [near] when one of [squares] (name to
         * bytes) has it, else the middle of the biggest square (the most
         * roads, not a square of open sea), and a point about 2 km north
         * (south at the square's top edge), so both are in the same square.
         * Null without a square.
         */
        fun warmUpPoints(squares: Map<String, Long>, near: GeoPoint?): List<GeoPoint>? {
            val names = squares.filterKeys(RoutingTiles::isTileName).entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .map { it.key }
            if (names.isEmpty()) return null
            val at = near?.takeIf { RoutingTiles.nameFor(it.lat, it.lng) in names }
                ?: RoutingTiles.bounds(names.first()).let { GeoPoint((it[0] + it[2]) / 2, (it[1] + it[3]) / 2) }
            val square = RoutingTiles.nameFor(at.lat, at.lng)
            val north = GeoPoint(at.lat + WARM_UP_STEP_DEG, at.lng)
            val other = if (RoutingTiles.nameFor(north.lat, north.lng) == square) north else GeoPoint(at.lat - WARM_UP_STEP_DEG, at.lng)
            return listOf(at, other)
        }

        /** Profiles and the fallback tag table shipped in the APK. */
        private val ASSETS = listOf("wheel.brf", "hiking-mountain.brf", "lookups.dat")

        /** The real engine: BRouter over the tiles in [store]. */
        fun brouterEngine(context: Context, store: OfflineTileStore): Engine = Engine { req, maxMs, stop ->
            val profiles = File(store.dir, "profiles").apply { mkdirs() }
            copyAssets(context, store, profiles)
            runBrouter(store.dir, profiles, req, maxMs, stop)
        }

        /** One BRouter run: tiles in [segmentDir], `<profile>.brf` and
         *  `lookups.dat` in [profilesDir]. Null when no route is found or
         *  [stop] ends it first. */
        fun runBrouter(
            segmentDir: File,
            profilesDir: File,
            req: Request,
            maxMs: Long,
            stop: EngineStop = EngineStop(),
        ): EngineResult? {
            val rc = RoutingContext().apply {
                localFunction = File(profilesDir, "${req.profile}.brf").absolutePath
                keyValues = HashMap(req.params + ("turnInstructionMode" to "9"))
                memoryclass = 64
                if (req.headingDeg != null) {
                    startDirection = req.headingDeg
                    forceUseStartDirection = true
                }
            }
            val wps = req.points.mapIndexed { i, p ->
                OsmNodeNamed().apply {
                    name = "via$i"
                    val (lon, lat) = BrouterMapping.toBrouter(p)
                    ilon = lon
                    ilat = lat
                }
            }
            val t0 = System.currentTimeMillis()
            // Reads the profile, or takes it from BRouter's cache when the
            // last run had the same one with the same switches.
            val engine = RoutingEngine(null, null, segmentDir, wps, rc)
            engine.quite = true
            // BRouter checks this flag while it searches and gives up.
            stop.onStop { engine.terminate() }
            if (stop.isStopped) {
                // doRun hands the profile back to BRouter's cache when it ends.
                // Without that the cache keeps it as in use, and the next
                // route reads the whole profile again.
                ProfileCache.releaseProfile(rc)
                return null
            }
            val t1 = System.currentTimeMillis()
            engine.doRun(maxMs)
            Log.i(TAG, "BRouter ${req.profile}: profile ${t1 - t0} ms, route ${System.currentTimeMillis() - t1} ms")
            if (stop.isStopped) {
                Log.w(TAG, "BRouter stopped")
                return null
            }
            val track = engine.foundTrack
            if (track == null) {
                Log.w(TAG, "BRouter: ${engine.errorMessage}")
                return null
            }
            return EngineResult(
                points = track.nodes.map { BrouterMapping.fromBrouter(it.iLon, it.iLat) },
                hints = track.voiceHints?.list.orEmpty().map {
                    BrouterMapping.Hint(it.command, it.indexInTrack, it.exitNumber)
                },
                distanceM = track.distance.toDouble(),
            )
        }

        @Volatile private var profilesCopied = false

        /** Profiles come from the APK, once per run, so an update ships a new
         *  wheel profile. The tag table must match the tiles, so the one
         *  downloaded with them wins and the APK's is only the fallback. */
        private fun copyAssets(context: Context, store: OfflineTileStore, profiles: File) {
            if (!profilesCopied) {
                for (name in ASSETS) {
                    context.assets.open("brouter/$name").use { input ->
                        File(profiles, name).outputStream().use { input.copyTo(it) }
                    }
                }
                profilesCopied = true
            }
            val downloaded = store.lookupsFile()
            val used = File(profiles, "lookups.dat")
            if (downloaded.isFile && downloaded.lastModified() != used.lastModified()) {
                downloaded.copyTo(used, overwrite = true)
                used.setLastModified(downloaded.lastModified())
            }
        }
    }
}
