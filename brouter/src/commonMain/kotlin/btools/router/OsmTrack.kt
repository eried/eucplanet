/**
 * Container for a track
 *
 * @author ab
 */
package btools.router

import btools.mapaccess.MatchedWaypoint
import btools.mapaccess.OsmPos
import btools.util.CompactLongMap
import btools.util.FrozenLongMap
import kotlin.jvm.JvmField

class OsmTrack {
  @JvmField var endPoint: MatchedWaypoint? = null
  @JvmField var nogoChecksums: LongArray? = null
  @JvmField var profileTimestamp: Long = 0L
  @JvmField var isDirty: Boolean = false

  @JvmField var showspeed: Boolean = false
  @JvmField var showSpeedProfile: Boolean = false
  @JvmField var showTime: Boolean = false

  @JvmField var params: MutableMap<String, String>? = null

  @JvmField var pois: MutableList<OsmNodeNamed> = ArrayList()

  class OsmPathElementHolder {
    @JvmField var node: OsmPathElement? = null
    @JvmField var nextHolder: OsmPathElementHolder? = null
  }

  @JvmField var nodes: MutableList<OsmPathElement> = ArrayList()

  private var nodesMap: CompactLongMap<OsmPathElementHolder>? = null

  private var detourMap: CompactLongMap<OsmPathElementHolder>? = null

  @JvmField var voiceHints: VoiceHintList? = null

  @JvmField var message: String? = null
  @JvmField var messageList: MutableList<String>? = null

  @JvmField var name: String = "unset"

  @JvmField internal var matchedWaypoints: MutableList<MatchedWaypoint>? = null
  @JvmField var exportWaypoints: Boolean = false
  @JvmField var exportCorrectedWaypoints: Boolean = false

  fun addNode(node: OsmPathElement) {
    nodes.add(0, node)
  }

  fun registerDetourForId(id: Long, detour: OsmPathElement?) {
    if (detourMap == null) {
      detourMap = CompactLongMap()
    }
    val nh = OsmPathElementHolder()
    nh.node = detour
    var h = detourMap!!.get(id)
    if (h != null) {
      while (h!!.nextHolder != null) {
        h = h.nextHolder
      }
      h!!.nextHolder = nh
    } else {
      detourMap!!.fastPut(id, nh)
    }
  }

  fun copyDetours(source: OsmTrack) {
    val sdm = source.detourMap
    detourMap = if (sdm == null) null else FrozenLongMap(sdm)
  }

  @Suppress("UNCHECKED_CAST")
  fun addDetours(source: OsmTrack) {
    val dm = detourMap
    if (dm != null) {
      val tmpDetourMap = CompactLongMap<OsmPathElementHolder>()

      // (upstream also fetched the unused value list here)
      val oldidlist = (dm as FrozenLongMap<OsmPathElementHolder>).getKeyArray()
      for (i in 0 until oldidlist.size) {
        val id = oldidlist[i]
        val v = dm.get(id)

        tmpDetourMap.put(id, v!!) // never null: the id comes from this map
      }

      val sdm = source.detourMap
      if (sdm != null) {
        val idlist = (sdm as FrozenLongMap<OsmPathElementHolder>).getKeyArray()
        for (i in 0 until idlist.size) {
          val id = idlist[i]
          val v = sdm.get(id)
          if (!tmpDetourMap.contains(id) && source.nodesMap!!.contains(id)) {
            tmpDetourMap.put(id, v!!)
          }
        }
      }
      detourMap = FrozenLongMap(tmpDetourMap)
    }
  }

  fun buildMap() {
    var map = CompactLongMap<OsmPathElementHolder>()
    for (node in nodes) {
      val id = node.getIdFromPos()
      val nh = OsmPathElementHolder()
      nh.node = node
      var h = map.get(id)
      if (h != null) {
        while (h!!.nextHolder != null) {
          h = h.nextHolder
        }
        h!!.nextHolder = nh
      } else {
        map.fastPut(id, nh)
      }
    }
    map = FrozenLongMap(map)
    nodesMap = map
  }

