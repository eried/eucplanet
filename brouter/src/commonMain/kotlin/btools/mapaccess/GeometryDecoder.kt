/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess

import btools.util.ByteDataReader

class GeometryDecoder {
  private val r = ByteDataReader(null)
  private val cachedNodes: Array<OsmTransferNode?>
  private var nCachedNodes = 128

  // result-cache
  private var firstTransferNode: OsmTransferNode? = null
  private var lastReverse = false
  private var lastGeometry: ByteArray? = null

  init {
    // create some caches
    cachedNodes = arrayOfNulls(nCachedNodes)
    for (i in 0 until nCachedNodes) {
      cachedNodes[i] = OsmTransferNode()
    }
  }

  fun decodeGeometry(geometry: ByteArray?, sourceNode: OsmNode?, targetNode: OsmNode?, reverseLink: Boolean): OsmTransferNode? {
    if ((lastGeometry === geometry) && (lastReverse == reverseLink)) {
      return firstTransferNode
    }

    firstTransferNode = null
    var lastTransferNode: OsmTransferNode? = null
    val startnode = (if (reverseLink) targetNode else sourceNode)!!
    r.reset(geometry)
    var olon = startnode.ilon
    var olat = startnode.ilat
    var oselev = startnode.selev.toInt()
    var idx = 0
    while (r.hasMoreData()) {
      val trans = if (idx < nCachedNodes) cachedNodes[idx++]!! else OsmTransferNode()
      trans.ilon = olon + r.readVarLengthSigned()
      trans.ilat = olat + r.readVarLengthSigned()
      trans.selev = (oselev + r.readVarLengthSigned()).toShort()
      olon = trans.ilon
      olat = trans.ilat
      oselev = trans.selev.toInt()
      if (reverseLink) { // reverse chaining
        trans.next = firstTransferNode
        firstTransferNode = trans
      } else {
        trans.next = null
        if (lastTransferNode == null) {
          firstTransferNode = trans
        } else {
          lastTransferNode.next = trans
        }
        lastTransferNode = trans
      }
    }

    lastReverse = reverseLink
    lastGeometry = geometry

    return firstTransferNode
  }
}
