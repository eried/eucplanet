/**
 * Set holding pairs of osm nodes
 *
 * @author ab
 */
package btools.mapaccess

import btools.util.CompactLongMap
import kotlin.jvm.JvmField

open class OsmNodePairSet(maxTempNodeCount: Int) {
  private var n1a: LongArray
  private var n2a: LongArray
  private var tempNodes = 0
  private var maxTempNodes = 0
  private var npairs = 0
  private var freezecount = 0

  init {
    maxTempNodes = maxTempNodeCount
    n1a = LongArray(maxTempNodes)
    n2a = LongArray(maxTempNodes)
  }

  private class OsmNodePair {
    @JvmField var node2: Long = 0
    @JvmField var next: OsmNodePair? = null
  }

  private var map: CompactLongMap<OsmNodePair>? = null

  fun addTempPair(n1: Long, n2: Long) {
    if (tempNodes < maxTempNodes) {
      n1a[tempNodes] = n1
      n2a[tempNodes] = n2
      tempNodes++
    }
  }

  fun freezeTempPairs() {
    freezecount++
    for (i in 0 until tempNodes) {
      addPair(n1a[i], n2a[i])
    }
    tempNodes = 0
  }

  fun clearTempPairs() {
    tempNodes = 0
  }

  private fun addPair(n1: Long, n2: Long) {
    if (map == null) {
      map = CompactLongMap()
    }
    npairs++

    var e = getElement(n1, n2)
    if (e == null) {
      e = OsmNodePair()
      e.node2 = n2

      val first: OsmNodePair? = map!!.get(n1)
      if (first != null) {
        var e0: OsmNodePair = first
        while (e0.next != null) {
          e0 = e0.next!!
        }
        e0.next = e
      } else {
        map!!.fastPut(n1, e)
      }
    }
  }

  fun size(): Int {
    return npairs
  }

  fun tempSize(): Int {
    return tempNodes
  }

  fun getMaxTmpNodes(): Int {
    return maxTempNodes
  }

  fun getFreezeCount(): Int {
    return freezecount
  }

  fun hasPair(n1: Long, n2: Long): Boolean {
    return map != null && (getElement(n1, n2) != null || getElement(n2, n1) != null)
  }

  private fun getElement(n1: Long, n2: Long): OsmNodePair? {
    var e: OsmNodePair? = map!!.get(n1)
    while (e != null) {
      if (e.node2 == n2) {
        return e
      }
      e = e.next
    }
    return null
  }
}