  fun addNodes(t: OsmTrack) {
    for (n in t.nodes)
      addNode(n)
    buildMap()
  }

  fun containsNode(node: OsmPos): Boolean {
    return nodesMap!!.contains(node.getIdFromPos())
  }

  fun getLink(n1: Long, n2: Long): OsmPathElement? {
    var h = nodesMap!!.get(n2)
    while (h != null) {
      val e1 = h.node!!.origin
      if (e1 != null && e1.getIdFromPos() == n1) {
        return h.node
      }
      h = h.nextHolder
    }
    return null
  }

  fun appendTrack(t: OsmTrack) {
    var i = 0

    val ourSize = nodes.size
    if (ourSize > 0 && t.nodes.size > 1) {
      val olde = nodes[ourSize - 1]
      t.nodes[1].origin = olde
    }
    val t0 = if (ourSize > 0) nodes[ourSize - 1].getTime() else 0f
    val e0 = if (ourSize > 0) nodes[ourSize - 1].getEnergy() else 0f
    val c0 = if (ourSize > 0) nodes[ourSize - 1].cost else 0
    i = 0
    while (i < t.nodes.size) {
      val e = t.nodes[i]
      if (i == 0 && ourSize > 0 && nodes[ourSize - 1].getSElev() == Short.MIN_VALUE)
        nodes[ourSize - 1].setSElev(e.getSElev())
      if (i > 0 || ourSize == 0) {
        e.setTime(e.getTime() + t0)
        e.setEnergy(e.getEnergy() + e0)
        e.cost = e.cost + c0
        val em = e.message
        if (em != null) {
          if (!(em.lon == e.getILon() && em.lat == e.getILat())) {
            em.lon = e.getILon()
            em.lat = e.getILat()
          }
        }
        nodes.add(e)
      }
      i++
    }

    val tvh = t.voiceHints
    if (tvh != null) {
      if (ourSize > 0) {
        for (hint in tvh.list) {
          hint.indexInTrack = hint.indexInTrack + ourSize - 1
        }
      }
      val vh = voiceHints
      if (vh == null) {
        voiceHints = tvh
      } else {
        vh.list.addAll(tvh.list)
      }
    } else {
      if (detourMap == null) {
        //copyDetours( t );
        detourMap = t.detourMap
      } else {
        addDetours(t)
      }
    }

    distance += t.distance
    ascend += t.ascend
    plainAscend += t.plainAscend
    cost += t.cost
    energy = nodes[nodes.size - 1].getEnergy().toInt()

    showspeed = showspeed or t.showspeed
    showSpeedProfile = showSpeedProfile or t.showSpeedProfile
  }

  @JvmField var distance: Int = 0
  @JvmField var ascend: Int = 0
  @JvmField var plainAscend: Int = 0
  @JvmField var cost: Int = 0
  @JvmField var energy: Int = 0
  @JvmField var iternity: MutableList<String>? = null

  fun getMatchedWaypoint(idx: Int): MatchedWaypoint? {
    val mws = matchedWaypoints ?: return null
    for (wp in mws) {
      if (idx == wp.indexInTrack) {
        return wp
      }
    }
    return null
  }

  fun getTotalSeconds(): Int {
    val s = if (nodes.size < 2) 0f else nodes[nodes.size - 1].getTime() - nodes[0].getTime()
    return (s + 0.5).toInt()
  }

  fun getFromDetourMap(id: Long): OsmPathElementHolder? {
    val dm = detourMap ?: return null
    return dm.get(id)
  }

  fun prepareSpeedProfile(rc: RoutingContext) {
    // sendSpeedProfile = rc.keyValues != null && rc.keyValues.containsKey( "vmax" );
  }

