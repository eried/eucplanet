package com.eried.eucplanet.nav.offline

import btools.router.VoiceHint
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.TurnType
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.RouteAvoidances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BRouter's track and hints become the same NavRoute the online routers give. */
class BrouterMappingTest {

    @Test fun commands_mapToOurTurns() {
        val expected = mapOf(
            VoiceHint.C to TurnType.CONTINUE,
            VoiceHint.TL to TurnType.LEFT,
            VoiceHint.TSLL to TurnType.SLIGHT_LEFT,
            VoiceHint.TSHL to TurnType.SHARP_LEFT,
            VoiceHint.TR to TurnType.RIGHT,
            VoiceHint.TSLR to TurnType.SLIGHT_RIGHT,
            VoiceHint.TSHR to TurnType.SHARP_RIGHT,
            VoiceHint.KL to TurnType.SLIGHT_LEFT,
            VoiceHint.KR to TurnType.SLIGHT_RIGHT,
            VoiceHint.EL to TurnType.SLIGHT_LEFT,
            VoiceHint.ER to TurnType.SLIGHT_RIGHT,
            VoiceHint.TU to TurnType.UTURN,
            VoiceHint.TLU to TurnType.UTURN,
            VoiceHint.TRU to TurnType.UTURN,
            VoiceHint.RNDB to TurnType.ROUNDABOUT,
            VoiceHint.RNLB to TurnType.ROUNDABOUT,
            VoiceHint.BL to TurnType.CONTINUE,
        )
        expected.forEach { (cmd, type) -> assertEquals("cmd $cmd", type, BrouterMapping.turnType(cmd)) }
    }

    @Test fun profiles_wheelForCycling_noneForCars() {
        assertEquals("wheel", BrouterMapping.profileFor(TravelMode.CYCLING))
        assertEquals("hiking-mountain", BrouterMapping.profileFor(TravelMode.WALKING))
        assertNull(BrouterMapping.profileFor(TravelMode.DRIVING))
        assertNull(BrouterMapping.profileFor(TravelMode.STRAIGHT))
    }

    @Test fun avoids_becomeProfileSwitches() {
        assertEquals(emptyMap<String, String>(), BrouterMapping.avoidParams(RouteAvoidances.NONE))
        assertEquals(
            mapOf("avoid_unsafe" to "true", "allow_ferries" to "false", "avoid_unpaved" to "true"),
            BrouterMapping.avoidParams(RouteAvoidances(highways = true, ferries = true, unpaved = true)),
        )
    }

    @Test fun aTrack_becomesARoute() {
        // Three points 100 m apart going north, a left turn at the middle one.
        val pts = listOf(GeoPoint(59.0, 10.0), GeoPoint(59.0009, 10.0), GeoPoint(59.0018, 10.0))
        val wps = listOf(Waypoint(59.0, 10.0), Waypoint(59.0018, 10.0))
        val r = BrouterMapping.toNavRoute(
            "r", wps, TravelMode.CYCLING, pts,
            listOf(BrouterMapping.Hint(cmd = VoiceHint.TL, index = 1, exit = 0)),
            distanceM = 200.0,
        )
        assertEquals(pts, r.geometry)
        assertEquals(200.0, r.totalDistanceM, 0.0)
        assertEquals(listOf(TurnType.DEPART, TurnType.LEFT, TurnType.ARRIVE), r.maneuvers.map { it.type })
        assertEquals(pts[1], r.maneuvers[1].point)
        assertEquals(100.0, r.maneuvers[1].distanceFromStartM, 2.0)
        assertEquals(200.0, r.maneuvers[2].distanceFromStartM, 4.0)
        assertEquals("", r.maneuvers[1].streetName)
    }

    @Test fun aHintPastTheEnd_isDropped() {
        val pts = listOf(GeoPoint(59.0, 10.0), GeoPoint(59.0009, 10.0))
        val r = BrouterMapping.toNavRoute(
            "r", emptyList(), TravelMode.CYCLING, pts,
            listOf(BrouterMapping.Hint(VoiceHint.TR, index = 9, exit = 0)), distanceM = 100.0,
        )
        assertEquals(listOf(TurnType.DEPART, TurnType.ARRIVE), r.maneuvers.map { it.type })
    }

    @Test fun coordinates_roundTripBrouterInts() {
        val (ilon, ilat) = BrouterMapping.toBrouter(GeoPoint(59.91, 10.75))
        assertEquals(190_750_000, ilon)
        assertEquals(149_910_000, ilat)
        val p = BrouterMapping.fromBrouter(ilon, ilat)
        assertEquals(59.91, p.lat, 1e-6)
        assertEquals(10.75, p.lng, 1e-6)
    }
}
