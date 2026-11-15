/**
 * Efficient cache or osmnodes
 *
 * @author ab
 */
package btools.mapaccess

import btools.codec.DataBuffers
import btools.codec.MicroCache
import btools.codec.WaypointMatcher
import btools.expressions.BExpressionContextWay
import btools.util.BFiles
import btools.util.IOException
import btools.util.fileName
import btools.util.joinPath
import kotlin.jvm.JvmField

class NodesCache(segmentDir: String, ctxWay: BExpressionContextWay, forceSecondaryData: Boolean, maxmem: Long, oldCache: NodesCache?, detailed: Boolean) {

  private var MAX_DYNAMIC_CATCHES = 20 // used with RoutingEngiine MAX_DYNAMIC_RANGE = 60000m


  private var segmentDir: String
  private var secondarySegmentsDir: String? = null

  @JvmField var nodesMap: OsmNodesMap
  private var expCtxWay: BExpressionContextWay
  private var lookupVersion: Int
  private var lookupMinorVersion: Int
  private var forceSecondaryData: Boolean
  private var currentFileName: String? = null

  private var fileCache: MutableMap<String, PhysicalFile?>
  private var dataBuffers: DataBuffers

  private var fileRows: Array<Array<OsmFile?>?>

  @JvmField var waypointMatcher: WaypointMatcher? = null

  @JvmField var first_file_access_failed: Boolean = false
  @JvmField var first_file_access_name: String? = null

  private var cacheSum: Long = 0
  private var maxmemtiles: Long
  private var detailed: Boolean // NOPMD used in constructor

  private var garbageCollectionEnabled = false
  private var ghostCleaningDone = false


  private var cacheSumClean: Long = 0
  private var ghostSum: Long = 0
  private var ghostWakeup: Long = 0

  // Java: !Boolean.getBoolean("disableDirectWeaving"), a JVM property the app never sets
  private var directWeaving = true

  fun formatStatus(): String {
    return "collecting=" + garbageCollectionEnabled + " noGhosts=" + ghostCleaningDone + " cacheSum=" + cacheSum + " cacheSumClean=" + cacheSumClean + " ghostSum=" + ghostSum + " ghostWakeup=" + ghostWakeup
  }

  init {
    this.maxmemtiles = maxmem / 8
    this.segmentDir = segmentDir
    this.nodesMap = OsmNodesMap()
    this.nodesMap.maxmem = (2L * maxmem) / 3L
    this.expCtxWay = ctxWay
    this.lookupVersion = ctxWay.meta!!.lookupVersion.toInt()
    this.lookupMinorVersion = ctxWay.meta!!.lookupMinorVersion.toInt()
    this.forceSecondaryData = forceSecondaryData
    this.detailed = detailed

    ctxWay.setDecodeForbidden(detailed)

    first_file_access_failed = false
    first_file_access_name = null

    // Java threw RuntimeException("segment directory " + segmentDir.getAbsolutePath() + " does not exist")
    // when !segmentDir.isDirectory(); the common file shim has no directory test, so a missing folder
    // surfaces as "datafile ... not found" instead.

    if (oldCache != null) {
      fileCache = oldCache.fileCache
      dataBuffers = oldCache.dataBuffers
      secondarySegmentsDir = oldCache.secondarySegmentsDir

      // re-use old, virgin caches (if same detail-mode)
      if (oldCache.detailed == detailed) {
        fileRows = oldCache.fileRows
        for (fileRow in fileRows) {
          if (fileRow == null)
            continue
          for (osmf in fileRow) {
            cacheSum += osmf!!.setGhostState()
          }
        }
      } else {
        fileRows = arrayOfNulls(180)
      }
    } else {
      fileCache = HashMap(4)
      fileRows = arrayOfNulls(180)
      dataBuffers = DataBuffers()
      // Java read a secondary segment folder from storageconfig.txt (StorageConfigHelper);
      // EUC Planet never has one, so it stays null.
      secondarySegmentsDir = null
    }
    ghostSum = cacheSum
  }

  fun clean(all: Boolean) {
    for (fileRow in fileRows) {
      if (fileRow == null)
        continue
      for (osmf in fileRow) {
        osmf!!.clean(all)
      }
    }
  }

  // if the cache sum exceeded a threshold,
  // clean all ghosts and enable garbage collection
  private fun checkEnableCacheCleaning() {
    if (cacheSum < maxmemtiles) {
      return
    }

    for (i in fileRows.indices) {
      val fileRow = fileRows[i] ?: continue
      for (osmf in fileRow) {
        if (garbageCollectionEnabled && !ghostCleaningDone) {
          cacheSum -= osmf!!.cleanGhosts()
        } else {
          cacheSum -= osmf!!.collectAll()
        }
      }
    }

    if (garbageCollectionEnabled) {
      ghostCleaningDone = true
      maxmemtiles *= 2
    } else {
      cacheSumClean = cacheSum
      garbageCollectionEnabled = true
    }
  }

