/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

internal class StdPath : OsmPath() {
  /**
   * The elevation-hysteresis-buffer (0-10 m)
   */
  private var ehbd: Int = 0 // in micrometer
  private var ehbu: Int = 0 // in micrometer

  private var totalTime: Float = 0f  // travel time (seconds)
  private var totalEnergy: Float = 0f // total route energy (Joule)
  private var elevation_buffer: Float = 0f // just another elevation buffer (for travel time)

  private var uphillcostdiv: Int = 0
  private var downhillcostdiv: Int = 0

  override fun init(orig: OsmPath) {
    val origin = orig as StdPath
    this.ehbd = origin.ehbd
    this.ehbu = origin.ehbu
    this.totalTime = origin.totalTime
    this.totalEnergy = origin.totalEnergy
    this.elevation_buffer = origin.elevation_buffer
  }

  override fun resetState() {
    ehbd = 0
    ehbu = 0
    totalTime = 0f
    totalEnergy = 0f
    uphillcostdiv = 0
    downhillcostdiv = 0
    elevation_buffer = 0f
  }

  override fun processWaySection(rc: RoutingContext, distance: Double, delta_h: Double, elevation: Double, angle: Double, cosangle: Double, isStartpoint: Boolean, nsection: Int, lastpriorityclassifier: Int): Double {
    val expctxWay = rc.expctxWay!!
    // calculate the costfactor inputs
    val turncostbase = expctxWay.getTurncost()
    val uphillcutoff = expctxWay.getUphillcutoff() * 10000
    val downhillcutoff = expctxWay.getDownhillcutoff() * 10000
    val uphillmaxslope = expctxWay.getUphillmaxslope() * 10000
    val downhillmaxslope = expctxWay.getDownhillmaxslope() * 10000
    var cfup = expctxWay.getUphillCostfactor()
    var cfdown = expctxWay.getDownhillCostfactor()
    val cf = expctxWay.getCostfactor()
    cfup = if (cfup == 0f) cf else cfup
    cfdown = if (cfdown == 0f) cf else cfdown

    downhillcostdiv = expctxWay.getDownhillcost().toInt()
    if (downhillcostdiv > 0) {
      downhillcostdiv = 1000000 / downhillcostdiv
    }

    var downhillmaxslopecostdiv = expctxWay.getDownhillmaxslopecost().toInt()
    if (downhillmaxslopecostdiv > 0) {
      downhillmaxslopecostdiv = 1000000 / downhillmaxslopecostdiv
    } else {
      // if not given, use legacy behavior
      downhillmaxslopecostdiv = downhillcostdiv
    }

    uphillcostdiv = expctxWay.getUphillcost().toInt()
    if (uphillcostdiv > 0) {
      uphillcostdiv = 1000000 / uphillcostdiv
    }

    var uphillmaxslopecostdiv = expctxWay.getUphillmaxslopecost().toInt()
    if (uphillmaxslopecostdiv > 0) {
      uphillmaxslopecostdiv = 1000000 / uphillmaxslopecostdiv
    } else {
      // if not given, use legacy behavior
      uphillmaxslopecostdiv = uphillcostdiv
    }

    val dist = distance.toInt() // legacy arithmetics needs int

    // penalty for turning angle
    var turncost = ((1.0 - cosangle) * turncostbase + 0.2).toInt() // e.g. turncost=90 -> 90 degree = 90m penalty

    val newPrio = expctxWay.getPriorityClassifier().toInt()
    val oldPrio = lastpriorityclassifier

    if (rc.bikeMode) {
      //   If the turn is LEFT and coming from "primary|secondary" to a lower priority highway
      //   AND estimated_crossing_class is defined on the node, than penalty!!!

      if (rc.consider_crossing && oldPrio > 0 && nsection == 0 && angle < 0 && oldPrio >= rc.crossing_Prio_H && newPrio <= rc.crossing_Prio_L) {
        var class_index = 0
        val sourceNode = this.sourceNode!!
        if (sourceNode.nodeDescription != null) {
          val nodeAccessGranted = expctxWay.getNodeAccessGranted().toDouble() != 0.0
          val node_tags = rc.expctxNode!!.getKeyValueDescription(nodeAccessGranted, sourceNode.nodeDescription!!)!!
          class_index = node_tags.indexOf("estimated_crossing_class=")
          if (class_index > -1) {
            val crossing_class = node_tags.substring(class_index + 25, class_index + 26)
            var additional_turn_cost = 0
            if (crossing_class == "1") { additional_turn_cost = rc.cost_ToLeft_from_H_class1 }
            if (crossing_class == "2") { additional_turn_cost = rc.cost_ToLeft_from_H_class2 }
            if (crossing_class == "3") { additional_turn_cost = rc.cost_ToLeft_from_H_class3 }
            if (crossing_class == "4") { additional_turn_cost = rc.cost_ToLeft_from_H_class4 }
            if (crossing_class == "5") { additional_turn_cost = rc.cost_ToLeft_from_H_class5 }
            if (crossing_class == "6") { additional_turn_cost = rc.cost_ToLeft_from_H_class6 }
            turncost += additional_turn_cost
          }
        }
      }

      // for left-hand traffic
      // If the turn is RIGHT and coming from "primary|secondary" to a lower priority HW AND estimated_crossing_class is defined on the node, than penalty!!!

      if (rc.consider_crossing && oldPrio > 0 && nsection == 0 && angle > 0 && oldPrio >= rc.crossing_Prio_H && newPrio <= rc.crossing_Prio_L) {
        var class_index = 0
        val sourceNode = this.sourceNode!!
        if (sourceNode.nodeDescription != null) {
          val nodeAccessGranted = expctxWay.getNodeAccessGranted().toDouble() != 0.0
          val node_tags = rc.expctxNode!!.getKeyValueDescription(nodeAccessGranted, sourceNode.nodeDescription!!)!!
          class_index = node_tags.indexOf("estimated_crossing_class=")
          if (class_index > -1) {
            val crossing_class = node_tags.substring(class_index + 25, class_index + 26)
            var additional_turn_cost = 0
            if (crossing_class == "1") { additional_turn_cost = rc.cost_ToRight_from_H_class1 }
            if (crossing_class == "2") { additional_turn_cost = rc.cost_ToRight_from_H_class2 }
            if (crossing_class == "3") { additional_turn_cost = rc.cost_ToRight_from_H_class3 }
            if (crossing_class == "4") { additional_turn_cost = rc.cost_ToRight_from_H_class4 }
            if (crossing_class == "5") { additional_turn_cost = rc.cost_ToRight_from_H_class5 }
            if (crossing_class == "6") { additional_turn_cost = rc.cost_ToRight_from_H_class6 }
            turncost += additional_turn_cost
          }
        }
      }
    }

    val message = this.message
    if (message != null) {
      message.linkturncost += turncost
      message.turnangle = angle.toFloat()
    }

    var sectionCost = turncost.toDouble()

    // *** penalty for elevation
    // only the part of the descend that does not fit into the elevation-hysteresis-buffers
    // leads to an immediate penalty

    val delta_h_micros = (1000000.0 * delta_h).toInt()
    ehbd = (ehbd + (-delta_h_micros - dist * downhillcutoff)).toInt()
    ehbu = (ehbu + (delta_h_micros - dist * uphillcutoff)).toInt()

    var downweight = 0f
    if (ehbd > rc.elevationpenaltybuffer) {
      downweight = 1f

      var excess = ehbd - rc.elevationpenaltybuffer
      var reduce = dist * rc.elevationbufferreduce
      if (reduce > excess) {
        downweight = (excess.toFloat()) / reduce
        reduce = excess
      }
      excess = ehbd - rc.elevationmaxbuffer
      if (reduce < excess) {
        reduce = excess
      }
      ehbd -= reduce
      var elevationCost = 0f
      if (downhillcostdiv > 0) {
        elevationCost += min(reduce.toFloat(), dist * downhillmaxslope) / downhillcostdiv
      }
      if (downhillmaxslopecostdiv > 0) {
        elevationCost += max(0f, reduce - dist * downhillmaxslope) / downhillmaxslopecostdiv
      }
      if (elevationCost > 0f) {
        sectionCost += elevationCost
        if (message != null) {
          message.linkelevationcost = (message.linkelevationcost + elevationCost).toInt()
        }
      }
    } else if (ehbd < 0) {
      ehbd = 0
    }

    var upweight = 0f
    if (ehbu > rc.elevationpenaltybuffer) {
      upweight = 1f

      var excess = ehbu - rc.elevationpenaltybuffer
      var reduce = dist * rc.elevationbufferreduce
      if (reduce > excess) {
        upweight = (excess.toFloat()) / reduce
        reduce = excess
      }
      excess = ehbu - rc.elevationmaxbuffer
      if (reduce < excess) {
        reduce = excess
      }
      ehbu -= reduce
      var elevationCost = 0f
      if (uphillcostdiv > 0) {
        elevationCost += min(reduce.toFloat(), dist * uphillmaxslope) / uphillcostdiv
      }
      if (uphillmaxslopecostdiv > 0) {
        elevationCost += max(0f, reduce - dist * uphillmaxslope) / uphillmaxslopecostdiv
      }
      if (elevationCost > 0f) {
        sectionCost += elevationCost
        if (message != null) {
          message.linkelevationcost = (message.linkelevationcost + elevationCost).toInt()
        }
      }
    } else if (ehbu < 0) {
      ehbu = 0
    }

    // get the effective costfactor (slope dependent)
    val costfactor = cfup * upweight + cf * (1f - upweight - downweight) + cfdown * downweight

    if (message != null) {
      message.costfactor = costfactor
    }

    sectionCost += dist * costfactor + 0.5f

    return sectionCost
  }

