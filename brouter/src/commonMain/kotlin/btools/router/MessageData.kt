/**
 * Information on matched way point
 *
 * @author ab
 */
package btools.router

import kotlin.jvm.JvmField

internal class MessageData {
  @JvmField var linkdist: Int = 0
  @JvmField var linkelevationcost: Int = 0
  @JvmField var linkturncost: Int = 0
  @JvmField var linknodecost: Int = 0
  @JvmField var linkinitcost: Int = 0

  @JvmField var costfactor: Float = 0f
  @JvmField var priorityclassifier: Int = 0
  @JvmField var classifiermask: Int = 0
  @JvmField var turnangle: Float = 0f
  @JvmField var wayKeyValues: String? = null
  @JvmField var nodeKeyValues: String? = null

  @JvmField var lon: Int = 0
  @JvmField var lat: Int = 0
  @JvmField var ele: Short = 0

  @JvmField var time: Float = 0f
  @JvmField var energy: Float = 0f

  // speed profile
  @JvmField var vmaxExplicit: Int = -1
  @JvmField var vmax: Int = -1
  @JvmField var vmin: Int = -1
  @JvmField var vnode0: Int = 999
  @JvmField var vnode1: Int = 999
  @JvmField var extraTime: Int = 0

  fun toMessage(): String? {
    if (wayKeyValues == null) {
      return null
    }

    val iCost = (costfactor * 1000 + 0.5f).toInt()
    return ((lon - 180000000).toString() + "\t"
      + (lat - 90000000) + "\t"
      + ele / 4 + "\t"
      + linkdist + "\t"
      + iCost + "\t"
      + linkelevationcost
      + "\t" + linkturncost
      + "\t" + linknodecost
      + "\t" + linkinitcost
      + "\t" + wayKeyValues
      + "\t" + (if (nodeKeyValues == null) "" else nodeKeyValues)
      + "\t" + (time.toInt())
      + "\t" + (energy.toInt()))
  }

  fun add(d: MessageData) {
    linkdist += d.linkdist
    linkelevationcost += d.linkelevationcost
    linkturncost += d.linkturncost
    linknodecost += d.linknodecost
    linkinitcost += d.linkinitcost
  }

  // Java used a shallow clone(); this copies the same fields one by one.
  fun copy(): MessageData {
    val m = MessageData()
    m.linkdist = linkdist
    m.linkelevationcost = linkelevationcost
    m.linkturncost = linkturncost
    m.linknodecost = linknodecost
    m.linkinitcost = linkinitcost
    m.costfactor = costfactor
    m.priorityclassifier = priorityclassifier
    m.classifiermask = classifiermask
    m.turnangle = turnangle
    m.wayKeyValues = wayKeyValues
    m.nodeKeyValues = nodeKeyValues
    m.lon = lon
    m.lat = lat
    m.ele = ele
    m.time = time
    m.energy = energy
    m.vmaxExplicit = vmaxExplicit
    m.vmax = vmax
    m.vmin = vmin
    m.vnode0 = vnode0
    m.vnode1 = vnode1
    m.extraTime = extraTime
    return m
  }

  override fun toString(): String {
    return "dist=" + linkdist + " prio=" + priorityclassifier + " turn=" + turnangle
  }

  fun getPrio(): Int {
    return priorityclassifier
  }

  fun isBadOneway(): Boolean {
    return (classifiermask and 1) != 0
  }

  fun isGoodOneway(): Boolean {
    return (classifiermask and 2) != 0
  }

  fun isRoundabout(): Boolean {
    return (classifiermask and 4) != 0
  }

  fun isLinktType(): Boolean {
    return (classifiermask and 8) != 0
  }

  fun isGoodForCars(): Boolean {
    return (classifiermask and 16) != 0
  }

}