  fun loadSegmentFor(ilon: Int, ilat: Int): Int {
    val mc = getSegmentFor(ilon, ilat)
    return if (mc == null) 0 else mc.getSize()
  }

  fun getSegmentFor(ilon: Int, ilat: Int): MicroCache? {
    try {
      val lonDegree = ilon / 1000000
      val latDegree = ilat / 1000000
      var osmf: OsmFile? = null
      val fileRow = fileRows[latDegree]
      val ndegrees = if (fileRow == null) 0 else fileRow.size
      for (i in 0 until ndegrees) {
        if (fileRow!![i]!!.lonDegree == lonDegree) {
          osmf = fileRow[i]
          break
        }
      }
      if (osmf == null) {
        osmf = fileForSegment(lonDegree, latDegree)
        val newFileRow = arrayOfNulls<OsmFile>(ndegrees + 1)
        for (i in 0 until ndegrees) {
          newFileRow[i] = fileRow!![i]
        }
        newFileRow[ndegrees] = osmf
        fileRows[latDegree] = newFileRow
      }
      currentFileName = osmf.filename

      if (!osmf.hasData()) {
        return null
      }

      var segment = osmf.getMicroCache(ilon, ilat)
      // needed for a second chance
      if (segment == null || (waypointMatcher != null && (waypointMatcher as WaypointMatcherImpl).useDynamicRange)) {
        checkEnableCacheCleaning()
        segment = osmf.createMicroCache(ilon, ilat, dataBuffers, expCtxWay, waypointMatcher, if (directWeaving) nodesMap else null)

        cacheSum += segment!!.getDataSize()
      } else if (segment.ghost) {
        segment.unGhost()
        ghostWakeup += segment.getDataSize()
      }
      return segment
    } catch (re: IOException) {
      throw RuntimeException(re.message)
    } catch (re: RuntimeException) {
      throw re
    } catch (e: Exception) {
      throw RuntimeException("error reading datafile " + currentFileName + ": " + e, e)
    }
  }

  /**
   * make sure the given node is non-hollow,
   * which means it contains not just the id,
   * but also the actual data
   *
   * @return true if successfull, false if node is still hollow
   */
  fun obtainNonHollowNode(node: OsmNode): Boolean {
    if (!node.isHollow())
      return true

    val segment = getSegmentFor(node.ilon, node.ilat)
    if (segment == null) {
      return false
    }
    if (!node.isHollow()) {
      return true // direct weaving...
    }

    val id = node.getIdFromPos()
    if (segment.getAndClear(id)) {
      node.parseNodeBody(segment, nodesMap, expCtxWay)
    }

    if (garbageCollectionEnabled) { // garbage collection
      cacheSum -= segment.collect(segment.getSize() shr 1) // threshold = 1/2 of size is deleted
    }

    return !node.isHollow()
  }


  /**
   * make sure all link targets of the given node are non-hollow
   */
  fun expandHollowLinkTargets(n: OsmNode) {
    var link = n.firstlink
    while (link != null) {
      obtainNonHollowNode(link.getTarget(n))
      link = link.getNext(n)
    }
  }

  /**
   * make sure all link targets of the given node are non-hollow
   */
  fun hasHollowLinkTargets(n: OsmNode): Boolean {
    var link = n.firstlink
    while (link != null) {
      if (link.getTarget(n).isHollow()) {
        return true
      }
      link = link.getNext(n)
    }
    return false
  }

  /**
   * get a node for the given id with all link-targets also non-hollow
   * <p>
   * It is required that an instance of the start-node does not yet
   * exist, not even a hollow instance, so getStartNode should only
   * be called once right after resetting the cache
   *
   * @param id the id of the node to load
   * @return the fully expanded node for id, or null if it was not found
   */
  fun getStartNode(id: Long): OsmNode? {
    // initialize the start-node
    val n = OsmNode(id)
    n.setHollow()
    nodesMap.put(n)
    if (!obtainNonHollowNode(n)) {
      return null
    }
    expandHollowLinkTargets(n)
    return n
  }

  fun getGraphNode(template: OsmNode): OsmNode {
    val graphNode = OsmNode(template.ilon, template.ilat)
    graphNode.setHollow()
    val existing = nodesMap.put(graphNode)
    if (existing == null) {
      return graphNode
    }
    nodesMap.put(existing)
    return existing
  }

