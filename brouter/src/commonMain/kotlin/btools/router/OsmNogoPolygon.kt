/**********************************************************************************************
 Copyright (C) 2018 Norbert Truchsess norbert.truchsess@t-online.de

 The following methods are based on work of Dan Sunday published at:
 http://geomalgorithms.com/a03-_inclusion.html

 cn_PnPoly, wn_PnPoly, inSegment, intersect2D_2Segments
 **********************************************************************************************/
package btools.router

import btools.util.CheapRuler
import kotlin.jvm.JvmField
import kotlin.math.min
import kotlin.math.sqrt

open class OsmNogoPolygon(closed: Boolean) : OsmNodeNamed() {
  class Point internal constructor(lon: Int, lat: Int) {
    @JvmField val y: Int
    @JvmField val x: Int

    init {
      x = lon
      y = lat
    }
  }

  @JvmField val points: MutableList<Point> = ArrayList()

  @JvmField val isClosed: Boolean

  init {
    this.isClosed = closed
    this.isNogo = true
    this.name = ""
  }

  fun addVertex(lon: Int, lat: Int) {
    points.add(Point(lon, lat))
  }

  /**
   * calcBoundingCircle is inspired by the algorithm described on
   * http://geomalgorithms.com/a08-_containers.html
   * (fast computation of bounding circly in c). It is not as fast (the original
   * algorithm runs in linear time), as it may do more iterations but it takes
   * into account the coslat-factor being used for the linear approximation that
   * is also used in other places of brouter does change when moving the centerpoint
   * with each iteration.
   * This is done to ensure the calculated radius being used
   * in RoutingContext.calcDistance will actually contain the whole polygon.
   * <p>
   * For reasonable distributed vertices the implemented algorithm runs in O(n*ln(n)).
   * As this is only run once on initialization of OsmNogoPolygon this methods
   * overall usage of cpu is neglegible in comparism to the cpu-usage of the
   * actual routing algoritm.
   */
  fun calcBoundingCircle() {
    var cxmin: Int
    var cxmax: Int
    var cymin: Int
    var cymax: Int
    cymin = Int.MAX_VALUE
    cxmin = cymin
    cymax = Int.MIN_VALUE
    cxmax = cymax

    // first calculate a starting center point as center of boundingbox
    for (i in 0 until points.size) {
      val p = points[i]
      if (p.x < cxmin) {
        cxmin = p.x
      }
      if (p.x > cxmax) {
        cxmax = p.x
      }
      if (p.y < cymin) {
        cymin = p.y
      }
      if (p.y > cymax) {
        cymax = p.y
      }
    }

    var cx = (cxmax + cxmin) / 2 // center of circle
    var cy = (cymax + cymin) / 2

    var lonlat2m = CheapRuler.getLonLatToMeterScales(cy) // conversion-factors at the center of circle
    var dlon2m = lonlat2m[0]
    var dlat2m = lonlat2m[1]

    var rad = 0.0  // radius

    var dmax = 0.0 // length of vector from center to point
    var i_max = -1

    do {
      // now identify the point outside of the circle that has the greatest distance
      for (i in 0 until points.size) {
        val p = points[i]

        // to get precisely the same results as in RoutingContext.calcDistance()
        // it's crucial to use the factors of the center!
        val x1 = (cx - p.x) * dlon2m
        val y1 = (cy - p.y) * dlat2m
        val dist = sqrt(x1 * x1 + y1 * y1)

        if (dist <= rad) {
          continue
        }
        if (dist > dmax) {
          // new maximum distance found
          dmax = dist
          i_max = i
        }
      }
      if (i_max < 0) {
        break // leave loop when no point outside the circle is found any more.
      }
      val dd = 0.5 * (1 - rad / dmax)

      val p = points[i_max] // calculate new radius to just include this point
      cx += (dd * (p.x - cx) + 0.5).toInt() // shift center toward point
      cy += (dd * (p.y - cy) + 0.5).toInt()

      // get new factors at shifted centerpoint
      lonlat2m = CheapRuler.getLonLatToMeterScales(cy)
      dlon2m = lonlat2m[0]
      dlat2m = lonlat2m[1]

      val x1 = (cx - p.x) * dlon2m
      val y1 = (cy - p.y) * dlat2m
      rad = sqrt(x1 * x1 + y1 * y1)
      dmax = rad
      i_max = -1
    } while (true)

    ilon = cx
    ilat = cy
    radius = rad * 1.001 + 1.0 // ensure the outside-of-enclosing-circle test in RoutingContext.calcDistance() is not passed by segments ending very close to the radius due to limited numerical precision
  }

