/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router

import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay


internal class StdModel : OsmPathModel() {
  override fun createPrePath(): OsmPrePath? {
    return null
  }

  override fun createPath(): OsmPath {
    return StdPath()
  }

  internal var ctxWay: BExpressionContextWay? = null
  internal var ctxNode: BExpressionContextNode? = null


  override fun init(expctxWay: BExpressionContextWay?, expctxNode: BExpressionContextNode?, keyValues: Map<String, String>?) {
    ctxWay = expctxWay
    ctxNode = expctxNode

    // (upstream also took an unused local "expctxGlobal = expctxWay" here)
  }
}
