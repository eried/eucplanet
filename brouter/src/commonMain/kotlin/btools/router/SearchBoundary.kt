/**
 * static helper class for handling datafiles
 *
 * @author ab
 */
package btools.router

import btools.mapaccess.OsmNode
import kotlin.jvm.JvmField


class SearchBoundary(n: OsmNode, radius: Int, direction: Int) {

  private var minlon0: Int = 0
  private var minlat0: Int = 0
  private var maxlon0: Int = 0
  private var maxlat0: Int = 0

  private var minlon: Int = 0
  private var minlat: Int = 0
  private var maxlon: Int = 0
  private var maxlat: Int = 0
  private var radius: Int = 0
  private var p: OsmNode

  @JvmField internal var direction: Int = 0

  /**
   * @param radius Search radius in meters.
   */
  init {
    this.radius = radius
    this.direction = direction

    p = OsmNode(n.ilon, n.ilat)

    val lon = (n.ilon / 5000000) * 5000000
    val lat = (n.ilat / 5000000) * 5000000

    minlon0 = lon - 5000000
    minlat0 = lat - 5000000
    maxlon0 = lon + 10000000
    maxlat0 = lat + 10000000

    minlon = lon - 1000000
    minlat = lat - 1000000
    maxlon = lon + 6000000
    maxlat = lat + 6000000
  }

  fun isInBoundary(n: OsmNode, cost: Int): Boolean {
    if (radius > 0) {
      return n.calcDistance(p) < radius
    }
    if (cost == 0) {
      return n.ilon > minlon0 && n.ilon < maxlon0 && n.ilat > minlat0 && n.ilat < maxlat0
    }
    return n.ilon > minlon && n.ilon < maxlon && n.ilat > minlat && n.ilat < maxlat
  }

  fun getBoundaryDistance(n: OsmNode): Int {
    return when (direction) {
      0 -> n.calcDistance(OsmNode(n.ilon, minlat))
      1 -> n.calcDistance(OsmNode(minlon, n.ilat))
      2 -> n.calcDistance(OsmNode(n.ilon, maxlat))
      3 -> n.calcDistance(OsmNode(maxlon, n.ilat))
      else -> throw IllegalArgumentException("undefined direction: " + direction)
    }
  }

  companion object {
    fun getFileName(n: OsmNode): String {
      val lon = (n.ilon / 5000000) * 5000000
      val lat = (n.ilat / 5000000) * 5000000

      val dlon = lon / 1000000 - 180
      val dlat = lat / 1000000 - 90

      val slon = if (dlon < 0) "W" + (-dlon) else "E" + dlon
      val slat = if (dlat < 0) "S" + (-dlat) else "N" + dlat
      return slon + "_" + slat + ".trf"
    }
  }

}
