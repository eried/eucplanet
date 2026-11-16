package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.RouteAvoidances
import btools.router.ProfileCache
import btools.router.RoutingContext
import btools.util.joinPath
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BRouter keeps one profile in a static cache, so two routes at once (a
 * reroute while the builder routes) must take turns. A slow or failing
 * engine gives null, and the caller moves on to the next router.
 */
class OfflineRouterTest {

    private val wps = listOf(Waypoint(59.0, 10.0), Waypoint(59.01, 10.0))

    private fun okResult() = OfflineRouter.EngineResult(
        points = listOf(GeoPoint(59.0, 10.0), GeoPoint(59.01, 10.0)),
        hints = emptyList(),
        distanceM = 1100.0,
    )

    @Test fun routesTakeTurns() = runBlocking {
        val inFlight = AtomicInteger()
        var most = 0
        val router = OfflineRouter(timeoutMs = 5_000) { _, _, _ ->
            most = maxOf(most, inFlight.incrementAndGet())
            Thread.sleep(50)
            inFlight.decrementAndGet()
            okResult()
        }
        val routes = (1..3).map {
            async { router.route("r", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null) }
        }.awaitAll()
        routes.forEach { assertNotNull(it) }
        assertEquals(1, most)
    }

    @Test fun aSlowEngine_givesNull() = runBlocking {
        val router = OfflineRouter(timeoutMs = 100) { _, _, _ -> Thread.sleep(2_000); okResult() }
        assertNull(router.route("r", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null))
    }

    @Test fun aFailingEngine_givesNull() = runBlocking {
        val router = OfflineRouter(timeoutMs = 5_000) { _, _, _ -> throw IllegalStateException("no tile") }
        assertNull(router.route("r", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null))
    }

    @Test fun driving_isNeverOffline() = runBlocking {
        var called = false
        val router = OfflineRouter(timeoutMs = 5_000) { _, _, _ -> called = true; okResult() }
        assertNull(router.route("r", wps, TravelMode.DRIVING, RouteAvoidances.NONE, null))
        assertEquals(false, called)
    }

    @Test fun theRequest_carriesProfileAvoidsAndHeading() = runBlocking {
        var seen: OfflineRouter.Request? = null
        val router = OfflineRouter(timeoutMs = 5_000) { req, _, _ -> seen = req; okResult() }
        router.route("r", wps, TravelMode.CYCLING, RouteAvoidances(ferries = true), heading = 95.0)
        val r = seen!!
        assertEquals("wheel", r.profile)
        assertEquals(mapOf("allow_ferries" to "0"), r.params)
        assertEquals(95, r.headingDeg)
        assertEquals(wps.map { GeoPoint(it.lat, it.lng) }, r.points)
    }

    @Test fun waitingForTheEngine_countsAgainstTheTimeout() = runBlocking {
        // A reroute queued behind a slow route must not wait past its budget.
        val router = OfflineRouter(timeoutMs = 300) { _, _, _ -> Thread.sleep(250); okResult() }
        // Warm first: a cold engine's first run gets a longer budget.
        assertNotNull(router.route("w", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null))
        val t0 = System.currentTimeMillis()
        val both = listOf(
            async { router.route("a", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null) },
            async { router.route("b", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null) },
        ).awaitAll()
        val ms = System.currentTimeMillis() - t0
        assertEquals(1, both.count { it != null })
        assert(ms < 700) { "took $ms ms" }
    }

    /** BRouter's own limit stretches and it ignores interrupts: a run that
     *  only ends when told to, like a slow search. */
    private fun stubbornEngine(stops: AtomicInteger) = OfflineRouter.Engine { _, _, stop ->
        stop.onStop { stops.incrementAndGet() }
        val t0 = System.currentTimeMillis()
        while (!stop.isStopped && System.currentTimeMillis() - t0 < 10_000) {
            try { Thread.sleep(5) } catch (_: InterruptedException) { }
        }
        if (stop.isStopped) null else okResult()
    }

