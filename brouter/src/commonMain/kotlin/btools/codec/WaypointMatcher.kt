package btools.codec

/**
 * a waypoint matcher gets way geometries
 * from the decoder to find the closest
 * matches to the waypoints
 */
interface WaypointMatcher {
  fun start(ilonStart: Int, ilatStart: Int, ilonTarget: Int, ilatTarget: Int, useAsStartWay: Boolean): Boolean

  fun transferNode(ilon: Int, ilat: Int)

  fun end()

  fun hasMatch(lon: Int, lat: Int): Boolean
}
