package btools.router

import btools.mapaccess.OsmPos
import btools.util.CheapRuler
import btools.util.jround
import kotlin.jvm.JvmField
import kotlin.math.max

/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */

open class OsmPathElement internal constructor() : OsmPos {
  private var ilat: Int = 0 // latitude
  private var ilon: Int = 0 // longitude
  private var selev: Short = 0 // longitude

  @JvmField internal var message: MessageData? = null // description

  @JvmField var cost: Int = 0

  // interface OsmPos
  final override fun getILat(): Int {
    return ilat
  }

  final override fun getILon(): Int {
    return ilon
  }

  final override fun getSElev(): Short {
    return selev
  }

  fun setSElev(s: Short) {
    selev = s
  }

  final override fun getElev(): Double {
    return selev / 4.0
  }

  fun getTime(): Float {
    val m = message
    return if (m == null) 0f else m.time
  }

  fun setTime(t: Float) {
    val m = message
    if (m != null) {
      m.time = t
    }
  }

  fun getEnergy(): Float {
    val m = message
    return if (m == null) 0f else m.energy
  }

  fun setEnergy(e: Float) {
    val m = message
    if (m != null) {
      m.energy = e
    }
  }

  fun setAngle(e: Float) {
    val m = message
    if (m != null) {
      m.turnangle = e
    }
  }

  final override fun getIdFromPos(): Long {
    return (ilon.toLong() shl 32) or ilat.toLong()
  }

  final override fun calcDistance(p: OsmPos): Int {
    return max(1.0, jround(CheapRuler.distance(ilon, ilat, p.getILon(), p.getILat())).toDouble()).toInt()
  }

  @JvmField var origin: OsmPathElement? = null

  override fun toString(): String {
    return ilon.toString() + "_" + ilat
  }

  fun positionEquals(e: OsmPathElement): Boolean {
    return this.ilat == e.ilat && this.ilon == e.ilon
  }

  companion object {
    // construct a path element from a path
    internal fun create(path: OsmPath): OsmPathElement {
      val n = path.getTargetNode()
      val pe = create(n.getILon(), n.getILat(), n.getSElev(), path.originElement)
      pe.cost = path.cost
      pe.message = path.message
      return pe
    }

    fun create(ilon: Int, ilat: Int, selev: Short, origin: OsmPathElement?): OsmPathElement {
      val pe = OsmPathElement()
      pe.ilon = ilon
      pe.ilat = ilat
      pe.selev = selev
      pe.origin = origin
      return pe
    }
  }
}
