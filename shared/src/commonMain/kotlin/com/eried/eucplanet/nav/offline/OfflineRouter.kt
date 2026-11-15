package com.eried.eucplanet.nav.offline

import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.util.joinPath
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.RouteAvoidances
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.Math
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.platform.runBlockingIo
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Routes on the phone from downloaded BRouter tiles. One route at a time:
 * BRouter keeps its profile in a static cache. Gives null on any failure or
 * after [timeoutMs], and the caller moves on to the next router.
 *
 * [engine] does the routing; the app's one is [brouterEngine], tests pass a fake.
 * BRouter is the multiplatform copy in :brouter, so both phones route alike.
 * A route that runs out of time or whose caller gives up is stopped, so the
 * next one does not spend its own budget waiting for it.
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
        private val lock = SynchronizedObject()
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
        // The budget covers waiting for a route already running: a reroute
        // queued behind a slow one gives up rather than wait twice as long.
        val start = System.currentTimeMillis()
        return withTimeoutOrNull(timeoutMs) {
            oneAtATime.withLock {
                val left = (timeoutMs - (System.currentTimeMillis() - start)).coerceAtLeast(1)
                val stop = EngineStop()
                try {
                    // The run is blocking and BRouter stretches its own time
                    // limit, so a timeout or a cancelled caller stops it here;
                    // the lock is held until it has ended.
                    coroutineScope {
                        val run = async { runBlockingIo { engine.run(req, left, stop) } }
                        try {
                            run.await()
                        } catch (e: CancellationException) {
                            stop.stop()
                            throw e
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "offline route failed: ${e.message}")
                    null
                }
            }
        }?.takeIf { it.points.size >= 2 }?.let {
            BrouterMapping.toNavRoute(name, waypoints, mode, it.points, it.hints, it.distanceM)
        }
    }

    companion object {
        private const val TAG = "OfflineRouter"
        const val TIMEOUT_MS = 20_000L

        /** The real engine: BRouter over the tiles in [segmentDir], with the
         *  profiles [profiles] gets ready. */
        fun brouterEngine(segmentDir: String, profiles: BrouterProfiles): Engine = Engine { req, maxMs, stop ->
            runBrouter(segmentDir, profiles.folder(), req, maxMs, stop)
        }

        /** One BRouter run: tiles in [segmentDir], `<profile>.brf` and
         *  `lookups.dat` in [profilesDir]. Null when no route is found or
         *  [stop] ends it first. */
        fun runBrouter(
            segmentDir: String,
            profilesDir: String,
            req: Request,
            maxMs: Long,
            stop: EngineStop = EngineStop(),
        ): EngineResult? {
            val rc = RoutingContext().apply {
                localFunction = joinPath(profilesDir, "${req.profile}.brf")
                keyValues = HashMap(req.params + ("turnInstructionMode" to "9"))
                memoryclass = 64
                if (req.headingDeg != null) {
                    startDirection = req.headingDeg
                    forceUseStartDirection = true
                }
            }
            val wps = req.points.mapIndexedTo(ArrayList()) { i, p ->
                OsmNodeNamed().apply {
                    name = "via$i"
                    val (lon, lat) = BrouterMapping.toBrouter(p)
                    ilon = lon
                    ilat = lat
                }
            }
            val engine = RoutingEngine(null, null, segmentDir, wps, rc)
            engine.quite = true
            // BRouter checks this flag while it searches and gives up.
            stop.onStop { engine.terminate() }
            if (stop.isStopped) return null
            engine.doRun(maxMs)
            if (stop.isStopped) {
                Log.w(TAG, "BRouter stopped")
                return null
            }
            // BRouter keeps an empty track when it finds none and says why
            // in errorMessage.
            val track = engine.foundTrack
            if (track == null || engine.errorMessage != null) {
                Log.w(TAG, "BRouter: ${engine.errorMessage}")
                return null
            }
            return EngineResult(
                points = track.nodes.map { BrouterMapping.fromBrouter(it.getILon(), it.getILat()) },
                hints = track.voiceHints?.getList().orEmpty().map {
                    BrouterMapping.Hint(it.getCommand(), it.getIndexInTrack(), it.getExitNumber())
                },
                distanceM = track.distance.toDouble(),
            )
        }
    }
}

/**
 * Where BRouter reads its profiles: a folder with `wheel.brf`,
 * `hiking-mountain.brf` and `lookups.dat`. Android copies them from the APK
 * (AndroidBrouterProfiles); the iPhone will from its bundle.
 */
fun interface BrouterProfiles {
    /** The folder, with the profiles written and current. */
    fun folder(): String
}