  /**
   * tests whether a segment defined by lon and lat of two points does either
   * intersect the polygon or any of the endpoints (or both) are enclosed by
   * the polygon. For this test the winding-number algorithm is
   * being used. That means a point being within an overlapping region of the
   * polygon is also taken as being 'inside' the polygon.
   *
   * @param lon0 longitude of start point
   * @param lat0 latitude of start point
   * @param lon1 longitude of end point
   * @param lat1 latitude of start point
   * @return true if segment or any of it's points are 'inside' of polygon
   */
  fun intersects(lon0: Int, lat0: Int, lon1: Int, lat1: Int): Boolean {
    val p0 = Point(lon0, lat0)
    val p1 = Point(lon1, lat1)
    val i_last = points.size - 1
    var p2 = points[if (isClosed) i_last else 0]
    for (i in (if (isClosed) 0 else 1)..i_last) {
      val p3 = points[i]
      // does it intersect with at least one of the polygon's segments?
      if (intersect2D_2Segments(p0, p1, p2, p3) > 0) {
        return true
      }
      p2 = p3
    }
    return false
  }

  fun isOnPolyline(px: Long, py: Long): Boolean {
    val i_last = points.size - 1
    var p1 = points[0]
    for (i in 1..i_last) {
      val p2 = points[i]
      if (isOnLine(px, py, p1.x.toLong(), p1.y.toLong(), p2.x.toLong(), p2.y.toLong())) {
        return true
      }
      p1 = p2
    }
    return false
  }

/* Copyright 2001 softSurfer, 2012 Dan Sunday, 2018 Norbert Truchsess
   This code may be freely used and modified for any purpose providing that
   this copyright notice is included with it. SoftSurfer makes no warranty for
   this code, and cannot be held liable for any real or imagined damage
   resulting from its use. Users of this code must verify correctness for
   their application. */

  /**
   * winding number test for a point in a polygon
   *
   * @param px longitude of the point to check
   * @param py latitude of the point to check
   * @return a boolean whether the point is within the polygon or not.
   */
  fun isWithin(px: Long, py: Long): Boolean {
    var wn = 0 // the winding number counter

    // loop through all edges of the polygon
    val i_last = points.size - 1
    val p0 = points[if (isClosed) i_last else 0]
    var p0x = p0.x.toLong() // need to use long to avoid overflow in products
    var p0y = p0.y.toLong()

    for (i in (if (isClosed) 0 else 1)..i_last) { // edge from v[i] to v[i+1]
      val p1 = points[i]

      val p1x = p1.x.toLong()
      val p1y = p1.y.toLong()

      if (isOnLine(px, py, p0x, p0y, p1x, p1y)) {
        return true
      }

      if (p0y <= py)  // start y <= p.y
      {
        if (p1y > py) { // an upward crossing, p left of edge
          if (((p1x - p0x) * (py - p0y) - (px - p0x) * (p1y - p0y)) > 0) {
            ++wn     // have a valid up intersect
          }
        }
      } else { // start y > p.y (no test needed)
        if (p1y <= py) { // a downward crossing, p right of edge
          if (((p1x - p0x) * (py - p0y) - (px - p0x) * (p1y - p0y)) < 0) {
            --wn      // have a valid down intersect
          }
        }
      }
      p0x = p1x
      p0y = p1y
    }
    return wn != 0
  }

