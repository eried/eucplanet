/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.mapaccess

import btools.codec.MicroCache
import btools.codec.MicroCache2
import btools.util.CheapRuler
import btools.util.IByteArrayUnifier
import btools.util.jround
import kotlin.jvm.JvmField
import kotlin.math.max

open class OsmNode : OsmLink, OsmPos {
  /**
   * The latitude
   */
  @JvmField var ilat: Int = 0

  /**
   * The longitude
   */
  @JvmField var ilon: Int = 0

  /**
   * The elevation
   */
  @JvmField var selev: Short = Short.MIN_VALUE

  /**
   * The node-tags, if any
   */
  @JvmField var nodeDescription: ByteArray? = null

  @JvmField var firstRestriction: TurnRestriction? = null

  @JvmField var visitID: Int = 0

  fun addTurnRestriction(tr: TurnRestriction) {
    tr.next = firstRestriction
    firstRestriction = tr
  }

  /**
   * The links to other nodes
   */
  @JvmField var firstlink: OsmLink? = null

  constructor() : super()

  constructor(ilon: Int, ilat: Int) : super() {
    this.ilon = ilon
    this.ilat = ilat
  }

  constructor(id: Long) : super() {
    ilon = (id shr 32).toInt()
    ilat = (id and 0xffffffffL).toInt()
  }


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

  final override fun getElev(): Double {
    return selev / 4.0
  }

  fun addLink(link: OsmLink, isReverse: Boolean, tn: OsmNode) {
    if (link === firstlink) {
      throw IllegalArgumentException("UUUUPS")
    }

    if (isReverse) {
      link.n1 = tn
      link.n2 = this
      link.next = tn.firstlink
      link.previous = firstlink
      tn.firstlink = link
      firstlink = link
    } else {
      link.n1 = this
      link.n2 = tn
      link.next = firstlink
      link.previous = tn.firstlink
      tn.firstlink = link
      firstlink = link
    }
  }

  final override fun calcDistance(p: OsmPos): Int {
    return max(1.0, jround(CheapRuler.distance(ilon, ilat, p.getILon(), p.getILat())).toDouble()).toInt()
  }

  override fun toString(): String {
    return "n_" + (ilon - 180000000) + "_" + (ilat - 90000000)
  }

  fun parseNodeBody(mc: MicroCache, hollowNodes: OsmNodesMap, expCtxWay: IByteArrayUnifier) {
    if (mc is MicroCache2) {
      parseNodeBody2(mc, hollowNodes, expCtxWay)
    } else
      throw IllegalArgumentException("unknown cache version: " + mc::class)
  }

  fun parseNodeBody2(mc: MicroCache2, hollowNodes: OsmNodesMap, expCtxWay: IByteArrayUnifier) {
    val abUnifier = hollowNodes.getByteArrayUnifier()

    // read turn restrictions
    while (mc.readBoolean()) {
      val tr = TurnRestriction()
      tr.exceptions = mc.readShort()
      tr.isPositive = mc.readBoolean()
      tr.fromLon = mc.readInt()
      tr.fromLat = mc.readInt()
      tr.toLon = mc.readInt()
      tr.toLat = mc.readInt()
      addTurnRestriction(tr)
    }

    selev = mc.readShort()
    val nodeDescSize = mc.readVarLengthUnsigned()
    nodeDescription = if (nodeDescSize == 0) null else mc.readUnified(nodeDescSize, abUnifier)

    while (mc.hasMoreData()) {
      // read link data
      val endPointer = mc.getEndPointer()
      val linklon = ilon + mc.readVarLengthSigned()
      val linklat = ilat + mc.readVarLengthSigned()
      val sizecode = mc.readVarLengthUnsigned()
      val isReverse = (sizecode and 1) != 0
      var description: ByteArray? = null
      val descSize = sizecode shr 1
      if (descSize > 0) {
        description = mc.readUnified(descSize, expCtxWay)
      }
      val geometry = mc.readDataUntil(endPointer)

      addLink(linklon, linklat, description, geometry, hollowNodes, isReverse)
    }
    hollowNodes.remove(this)
  }

  open fun addLink(linklon: Int, linklat: Int, description: ByteArray?, geometry: ByteArray?, hollowNodes: OsmNodesMap, isReverse: Boolean) {
    if (linklon == ilon && linklat == ilat) {
      return  // skip self-ref
    }

    var tn: OsmNode? = null // find the target node
    var link: OsmLink? = null

    // ...in our known links
    var l = firstlink
    while (l != null) {
      val t = l.getTarget(this)
      if (t.ilon == linklon && t.ilat == linklat) {
        tn = t
        if (isReverse || (l.descriptionBitmap == null && !l.isReverse(this))) {
          link = l // the correct one that needs our data
          break
        }
      }
      l = l.getNext(this)
    }
    if (tn == null) { // .. not found, then check the hollow nodes
      tn = hollowNodes.get(linklon, linklat) // target node
      if (tn == null) { // node not yet known, create a new hollow proxy
        tn = OsmNode(linklon, linklat)
        tn.setHollow()
        hollowNodes.put(tn)
        link = tn
        addLink(link, isReverse, tn) // technical inheritance: link instance in node
      }
    }
    if (link == null) {
      link = OsmLink()
      addLink(link, isReverse, tn)
    }
    if (!isReverse) {
      link.descriptionBitmap = description
      link.geometry = geometry
    }
  }


  fun isHollow(): Boolean {
    return selev.toInt() == -12345
  }

  fun setHollow() {
    selev = -12345
  }

  final override fun getIdFromPos(): Long {
    return (ilon.toLong() shl 32) or ilat.toLong()
  }

  open fun vanish() {
    if (!isHollow()) {
      var l = firstlink
      while (l != null) {
        val target = l.getTarget(this)
        val nextLink = l.getNext(this)
        if (!target.isHollow()) {
          unlinkLink(l)
          if (!l.isLinkUnused()) {
            target.unlinkLink(l)
          }
        }
        l = nextLink
      }
    }
  }

  fun unlinkLink(link: OsmLink) {
    val n = link.clear(this)

    if (link === firstlink) {
      firstlink = n
      return
    }
    var l = firstlink
    while (l != null) {
      // if ( l.isReverse( this ) )
      if (l.n1 !== this && l.n1 != null) { // isReverse inline
        val nl = l.previous
        if (nl === link) {
          l.previous = n
          return
        }
        l = nl
      } else if (l.n2 !== this && l.n2 != null) {
        val nl = l.next
        if (nl === link) {
          l.next = n
          return
        }
        l = nl
      } else {
        throw IllegalArgumentException("unlinkLink: unknown source")
      }
    }
  }


  final override fun equals(other: Any?): Boolean {
    return (other as OsmNode).ilon == ilon && other.ilat == ilat
  }

  final override fun hashCode(): Int {
    return ilon + ilat
  }
}
