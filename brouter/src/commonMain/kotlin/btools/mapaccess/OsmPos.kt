/**
 * Interface for a position (OsmNode or OsmPath)
 *
 * @author ab
 */
package btools.mapaccess

interface OsmPos {
  fun getILat(): Int

  fun getILon(): Int

  fun getSElev(): Short

  fun getElev(): Double

  fun calcDistance(p: OsmPos): Int

  fun getIdFromPos(): Long

}
