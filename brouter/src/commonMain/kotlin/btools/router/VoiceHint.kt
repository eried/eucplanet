/**
 * Container for a voice hint
 * (both input- and result data for voice hint processing)
 *
 * @author ab
 */
package btools.router

import kotlin.jvm.JvmField
import kotlin.math.abs

open class VoiceHint {
  @JvmField internal var ilon: Int = 0
  @JvmField internal var ilat: Int = 0
  @JvmField internal var selev: Short = 0
  @JvmField var cmd: Int = 0
  @JvmField internal var oldWay: MessageData? = null
  @JvmField internal var goodWay: MessageData? = null
  @JvmField internal var badWays: MutableList<MessageData>? = null
  @JvmField var distanceToNext: Double = 0.0
  @JvmField var indexInTrack: Int = 0

  fun getTime(): Float {
    val ow = oldWay
    return if (ow == null) 0f else ow.time
  }

  @JvmField var angle: Float = Float.MAX_VALUE
  @JvmField internal var lowerBadWayAngle: Float = -181f
  @JvmField internal var higherBadWayAngle: Float = 181f

  @JvmField internal var turnAngleConsumed: Boolean = false
  @JvmField internal var needsRealTurn: Boolean = false
  @JvmField internal var maxBadPrio: Int = -1

  @JvmField var roundaboutExit: Int = 0

  // EUC Planet: read access for the app, which lives in another package.
  fun getCommand(): Int {
    return cmd
  }

  fun getIndexInTrack(): Int {
    return indexInTrack
  }

  fun getDistanceToNext(): Double {
    return distanceToNext
  }

  fun getAngle(): Float {
    return angle
  }

  internal fun isRoundabout(): Boolean {
    return roundaboutExit != 0
  }

  internal fun addBadWay(badWay: MessageData?) {
    if (badWay == null) {
      return
    }
    if (badWays == null) {
      badWays = ArrayList()
    }
    badWays!!.add(badWay)
  }

  fun getExitNumber(): Int {
    return roundaboutExit
  }

  fun calcCommand() {
    val bws = badWays
    if (bws != null) {
      for (badWay in bws) {
        if (badWay.isBadOneway()) {
          continue
        }
        if (lowerBadWayAngle < badWay.turnangle && badWay.turnangle < goodWay!!.turnangle) {
          lowerBadWayAngle = badWay.turnangle
        }
        if (higherBadWayAngle > badWay.turnangle && badWay.turnangle > goodWay!!.turnangle) {
          higherBadWayAngle = badWay.turnangle
        }
      }
    }

    var cmdAngle = angle

    // fall back to local angle if otherwise inconsistent
    //if ( lowerBadWayAngle > angle || higherBadWayAngle < angle )
    //{
    //cmdAngle = goodWay.turnangle;
    //}
    if (angle == Float.MAX_VALUE) {
      cmdAngle = goodWay!!.turnangle
    }
    if (cmd == BL) return

    if (roundaboutExit > 0) {
      cmd = RNDB
    } else if (roundaboutExit < 0) {
      cmd = RNLB
    } else if (is180DegAngle(cmdAngle) && cmdAngle <= -179f && higherBadWayAngle == 181f && lowerBadWayAngle == -181f) {
      cmd = TU
    } else if (cmdAngle < -159f) {
      cmd = TLU
    } else if (cmdAngle < -135f) {
      cmd = TSHL
    } else if (cmdAngle < -45f) {
      // a TL can be pushed in either direction by a close-by alternative
      if (cmdAngle < -95f && higherBadWayAngle < -30f && lowerBadWayAngle < -180f) {
        cmd = TSHL
      } else if (cmdAngle > -85f && lowerBadWayAngle > -180f && higherBadWayAngle > -10f) {
        cmd = TSLL
      } else {
        if (cmdAngle < -110f) {
          cmd = TSHL
        } else if (cmdAngle > -60f) {
          cmd = TSLL
        } else {
          cmd = TL
        }
      }
    } else if (cmdAngle < -21f) {
      if (cmd != KR) { // don't overwrite KR with TSLL
        cmd = TSLL
      }
    } else if (cmdAngle < -5f) {
      if (lowerBadWayAngle < -100f && higherBadWayAngle < 45f) {
        cmd = TSLL
      } else if (lowerBadWayAngle >= -100f && higherBadWayAngle < 45f) {
        cmd = KL
      } else {
        if (lowerBadWayAngle > -35f && higherBadWayAngle > 55f) {
          cmd = KR
        } else {
          cmd = C
        }
      }
    } else if (cmdAngle < 5f) {
      if (lowerBadWayAngle > -30f) {
        cmd = KR
      } else if (higherBadWayAngle < 30f) {
        cmd = KL
      } else {
        cmd = C
      }
    } else if (cmdAngle < 21f) {
      // a TR can be pushed in either direction by a close-by alternative
      if (lowerBadWayAngle > -45f && higherBadWayAngle > 100f) {
        cmd = TSLR
      } else if (lowerBadWayAngle > -45f && higherBadWayAngle <= 100f) {
        cmd = KR
      } else {
        if (lowerBadWayAngle < -55f && higherBadWayAngle < 35f) {
          cmd = KL
        } else {
          cmd = C
        }
      }
    } else if (cmdAngle < 45f) {
      cmd = TSLR
    } else if (cmdAngle < 135f) {
      if (cmdAngle < 85f && higherBadWayAngle < 180f && lowerBadWayAngle < 10f) {
        cmd = TSLR
      } else if (cmdAngle > 95f && lowerBadWayAngle > 30f && higherBadWayAngle > 180f) {
        cmd = TSHR
      } else {
        if (cmdAngle.toDouble() > 110.0) {
          cmd = TSHR
        } else if (cmdAngle.toDouble() < 60.0) {
          cmd = TSLR
        } else {
          cmd = TR
        }
      }
    } else if (cmdAngle < 159f) {
      cmd = TSHR
    } else if (is180DegAngle(cmdAngle) && cmdAngle >= 179f && higherBadWayAngle == 181f && lowerBadWayAngle == -181f) {
      cmd = TU
    } else {
      cmd = TRU
    }
  }

  fun hasGiveWay(): Boolean {
    val ow = oldWay
    if (ow != null && ow.nodeKeyValues != null) {
      val nkv = ow.nodeKeyValues!!
      if (ow.wayKeyValues!!.contains("reversedirection=yes")) {
        return (nkv.contains("highway=give_way") || nkv.contains("highway=stop")) && nkv.contains("direction=backward")
      } else {
        return (nkv.contains("highway=give_way") || nkv.contains("highway=stop")) && !nkv.contains("direction=backward")
      }
    }
    return false

  }

  companion object {
    const val C = 1 // continue (go straight)
    const val TL = 2 // turn left
    const val TSLL = 3 // turn slightly left
    const val TSHL = 4 // turn sharply left
    const val TR = 5 // turn right
    const val TSLR = 6 // turn slightly right
    const val TSHR = 7 // turn sharply right
    const val KL = 8 // keep left
    const val KR = 9 // keep right
    const val TLU = 10 // U-turn
    const val TRU = 11 // Right U-turn
    const val OFFR = 12 // Off route
    const val RNDB = 13 // Roundabout
    const val RNLB = 14 // Roundabout left
    const val TU = 15 // 180 degree u-turn
    const val BL = 16 // Beeline routing
    const val EL = 17 // exit left
    const val ER = 18 // exit right

    const val END = 100 // end point

    internal fun is180DegAngle(angle: Float): Boolean {
      return (abs(angle) <= 180f && abs(angle) >= 179f)
    }
  }
}
