package com.eried.eucplanet.share

import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.*
import org.junit.Test

class ShareModelTest {
    /** A phone sharing its location with no wheel paired reads 0 km/h, 0 %
     *  and 0 degC out of the empty telemetry, and a peer cannot tell those
     *  zeros from a parked wheel on a cold morning. So the toggle alone does
     *  not publish stats; the wheel has to be connected. */
    @Test fun noWheelConnected_publishesNoStats() {
        val wheel = WheelData(speed = 0f, batteryPercent = 0, maxTemperature = 0f)
        assertNull(shareStatsOf(shareStats = true, wheelConnected = false, wheel = wheel))
    }
    @Test fun connectedWheel_publishesItsReadings() {
        val wheel = WheelData(speed = 24.5f, batteryPercent = 71, maxTemperature = 33f)
        assertEquals(
            ShareStats(24.5f, 71, 33f),
            shareStatsOf(shareStats = true, wheelConnected = true, wheel = wheel)
        )
    }
    @Test fun statsToggleOffPublishesNothing() {
        val wheel = WheelData(speed = 24.5f, batteryPercent = 71, maxTemperature = 33f)
        assertNull(shareStatsOf(shareStats = false, wheelConnected = true, wheel = wheel))
    }
    private fun p(t: Long = 1000L, stats: ShareStats? = ShareStats(25.5f, 80, 31f)) = SharePayload(
        id = "abc", name = "Erwin", mode = IdentityMode.SESSION, color = "#E53935", icon = null,
        avatarUrl = null, flag = "NO", lat = 59.91, lng = 10.75, heading = 90f, t = t, stats = stats)

    @Test fun payloadJsonRoundTrip() {
        val back = SharePayload.fromJson(p().toJson())!!
        assertEquals(p(), back)
    }
    @Test fun payloadWithoutStats_omitsStatsKey() {
        val json = p(stats = null).toJson()
        assertFalse(json.contains("\"stats\""))
        assertNull(SharePayload.fromJson(json)!!.stats)
    }
    @Test fun payloadRejectsWrongVersionOrGarbage() {
        assertNull(SharePayload.fromJson("{\"v\":2}")); assertNull(SharePayload.fromJson("nope"))
    }
    /** A position that is not a real number is dropped rather than carried:
     *  it cannot be drawn, and JSONObject.put refuses NaN and infinity, so
     *  re-serialising it for the map bridge would throw. */
    @Test fun payloadRejectsNonFiniteCoordinates() {
        fun frame(lat: String, lng: String) = """
            {"v":1,"id":"abc","name":"Erwin","mode":"ANON","color":"#E53935",
             "lat":$lat,"lng":$lng,"t":1000}""".trimIndent()
        // 1e400 overflows a double to +Infinity while still being valid JSON.
        assertNull(SharePayload.fromJson(frame("1e400", "10.75")))
        assertNull(SharePayload.fromJson(frame("59.91", "-1e400")))
        assertNull(SharePayload.fromJson(frame("NaN", "10.75")))
        // The same frame with real coordinates still parses, so the guard is
        // rejecting the value and not the shape.
        assertEquals(59.91, SharePayload.fromJson(frame("59.91", "10.75"))!!.lat, 0.0)
    }
    /** The relay closes a room at capacity with 1013, and the group dialog
     *  decides "this group is full" by comparing against the typed error the
     *  session raises for exactly that code. Both halves are a contract with
     *  the relay, so they are pinned here rather than left to be reworded. */
    @Test fun roomFullCloseCodeAndErrorArePinned() {
        assertEquals(1013, ShareSession.CLOSE_ROOM_FULL)
        assertEquals("room_full", ShareSession.ERR_ROOM_FULL)
    }
    @Test fun paletteIsFixedAndWraps() {
        assertEquals(12, PeerPalette.COLORS.size)
        assertEquals("#E53935", PeerPalette.colorFor(0)); assertEquals("#1E88E5", PeerPalette.colorFor(1))
        assertEquals(PeerPalette.colorFor(0), PeerPalette.colorFor(12))
    }
    @Test fun stalenessThresholds() {
        assertEquals(Freshness.FRESH, Staleness.of(0)); assertEquals(Freshness.FRESH, Staleness.of(14_999))
        assertEquals(Freshness.STALE, Staleness.of(15_000)); assertEquals(Freshness.STALE, Staleness.of(119_999))
        assertEquals(Freshness.LOST, Staleness.of(120_000))
    }
    @Test fun trailKeepsOnlyRecent_andFades() {
        val tr = Trail(maxAgeMs = 5 * 60_000L)
        tr.add(1.0, 1.0, t = 0L)                 // 10 min old at now=600s -> dropped
        tr.add(2.0, 2.0, t = 400_000L)
        tr.add(3.0, 3.0, t = 600_000L)
        val pts = tr.points(now = 600_000L)
        assertEquals(2, pts.size)
        assertEquals(3.0, pts.last().lat, 0.0); assertEquals(1.0f, pts.last().alpha, 0.001f)
        assertTrue(pts.first().alpha < pts.last().alpha && pts.first().alpha >= 0.15f)
    }
    /** The map draws one polyline per band and reads the band number straight
     *  off the wire, so a band that ever landed outside 0..3 would index past
     *  the page's opacity table. The default trail is five minutes, and these
     *  are the boundaries the page's PEER_TRAIL_OPACITY comment names. */
    @Test fun trailBandsAreFourBucketsInOrder() {
        val fiveMin = 5 * 60_000L
        assertEquals(4, TrailBands.COUNT)
        assertEquals(0, TrailBands.of(0L, fiveMin))
        assertEquals(0, TrailBands.of(59_999L, fiveMin))
        assertEquals(1, TrailBands.of(60_000L, fiveMin))
        assertEquals(1, TrailBands.of(119_999L, fiveMin))
        assertEquals(2, TrailBands.of(120_000L, fiveMin))
        assertEquals(2, TrailBands.of(209_999L, fiveMin))
        assertEquals(3, TrailBands.of(210_000L, fiveMin))
        // Older than the longest trail the settings allow still lands in the
        // last band rather than off the end of it.
        assertEquals(3, TrailBands.of(60 * 60_000L, fiveMin))
        // Monotonic: an older point can never come back to a brighter band.
        var previous = 0
        for (ageS in 0..400) {
            val band = TrailBands.of(ageS * 1000L, fiveMin)
            assertTrue("band went backwards at $ageS s", band >= previous)
            assertTrue(band in 0 until TrailBands.COUNT)
            previous = band
        }
    }

