package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchMapSessionTest {
    private fun frame(
        phoneSessionId: String = "phone-1",
        viewerId: String = "viewer-1",
        viewerEpoch: Long = 1L,
        presenceSequence: Long = 1L,
        sequence: Long = 1L,
        navigationSessionId: String = "nav-1",
        routeRevision: Long = 1L,
        fixAgeMs: Long = 100L,
        fixMaxAgeMs: Long = 2_000L,
    ) = WatchMapFrame(
        phoneSessionId = phoneSessionId,
        viewerId = viewerId,
        viewerEpoch = viewerEpoch,
        presenceSequence = presenceSequence,
        sequence = sequence,
        enabled = true,
        headingUp = false,
        layerId = "OSM",
        unavailableTiles = emptyList(),
        locationStatus = WatchMapLocationStatus.LIVE,
        fix = WatchMapFix(WatchMapPoint(59.91, 10.75), fixAgeMs),
        anchor = WatchMapPoint(59.91, 10.75),
        fixMaxAgeMs = fixMaxAgeMs,
        headingDeg = 10f,
        navigationSessionId = navigationSessionId,
        navigationActive = true,
        routeRevision = routeRevision,
        target = WatchMapPoint(59.92, 10.76),
        cue = WatchMapCue(45f, "Turn right", "100 m", false),
    )

    private fun route(sessionId: String = "nav-1", revision: Long = 1L) = WatchMapRoute(
        navigationSessionId = sessionId,
        revision = revision,
        coordinates = doubleArrayOf(59.91, 10.75, 59.92, 10.76),
    )

    @Test
    fun routeStopsDisplayingAsSoonAsFrameChangesRevision() {
        val session = WatchMapSession()
        session.begin("viewer-1", 1L)
        session.sentPresence(1L, 100L)
        assertTrue(session.acceptFrame(frame(), 200L))
        val route = route()
        assertTrue(session.acceptRoute(route))
        assertSame(route, session.snapshot(200L).route)

        session.sentPresence(2L, 300L)
        assertTrue(session.acceptFrame(frame(presenceSequence = 2L, sequence = 2L, routeRevision = 2L), 350L))

        assertNull(session.snapshot(350L).route)
    }

    @Test
    fun lateRouteFromPreviousNavigationSessionCannotReplaceCurrentRoute() {
        val session = WatchMapSession()
        session.begin("viewer-1", 1L)
        session.sentPresence(1L, 100L)
        assertTrue(session.acceptFrame(frame(), 200L))
        assertTrue(session.acceptRoute(route()))

        session.sentPresence(2L, 300L)
        assertTrue(
            session.acceptFrame(
                frame(
                    presenceSequence = 2L,
                    sequence = 2L,
                    navigationSessionId = "nav-2",
                    routeRevision = 1L,
                ),
                350L,
            ),
        )
        val currentRoute = route("nav-2", 1L)
        assertTrue(session.acceptRoute(currentRoute))
        assertFalse(session.acceptRoute(route("nav-1", 1L)))
        assertSame(currentRoute, session.snapshot(350L).route)
    }

    @Test
    fun routeBeforeFrameIsRejectedThenAcceptedAfterMatchingFrame() {
        val session = WatchMapSession()
        session.begin("viewer-1", 1L)
        val route = route()
        assertFalse(session.acceptRoute(route))

        session.sentPresence(1L, 100L)
        assertTrue(session.acceptFrame(frame(), 200L))
        assertTrue(session.acceptRoute(route))
        assertSame(route, session.snapshot(200L).route)
    }

    @Test
    fun expiredLinkDisablesFixEligibilityButKeepsCachedGeometry() {
        val session = WatchMapSession()
        session.begin("viewer-1", 1L)
        session.sentPresence(1L, 100L)
        assertTrue(session.acceptFrame(frame(fixMaxAgeMs = 10_000L), 200L))
        val route = route()
        assertTrue(session.acceptRoute(route))

        val expired = session.snapshot(200L + WatchMapProtocol.LINK_STALE_MS)
        assertFalse(expired.linkLive)
        assertFalse(expired.fixLive)
        assertSame(route, expired.route)
        assertEquals("Turn right", expired.frame?.cue?.primary)
    }

    @Test
    fun fixAgeContinuesFromEchoedPresenceTimeWithoutNewFrames() {
        val session = WatchMapSession()
        session.begin("viewer-1", 1L)
        session.sentPresence(1L, 100L)
        assertTrue(session.acceptFrame(frame(fixAgeMs = 700L, fixMaxAgeMs = 1_000L), 200L))

        assertTrue(session.snapshot(400L).fixLive)
        assertFalse(session.snapshot(401L).fixLive)
    }

    @Test
    fun foreignAndOlderPresenceEchoesDoNotRefreshTheLiveClock() {
        val session = WatchMapSession()
        session.begin("viewer-1", 1L)
        session.sentPresence(1L, 100L)
        session.sentPresence(2L, 200L)
        assertTrue(session.acceptFrame(frame(presenceSequence = 2L), 250L))

        assertFalse(session.acceptFrame(frame(presenceSequence = 99L, sequence = 2L), 2_000L))
        assertFalse(session.acceptFrame(frame(presenceSequence = 1L, sequence = 3L), 2_100L))
        assertFalse(session.snapshot(250L + WatchMapProtocol.LINK_STALE_MS).linkLive)
    }

    @Test
    fun phoneSessionHandoffNeedsANewerEchoAndCannotReturnToRetiredSession() {
        val session = WatchMapSession()
        session.begin("viewer-1", 1L)
        session.sentPresence(1L, 100L)
        session.sentPresence(2L, 200L)
        assertTrue(session.acceptFrame(frame(phoneSessionId = "phone-old", presenceSequence = 1L), 250L))
        assertFalse(
            session.acceptFrame(
                frame(phoneSessionId = "phone-new", presenceSequence = 1L, sequence = 1L),
                260L,
            ),
        )
        assertTrue(
            session.acceptFrame(
                frame(phoneSessionId = "phone-new", presenceSequence = 2L, sequence = 1L),
                270L,
            ),
        )
        assertFalse(
            session.acceptFrame(
                frame(phoneSessionId = "phone-old", presenceSequence = 2L, sequence = 2L),
                280L,
            ),
        )
    }
}
