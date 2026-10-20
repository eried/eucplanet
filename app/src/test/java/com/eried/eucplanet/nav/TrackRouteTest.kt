package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.TurnType
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.util.GpxData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRouteTest {

    // About 111 m per 0.001 degree of latitude; near the equator the same for longitude.
    private fun p(northM: Double, eastM: Double) = GeoPoint(northM / 111_320.0, eastM / 111_320.0)

    /** A line north for [m] metres in 10 m steps from [from]. */
    private fun north(from: GeoPoint, m: Int) = (1..m / 10).map { GeoPoint(from.lat + it * 10 / 111_320.0, from.lng) }
    private fun east(from: GeoPoint, m: Int) = (1..m / 10).map { GeoPoint(from.lat, from.lng + it * 10 / 111_320.0) }
    private fun west(from: GeoPoint, m: Int) = (1..m / 10).map { GeoPoint(from.lat, from.lng - it * 10 / 111_320.0) }
    private fun south(from: GeoPoint, m: Int) = (1..m / 10).map { GeoPoint(from.lat - it * 10 / 111_320.0, from.lng) }

    /** 500 m north, then 500 m east: one right turn. */
    private val ell: List<GeoPoint> = run {
        val a = listOf(p(0.0, 0.0)) + north(p(0.0, 0.0), 500)
        a + east(a.last(), 500)
    }

    /** A 400 m square that ends where it starts. */
    private val loop: List<GeoPoint> = run {
        var l = listOf(p(0.0, 0.0))
        l = l + north(l.last(), 400); l = l + east(l.last(), 400)
        l = l + south(l.last(), 400); l + west(l.last(), 400)
    }

    @Test
    fun `places are kept once, only on the line, in riding order`() {
        val onLate = Waypoint(p(500.0, 300.0).lat, p(500.0, 300.0).lng, "Bridge ")
        val onEarly = Waypoint(p(200.0, 20.0).lat, p(200.0, 20.0).lng, "Cafe")
        val far = Waypoint(p(200.0, 400.0).lat, p(200.0, 400.0).lng, "Far")
        val atStart = Waypoint(p(5.0, 0.0).lat, p(5.0, 0.0).lng, "Start")
        val places = listOf(onLate, far, onLate, onEarly, atStart, onLate)
        val kept = TrackRoute.placesOnTrack(places, ell)
        assertEquals(listOf("Cafe", "Bridge"), kept.map { it.name })
    }

    @Test
    fun `locked stops are start, places, end`() {
        val cafe = Waypoint(p(200.0, 0.0).lat, p(200.0, 0.0).lng, "Cafe")
        val stops = TrackRoute.stops(ell, listOf(cafe))
        assertEquals(3, stops.size)
        assertEquals(ell.first(), stops.first().point())
        assertEquals("Cafe", stops[1].name)
        assertEquals(ell.last(), stops.last().point())
    }

    @Test
    fun `a right angle is one right turn, then the arrival`() {
        val turns = TrackRoute.turns(ell)
        assertEquals(listOf(TurnType.RIGHT, TurnType.ARRIVE), turns.map { it.type })
        assertEquals(500.0, turns[0].distanceFromStartM, 15.0)
    }

    @Test
    fun `a straight line has only the arrival`() {
        val line = listOf(p(0.0, 0.0)) + north(p(0.0, 0.0), 800)
        assertEquals(listOf(TurnType.ARRIVE), TrackRoute.turns(line).map { it.type })
    }

    @Test
    fun `turn direction and size`() {
        assertEquals(90.0, TrackRoute.turnDeg(0.0, 90.0), 1e-9)
        assertEquals(-90.0, TrackRoute.turnDeg(10.0, 280.0), 1e-9)
        assertEquals(TurnType.SLIGHT_LEFT, TrackRoute.turnType(-45.0))
        assertEquals(TurnType.SHARP_RIGHT, TrackRoute.turnType(140.0))
        assertEquals(TurnType.UTURN, TrackRoute.turnType(-175.0))
    }

    @Test
    fun `the route rides the line itself and never asks a router`() {
        val r = TrackRoute.route("Ride", ell, TrackRoute.stops(ell, emptyList()))
        assertEquals(TravelMode.STRAIGHT, r.travelMode)
        assertEquals(ell, r.geometry)
        assertTrue(r.isLockedTrack)
        assertEquals(1000.0, r.totalDistanceM, 5.0)
    }

    @Test
    fun `a leg from a stop part way runs to the end and keeps the whole track`() {
        val mid = Waypoint(p(500.0, 0.0).lat, p(500.0, 0.0).lng, "Corner")
        val end = Waypoint(ell.last().lat, ell.last().lng)
        val leg = TrackRoute.leg("Ride", ell, listOf(mid, end))
        assertEquals(500.0, leg.totalDistanceM, 5.0)
        assertEquals(ell, leg.track)
        assertEquals(listOf(TurnType.ARRIVE), leg.maneuvers.map { it.type })
    }

    @Test
    fun `a loop's leg from its start is the whole loop`() {
        val start = Waypoint(loop.first().lat, loop.first().lng)
        val leg = TrackRoute.leg("Loop", loop, listOf(start, start.copy()))
        assertEquals(1600.0, leg.totalDistanceM, 5.0)
    }

    @Test
    fun `stops along a loop are measured in order, its end is not its start`() {
        val along = TrackRoute.alongM(listOf(loop.first(), p(400.0, 200.0), loop.last()), loop)
        assertEquals(0.0, along[0], 1e-6)
        assertEquals(600.0, along[1], 5.0)
        assertEquals(1600.0, along[2], 5.0)
    }

    @Test
    fun `progress at a loop's start stays at the start, then follows the ride round`() {
        val prog = TrackRoute.Progress(loop)
        assertEquals(0.0, prog.locate(p(1.0, 0.0))!!.alongM, 5.0)
        // Ride round in 50 m steps: the position only ever moves on.
        var last = 0.0
        for (i in 1 until loop.size step 5) {
            val at = prog.locate(loop[i])!!.alongM
            assertTrue("went back at $i: $at < $last", at >= last - 1.0)
            last = at
        }
        // Back at the start after the ride round, the end is where we are.
        assertEquals(1600.0, prog.locate(p(1.0, 0.0))!!.alongM, 5.0)
    }

    @Test
    fun `unlocking keeps the corner first, and more stops stray less`() {
        val simp = TrackRoute.Simplifier(ell)
        assertEquals(2, simp.fixedCount)
        val one = simp.pick(1)
        assertEquals(3, one.size)
        assertEquals(500.0, GeoMath.distanceM(ell[one[1]], ell.first()), 15.0)
        assertTrue(simp.maxOffM(0) > 300.0)
        assertTrue(simp.maxOffM(1) < 1.0)
        assertEquals(1, simp.fewestWithin(50.0, 48))
        assertEquals(0, simp.fewestWithin(50.0, 0))
    }

    @Test
    fun `unlocking keeps the places on the line as stops`() {
        val simp = TrackRoute.Simplifier(ell, keep = listOf(10))
        assertEquals(3, simp.fixedCount)
        assertTrue(10 in simp.pick(0))
    }

    @Test
    fun `route points win, then a track, then places`() {
        val w = Waypoint(1.0, 2.0, "A")
        val t = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertTrue(GpxData("x", listOf(w), listOf(w), t).contents() is GpxData.Contents.Stops)
        val track = GpxData("x", emptyList(), listOf(w), t).contents()
        assertTrue(track is GpxData.Contents.Track)
        assertEquals(listOf(w), (track as GpxData.Contents.Track).places)
        assertEquals(GpxData.Contents.Stops(listOf(w)), GpxData("x", emptyList(), listOf(w), emptyList()).contents())
        assertEquals(GpxData.Contents.Nothing, GpxData("x", emptyList(), emptyList(), emptyList()).contents())
    }

    @Test
    fun `a locked track counts as a fixed line`() {
        val r = TrackRoute.route("Ride", ell, TrackRoute.stops(ell, emptyList()))
        assertTrue(r.isFixedTrack(r.waypoints))
    }
}
