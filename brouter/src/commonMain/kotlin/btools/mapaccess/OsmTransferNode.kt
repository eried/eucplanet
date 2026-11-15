/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess

import kotlin.jvm.JvmField

class OsmTransferNode {
  @JvmField var next: OsmTransferNode? = null

  @JvmField var ilon: Int = 0
  @JvmField var ilat: Int = 0
  @JvmField var selev: Short = 0

}
