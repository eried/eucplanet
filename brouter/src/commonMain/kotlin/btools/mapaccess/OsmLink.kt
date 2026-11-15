/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess

import kotlin.jvm.JvmField

open class OsmLink {
  /**
   * The description bitmap contains the waytags (valid for both directions)
   */
  @JvmField var descriptionBitmap: ByteArray? = null

  /**
   * The geometry contains intermediate nodes, null for none (valid for both directions)
   */
  @JvmField var geometry: ByteArray? = null

  // a link logically knows only its target, but for the reverse link, source and target are swapped
  @JvmField internal var n1: OsmNode? = null
  @JvmField internal var n2: OsmNode? = null

  // same for the next-link-for-node pointer: previous applies to the reverse link
  @JvmField internal var previous: OsmLink? = null
  @JvmField internal var next: OsmLink? = null

  private var reverselinkholder: OsmLinkHolder? = null
  private var firstlinkholder: OsmLinkHolder? = null

  internal constructor()

  constructor(source: OsmNode?, target: OsmNode?) {
    n1 = source
    n2 = target
  }

  /**
   * Get the relevant target-node for the given source
   */
  fun getTarget(source: OsmNode?): OsmNode {
    return if (n2 !== source && n2 != null) n2!! else n1!!
    /* if ( n2 != null && n2 != source )
    {
      return n2;
    }
    else if ( n1 != null && n1 != source )
    {
      return n1;
    }
    else
    {
      new Throwable( "ups" ).printStackTrace();
      throw new IllegalArgumentException( "internal error: getTarget: unknown source; " + source + " n1=" + n1 + " n2=" + n2 );
    } */
  }

  /**
   * Get the relevant next-pointer for the given source
   */
  fun getNext(source: OsmNode?): OsmLink? {
    return if (n2 !== source && n2 != null) next else previous
    /* if ( n2 != null && n2 != source )
    {
      return next;
    }
    else if ( n1 != null && n1 != source )
    {
      return previous;
    }
    else
    {
      throw new IllegalArgumentException( "internal error: gextNext: unknown source" );
    } */
  }

  /**
   * Reset this link for the given direction
   */
  internal fun clear(source: OsmNode?): OsmLink? {
    val n: OsmLink?
    if (n2 != null && n2 !== source) {
      n = next
      next = null
      n2 = null
      firstlinkholder = null
    } else if (n1 != null && n1 !== source) {
      n = previous
      previous = null
      n1 = null
      reverselinkholder = null
    } else {
      throw IllegalArgumentException("internal error: setNext: unknown source")
    }
    if (n1 == null && n2 == null) {
      descriptionBitmap = null
      geometry = null
    }
    return n
  }

  fun setFirstLinkHolder(holder: OsmLinkHolder?, source: OsmNode?) {
    if (n2 != null && n2 !== source) {
      firstlinkholder = holder
    } else if (n1 != null && n1 !== source) {
      reverselinkholder = holder
    } else {
      throw IllegalArgumentException("internal error: setFirstLinkHolder: unknown source")
    }
  }

  fun getFirstLinkHolder(source: OsmNode?): OsmLinkHolder? {
    if (n2 != null && n2 !== source) {
      return firstlinkholder
    } else if (n1 != null && n1 !== source) {
      return reverselinkholder
    } else {
      throw IllegalArgumentException("internal error: getFirstLinkHolder: unknown source")
    }
  }

  fun isReverse(source: OsmNode?): Boolean {
    return n1 !== source && n1 != null
    /* if ( n2 != null && n2 != source )
    {
      return false;
    }
    else if ( n1 != null && n1 != source )
   {
      return true;
    }
    else
    {
      throw new IllegalArgumentException( "internal error: isReverse: unknown source" );
    } */
  }

  fun isBidirectional(): Boolean {
    return n1 != null && n2 != null
  }

  fun isLinkUnused(): Boolean {
    return n1 == null && n2 == null
  }

  fun addLinkHolder(holder: OsmLinkHolder, source: OsmNode?) {
    val firstHolder = getFirstLinkHolder(source)
    if (firstHolder != null) {
      holder.setNextForLink(firstHolder)
    }
    setFirstLinkHolder(holder, source)
  }

}
