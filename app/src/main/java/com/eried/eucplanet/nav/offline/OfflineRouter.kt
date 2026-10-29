package com.eried.eucplanet.nav.offline

import android.content.Context
import android.util.Log
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.RouteAvoidances
import java.io.File
import kotlinx.coroutines.Dispatchers
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
 */
class OfflineRouter(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val engine: (Request, Long) -> EngineResult?,
) {
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
                try {
                    runInterruptible(Dispatchers.IO) { engine(req, left) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
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

        /** Profiles and the fallback tag table shipped in the APK. */
        private val ASSETS = listOf("wheel.brf", "hiking-mountain.brf", "lookups.dat")

        /** The real engine: BRouter over the tiles in [store]. */
        fun brouterEngine(context: Context, store: OfflineTileStore): (Request, Long) -> EngineResult? = { req, maxMs ->
            val profiles = File(store.dir, "profiles").apply { mkdirs() }
            copyAssets(context, store, profiles)
            runBrouter(store.dir, profiles, req, maxMs)
        }

        /** One BRouter run: tiles in [segmentDir], `<profile>.brf` and
         *  `lookups.dat` in [profilesDir]. Null when no route is found. */
        fun runBrouter(segmentDir: File, profilesDir: File, req: Request, maxMs: Long): EngineResult? {
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
            val engine = RoutingEngine(null, null, segmentDir, wps, rc)
            engine.quite = true
            engine.doRun(maxMs)
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
