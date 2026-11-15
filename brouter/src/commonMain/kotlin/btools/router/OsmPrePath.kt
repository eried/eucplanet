/**
 * Simple version of OsmPath just to get angle and priority of first segment
 *
 * @author ab
 */
package btools.router

import btools.mapaccess.OsmLink
import btools.mapaccess.OsmNode
import kotlin.jvm.JvmField

abstract class OsmPrePath {
  @JvmField internal var sourceNode: OsmNode? = null
  @JvmField internal var targetNode: OsmNode? = null
  @JvmField internal var link: OsmLink? = null

  @JvmField var next: OsmPrePath? = null

  internal fun init(origin: OsmPath, link: OsmLink, rc: RoutingContext) {
    this.link = link
    this.sourceNode = origin.getTargetNode()
    this.targetNode = link.getTarget(sourceNode)
    initPrePath(origin, rc)
  }

  internal abstract fun initPrePath(origin: OsmPath, rc: RoutingContext)
}