  fun matchWaypointsToNodes(unmatchedWaypoints: List<MatchedWaypoint>, maxDistance: Double, islandNodePairs: OsmNodePairSet): Boolean {
    waypointMatcher = WaypointMatcherImpl(unmatchedWaypoints, maxDistance, islandNodePairs)
    for (mwp in unmatchedWaypoints) {
      var cellsize = 12500
      preloadPosition(mwp.waypoint!!, cellsize, 1, false)
      // get a second chance
      if (mwp.crosspoint == null || mwp.radius > RETRY_RANGE) {
        cellsize = 1000000 / 32
        preloadPosition(mwp.waypoint!!, cellsize, if (maxDistance < 0) MAX_DYNAMIC_CATCHES else 2, maxDistance < 0)
      }
    }

    if (first_file_access_failed) {
      throw IllegalArgumentException("datafile " + first_file_access_name + " not found")
    }
    val len = unmatchedWaypoints.size
    for (i in 0 until len) {
      val mwp = unmatchedWaypoints[i]
      if (mwp.crosspoint == null) {
        if (unmatchedWaypoints.size > 1 && i == unmatchedWaypoints.size - 1 && unmatchedWaypoints[i - 1].wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) {
          mwp.crosspoint = OsmNode(mwp.waypoint!!.ilon, mwp.waypoint!!.ilat)
          mwp.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT
        } else {
          // do not break here throw new IllegalArgumentException(mwp.name + "-position not mapped in existing datafile");
          return false
        }
      }
      if (unmatchedWaypoints.size > 1 && i == unmatchedWaypoints.size - 1 && unmatchedWaypoints[i - 1].wpttype == MatchedWaypoint.WAYPOINT_TYPE_DIRECT) {
        mwp.crosspoint = OsmNode(mwp.waypoint!!.ilon, mwp.waypoint!!.ilat)
        mwp.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT
      }
    }
    return true
  }

  private fun preloadPosition(n: OsmNode, d: Int, maxscale: Int, bUseDynamicRange: Boolean) {
    first_file_access_failed = false
    first_file_access_name = null
    loadSegmentFor(n.ilon, n.ilat)
    if (first_file_access_failed) {
      throw IllegalArgumentException("datafile " + first_file_access_name + " not found")
    }
    var scale = 1
    while (scale < maxscale) {
      for (idxLat in -scale..scale)
        for (idxLon in -scale..scale) {
          if (idxLon != 0 || idxLat != 0) {
            loadSegmentFor(n.ilon + d * idxLon, n.ilat + d * idxLat)
          }
        }
      if (bUseDynamicRange && waypointMatcher!!.hasMatch(n.ilon, n.ilat)) break
      scale++
    }
  }

  private fun fileForSegment(lonDegree: Int, latDegree: Int): OsmFile {
    val lonMod5 = lonDegree % 5
    val latMod5 = latDegree % 5

    val lon = lonDegree - 180 - lonMod5
    val slon = if (lon < 0) "W" + (-lon) else "E" + lon
    val lat = latDegree - 90 - latMod5

    val slat = if (lat < 0) "S" + (-lat) else "N" + lat
    val filenameBase = slon + "_" + slat

    currentFileName = filenameBase + ".rd5"

    var ra: PhysicalFile? = null
    if (!fileCache.containsKey(filenameBase)) {
      var f: String? = null
      if (!forceSecondaryData) {
        val primary = joinPath(segmentDir, filenameBase + ".rd5")
        if (BFiles.isFile(primary)) {
          f = primary
        }
      }
      // Java built new File(null, name) when there is no secondary folder, a path relative
      // to the working directory that never holds segments; skip it instead.
      val secondarySegmentsDir = secondarySegmentsDir
      if (f == null && secondarySegmentsDir != null) {
        val secondary = joinPath(secondarySegmentsDir, filenameBase + ".rd5")
        if (BFiles.isFile(secondary)) {
          f = secondary
        }
      }
      if (f != null) {
        currentFileName = fileName(f)
        ra = PhysicalFile(f, dataBuffers, lookupVersion, lookupMinorVersion)
      }
      fileCache.put(filenameBase, ra)
    }
    ra = fileCache.get(filenameBase)
    val osmf = OsmFile(ra, lonDegree, latDegree, dataBuffers)

    if (first_file_access_name == null) {
      first_file_access_name = currentFileName
      first_file_access_failed = osmf.filename == null
    }

    return osmf
  }

  fun close() {
    for (f in fileCache.values) {
      try {
        if (f != null)
          f.ra!!.close()
      } catch (ioe: IOException) {
        // ignore
      }
    }
  }

  fun getElevationType(ilon: Int, ilat: Int): Int {
    val lonDegree = ilon / 1000000
    val latDegree = ilat / 1000000
    val fileRow = fileRows[latDegree]
    val ndegrees = if (fileRow == null) 0 else fileRow.size
    for (i in 0 until ndegrees) {
      if (fileRow!![i]!!.lonDegree == lonDegree) {
        val osmf = fileRow[i]
        if (osmf != null) return osmf.elevationType.toInt()
        break
      }
    }
    return 3
  }

  companion object {
    internal const val RETRY_RANGE = 250
  }

}