    /** The trail length is a 1..30 minute rider setting, so the bands are
     *  fractions of it. A fixed table gave a one-minute trail a single flat
     *  band and a thirty-minute one 26 minutes of the faintest. */
    @Test fun trailBandsScaleWithTheTrailLength() {
        assertArrayEquals(longArrayOf(60_000L, 120_000L, 210_000L), TrailBands.edgesMs(5 * 60_000L))
        // One minute: the same four steps, a fifth of a minute each to start.
        val oneMin = 60_000L
        assertArrayEquals(longArrayOf(12_000L, 24_000L, 42_000L), TrailBands.edgesMs(oneMin))
        assertEquals(0, TrailBands.of(11_999L, oneMin))
        assertEquals(1, TrailBands.of(12_000L, oneMin))
        assertEquals(2, TrailBands.of(24_000L, oneMin))
        assertEquals(3, TrailBands.of(42_000L, oneMin))
        // Thirty minutes: the faintest band starts at 21 minutes, not at 3.5.
        val thirtyMin = 30 * 60_000L
        assertArrayEquals(
            longArrayOf(6 * 60_000L, 12 * 60_000L, 21 * 60_000L),
            TrailBands.edgesMs(thirtyMin)
        )
        assertEquals(0, TrailBands.of(5 * 60_000L, thirtyMin))
        assertEquals(1, TrailBands.of(6 * 60_000L, thirtyMin))
        assertEquals(2, TrailBands.of(12 * 60_000L, thirtyMin))
        assertEquals(3, TrailBands.of(21 * 60_000L, thirtyMin))
        // Every settable trail length keeps all four bands, in order, with no
        // boundary landing on top of another.
        for (minutes in 1..30) {
            val maxAge = minutes * 60_000L
            val edges = TrailBands.edgesMs(maxAge)
            assertEquals(TrailBands.COUNT - 1, edges.size)
            assertTrue("edges not ascending at $minutes min", edges[0] < edges[1] && edges[1] < edges[2])
            assertTrue("last edge past the trail at $minutes min", edges[2] < maxAge)
            assertEquals(0, TrailBands.of(0L, maxAge))
            assertEquals(TrailBands.COUNT - 1, TrailBands.of(maxAge, maxAge))
        }
    }

    /** The band stamp reads the ring's own length, so the two cannot drift. */
    @Test fun trailExposesTheAgeTheBandsAreCutFrom() {
        assertEquals(7 * 60_000L, Trail(maxAgeMs = 7 * 60_000L).maxAgeMs)
    }
}