  /**
   * Compute the length of the segment within the polygon.
   *
   * @param lon1 Integer longitude of the first point of the segment.
   * @param lat1 Integer latitude of the first point of the segment.
   * @param lon2 Integer longitude of the last point of the segment.
   * @param lat2 Integer latitude of the last point of the segment.
   * @return The length, in meters, of the portion of the segment which is
   * included in the polygon.
   */
  fun distanceWithinPolygon(lon1: Int, lat1: Int, lon2: Int, lat2: Int): Double {
    var distance = 0.0

    // Extremities of the segments
    val p1 = Point(lon1, lat1)
    val p2 = Point(lon2, lat2)

    var previousIntersectionOnSegment: Point? = null
    if (isWithin(lon1.toLong(), lat1.toLong())) {
      // Start point of the segment is within the polygon, this is the first
      // "intersection".
      previousIntersectionOnSegment = p1
    }

    // Loop over edges of the polygon to find intersections
    val i_last = points.size - 1
    var i = if (isClosed) 0 else 1
    var j = if (isClosed) i_last else 0
    while (i <= i_last) {
      val edgePoint1 = points[j]
      val edgePoint2 = points[i]
      val intersectsEdge = intersect2D_2Segments(p1, p2, edgePoint1, edgePoint2)

      if (isClosed && intersectsEdge == 1) {
        // Intersects with a (closed) polygon edge on a single point
        // Distance is zero when crossing a polyline.
        // Let's find this intersection point
        val xdiffSegment = lon1 - lon2
        val xdiffEdge = edgePoint1.x - edgePoint2.x
        val ydiffSegment = lat1 - lat2
        val ydiffEdge = edgePoint1.y - edgePoint2.y
        val div = xdiffSegment * ydiffEdge - xdiffEdge * ydiffSegment
        val dSegment = lon1.toLong() * lat2.toLong() - lon2.toLong() * lat1.toLong()
        val dEdge = edgePoint1.x.toLong() * edgePoint2.y.toLong() - edgePoint2.x.toLong() * edgePoint1.y.toLong()
        // Coordinates of the intersection
        val intersection = Point(
          ((dSegment * xdiffEdge - dEdge * xdiffSegment) / div).toInt(),
          ((dSegment * ydiffEdge - dEdge * ydiffSegment) / div).toInt()
        )
        if (
          previousIntersectionOnSegment != null
          && isWithin(
            ((intersection.x + previousIntersectionOnSegment.x) shr 1).toLong(),
            ((intersection.y + previousIntersectionOnSegment.y) shr 1).toLong()
          )
        ) {
          // There was a previous match within the polygon and this part of the
          // segment is within the polygon.
          distance += CheapRuler.distance(
            previousIntersectionOnSegment.x, previousIntersectionOnSegment.y,
            intersection.x, intersection.y
          )
        }
        previousIntersectionOnSegment = intersection
      } else if (intersectsEdge == 2) {
        // Segment and edge overlaps
        // FIXME: Could probably be done in a smarter way
        distance += min(
          CheapRuler.distance(p1.x, p1.y, p2.x, p2.y),
          min(
            CheapRuler.distance(edgePoint1.x, edgePoint1.y, edgePoint2.x, edgePoint2.y),
            min(
              CheapRuler.distance(p1.x, p1.y, edgePoint2.x, edgePoint2.y),
              CheapRuler.distance(edgePoint1.x, edgePoint1.y, p2.x, p2.y)
            )
          )
        )
        // FIXME: We could store intersection.
        previousIntersectionOnSegment = null
      }
      j = i++
    }

    if (
      previousIntersectionOnSegment != null
      && isWithin(lon2.toLong(), lat2.toLong())
    ) {
      // Last point is within the polygon, add the remaining missing distance.
      distance += CheapRuler.distance(
        previousIntersectionOnSegment.x, previousIntersectionOnSegment.y,
        lon2, lat2
      )
    }
    return distance
  }

