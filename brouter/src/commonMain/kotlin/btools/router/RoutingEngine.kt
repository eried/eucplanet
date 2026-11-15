package btools.router

import btools.mapaccess.MatchedWaypoint
import btools.mapaccess.NodesCache
import btools.mapaccess.OsmLink
import btools.mapaccess.OsmLinkHolder
import btools.mapaccess.OsmNode
import btools.mapaccess.OsmNodePairSet
import btools.mapaccess.OsmPos
import btools.util.CheapAngleMeter
import btools.util.CompactLongMap
import btools.util.SortedHeap
import btools.util.currentTimeMillis
import btools.util.jParseDouble
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.math.abs
import kotlin.math.min

/*
 * Kotlin Multiplatform copy of the upstream RoutingEngine, routing mode only.
 * It no longer extends Thread: the caller runs doRun on its own thread.
 */
open class RoutingEngine(
  outfileBase: String?, logfileBase: String?, segmentDir: String,
  waypoints: MutableList<OsmNodeNamed>, rc: RoutingContext, engineMode: Int
) {

  companion object {
    const val BROUTER_ENGINEMODE_ROUTING = 0
    const val BROUTER_ENGINEMODE_SEED = 1
    const val BROUTER_ENGINEMODE_GETELEV = 2
    const val BROUTER_ENGINEMODE_GETINFO = 3
    const val BROUTER_ENGINEMODE_ROUNDTRIP = 4
  }

  private var nodesCache: NodesCache? = null
  private val openSet = SortedHeap<OsmPath>()
  private var finished = false

  @JvmField
  internal var waypoints: MutableList<OsmNodeNamed> = waypoints

  @JvmField
  internal var extraWaypoints: MutableList<OsmNodeNamed>? = null

  @JvmField
  internal var matchedWaypoints: MutableList<MatchedWaypoint>? = null
  private var linksProcessed = 0

  private var nodeLimit = 0 // used for target island search
  private var MAXNODES_ISLAND_CHECK = 500
  private var islandNodePairs = OsmNodePairSet(MAXNODES_ISLAND_CHECK)
  private var useNodePoints = false // use the start/end nodes  instead of crosspoint

  private var engineMode = engineMode

  private var MAX_STEPS_CHECK = 500

  private var MAX_DYNAMIC_RANGE = 60000

  @JvmField
  var foundTrack: OsmTrack? = OsmTrack()
  private var foundRawTrack: OsmTrack? = null

  @JvmField
  var errorMessage: String? = null

  @Volatile
  private var terminated = false

  @JvmField
  internal var segmentDir: String = segmentDir

  @JvmField
  internal var routingContext: RoutingContext = rc

  @JvmField
  var airDistanceCostFactor = 0.0

  @JvmField
  var lastAirDistanceCostFactor = 0.0

  private var guideTrack: OsmTrack? = null

  private var matchPath: OsmPathElement? = null

  private var startTime: Long = 0
  private var maxRunningTime: Long = 0

  @JvmField
  var boundary: SearchBoundary? = null

  @JvmField
  var quite = false

  private val directWeaving = true

  constructor(
    outfileBase: String?, logfileBase: String?, segmentDir: String,
    waypoints: MutableList<OsmNodeNamed>, rc: RoutingContext
  ) : this(outfileBase, logfileBase, segmentDir, waypoints, rc, 0)

  init {
    ProfileCache.parseProfile(rc)
  }

  fun doRun(maxRunningTime: Long) {
    when (engineMode) {
      BROUTER_ENGINEMODE_ROUTING -> {
        if (waypoints.size < 2) {
          throw IllegalArgumentException("we need two lat/lon points at least!")
        }
        doRouting(maxRunningTime)
      }
      else -> throw IllegalArgumentException("not a valid engine mode")
    }
  }


  fun doRouting(maxRunningTime: Long) {
    try {
      startTime = currentTimeMillis()
      this.maxRunningTime = maxRunningTime

      if (routingContext.allowSamewayback) {
        if (waypoints.size == 2) {
          val onn = OsmNodeNamed(OsmNode(waypoints[0].ilon, waypoints[0].ilat))
          onn.name = "to"
          waypoints.add(onn)
        } else {
          waypoints[waypoints.size - 1].name = "via" + (waypoints.size - 1) + "_center"
          val newpoints: MutableList<OsmNodeNamed> = ArrayList()
          for (i in waypoints.size - 2 downTo 0) {
            // System.out.println("back " + waypoints.get(i));
            val onn = OsmNodeNamed(OsmNode(waypoints[i].ilon, waypoints[i].ilat))
            onn.name = "via"
            newpoints.add(onn)
          }
          newpoints[newpoints.size - 1].name = "to"
          waypoints.addAll(newpoints)
        }
      }

      val nsections = waypoints.size - 1
      val refTracks = arrayOfNulls<OsmTrack>(nsections) // used ways for alternatives
      val lastTracks = arrayOfNulls<OsmTrack>(nsections)
      var track: OsmTrack
      val messageList: MutableList<String> = ArrayList()
      var i = 0
      while (true) {
        track = findTrack(refTracks, lastTracks)

        val message = "track-length = " + track.distance + " filtered ascend = " + track.ascend +
          " plain-ascend = " + track.plainAscend + " cost=" + track.cost
        track.message = message
        track.name = "brouter_" + routingContext.getProfileName() + "_" + i

        messageList.add(message)
        track.messageList = messageList
        if (i == routingContext.getAlternativeIdx(0, 3)) {
          foundTrack = track
        } else {
          i++
          continue
        }
        break
      }
    } catch (e: IllegalArgumentException) {
      logException(e)
    } catch (e: Exception) {
      logException(e)
    } catch (e: Throwable) {
      cleanOnOOM()
      logException(e)
    } finally {
      ProfileCache.releaseProfile(routingContext)

      val nc = nodesCache
      if (nc != null) {
        nc.close()
        nodesCache = null
      }
      openSet.clear()
      finished = true // this signals termination to outside
    }
  }


  private fun postElevationCheck(track: OsmTrack) {
    var lastPt: OsmPathElement? = null
    var startPt: OsmPathElement? = null
    var lastElev = Short.MIN_VALUE
    var startElev = Short.MIN_VALUE
    var endElev = Short.MIN_VALUE
    var startIdx = 0
    var endIdx = -1
    var dist = 0
    val ourSize = track.nodes.size
    for (idx in 0 until ourSize) {
      val n = track.nodes[idx]
      if (n.getSElev() == Short.MIN_VALUE && lastElev != Short.MIN_VALUE && idx < ourSize - 1) {
        // start one point before entry point to get better elevation results
        if (idx > 1)
          startElev = track.nodes[idx - 2].getSElev()
        if (startElev == Short.MIN_VALUE)
          startElev = lastElev
        startIdx = idx
        startPt = lastPt
        dist = 0
        if (lastPt != null)
          dist += n.calcDistance(lastPt)
      } else if (n.getSElev() != Short.MIN_VALUE && lastElev == Short.MIN_VALUE && startElev != Short.MIN_VALUE) {
        // end one point behind exit point to get better elevation results
        if (idx + 1 < track.nodes.size)
          endElev = track.nodes[idx + 1].getSElev()
        if (endElev == Short.MIN_VALUE)
          endElev = n.getSElev()
        endIdx = idx
        var tmpPt = track.nodes[if (startIdx > 1) startIdx - 2 else startIdx - 1]
        val diffElev = endElev - startElev
        dist += tmpPt.calcDistance(startPt!!)
        dist += n.calcDistance(lastPt!!)
        var distRest = dist
        var incline = diffElev / (dist / 100.0)
        var lastMsg = ""
        var tmpincline = 0.0
        var startincline = 0.0
        var selev = track.nodes[if (startIdx > 1) startIdx - 2 else startIdx - 1].getSElev().toDouble()
        var hasInclineTags = false
        for (i in startIdx - 1 until endIdx + 1) {
          val tmp = track.nodes[i]
          if (tmp.message != null) {
            val md = tmp.message!!.copy()
            val msg = md.wayKeyValues!!
            if (msg != lastMsg) {
              val revers = msg.contains("reversedirection=yes")
              var pos = msg.indexOf("incline=")
              if (pos != -1) {
                hasInclineTags = true
                var s = msg.substring(pos + 8)
                pos = s.indexOf(" ")
                if (pos != -1)
                  s = s.substring(0, pos)

                if (s.length > 0) {
                  try {
                    var ind = s.indexOf("%")
                    if (ind != -1)
                      s = s.substring(0, ind)
                    ind = s.indexOf("°")
                    if (ind != -1)
                      s = s.substring(0, ind)
                    tmpincline = jParseDouble(s.trim())
                    if (revers)
                      tmpincline *= -1.0
                  } catch (e: NumberFormatException) {
                    tmpincline = 0.0
                  }
                }
              } else {
                tmpincline = 0.0
              }
              if (startincline == 0.0) {
                startincline = tmpincline
              } else if (startincline < 0 && tmpincline > 0) {
                // for the way up find the exit point
                val diff = endElev - selev
                tmpincline = diff / (distRest / 100.0)
              }
            }
            lastMsg = msg
          }
          val tmpdist = tmp.calcDistance(tmpPt)
          distRest -= tmpdist
          if (hasInclineTags)
            incline = tmpincline
          selev = (selev + (tmpdist / 100.0 * incline))
          tmp.setSElev(selev.toInt().toShort())
          tmp.message!!.ele = selev.toInt().toShort()
          tmpPt = tmp
        }
        dist = 0
      } else if (n.getSElev() != Short.MIN_VALUE && lastElev == Short.MIN_VALUE && startIdx == 0) {
        // fill at start
        for (i in 0 until idx) {
          track.nodes[i].setSElev(n.getSElev())
        }
      } else if (n.getSElev() == Short.MIN_VALUE && idx == track.nodes.size - 1) {
        // fill at end
        startIdx = idx
        for (i in startIdx until track.nodes.size) {
          track.nodes[i].setSElev(lastElev)
        }
      } else if (n.getSElev() == Short.MIN_VALUE) {
        if (lastPt != null)
          dist += n.calcDistance(lastPt)
      }
      lastElev = n.getSElev()
      lastPt = n
    }

  }

  private fun logException(t: Throwable) {
    errorMessage = if (t is RuntimeException) t.message else t.toString()
  }


  fun cleanOnOOM() {
    terminate()
  }

  private fun findTrack(refTracks: Array<OsmTrack?>, lastTracks: Array<OsmTrack?>): OsmTrack {
    while (true) {
      try {
        return tryFindTrack(refTracks, lastTracks)
      } catch (rie: RoutingIslandException) {
        if (routingContext.useDynamicDistance) {
          for (mwp in matchedWaypoints!!) {
            if (mwp.name!!.contains("_add")) {
              val n1 = mwp.node1!!.getIdFromPos()
              val n2 = mwp.node2!!.getIdFromPos()
              islandNodePairs.addTempPair(n1, n2)
            }
          }
        }
        islandNodePairs.freezeTempPairs()
        nodesCache!!.clean(true)
        matchedWaypoints = null
      }
    }
  }

  private fun tryFindTrack(refTracks0: Array<OsmTrack?>, lastTracks0: Array<OsmTrack?>): OsmTrack {
    var refTracks = refTracks0
    var lastTracks = lastTracks0
    val totaltrack = OsmTrack()
    var nUnmatched = waypoints.size
    var hasDirectRouting = false

    val extra = extraWaypoints
    if (useNodePoints && extra != null) {
      // add extra waypoints from the last broken round
      for (wp in extra) {
        if (wp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) hasDirectRouting = true
        if (wp.name!!.startsWith("from")) {
          waypoints.add(1, wp)
          waypoints[0].wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT
          nUnmatched++
        } else {
          waypoints.add(waypoints.size - 1, wp)
          waypoints[waypoints.size - 2].wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT
          nUnmatched++
        }
      }
      extraWaypoints = null
    }
    if (lastTracks.size < waypoints.size - 1) {
      refTracks = arrayOfNulls(waypoints.size - 1) // used ways for alternatives
      lastTracks = arrayOfNulls(waypoints.size - 1)
      hasDirectRouting = true
    }
    for (wp in waypoints) {
      if (wp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) hasDirectRouting = true
    }

    // check for a track for that target
    // Upstream reads a cached track from rawTrackPath here; the app never sets
    // one, so there is never a nearby track.
    val nearbyTrack: OsmTrack? = null

    if (matchedWaypoints == null) { // could exist from the previous alternative level
      val mws: MutableList<MatchedWaypoint> = ArrayList()
      matchedWaypoints = mws
      for (i in 0 until nUnmatched) {
        val mwp = MatchedWaypoint()
        mwp.waypoint = waypoints[i]
        mwp.name = waypoints[i].name
        mwp.wpttype = waypoints[i].wpttype
        mws.add(mwp)
      }
      val startSize = mws.size
      matchWaypointsToNodes(mws)
      if (startSize < mws.size) {
        refTracks = arrayOfNulls(mws.size - 1) // used ways for alternatives
        lastTracks = arrayOfNulls(mws.size - 1)
        hasDirectRouting = true
      }

      routingContext.checkMatchedWaypointAgainstNogos(mws)

      // detect target islands: restricted search in inverse direction
      routingContext.inverseDirection = !routingContext.inverseRouting
      airDistanceCostFactor = 0.0
      var i = 0
      while (i < mws.size - 1) {
        nodeLimit = MAXNODES_ISLAND_CHECK
        if (mws[i].wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) {
          i++
          continue
        }
        if (routingContext.inverseRouting) {
          val seg = findTrack("start-island-check", mws[i], mws[i + 1], null, null, false)
          if (seg == null && nodeLimit > 0) {
            throw IllegalArgumentException("start island detected for section " + i)
          }
        } else {
          val seg = findTrack("target-island-check", mws[i + 1], mws[i], null, null, false)
          if (seg == null && nodeLimit > 0) {
            throw IllegalArgumentException("target island detected for section " + i)
          }
        }
        i++
      }
      routingContext.inverseDirection = false
      nodeLimit = 0

      if (nearbyTrack != null) {
        mws.add(nearbyTrack.endPoint!!)
      }
    } else {
      if (lastTracks.size < matchedWaypoints!!.size - 1) {
        refTracks = arrayOfNulls(matchedWaypoints!!.size - 1) // used ways for alternatives
        lastTracks = arrayOfNulls(matchedWaypoints!!.size - 1)
        hasDirectRouting = true
      }
    }
    val matchedWaypoints = this.matchedWaypoints!!

    routingContext.hasDirectRouting = hasDirectRouting

    OsmPath.seg = 1 // set segment counter
    for (i in 0 until matchedWaypoints.size - 1) {
      val lastTrack = lastTracks[i]
      if (lastTrack != null) {
        if (refTracks[i] == null) refTracks[i] = OsmTrack()
        refTracks[i]!!.addNodes(lastTrack)
      }

      val seg: OsmTrack
      val wptIndex: Int
      if (routingContext.inverseRouting) {
        routingContext.inverseDirection = true
        seg = searchTrack(matchedWaypoints[i + 1], matchedWaypoints[i], null, refTracks[i])
        routingContext.inverseDirection = false
        wptIndex = i + 1
      } else {
        seg = searchTrack(matchedWaypoints[i], matchedWaypoints[i + 1], if (i == matchedWaypoints.size - 2) nearbyTrack else null, refTracks[i])
        wptIndex = i
        if (routingContext.continueStraight) {
          if (i < matchedWaypoints.size - 2) {
            val lastPoint = if (seg.containsNode(matchedWaypoints[i + 1].node1!!)) matchedWaypoints[i + 1].node1!! else matchedWaypoints[i + 1].node2!!
            val nogo = OsmNodeNamed(lastPoint)
            nogo.radius = 5.0
            nogo.name = "nogo" + (i + 1)
            nogo.nogoWeight = 9999.0
            nogo.isNogo = true
            if (routingContext.nogopoints == null) routingContext.nogopoints = ArrayList()
            routingContext.nogopoints!!.add(nogo)
          }
        }
      }

      var changed = false
      if (routingContext.correctMisplacedViaPoints &&
        matchedWaypoints[i].wpttype != MatchedWaypoint.WAYPOINT_TYPE_DIRECT &&
        matchedWaypoints[i].wpttype != MatchedWaypoint.WAYPOINT_TYPE_MEETING &&
        !routingContext.allowSamewayback) {
        changed = snapPathConnection(totaltrack, seg, if (routingContext.inverseRouting) matchedWaypoints[i + 1] else matchedWaypoints[i])
      }
      if (wptIndex > 0)
        matchedWaypoints[wptIndex].indexInTrack = totaltrack.nodes.size - 1

      totaltrack.appendTrack(seg)
      lastTracks[i] = seg
    }

    postElevationCheck(totaltrack)

    recalcTrack(totaltrack)

    matchedWaypoints[matchedWaypoints.size - 1].indexInTrack = totaltrack.nodes.size - 1
    totaltrack.matchedWaypoints = matchedWaypoints
    totaltrack.processVoiceHints(routingContext)
    totaltrack.prepareSpeedProfile(routingContext)

    totaltrack.showTime = routingContext.showTime
    totaltrack.params = routingContext.keyValues

    val poipoints = routingContext.poipoints
    if (poipoints != null)
      totaltrack.pois = poipoints

    return totaltrack
  }

  internal fun getExtraSegment(start: OsmPathElement?, end: OsmPathElement?): OsmTrack? {

    if (start == null || end == null) return null

    val wptlist: MutableList<MatchedWaypoint> = ArrayList()
    val wpt1 = MatchedWaypoint()
    wpt1.waypoint = OsmNode(start.getILon(), start.getILat())
    wpt1.name = "wptx1"
    wpt1.crosspoint = OsmNode(start.getILon(), start.getILat())
    wpt1.node1 = OsmNode(start.getILon(), start.getILat())
    wpt1.node2 = OsmNode(end.getILon(), end.getILat())
    wptlist.add(wpt1)
    val wpt2 = MatchedWaypoint()
    wpt2.waypoint = OsmNode(end.getILon(), end.getILat())
    wpt2.name = "wptx2"
    wpt2.crosspoint = OsmNode(end.getILon(), end.getILat())
    wpt2.node2 = OsmNode(start.getILon(), start.getILat())
    wpt2.node1 = OsmNode(end.getILon(), end.getILat())
    wptlist.add(wpt2)

    val mwp1 = wptlist[0]
    val mwp2 = wptlist[1]

    var mid: OsmTrack? = null

    val corr = routingContext.correctMisplacedViaPoints
    routingContext.correctMisplacedViaPoints = false

    val gt = OsmTrack()
    guideTrack = gt
    gt.addNode(start)
    gt.addNode(end)

    mid = findTrack("getinfo", mwp1, mwp2, null, null, false)

    guideTrack = null
    routingContext.correctMisplacedViaPoints = corr

    return mid
  }

  private fun snapRoundaboutConnection(tt: OsmTrack, t: OsmTrack, indexStart: Int, indexEnd0: Int, indexMeeting: Int, startWp: MatchedWaypoint): Int {
    var indexEnd = indexEnd0

    val indexMeetingBack = (if (indexMeeting == -1) tt.nodes.size - 1 else indexMeeting)
    var indexMeetingFore = 0
    var indexStartBack = indexStart
    var indexStartFore = 0

    val ptStart = tt.nodes[indexStartBack]
    val ptMeeting = tt.nodes[indexMeetingBack]
    val ptEnd = t.nodes[indexEnd]

    val bMeetingIsOnRoundabout = ptMeeting.message!!.isRoundabout()
    var bMeetsRoundaboutStart = false
    var wayDistance = 0

    var i: Int
    var last_n: OsmPathElement? = null

    i = 0
    while (i < indexEnd) {
      val n = t.nodes[i]
      if (last_n != null) wayDistance += n.calcDistance(last_n)
      last_n = n
      if (n.positionEquals(ptStart)) {
        indexStartFore = i
        bMeetsRoundaboutStart = true
      }
      if (n.positionEquals(ptMeeting)) {
        indexMeetingFore = i
      }
      i++
    }

    if (routingContext.correctMisplacedViaPointsDistance > 0 &&
      wayDistance.toDouble() > routingContext.correctMisplacedViaPointsDistance) {
      return 0
    }

    if (!bMeetsRoundaboutStart && bMeetingIsOnRoundabout) {
      indexEnd = indexMeetingFore
    }
    if (bMeetsRoundaboutStart && bMeetingIsOnRoundabout) {
      indexEnd = indexStartFore
    }

    val removeList: MutableList<OsmPathElement> = ArrayList()
    if (!bMeetsRoundaboutStart) {
      indexStartBack = indexMeetingBack
      while (!tt.nodes[indexStartBack].message!!.isRoundabout()) {
        indexStartBack--
        if (indexStartBack == 2) break
      }
    }

    i = indexStartBack + 1
    while (i < tt.nodes.size) {
      val n = tt.nodes[i]
      val detours = tt.getFromDetourMap(n.getIdFromPos())
      if (detours != null) {
        var h: OsmTrack.OsmPathElementHolder? = detours
        while (h != null) {
          h = h.nextHolder
        }
      }
      removeList.add(n)
      i++
    }

    var ttend: OsmPathElement? = null
    if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart) {
      ttend = tt.nodes[indexStartBack]
      val ttend_detours = tt.getFromDetourMap(ttend.getIdFromPos())
      if (ttend_detours != null) {
        tt.registerDetourForId(ttend.getIdFromPos(), null)
      }
    }

    for (e in removeList) {
      tt.nodes.remove(e)
    }
    removeList.clear()


    i = 0
    while (i < indexEnd) {
      val n = t.nodes[i]
      if (n.positionEquals(if (bMeetsRoundaboutStart) ptStart else ptEnd)) break
      if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart && n.message!!.isRoundabout()) break

      val detours = t.getFromDetourMap(n.getIdFromPos())
      if (detours != null) {
        var h: OsmTrack.OsmPathElementHolder? = detours
        while (h != null) {
          h = h.nextHolder
        }
      }
      removeList.add(n)
      i++
    }

    // time hold
    var atime = 0f
    var aenergy = 0f
    var acost = 0
    if (i > 1) {
      atime = t.nodes[i].getTime()
      aenergy = t.nodes[i].getEnergy()
      acost = t.nodes[i].cost
    }

    for (e in removeList) {
      t.nodes.remove(e)
    }
    removeList.clear()

    if (atime > 0f) {
      for (e in t.nodes) {
        e.setTime(e.getTime() - atime)
        e.setEnergy(e.getEnergy() - aenergy)
        e.cost = e.cost - acost
      }
    }

    if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart) {

      val ttend_detours = tt.getFromDetourMap(ttend!!.getIdFromPos())

      var mid: OsmTrack? = null
      if (ttend_detours != null && ttend_detours.node != null) {
        mid = getExtraSegment(ttend, ttend_detours.node)
      }
      val tt_end = tt.nodes[tt.nodes.size - 1]

      val last_cost = tt_end.cost
      val last_time = tt_end.getTime()
      val last_energy = tt_end.getEnergy()
      var tmp_cost = 0
      var tmp_time = 0f
      var tmp_energy = 0f

      if (mid != null) {
        var start = false
        for (e in mid.nodes) {
          if (start) {
            if (e.positionEquals(ttend_detours!!.node!!)) {
              tmp_cost = e.cost
              tmp_time = e.getTime()
              tmp_energy = e.getEnergy()
              break
            }
            e.cost = last_cost + e.cost
            e.setTime(last_time + e.getTime())
            e.setEnergy(last_energy + e.getEnergy())
            tt.nodes.add(e)
          }
          if (e.positionEquals(tt_end)) start = true
        }

        val detourNode = ttend_detours!!.node!!
        detourNode.cost = last_cost + tmp_cost
        detourNode.setTime(last_time + tmp_time)
        detourNode.setEnergy(last_energy + tmp_energy)
        tt.nodes.add(detourNode)
        t.nodes.add(0, detourNode)
      }

    }

    tt.cost = tt.nodes[tt.nodes.size - 1].cost
    t.cost = t.nodes[t.nodes.size - 1].cost

    startWp.correctedpoint = OsmNode(ptStart.getILon(), ptStart.getILat())

    return (t.nodes.size)
  }

  // check for way back on way point
  private fun snapPathConnection(tt: OsmTrack, t: OsmTrack, startWp: MatchedWaypoint): Boolean {
    if (!startWp.name!!.startsWith("via") && !startWp.name!!.startsWith("rt"))
      return false

    val ourSize = tt.nodes.size
    if (ourSize > 0) {
      val testPoint = tt.nodes[ourSize - 1]
      val poipoints = routingContext.poipoints
      if (poipoints != null) {
        for (node in poipoints) {

          val lon0 = tt.nodes[ourSize - 2].getILon()
          val lat0 = tt.nodes[ourSize - 2].getILat()
          val lon1 = startWp.crosspoint!!.ilon
          val lat1 = startWp.crosspoint!!.ilat
          val lon2 = node.ilon
          val lat2 = node.ilat
          val angle3 = routingContext.anglemeter.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2)
          val dist = node.calcDistance(startWp.crosspoint!!)
          if (dist.toDouble() < routingContext.waypointCatchingRange)
            return false
        }
      }
      val removeBackList: MutableList<OsmPathElement> = ArrayList()
      val removeForeList: MutableList<OsmPathElement> = ArrayList()
      val removeVoiceHintList: MutableList<Int> = ArrayList()
      var last: OsmPathElement? = null
      val lastJunction: OsmPathElement? = null
      val lastJunctions = CompactLongMap<OsmTrack.OsmPathElementHolder>()
      var newJunction: OsmPathElement? = null
      var newTarget: OsmPathElement? = null
      var tmpback: OsmPathElement? = null
      var tmpfore: OsmPathElement? = null
      var tmpStart: OsmPathElement? = null
      var indexback = ourSize - 1
      var indexfore = 0
      val stop = (if (indexback - MAX_STEPS_CHECK > 1) indexback - MAX_STEPS_CHECK else 1)
      var wayDistance = 0.0
      var nextDist = 0.0
      var bCheckRoundAbout = false
      var bBackRoundAbout = false
      var bForeRoundAbout = false
      var indexBackFound = 0
      var indexForeFound = 0
      var differentLanePoints = 0
      var indexMeeting = -1
      while (indexback >= 1 && indexback >= stop && indexfore < t.nodes.size) {
        tmpback = tt.nodes[indexback]
        tmpfore = t.nodes[indexfore]
        if (!bBackRoundAbout && tmpback.message != null && tmpback.message!!.isRoundabout()) {
          bBackRoundAbout = true
          indexBackFound = indexfore
        }
        if (!bForeRoundAbout &&
          tmpfore.message != null && tmpfore.message!!.isRoundabout() ||
          (tmpback.positionEquals(tmpfore) && tmpback.message!!.isRoundabout())) {
          bForeRoundAbout = true
          indexForeFound = indexfore
        }
        if (indexfore == 0) {
          tmpStart = t.nodes[0]
        } else {
          val dirback = CheapAngleMeter.getDirection(tmpStart!!.getILon(), tmpStart.getILat(), tmpback.getILon(), tmpback.getILat())
          val dirfore = CheapAngleMeter.getDirection(tmpStart.getILon(), tmpStart.getILat(), tmpfore.getILon(), tmpfore.getILat())
          val dirdiff = CheapAngleMeter.getDifferenceFromDirection(dirback, dirfore)
          // walking wrong direction
          if (dirdiff > 60 && !bBackRoundAbout && !bForeRoundAbout) break
        }
        // seems no roundabout, only on one end
        if (bBackRoundAbout != bForeRoundAbout && indexfore - abs(indexForeFound - indexBackFound) > 8) break
        if (!tmpback.positionEquals(tmpfore)) differentLanePoints++
        if (tmpback.positionEquals(tmpfore)) indexMeeting = indexback
        bCheckRoundAbout = bBackRoundAbout && bForeRoundAbout
        if (bCheckRoundAbout) break
        indexback--
        indexfore++
      }
      //System.out.println("snap round result " + indexback + ": " + bBackRoundAbout + " - " + indexfore + "; " + bForeRoundAbout + " pts " + differentLanePoints);
      if (bCheckRoundAbout) {

        tmpback = tt.nodes[--indexback]
        while (tmpback!!.message != null && tmpback.message!!.isRoundabout()) {
          tmpback = tt.nodes[--indexback]
        }

        var ifore = ++indexfore
        var testfore = t.nodes[ifore]
        while (ifore < t.nodes.size && testfore.message != null && testfore.message!!.isRoundabout()) {
          testfore = t.nodes[ifore]
          ifore++
        }

        snapRoundaboutConnection(tt, t, indexback, --ifore, indexMeeting, startWp)

        // remove filled arrays
        removeVoiceHintList.clear()
        removeBackList.clear()
        removeForeList.clear()
        return true
      }
      indexback = ourSize - 1
      indexfore = 0
      while (indexback >= 1 && indexback >= stop && indexfore < t.nodes.size) {
        var junctions = 0
        tmpback = tt.nodes[indexback]
        tmpfore = t.nodes[indexfore]
        if (tmpback.message != null && tmpback.message!!.isRoundabout()) {
          bCheckRoundAbout = true
        }
        if (tmpfore.message != null && tmpfore.message!!.isRoundabout()) {
          bCheckRoundAbout = true
        }

        val dist = tmpback.calcDistance(tmpfore)
        val detours = tt.getFromDetourMap(tmpback.getIdFromPos())
        var h = detours
        while (h != null) {
          junctions++
          lastJunctions.put(h.node!!.getIdFromPos(), h)
          h = h.nextHolder
        }

        if (dist == 1 && indexfore > 0) {
          if (indexfore == 1) {
            removeBackList.add(tt.nodes[tt.nodes.size - 1]) // last and first should be equal, so drop only on second also equal
            removeForeList.add(t.nodes[0])
            removeBackList.add(tmpback)
            removeForeList.add(tmpfore)
            removeVoiceHintList.add(tt.nodes.size - 1)
            removeVoiceHintList.add(indexback)
          } else {
            removeBackList.add(tmpback)
            removeForeList.add(tmpfore)
            removeVoiceHintList.add(indexback)
          }
          nextDist = t.nodes[indexfore - 1].calcDistance(tmpfore).toDouble()
          wayDistance += nextDist

        }
        if (dist > 1 || indexback == 1) {
          if (removeBackList.size != 0) {
            // recover last - should be the cross point
            removeBackList.remove(removeBackList[removeBackList.size - 1])
            removeForeList.remove(removeForeList[removeForeList.size - 1])
            break
          } else {
            return false
          }
        }
        indexback--
        indexfore++

        if (routingContext.correctMisplacedViaPointsDistance > 0 &&
          wayDistance > routingContext.correctMisplacedViaPointsDistance) {
          removeVoiceHintList.clear()
          removeBackList.clear()
          removeForeList.clear()
          return false
        }
      }


      // time hold
      var atime = 0f
      var aenergy = 0f
      var acost = 0
      if (removeForeList.size > 1) {
        atime = t.nodes[indexfore - 1].getTime()
        aenergy = t.nodes[indexfore - 1].getEnergy()
        acost = t.nodes[indexfore - 1].cost
      }

      for (e in removeBackList) {
        tt.nodes.remove(e)
      }
      for (e in removeForeList) {
        t.nodes.remove(e)
      }
      for (e in removeVoiceHintList) {
        tt.removeVoiceHint(e)
      }
      removeVoiceHintList.clear()
      removeBackList.clear()
      removeForeList.clear()

      if (atime > 0f) {
        for (e in t.nodes) {
          e.setTime(e.getTime() - atime)
          e.setEnergy(e.getEnergy() - aenergy)
          e.cost = e.cost - acost
        }
      }

      if (t.nodes.size < 2)
        return true
      if (tt.nodes.size < 1)
        return true
      if (tt.nodes.size == 1) {
        last = tt.nodes[0]
      } else {
        last = tt.nodes[tt.nodes.size - 2]
      }
      newJunction = t.nodes[0]
      newTarget = t.nodes[1]

      tt.cost = tt.nodes[tt.nodes.size - 1].cost
      t.cost = t.nodes[t.nodes.size - 1].cost

      // fill to correctedpoint
      startWp.correctedpoint = OsmNode(newJunction.getILon(), newJunction.getILat())

      return true
    }
    return false
  }

  private fun recalcTrack(t: OsmTrack) {
    var totaldist = 0
    var totaltime = 0
    var lasttime = 0f
    var lastenergy = 0f
    var speed_min = 9999f
    val directMap: MutableMap<Int, Int> = HashMap()
    var tmptime = 1f
    var speed = 1f
    var dist: Int
    var angle: Double

    var ascend = 0.0
    var ehb = 0.0
    val ourSize = t.nodes.size

    var ele_start = Short.MIN_VALUE
    var ele_end = Short.MIN_VALUE
    val eleFactor = if (routingContext.inverseRouting) 0.25 else -0.25

    for (i in 0 until ourSize) {
      val n = t.nodes[i]
      if (n.message == null) n.message = MessageData()
      var nLast: OsmPathElement? = null
      if (i == 0) {
        angle = 0.0
        dist = 0
      } else if (i == 1) {
        angle = 0.0
        nLast = t.nodes[0]
        dist = nLast.calcDistance(n)
      } else {
        val lon0 = t.nodes[i - 2].getILon()
        val lat0 = t.nodes[i - 2].getILat()
        val lon1 = t.nodes[i - 1].getILon()
        val lat1 = t.nodes[i - 1].getILat()
        val lon2 = t.nodes[i].getILon()
        val lat2 = t.nodes[i].getILat()
        angle = routingContext.anglemeter.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2)
        nLast = t.nodes[i - 1]
        dist = nLast.calcDistance(n)
      }
      n.message!!.linkdist = dist
      n.message!!.turnangle = angle.toFloat()
      totaldist += dist
      totaltime = (totaltime + n.getTime()).toInt()
      tmptime = (n.getTime() - lasttime)
      if (dist > 0) {
        speed = dist / tmptime * 3.6f
        speed_min = min(speed_min, speed)
      }
      if (tmptime == 1f) { // no time used here
        directMap[i] = dist
      }

      lastenergy = n.getEnergy()
      lasttime = n.getTime()

      val ele = n.getSElev()
      if (ele != Short.MIN_VALUE)
        ele_end = ele
      if (ele_start == Short.MIN_VALUE)
        ele_start = ele

      if (nLast != null) {
        val ele_last = nLast.getSElev()
        if (ele_last != Short.MIN_VALUE) {
          ehb = ehb + (ele_last - ele) * eleFactor
        }
        val filter = elevationFilter(n)
        if (ehb > 0) {
          ascend += ehb
          ehb = 0.0
        } else if (ehb < filter) {
          ehb = filter
        }
      }

    }

    t.ascend = ascend.toInt()
    t.plainAscend = ((ele_start - ele_end) * eleFactor + 0.5).toInt()

    t.distance = totaldist
    //t.energy = totalenergy;

    // upstream iterates a TreeSet of the keys: the same keys, ascending
    val keys = directMap.keys.toIntArray()
    keys.sort()
    for (key in keys) {
      val value = directMap[key]!!
      val addTime = (value / (speed_min / 3.6f))

      var addEnergy = 0.0
      if (key > 0) {
        val GRAVITY = 9.81  // in meters per second^(-2)
        val incline = (if (t.nodes[key - 1].getSElev() == Short.MIN_VALUE || t.nodes[key].getSElev() == Short.MIN_VALUE) 0.0 else (t.nodes[key - 1].getElev() - t.nodes[key].getElev()) / value)
        val f_roll = routingContext.totalMass * GRAVITY * (routingContext.defaultC_r + incline)
        val spd = speed_min / 3.6
        addEnergy = value * (routingContext.S_C_x * spd * spd + f_roll)
      }
      for (j in key until ourSize) {
        val n = t.nodes[j]
        n.setTime(n.getTime() + addTime)
        n.setEnergy(n.getEnergy() + addEnergy.toFloat())
      }
    }
    t.energy = t.nodes[t.nodes.size - 1].getEnergy().toInt()
  }

  /**
   * find the elevation type for position
   * to determine the filter value
   *
   * @param n  the point
   * @return  the filter value for 1sec / 3sec elevation source
   */
  internal fun elevationFilter(n: OsmPos): Double {
    val nc = nodesCache
    if (nc != null) {
      val r = nc.getElevationType(n.getILon(), n.getILat())
      if (r == 1) return -5.0
    }
    return -10.0
  }

  // geometric position matching finding the nearest routable way-section
  private fun matchWaypointsToNodes(unmatchedWaypoints: MutableList<MatchedWaypoint>) {
    resetCache(false)
    val useDynamicDistance = routingContext.useDynamicDistance
    val bAddBeeline = routingContext.buildBeelineOnRange
    var range = routingContext.waypointCatchingRange
    var ok = nodesCache!!.matchWaypointsToNodes(unmatchedWaypoints, range, islandNodePairs)
    if (!ok && useDynamicDistance) {
      resetCache(false)
      range = (-MAX_DYNAMIC_RANGE).toDouble()
      val tmp: MutableList<MatchedWaypoint> = ArrayList()
      for (mwp in unmatchedWaypoints) {
        if (mwp.crosspoint == null || mwp.radius >= routingContext.waypointCatchingRange)
          tmp.add(mwp)
      }
      ok = nodesCache!!.matchWaypointsToNodes(tmp, range, islandNodePairs)
    }
    if (!ok) {
      for (mwp in unmatchedWaypoints) {
        if (mwp.crosspoint == null)
          throw IllegalArgumentException(mwp.name + "-position not mapped in existing datafile")
      }
    }
    // add beeline points when not already done
    if (useDynamicDistance && !useNodePoints && bAddBeeline) {
      val waypoints: MutableList<MatchedWaypoint> = ArrayList()
      for (i in 0 until unmatchedWaypoints.size) {
        val wp = unmatchedWaypoints[i]
        if (wp.waypoint!!.calcDistance(wp.crosspoint!!).toDouble() > routingContext.waypointCatchingRange) {

          val nmw = MatchedWaypoint()
          if (i == 0) {
            var onn = OsmNodeNamed(wp.waypoint!!)
            onn.name = "from"
            nmw.waypoint = onn
            nmw.name = onn.name
            nmw.crosspoint = OsmNode(wp.waypoint!!.ilon, wp.waypoint!!.ilat)
            nmw.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT
            onn = OsmNodeNamed(wp.crosspoint!!)
            onn.name = wp.name + "_add"
            wp.waypoint = onn
            waypoints.add(nmw)
            wp.name = wp.name + "_add"
            waypoints.add(wp)
          } else {
            val onn = OsmNodeNamed(wp.crosspoint!!)
            onn.name = wp.name + "_add"
            nmw.waypoint = onn
            nmw.crosspoint = OsmNode(wp.crosspoint!!.ilon, wp.crosspoint!!.ilat)
            nmw.node1 = OsmNode(wp.node1!!.ilon, wp.node1!!.ilat)
            nmw.node2 = OsmNode(wp.node2!!.ilon, wp.node2!!.ilat)
            nmw.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT

            if (wp.name != null) nmw.name = wp.name
            waypoints.add(nmw)
            wp.name = wp.name + "_add"
            waypoints.add(wp)
            if (wp.name!!.startsWith("via")) {
              wp.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT
              val emw = MatchedWaypoint()
              val onn2 = OsmNodeNamed(wp.crosspoint!!)
              onn2.name = wp.name + "_2"
              emw.name = onn2.name
              emw.waypoint = onn2
              emw.crosspoint = OsmNode(nmw.crosspoint!!.ilon, nmw.crosspoint!!.ilat)
              emw.node1 = OsmNode(nmw.node1!!.ilon, nmw.node1!!.ilat)
              emw.node2 = OsmNode(nmw.node2!!.ilon, nmw.node2!!.ilat)
              emw.wpttype = MatchedWaypoint.WAYPOINT_TYPE_SHAPING
              waypoints.add(emw)
            }
            wp.crosspoint = OsmNode(wp.waypoint!!.ilon, wp.waypoint!!.ilat)
          }
        } else {
          waypoints.add(wp)
        }
      }
      unmatchedWaypoints.clear()
      unmatchedWaypoints.addAll(waypoints)
    }

  }

  private fun searchTrack(startWp: MatchedWaypoint, endWp: MatchedWaypoint, nearbyTrack: OsmTrack?, refTrack: OsmTrack?): OsmTrack {
    // remove nogos with waypoints inside
    try {
      val calcBeeline = startWp.wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT

      if (!calcBeeline)
        return searchRoutedTrack(startWp, endWp, nearbyTrack, refTrack)

      // we want a beeline-segment
      var path = routingContext.createPath(OsmLink(null, startWp.crosspoint!!))
      path = routingContext.createPath(path, OsmLink(startWp.crosspoint!!, endWp.crosspoint!!), null, false)
      return compileTrack(path, false)
    } finally {
      routingContext.restoreNogoList()
    }
  }

  private fun searchRoutedTrack(startWp: MatchedWaypoint, endWp: MatchedWaypoint, nearbyTrack: OsmTrack?, refTrack: OsmTrack?): OsmTrack {
    var track: OsmTrack? = null
    val airDistanceCostFactors = doubleArrayOf(
      routingContext.pass1coefficient,
      routingContext.pass2coefficient
    )
    var isDirty = false
    var dirtyMessage: IllegalArgumentException? = null

    if (nearbyTrack != null) {
      airDistanceCostFactor = 0.0
      try {
        track = findTrack("re-routing", startWp, endWp, nearbyTrack, refTrack, true)
      } catch (iae: IllegalArgumentException) {
        if (terminated) throw iae

        // fast partial recalcs: if that timed out, but we had a match,
        // build the concatenation from the partial and the nearby track
        val mp = matchPath
        if (mp != null) {
          track = mergeTrack(mp, nearbyTrack)
          isDirty = true
          dirtyMessage = iae
        }
        if (maxRunningTime > 0) {
          maxRunningTime += currentTimeMillis() - startTime // reset timeout...
        }
      }
    }

    if (track == null) {
      for (cfi in 0 until airDistanceCostFactors.size) {
        if (cfi > 0) lastAirDistanceCostFactor = airDistanceCostFactors[cfi - 1]
        airDistanceCostFactor = airDistanceCostFactors[cfi]

        if (airDistanceCostFactor < 0.0) {
          continue
        }

        var t: OsmTrack?
        try {
          t = findTrack(if (cfi == 0) "pass0" else "pass1", startWp, endWp, track, refTrack, false)
        } catch (iae: IllegalArgumentException) {
          val mp = matchPath
          if (!terminated && mp != null) { // timeout, but eventually prepare a dirty ref track
            val frt = mergeTrack(mp, track!!)
            foundRawTrack = frt
            frt.endPoint = endWp
            frt.nogoChecksums = routingContext.getNogoChecksums()
            frt.profileTimestamp = routingContext.profileTimestamp
            frt.isDirty = true
          }
          throw iae
        }

        val mp = matchPath
        if (t == null && track != null && mp != null) {
          // ups, didn't find it, use a merge
          t = mergeTrack(mp, track)
        }
        if (t != null) {
          track = t
        } else {
          throw IllegalArgumentException("no track found at pass=" + cfi)
        }
      }
    }
    if (track == null) throw IllegalArgumentException("no track found")

    val lastElement: OsmPathElement? = null

    val wasClean = nearbyTrack != null && !nearbyTrack.isDirty
    if (refTrack == null && !(wasClean && isDirty)) { // do not overwrite a clean with a dirty track
      track.endPoint = endWp
      track.nogoChecksums = routingContext.getNogoChecksums()
      track.profileTimestamp = routingContext.profileTimestamp
      track.isDirty = isDirty
      foundRawTrack = track
    }

    if (!wasClean && isDirty) {
      throw dirtyMessage!!
    }

    // final run for verbose log info and detail nodes
    airDistanceCostFactor = 0.0
    lastAirDistanceCostFactor = 0.0
    guideTrack = track
    startTime = currentTimeMillis() // reset timeout...
    try {
      val tt = findTrack("re-tracking", startWp, endWp, null, refTrack, false)
      if (tt == null) throw IllegalArgumentException("error re-tracking track")
      return tt
    } finally {
      guideTrack = null
    }
  }


  private fun resetCache(detailed: Boolean) {
    val maxmem = routingContext.memoryclass * 1024L * 1024L // in MB

    nodesCache = NodesCache(segmentDir, routingContext.expctxWay!!, routingContext.forceSecondaryData, maxmem, nodesCache, detailed)
    islandNodePairs.clearTempPairs()
  }

  private fun getStartPath(n1: OsmNode, n2: OsmNode, mwp: MatchedWaypoint, endPos: OsmNodeNamed?, sameSegmentSearch: Boolean): OsmPath? {
    if (endPos != null) {
      endPos.radius = 1.5
    }
    val p = getStartPath(n1, n2, OsmNodeNamed(mwp.crosspoint!!), endPos, sameSegmentSearch)

    // special case: start+end on same segment
    if (p != null && p.cost >= 0 && sameSegmentSearch && endPos != null && endPos.radius < 1.5) {
      p.treedepth = 0 // hack: mark for the final-check
    }
    return p
  }


  private fun getStartPath(n1: OsmNode, n2: OsmNode, wp: OsmNodeNamed, endPos: OsmNodeNamed?, sameSegmentSearch: Boolean): OsmPath? {
    try {
      routingContext.setWaypoint(wp, if (sameSegmentSearch) endPos else null, false)
      var bestPath: OsmPath? = null
      var bestLink: OsmLink? = null
      val startLink = OsmLink(null, n1)
      val startPath = routingContext.createPath(startLink)
      startLink.addLinkHolder(startPath, null)
      var minradius = 1e10
      var link = n1.firstlink
      while (link != null) {
        val nextNode = link.getTarget(n1)!!
        if (nextNode.isHollow()) {
          link = link.getNext(n1)
          continue // border node?
        }
        if (nextNode.firstlink == null) {
          link = link.getNext(n1)
          continue // don't care about dead ends
        }
        if (nextNode === n1) {
          link = link.getNext(n1)
          continue // ?
        }
        if (nextNode !== n2) {
          link = link.getNext(n1)
          continue // just that link
        }

        wp.radius = 1.5
        val testPath = routingContext.createPath(startPath, link, null, guideTrack != null)
        testPath.airdistance = if (endPos == null) 0 else nextNode.calcDistance(endPos)
        if (wp.radius < minradius) {
          bestPath = testPath
          minradius = wp.radius
          bestLink = link
        }
        link = link.getNext(n1)
      }
      if (bestLink != null) {
        bestLink.addLinkHolder(bestPath!!, n1)
      }
      if (bestPath != null) bestPath.treedepth = 1

      return bestPath
    } finally {
      routingContext.unsetWaypoint()
    }
  }

  private fun findTrack(operationName: String, startWp: MatchedWaypoint, endWp: MatchedWaypoint?, costCuttingTrack: OsmTrack?, refTrack: OsmTrack?, fastPartialRecalc: Boolean): OsmTrack? {
    try {
      val wpts2: MutableList<OsmNode> = ArrayList()
      wpts2.add(startWp.waypoint!!)
      if (endWp != null) wpts2.add(endWp.waypoint!!)
      routingContext.cleanNogoList(wpts2)

      val detailed = guideTrack != null
      resetCache(detailed)
      nodesCache!!.nodesMap.cleanupMode = if (detailed) 0 else (if (routingContext.considerTurnRestrictions) 2 else 1)
      return _findTrack(operationName, startWp, endWp, costCuttingTrack, refTrack, fastPartialRecalc)
    } finally {
      routingContext.restoreNogoList()
      nodesCache!!.clean(false) // clean only non-virgin caches
    }
  }


  private fun _findTrack(operationName: String, startWp: MatchedWaypoint, endWp: MatchedWaypoint?, costCuttingTrack: OsmTrack?, refTrack: OsmTrack?, fastPartialRecalc0: Boolean): OsmTrack? {
    var fastPartialRecalc = fastPartialRecalc0
    val nodesCache = this.nodesCache!!
    val verbose = guideTrack != null

    var maxTotalCost = if (guideTrack != null) guideTrack!!.cost + 5000 else 1000000000
    var firstMatchCost = 1000000000

    matchPath = null
    var nodesVisited = 0

    val startNodeId1 = startWp.node1!!.getIdFromPos()
    val startNodeId2 = startWp.node2!!.getIdFromPos()
    val endNodeId1 = if (endWp == null) -1L else endWp.node1!!.getIdFromPos()
    val endNodeId2 = if (endWp == null) -1L else endWp.node2!!.getIdFromPos()
    var end1: OsmNode? = null
    var end2: OsmNode? = null
    var endPos: OsmNodeNamed? = null

    var sameSegmentSearch = false
    val start1 = nodesCache.getGraphNode(startWp.node1!!)
    val start2 = nodesCache.getGraphNode(startWp.node2!!)
    if (endWp != null) {
      end1 = nodesCache.getGraphNode(endWp.node1!!)
      end2 = nodesCache.getGraphNode(endWp.node2!!)
      nodesCache.nodesMap.endNode1 = end1
      nodesCache.nodesMap.endNode2 = end2
      endPos = OsmNodeNamed(endWp.crosspoint!!)
      sameSegmentSearch = (start1 === end1 && start2 === end2) || (start1 === end2 && start2 === end1)
    }
    if (!nodesCache.obtainNonHollowNode(start1)) {
      return null
    }
    nodesCache.expandHollowLinkTargets(start1)
    if (!nodesCache.obtainNonHollowNode(start2)) {
      return null
    }
    nodesCache.expandHollowLinkTargets(start2)


    routingContext.startDirectionValid = routingContext.forceUseStartDirection || fastPartialRecalc
    routingContext.startDirectionValid = routingContext.startDirectionValid and (routingContext.startDirection != null && !routingContext.inverseDirection)

    val startPath1 = getStartPath(start1, start2, startWp, endPos, sameSegmentSearch)
    val startPath2 = getStartPath(start2, start1, startWp, endPos, sameSegmentSearch)

    // check for an INITIAL match with the cost-cutting-track
    if (costCuttingTrack != null) {
      val pe1 = costCuttingTrack.getLink(startNodeId1, startNodeId2)
      if (pe1 != null) {
        var c = startPath1!!.cost - pe1.cost
        if (c < 0) c = 0
        if (c < firstMatchCost) firstMatchCost = c
      }

      val pe2 = costCuttingTrack.getLink(startNodeId2, startNodeId1)
      if (pe2 != null) {
        var c = startPath2!!.cost - pe2.cost
        if (c < 0) c = 0
        if (c < firstMatchCost) firstMatchCost = c
      }
    }

    if (startPath1 == null) return null
    if (startPath2 == null) return null

    openSet.clear()
    addToOpenset(startPath1)
    addToOpenset(startPath2)
    val openBorderList: MutableList<OsmPath> = ArrayList(4096)
    var memoryPanicMode = false
    var needNonPanicProcessing = false

    while (true) {
      if (terminated) {
        throw IllegalArgumentException("operation killed by thread-priority-watchdog after " + (currentTimeMillis() - startTime) / 1000 + " seconds")
      }

      if (maxRunningTime > 0) {
        val timeout = if (matchPath == null && fastPartialRecalc) maxRunningTime / 3 else maxRunningTime
        if (currentTimeMillis() - startTime > timeout) {
          throw IllegalArgumentException(operationName + " timeout after " + (timeout / 1000) + " seconds")
        }
      }

      val path = openSet.popLowestKeyValue()
      if (path == null) {
        if (openBorderList.isEmpty()) {
          break
        }
        for (p in openBorderList) {
          openSet.add(p.cost + (p.airdistance * airDistanceCostFactor).toInt(), p)
        }
        openBorderList.clear()
        memoryPanicMode = false
        needNonPanicProcessing = true
        continue
      }

      if (path.airdistance == -1) {
        continue
      }

      if (directWeaving && nodesCache.hasHollowLinkTargets(path.getTargetNode()!!)) {
        if (!memoryPanicMode) {
          if (!nodesCache.nodesMap.isInMemoryBounds(openSet.getSize(), false)) {
            nodesCache.nodesMap.collectOutreachers()
            while (true) {
              val p3 = openSet.popLowestKeyValue() ?: break
              if (p3.airdistance != -1 && nodesCache.nodesMap.canEscape(p3.getTargetNode()!!)) {
                openBorderList.add(p3)
              }
            }
            nodesCache.nodesMap.clearTemp()
            for (p in openBorderList) {
              openSet.add(p.cost + (p.airdistance * airDistanceCostFactor).toInt(), p)
            }
            openBorderList.clear()
            if (!nodesCache.nodesMap.isInMemoryBounds(openSet.getSize(), true)) {
              if (maxTotalCost < 1000000000 || needNonPanicProcessing || fastPartialRecalc) {
                throw IllegalArgumentException("memory limit reached")
              }
              memoryPanicMode = true
            }
          }
        }
        if (memoryPanicMode) {
          openBorderList.add(path)
          continue
        }
      }
      needNonPanicProcessing = false


      if (fastPartialRecalc && matchPath != null && path.cost > 30L * firstMatchCost && !costCuttingTrack!!.isDirty) {

        // use an early exit, unless there's a realistc chance to complete within the timeout
        if (path.cost > maxTotalCost / 2 && currentTimeMillis() - startTime < maxRunningTime / 3) {
          startTime = currentTimeMillis()
          fastPartialRecalc = false
        } else {
          throw IllegalArgumentException("early exit for a close recalc")
        }
      }

      if (nodeLimit > 0) { // check node-limit for target island search
        if (--nodeLimit == 0) {
          return null
        }
      }

      nodesVisited++
      linksProcessed++

      val currentLink = path.getLink()!!
      val sourceNode = path.getSourceNode()
      val currentNode = path.getTargetNode()!!

      if (currentLink.isLinkUnused()) {
        continue
      }

      val currentNodeId = currentNode.getIdFromPos()
      val sourceNodeId = sourceNode!!.getIdFromPos()

      if (!path.didEnterDestinationArea()) {
        islandNodePairs.addTempPair(sourceNodeId, currentNodeId)
      }

      if (path.treedepth != 1) {
        if (path.treedepth == 0) { // hack: sameSegment Paths marked treedepth=0 to pass above check
          path.treedepth = 1
        }

        if ((sourceNodeId == endNodeId1 && currentNodeId == endNodeId2) ||
          (sourceNodeId == endNodeId2 && currentNodeId == endNodeId1)) {
          // track found, compile
          val t = compileTrack(path, verbose)
          t.showspeed = routingContext.showspeed
          t.showSpeedProfile = routingContext.showSpeedProfile
          return t
        }

        // check for a match with the cost-cutting-track
        if (costCuttingTrack != null) {
          val pe = costCuttingTrack.getLink(sourceNodeId, currentNodeId)
          if (pe != null) {
            // remember first match cost for fast termination of partial recalcs
            var parentcost = if (path.originElement == null) 0 else path.originElement!!.cost

            // hitting start-element of costCuttingTrack?
            val c = path.cost - parentcost - pe.cost
            if (c > 0) parentcost += c

            if (parentcost < firstMatchCost) firstMatchCost = parentcost

            val costEstimate = path.cost +
              path.elevationCorrection() +
              (costCuttingTrack.cost - pe.cost)
            if (costEstimate <= maxTotalCost) {
              matchPath = OsmPathElement.create(path)
            }
            if (costEstimate < maxTotalCost) {
              maxTotalCost = costEstimate
            }
          }
        }
      }

      val firstLinkHolder = currentLink.getFirstLinkHolder(sourceNode)
      var linkHolder0 = firstLinkHolder
      while (linkHolder0 != null) {
        (linkHolder0 as OsmPath).airdistance = -1 // invalidate the entry in the open set;
        linkHolder0 = linkHolder0.getNextForLink()
      }

      if (path.treedepth > 1) {
        val isBidir = currentLink.isBidirectional()
        sourceNode.unlinkLink(currentLink)

        // if the counterlink is alive and does not yet have a path, remove it
        if (isBidir && currentLink.getFirstLinkHolder(currentNode) == null && !routingContext.considerTurnRestrictions) {
          currentNode.unlinkLink(currentLink)
        }
      }

      // recheck cutoff before doing expensive stuff
      val addDiff = 100
      if (path.cost + path.airdistance > maxTotalCost + addDiff) {
        continue
      }

      nodesCache.nodesMap.currentMaxCost = maxTotalCost
      nodesCache.nodesMap.currentPathCost = path.cost
      nodesCache.nodesMap.destination = endPos

      routingContext.firstPrePath = null

      var link = currentNode.firstlink
      while (link != null) {
        val nextNode = link.getTarget(currentNode)!!

        if (!nodesCache.obtainNonHollowNode(nextNode)) {
          link = link.getNext(currentNode)
          continue // border node?
        }
        if (nextNode.firstlink == null) {
          link = link.getNext(currentNode)
          continue // don't care about dead ends
        }
        if (nextNode === sourceNode) {
          link = link.getNext(currentNode)
          continue // border node?
        }

        val prePath = routingContext.createPrePath(path, link)
        if (prePath != null) {
          prePath.next = routingContext.firstPrePath
          routingContext.firstPrePath = prePath
        }
        link = link.getNext(currentNode)
      }

      link = currentNode.firstlink
      while (link != null) {
        val nextNode = link.getTarget(currentNode)!!

        if (!nodesCache.obtainNonHollowNode(nextNode)) {
          link = link.getNext(currentNode)
          continue // border node?
        }
        if (nextNode.firstlink == null) {
          link = link.getNext(currentNode)
          continue // don't care about dead ends
        }
        if (nextNode === sourceNode) {
          link = link.getNext(currentNode)
          continue // border node?
        }

        val gt = guideTrack
        if (gt != null) {
          val gidx = path.treedepth + 1
          if (gidx >= gt.nodes.size) {
            link = link.getNext(currentNode)
            continue
          }
          val guideNode = gt.nodes[if (routingContext.inverseRouting) gt.nodes.size - 1 - gidx else gidx]
          val nextId = nextNode.getIdFromPos()
          if (nextId != guideNode.getIdFromPos()) {
            // not along the guide-track, discard, but register for voice-hint processing
            if (routingContext.turnInstructionMode > 0) {
              val detour = routingContext.createPath(path, link, refTrack, true)
              if (detour.cost.toDouble() >= 0.0 && nextId != startNodeId1 && nextId != startNodeId2) {
                gt.registerDetourForId(currentNode.getIdFromPos(), OsmPathElement.create(detour))
              }
            }
            link = link.getNext(currentNode)
            continue
          }
        }

        var bestPath: OsmPath? = null

        var isFinalLink = false
        val targetNodeId = nextNode.getIdFromPos()
        if (currentNodeId == endNodeId1 || currentNodeId == endNodeId2) {
          if (targetNodeId == endNodeId1 || targetNodeId == endNodeId2) {
            isFinalLink = true
          }
        }

        var linkHolder = firstLinkHolder
        while (linkHolder != null) {
          val otherPath = linkHolder as OsmPath
          try {
            if (isFinalLink) {
              endPos!!.radius = 1.5 // 1.5 meters is the upper limit that will not change the unit-test result..
              routingContext.setWaypoint(endPos, true)
            }
            val testPath = routingContext.createPath(otherPath, link, refTrack, guideTrack != null)
            if (testPath.cost >= 0 && (bestPath == null || testPath.cost < bestPath.cost) &&
              (testPath.sourceNode!!.getIdFromPos() != testPath.targetNode!!.getIdFromPos())) {
              bestPath = testPath
            }
          } finally {
            if (isFinalLink) {
              routingContext.unsetWaypoint()
            }
          }
          linkHolder = linkHolder.getNextForLink()
        }
        if (bestPath != null) {
          bestPath.airdistance = if (isFinalLink) 0 else nextNode.calcDistance(endPos!!)

          val boundary = this.boundary
          val inRadius = boundary == null || boundary.isInBoundary(nextNode, bestPath.cost)

          if (inRadius && (isFinalLink || (bestPath.cost + bestPath.airdistance).toDouble() <= (if (lastAirDistanceCostFactor != 0.0) maxTotalCost * lastAirDistanceCostFactor else maxTotalCost.toDouble()) + addDiff)) {
            // add only if this may beat an existing path for that link
            var dominator = link.getFirstLinkHolder(currentNode)
            while (dominator != null) {
              val dp = dominator as OsmPath
              if (dp.airdistance != -1 && bestPath.definitlyWorseThan(dp)) {
                break
              }
              dominator = dominator.getNextForLink()
            }

            if (dominator == null) {
              bestPath.treedepth = path.treedepth + 1
              link.addLinkHolder(bestPath, currentNode)
              addToOpenset(bestPath)
            }
          }
        }
        link = link.getNext(currentNode)
      }
    }

    if (nodesVisited < MAXNODES_ISLAND_CHECK && islandNodePairs.getFreezeCount() < 5) {
      throw RoutingIslandException()
    }

    return null
  }

  private fun addToOpenset(path: OsmPath) {
    if (path.cost >= 0) {
      openSet.add(path.cost + (path.airdistance * airDistanceCostFactor).toInt(), path)
    }
  }

  private fun compileTrack(path: OsmPath, verbose: Boolean): OsmTrack {
    var element: OsmPathElement? = OsmPathElement.create(path)

    // for final track, cut endnode
    if (guideTrack != null && element!!.origin != null) {
      element = element.origin
    }

    val totalTime = element!!.getTime()
    val totalEnergy = element.getEnergy()

    val track = OsmTrack()
    track.cost = path.cost
    track.energy = path.getTotalEnergy().toInt()

    var distance = 0

    val eleFactor = if (routingContext.inverseRouting) -0.25 else 0.25
    while (element != null) {
      if (guideTrack != null && element.message == null) {
        element.message = MessageData()
      }
      val nextElement = element.origin
      // ignore double element
      if (nextElement != null && nextElement.positionEquals(element)) {
        element = nextElement
        continue
      }
      if (routingContext.inverseRouting) {
        element.setTime(totalTime - element.getTime())
        element.setEnergy(totalEnergy - element.getEnergy())
        track.nodes.add(element)
      } else {
        track.nodes.add(0, element)
      }

      if (nextElement != null) {
        distance += element.calcDistance(nextElement)
      }
      element = nextElement
    }
    track.distance = distance
    track.buildMap()

    // for final track..
    val gt = guideTrack
    if (gt != null) {
      track.copyDetours(gt)
    }
    return track
  }

  private fun mergeTrack(match: OsmPathElement, oldTrack: OsmTrack): OsmTrack {
    var element: OsmPathElement? = match
    val track = OsmTrack()
    track.cost = oldTrack.cost

    while (element != null) {
      track.addNode(element)
      element = element.origin
    }
    var lastId: Long = 0
    val id1 = match.getIdFromPos()
    val id0 = if (match.origin == null) 0L else match.origin!!.getIdFromPos()
    var appending = false
    for (n in oldTrack.nodes) {
      if (appending) {
        track.nodes.add(n)
      }

      val id = n.getIdFromPos()
      if (id == id1 && lastId == id0) {
        appending = true
      }
      lastId = id
    }


    track.buildMap()
    return track
  }

  fun isFinished(): Boolean {
    return finished
  }

  fun getLinksProcessed(): Int {
    return linksProcessed
  }

  fun getDistance(): Int {
    return foundTrack!!.distance
  }

  fun getAscend(): Int {
    return foundTrack!!.ascend
  }

  fun getPlainAscend(): Int {
    return foundTrack!!.plainAscend
  }

  fun getFoundTrack(): OsmTrack? {
    return foundTrack
  }

  fun getFoundRawTrack(): OsmTrack? {
    return foundRawTrack
  }

  fun getErrorMessage(): String? {
    return errorMessage
  }

  fun terminate() {
    terminated = true
  }

  fun isTerminated(): Boolean {
    return terminated
  }
}