  override fun processTargetNode(rc: RoutingContext): Double {
    // finally add node-costs for target node
    val targetNode = this.targetNode!!
    if (targetNode.nodeDescription != null) {
      val nodeAccessGranted = rc.expctxWay!!.getNodeAccessGranted().toDouble() != 0.0
      val expctxNode = rc.expctxNode!!
      expctxNode.evaluate(nodeAccessGranted, targetNode.nodeDescription!!)
      val initialcost = expctxNode.getInitialcost()
      if (initialcost.toDouble() >= 1000000.0) {
        return -1.0
      }
      val message = this.message
      if (message != null) {
        message.linknodecost += initialcost.toInt()
        message.nodeKeyValues = expctxNode.getKeyValueDescription(nodeAccessGranted, targetNode.nodeDescription!!)
      }
      return initialcost.toDouble()
    }
    return 0.0
  }

  override fun elevationCorrection(): Int {
    return ((if (downhillcostdiv > 0) ehbd / downhillcostdiv else 0)
      + (if (uphillcostdiv > 0) ehbu / uphillcostdiv else 0))
  }

  override fun definitlyWorseThan(path: OsmPath): Boolean {
    val p = path as StdPath

    var c = p.cost
    if (p.downhillcostdiv > 0) {
      val delta = p.ehbd / p.downhillcostdiv - (if (downhillcostdiv > 0) ehbd / downhillcostdiv else 0)
      if (delta > 0) c += delta
    }
    if (p.uphillcostdiv > 0) {
      val delta = p.ehbu / p.uphillcostdiv - (if (uphillcostdiv > 0) ehbu / uphillcostdiv else 0)
      if (delta > 0) c += delta
    }

    return cost > c
  }