    @Test fun aTimedOutRoute_isStopped_soTheNextOneGetsItsOwnBudget() = runBlocking {
        // A reroute after a timed-out one used to wait for it inside its own
        // budget and time out too.
        val stops = AtomicInteger()
        var stubborn = true
        val slow = stubbornEngine(stops)
        val router = OfflineRouter(timeoutMs = 300) { req, ms, stop ->
            if (stubborn) slow.run(req, ms, stop) else okResult()
        }
        val t0 = System.currentTimeMillis()
        assertNull(router.route("a", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null))
        val first = System.currentTimeMillis() - t0
        assertEquals(1, stops.get())
        assert(first < 2_000) { "the timed-out run took $first ms to end" }
        stubborn = false
        assertNotNull(router.route("b", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null))
    }

    @Test fun aCancelledRoute_isStopped() = runBlocking {
        val stops = AtomicInteger()
        val router = OfflineRouter(timeoutMs = 10_000, engine = stubbornEngine(stops))
        val job = launch { router.route("a", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null) }
        delay(150)
        val t0 = System.currentTimeMillis()
        job.cancelAndJoin()
        assertEquals(1, stops.get())
        assert(System.currentTimeMillis() - t0 < 2_000)
    }

    // --- The warm-up before the first route ---

    /** A tile folder holding the square [name] (any bytes will do for the router's fakes). */
    private fun storeWith(vararg names: String): OfflineTileStore {
        val dir = Files.createTempDirectory("tiles").toFile().apply { deleteOnExit() }
        for (n in names) File(dir, "$n.rd5").writeBytes(ByteArray(8))
        return OfflineTileStore(dir.path, JvmOfflineFiles())
    }

    @Test fun theWarmUp_runsAShortRouteInTheSquare_withTheRidersProfileAndAvoids() = runBlocking {
        var seen: OfflineRouter.Request? = null
        var seenMs = 0L
        val router = OfflineRouter(timeoutMs = 5_000) { req, ms, _ -> seen = req; seenMs = ms; okResult() }
        assertTrue(router.warmUp(storeWith("E10_N55"), GeoPoint(59.91, 10.75), TravelMode.CYCLING, RouteAvoidances(unpaved = true)))
        val r = seen!!
        assertEquals("wheel", r.profile)
        assertEquals(mapOf("avoid_unpaved" to "1"), r.params)
        assertEquals(GeoPoint(59.91, 10.75), r.points.first())
        assertEquals(2, r.points.size)
        assertEquals(OfflineRouter.WARM_UP_MS, seenMs)
    }

    @Test fun noSquare_noWarmUp() = runBlocking {
        var called = false
        val router = OfflineRouter(timeoutMs = 5_000) { _, _, _ -> called = true; okResult() }
        assertEquals(false, router.warmUp(storeWith(), null, TravelMode.CYCLING, RouteAvoidances.NONE))
        assertEquals(false, called)
    }

    @Test fun afterARouteWithTheSameProfile_theWarmUpHasNothingToDo() = runBlocking {
        val calls = AtomicInteger()
        val router = OfflineRouter(timeoutMs = 5_000) { _, _, _ -> calls.incrementAndGet(); okResult() }
        assertNotNull(router.route("r", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null))
        val store = storeWith("E10_N55")
        assertEquals(false, router.warmUp(store, null, TravelMode.CYCLING, RouteAvoidances.NONE))
        assertEquals(1, calls.get())
        // Another profile is not ready: BRouter keeps one.
        assertTrue(router.warmUp(store, null, TravelMode.WALKING, RouteAvoidances.NONE))
        assertEquals(2, calls.get())
    }

