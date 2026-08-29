package com.eried.eucplanet.share

import org.junit.Assert.*
import org.junit.Test

class ShareModelTest {
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
}
