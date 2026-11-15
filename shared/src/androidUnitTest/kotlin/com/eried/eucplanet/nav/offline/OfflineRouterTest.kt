package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.RouteAvoidances
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

    @Test fun aStopBeforeTheEngineListens_stillReachesIt() {
        val stop = OfflineRouter.EngineStop()
        stop.stop()
        var told = false
        stop.onStop { told = true }
        assert(told)
        assert(stop.isStopped)
    }
}