    @Test fun aRoute_stopsTheWarmUp_andWaitingForItIsNotCounted() = runBlocking {
        // The warm-up is in BRouter's profile read, which cannot be stopped;
        // the route waits for it outside its own budget and then routes.
        val warmStops = AtomicInteger()
        val router = OfflineRouter(timeoutMs = 300) { _, ms, stop ->
            if (ms == OfflineRouter.WARM_UP_MS) {
                stop.onStop { warmStops.incrementAndGet() }
                Thread.sleep(500)
                null
            } else {
                okResult()
            }
        }
        val warm = async { router.warmUp(storeWith("E10_N55"), null, TravelMode.CYCLING, RouteAvoidances.NONE) }
        delay(100)
        assertNotNull(router.route("r", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null))
        assertEquals(1, warmStops.get())
        assertEquals(false, warm.await())
    }

    @Test fun noWarmUp_whileARouteIsRunning() = runBlocking {
        val warmUps = AtomicInteger()
        val router = OfflineRouter(timeoutMs = 5_000) { _, ms, _ ->
            if (ms == OfflineRouter.WARM_UP_MS) warmUps.incrementAndGet() else Thread.sleep(300)
            okResult()
        }
        val route = async { router.route("r", wps, TravelMode.WALKING, RouteAvoidances.NONE, null) }
        delay(100)
        assertEquals(false, router.warmUp(storeWith("E10_N55"), null, TravelMode.CYCLING, RouteAvoidances.NONE))
        assertNotNull(route.await())
        assertEquals(0, warmUps.get())
    }

    @Test fun theWarmUpPoints_stayInADownloadedSquare() {
        // At the rider when a square has them.
        val near = GeoPoint(59.91, 10.75)
        val oslo = mapOf("E10_N55" to 65_000_000L)
        assertEquals(near, OfflineRouter.warmUpPoints(oslo, near)!!.first())
        // Elsewhere: the middle of the biggest square, not a small one of sea.
        assertEquals(GeoPoint(57.5, 12.5), OfflineRouter.warmUpPoints(oslo, GeoPoint(48.0, 2.0))!!.first())
        assertEquals(
            GeoPoint(57.5, 12.5),
            OfflineRouter.warmUpPoints(mapOf("E0_N55" to 40_000L, "junk" to 1L) + oslo, null)!!.first(),
        )
        // At the top edge the second stop goes south, inside the square.
        val top = OfflineRouter.warmUpPoints(oslo, GeoPoint(59.999, 10.5))!!
        assertTrue(top.all { RoutingTiles.nameFor(it.lat, it.lng) == "E10_N55" })
        assertNull(OfflineRouter.warmUpPoints(emptyMap(), near))
    }

    @Test fun aRunStoppedBeforeItsSearch_handsTheProfileBack() {
        // BRouter's cache kept the profile marked in use after such a run, so
        // the next route read it again: a first route that timed out in its
        // profile read made the second one read it too.
        val profiles = File("../app/src/main/assets/brouter").absolutePath
        val req = OfflineRouter.Request("wheel", mapOf("avoid_unsafe" to "1"), null, wps.map { GeoPoint(it.lat, it.lng) })
        val stop = OfflineRouter.EngineStop().apply { stop() }
        assertNull(OfflineRouter.runBrouter(profiles, profiles, req, 1_000, stop))
        val rc = RoutingContext().apply {
            localFunction = joinPath(profiles, "wheel.brf")
            keyValues = HashMap(req.params + ("turnInstructionMode" to "9"))
            memoryclass = 64
        }
        assertTrue("the profile was read again", ProfileCache.parseProfile(rc))
        ProfileCache.releaseProfile(rc)
    }

    @Test fun theFirstRunWithAProfile_getsTwiceTheTime() = runBlocking {
        // Nothing warmed the engine (a route right at start): its first run
        // rates every road type and gets twice the budget; the next does not.
        val router = OfflineRouter(timeoutMs = 300) { _, _, _ -> Thread.sleep(450); okResult() }
        assertNotNull(router.route("cold", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null))
        assertNull(router.route("warm", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null))
    }

    @Test fun aStopBeforeTheEngineListens_stillReachesIt() {
        val stop = OfflineRouter.EngineStop()
        stop.stop()
        var told = false
        stop.onStop { told = true }
        assert(told)
        assert(stop.isStopped)
    }
}
