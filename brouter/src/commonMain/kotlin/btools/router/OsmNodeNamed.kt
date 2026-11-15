/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.router

import btools.mapaccess.MatchedWaypoint
import btools.mapaccess.OsmNode
import btools.util.CheapRuler
import btools.util.jParseDouble
import kotlin.jvm.JvmField
import kotlin.math.sqrt

open class OsmNodeNamed : OsmNode {
  @JvmField var name: String? = null
  @JvmField var radius: Double = 0.0 // radius of nogopoint (in meters)
  @JvmField var nogoWeight: Double = 0.0  // weight for nogopoint
  @JvmField var isNogo: Boolean = false
  @JvmField var wpttype: Byte = MatchedWaypoint.WAYPOINT_TYPE_SHAPING // set default type

  constructor() : super()

  constructor(n: OsmNode) : super(n.ilon, n.ilat)

  override fun toString(): String {
    if (nogoWeight.isNaN()) {
      return ilon.toString() + "," + ilat + "," + name
    } else {
      return ilon.toString() + "," + ilat + "," + name + "," + nogoWeight
    }
  }

  fun distanceWithinRadius(lon1In: Int, lat1In: Int, lon2In: Int, lat2In: Int, totalSegmentLength: Double): Double {
    var lon1 = lon1In
    var lat1 = lat1In
    var lon2 = lon2In
    var lat2 = lat2In
    val lonlat2m = CheapRuler.getLonLatToMeterScales((lat1 + lat2) shr 1)

    var isFirstPointWithinCircle = CheapRuler.distance(lon1, lat1, ilon, ilat) < radius
    var isLastPointWithinCircle = CheapRuler.distance(lon2, lat2, ilon, ilat) < radius
    // First point is within the circle
    if (isFirstPointWithinCircle) {
      // Last point is within the circle
      if (isLastPointWithinCircle) {
        return totalSegmentLength
      }
      // Last point is not within the circle
      // Just swap points and go on with first first point not within the
      // circle now.
      // Swap longitudes
      var tmp = lon2
      lon2 = lon1
      lon1 = tmp
      // Swap latitudes
      tmp = lat2
      lat2 = lat1
      lat1 = tmp
      // Fix boolean values
      isLastPointWithinCircle = isFirstPointWithinCircle
      isFirstPointWithinCircle = false
    }
    // Distance between the initial point and projection of center of
    // the circle on the current segment.
    val initialToProject = (
      (lon2 - lon1) * (ilon - lon1) * lonlat2m[0] * lonlat2m[0]
        + (lat2 - lat1) * (ilat - lat1) * lonlat2m[1] * lonlat2m[1]
      ) / totalSegmentLength
    // Distance between the initial point and the center of the circle.
    val initialToCenter = CheapRuler.distance(ilon, ilat, lon1, lat1)
    // Half length of the segment within the circle
    val halfDistanceWithin = sqrt(
      radius * radius - (
        initialToCenter * initialToCenter -
          initialToProject * initialToProject
        )
    )
    // Last point is within the circle
    if (isLastPointWithinCircle) {
      return halfDistanceWithin + (totalSegmentLength - initialToProject)
    }
    return 2 * halfDistanceWithin
  }

  companion object {
    fun decodeNogo(s: String): OsmNodeNamed {
      val n = OsmNodeNamed()
      val idx1 = s.indexOf(',')
      n.ilon = s.substring(0, idx1).toInt()
      val idx2 = s.indexOf(',', idx1 + 1)
      n.ilat = s.substring(idx1 + 1, idx2).toInt()
      val idx3 = s.indexOf(',', idx2 + 1)
      if (idx3 == -1) {
        n.name = s.substring(idx2 + 1)
        n.nogoWeight = Double.NaN
      } else {
        n.name = s.substring(idx2 + 1, idx3)
        n.nogoWeight = jParseDouble(s.substring(idx3 + 1))
      }
      n.isNogo = true
      return n
    }
  }
}