  companion object {
    fun isOnLine(px: Long, py: Long, p0x: Long, p0y: Long, p1x: Long, p1y: Long): Boolean {
      val v10x = (px - p0x).toDouble()
      val v10y = (py - p0y).toDouble()
      val v12x = (p1x - p0x).toDouble()
      val v12y = (p1y - p0y).toDouble()

      if (v10x == 0.0) { // P0->P1 vertical?
        if (v10y == 0.0) { // P0 == P1?
          return true
        }
        if (v12x != 0.0) { // P1->P2 not vertical?
          return false
        }
        return (v12y / v10y) >= 1 // P1->P2 at least as long as P1->P0?
      }
      if (v10y == 0.0) { // P0->P1 horizontal?
        if (v12y != 0.0) { // P1->P2 not horizontal?
          return false
        }
        // if ( P10x == 0 ) // P0 == P1? already tested
        return (v12x / v10x) >= 1 // P1->P2 at least as long as P1->P0?
      }
      val kx = v12x / v10x
      if (kx < 1) {
        return false
      }
      return kx == v12y / v10y
    }

/* Copyright 2001 softSurfer, 2012 Dan Sunday, 2018 Norbert Truchsess
   This code may be freely used and modified for any purpose providing that
   this copyright notice is included with it. SoftSurfer makes no warranty for
   this code, and cannot be held liable for any real or imagined damage
   resulting from its use. Users of this code must verify correctness for
   their application. */

    /**
     * inSegment(): determine if a point is inside a segment
     *
     * @param p      a point
     * @param seg_p0 starting point of segment
     * @param seg_p1 ending point of segment
     * @return 1 = P is inside S
     * 0 = P is not inside S
     */
    private fun inSegment(p: Point, seg_p0: Point, seg_p1: Point): Boolean {
      val sp0x = seg_p0.x
      val sp1x = seg_p1.x

      if (sp0x != sp1x) { // S is not vertical
        val px = p.x
        if (sp0x <= px && px <= sp1x) {
          return true
        }
        if (sp0x >= px && px >= sp1x) {
          return true
        }
      } else // S is vertical, so test y coordinate
      {
        val sp0y = seg_p0.y
        val sp1y = seg_p1.y
        val py = p.y

        if (sp0y <= py && py <= sp1y) {
          return true
        }
        if (sp0y >= py && py >= sp1y) {
          return true
        }
      }
      return false
    }

/* Copyright 2001 softSurfer, 2012 Dan Sunday, 2018 Norbert Truchsess
   This code may be freely used and modified for any purpose providing that
   this copyright notice is included with it. SoftSurfer makes no warranty for
   this code, and cannot be held liable for any real or imagined damage
   resulting from its use. Users of this code must verify correctness for
   their application. */

    /**
     * intersect2D_2Segments(): find the 2D intersection of 2 finite segments
     *
     * @param s1p0 start point of segment 1
     * @param s1p1 end point of segment 1
     * @param s2p0 start point of segment 2
     * @param s2p1 end point of segment 2
     * @return 0=disjoint (no intersect)
     * 1=intersect in unique point I0
     * 2=overlap in segment from I0 to I1
     */
    private fun intersect2D_2Segments(s1p0: Point, s1p1: Point, s2p0: Point, s2p1: Point): Int {
      val ux = (s1p1.x - s1p0.x).toLong() // vector u = S1P1-S1P0 (segment 1)
      val uy = (s1p1.y - s1p0.y).toLong()
      val vx = (s2p1.x - s2p0.x).toLong() // vector v = S2P1-S2P0 (segment 2)
      val vy = (s2p1.y - s2p0.y).toLong()
      val wx = (s1p0.x - s2p0.x).toLong() // vector w = S1P0-S2P0 (from start of segment 2 to start of segment 1
      val wy = (s1p0.y - s2p0.y).toLong()

      val d = (ux * vy - uy * vx).toDouble()

      // test if  they are parallel (includes either being a point)
      if (d == 0.0)           // S1 and S2 are parallel
      {
        if ((ux * wy - uy * wx) != 0L || (vx * wy - vy * wx) != 0L) {
          return 0 // they are NOT collinear
        }

        // they are collinear or degenerate
        // check if they are degenerate  points
        val du = ((ux == 0L) && (uy == 0L))
        val dv = ((vx == 0L) && (vy == 0L))
        if (du && dv)            // both segments are points
        {
          return if (wx == 0L && wy == 0L) 0 else 1 // return 0 if they are distinct points
        }
        if (du)                     // S1 is a single point
        {
          return if (inSegment(s1p0, s2p0, s2p1)) 1 else 0 // is it part of S2?
        }
        if (dv)                     // S2 a single point
        {
          return if (inSegment(s2p0, s1p0, s1p1)) 1 else 0  // is it part of S1?
        }
        // they are collinear segments - get  overlap (or not)
        var t0: Double
        var t1: Double                    // endpoints of S1 in eqn for S2
        val w2x = s1p1.x - s2p0.x // vector w2 = S1P1-S2P0 (from start of segment 2 to end of segment 1)
        val w2y = s1p1.y - s2p0.y
        if (vx != 0L) {
          t0 = (wx / vx).toDouble()
          t1 = (w2x / vx).toDouble()
        } else {
          t0 = (wy / vy).toDouble()
          t1 = (w2y / vy).toDouble()
        }
        if (t0 > t1)                   // must have t0 smaller than t1
        {
          val t = t0     // swap if not
          t0 = t1
          t1 = t
        }
        if (t0 > 1 || t1 < 0) {
          return 0      // NO overlap
        }
        t0 = if (t0 < 0) 0.0 else t0               // clip to min 0
        t1 = if (t1 > 1) 1.0 else t1               // clip to max 1

        return if (t0 == t1) 1 else 2        // return 1 if intersect is a point
      }

      // the segments are skew and may intersect in a point
      // get the intersect parameter for S1

      val sI = (vx * wy - vy * wx) / d
      if (sI < 0 || sI > 1)               // no intersect with S1
      {
        return 0
      }

      // get the intersect parameter for S2
      val tI = (ux * wy - uy * wx) / d
      return if (tI < 0 || tI > 1) 0 else 1 // return 0 if no intersect with S2
    }
  }
}