  fun processVoiceHints(rc: RoutingContext) {
    val voiceHints = VoiceHintList()
    this.voiceHints = voiceHints
    voiceHints.setTransportMode(rc.carMode, rc.bikeMode)
    voiceHints.turnInstructionMode = rc.turnInstructionMode

    if (detourMap == null && !rc.hasDirectRouting) {
      // only when no direct way points
      return
    }
    var nodeNr = nodes.size - 1
    var node: OsmPathElement? = nodes[nodeNr]
    while (node != null) {
      node = node.origin
    }

    node = nodes[nodeNr]
    val inputs: MutableList<VoiceHint> = ArrayList()
    while (node != null) {
      val origin = node.origin
      if (origin != null) {
        if (nodeNr == nodes.size - 1) {
          val input = VoiceHint()
          inputs.add(0, input)
          input.ilat = node.getILat()
          input.ilon = node.getILon()
          input.selev = node.getSElev()
          input.goodWay = node.message
          input.oldWay = node.message
          input.indexInTrack = nodes.size - 1
          input.cmd = VoiceHint.END
        }
        val input = VoiceHint()
        inputs.add(input)
        input.ilat = origin.getILat()
        input.ilon = origin.getILon()
        input.selev = origin.getSElev()
        input.indexInTrack = --nodeNr
        input.goodWay = node.message
        input.oldWay = if (origin.message == null) node.message else origin.message
        if (rc.turnInstructionMode == 8 ||
          rc.turnInstructionMode == 4 ||
          rc.turnInstructionMode == 2 ||
          rc.turnInstructionMode == 9) {
          val mwpt = getMatchedWaypoint(nodeNr)
          if (mwpt != null && mwpt.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) {
            input.cmd = VoiceHint.BL
            input.angle = (if (nodeNr == 0) origin.message!!.turnangle else node.message!!.turnangle)
            input.distanceToNext = node.calcDistance(origin).toDouble()
          }
        }
        val dm = detourMap
        if (dm != null) {
          val detours = dm.get(origin.getIdFromPos())
          if (nodeNr >= 0 && detours != null) {
            var h: OsmPathElementHolder? = detours
            while (h != null) {
              val e = h.node
              input.addBadWay(startSection(e, origin))
              h = h.nextHolder
            }
          }
        }
        /* else if (nodeNr == 0 && detours != null) {
          OsmPathElementHolder h = detours;
          OsmPathElement e = h.node;
          input.addBadWay(startSection(e, e));
        } */
      }
      node = node.origin
    }

    val transportMode = voiceHints.transportMode()
    val vproc = VoiceHintProcessor(rc.turnInstructionCatchingRange, rc.turnInstructionRoundabouts, transportMode)
    val results = vproc.process(inputs)

    val minDistance = getMinDistance().toDouble()
    val resultsLast = vproc.postProcess(results, rc.turnInstructionCatchingRange, minDistance)
    for (hint in resultsLast) {
      voiceHints.list.add(hint)
    }

  }

  internal fun getMinDistance(): Int {
    val vh = voiceHints
    if (vh != null) {
      return when (vh.transportMode()) {
        VoiceHintList.TRANS_MODE_CAR -> 20
        VoiceHintList.TRANS_MODE_FOOT -> 3
        VoiceHintList.TRANS_MODE_BIKE -> 5
        else -> 5
      }
    }
    return 2
  }

  fun removeVoiceHint(i: Int) {
    val vhl = voiceHints
    if (vhl != null) {
      var remove: VoiceHint? = null
      for (vh in vhl.list) {
        if (vh.indexInTrack == i)
          remove = vh
      }
      if (remove != null)
        vhl.list.remove(remove)
    }
  }

  private fun startSection(element: OsmPathElement?, root: OsmPathElement): MessageData? {
    var e = element
    var cnt = 0
    while (e != null && e.origin != null) {
      if (e.origin!!.getILat() == root.getILat() && e.origin!!.getILon() == root.getILon()) {
        return e.message
      }
      e = e.origin
      if (cnt++ == 1000000) {
        throw IllegalArgumentException("ups: " + root + "->" + element)
      }
    }
    return null
  }
}
