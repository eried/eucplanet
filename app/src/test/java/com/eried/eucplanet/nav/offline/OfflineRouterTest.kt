package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.RouteAvoidances
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        val router = OfflineRouter(timeoutMs = 5_000) { _, _ ->
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
        val router = OfflineRouter(timeoutMs = 100) { _, _ -> Thread.sleep(2_000); okResult() }
        assertNull(router.route("r", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null))
    }

    @Test fun aFailingEngine_givesNull() = runBlocking {
        val router = OfflineRouter(timeoutMs = 5_000) { _, _ -> throw IllegalStateException("no tile") }
        assertNull(router.route("r", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null))
    }

    @Test fun driving_isNeverOffline() = runBlocking {
        var called = false
        val router = OfflineRouter(timeoutMs = 5_000) { _, _ -> called = true; okResult() }
        assertNull(router.route("r", wps, TravelMode.DRIVING, RouteAvoidances.NONE, null))
        assertEquals(false, called)
    }

    @Test fun theRequest_carriesProfileAvoidsAndHeading() = runBlocking {
        var seen: OfflineRouter.Request? = null
        val router = OfflineRouter(timeoutMs = 5_000) { req, _ -> seen = req; okResult() }
        router.route("r", wps, TravelMode.CYCLING, RouteAvoidances(ferries = true), heading = 95.0)
        val r = seen!!
        assertEquals("wheel", r.profile)
        assertEquals(mapOf("allow_ferries" to "false"), r.params)
        assertEquals(95, r.headingDeg)
        assertEquals(wps.map { GeoPoint(it.lat, it.lng) }, r.points)
    }

    @Test fun waitingForTheEngine_countsAgainstTheTimeout() = runBlocking {
        // A reroute queued behind a slow route must not wait past its budget.
        val router = OfflineRouter(timeoutMs = 300) { _, _ -> Thread.sleep(250); okResult() }
        val t0 = System.currentTimeMillis()
        val both = listOf(
            async { router.route("a", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null) },
            async { router.route("b", wps, TravelMode.CYCLING, RouteAvoidances.NONE, null) },
        ).awaitAll()
        val ms = System.currentTimeMillis() - t0
        assertEquals(1, both.count { it != null })
        assert(ms < 700) { "took $ms ms" }
    }
}