  private fun calcIncline(dist: Double): Double {
    val min_delta = 3.0
    var shift = 0.0
    if (elevation_buffer.toDouble() > min_delta) {
      shift = -min_delta
    } else if (elevation_buffer.toDouble() < -min_delta) {
      shift = min_delta
    }
    val decayFactor = exp(-dist / 100.0)
    val new_elevation_buffer = ((elevation_buffer + shift) * decayFactor - shift).toFloat()
    val incline = (elevation_buffer - new_elevation_buffer) / dist
    elevation_buffer = new_elevation_buffer
    return incline
  }

  override fun computeKinematic(rc: RoutingContext, dist: Double, delta_h: Double, detailMode: Boolean) {
    if (!detailMode) {
      return
    }

    // compute incline
    elevation_buffer = (elevation_buffer + delta_h).toFloat()
    val incline = calcIncline(dist)

    var maxSpeed = rc.maxSpeed
    val speedLimit = (rc.expctxWay!!.getMaxspeed() / 3.6f).toDouble()
    if (speedLimit > 0) {
      maxSpeed = min(maxSpeed, speedLimit)
    }

    var speed = maxSpeed // Travel speed
    val f_roll = rc.totalMass * GRAVITY * (rc.defaultC_r + incline)
    if (rc.footMode) {
      // Use Tobler's hiking function for walking sections
      speed = rc.maxSpeed * exp(-3.5 * abs(incline + 0.05))
    } else if (rc.bikeMode) {
      speed = solveCubic(rc.S_C_x, f_roll, rc.bikerPower)
      speed = min(speed, maxSpeed)
    }
    val dt = (dist / speed).toFloat()
    totalTime += dt
    // Calc energy assuming biking (no good model yet for hiking)
    // (Count only positive, negative would mean breaking to enforce maxspeed)
    val energy = dist * (rc.S_C_x * speed * speed + f_roll)
    if (energy > 0.0) {
      totalEnergy = (totalEnergy + energy).toFloat()
    }
  }

  override fun getTotalTime(): Double {
    return totalTime.toDouble()
  }

  override fun getTotalEnergy(): Double {
    return totalEnergy.toDouble()
  }

  companion object {
    // Gravitational constant, g
    private const val GRAVITY = 9.81  // in meters per second^(-2)

    private fun solveCubic(a: Double, c: Double, d: Double): Double {
      // Solves a * v^3 + c * v = d with a Newton method
      // to get the speed v for the section.

      var v = 8.0
      var findingStartvalue = true
      for (i in 0 until 10) {
        val y = (a * v * v + c) * v - d
        if (y < 0.1) {
          if (findingStartvalue) {
            v *= 2.0
            continue
          }
          break
        }
        findingStartvalue = false
        val y_prime = 3 * a * v * v + c
        v -= y / y_prime
      }
      return v
    }
  }
}
