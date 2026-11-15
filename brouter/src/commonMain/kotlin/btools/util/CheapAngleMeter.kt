/**
 * Calculate the angle defined by 3 points
 * (and deliver it's cosine on the fly)
 */
package btools.util

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class CheapAngleMeter {
  private var cosangle = 0.0

  fun getCosAngle(): Double {
    return cosangle
  }

  fun calcAngle(lon0: Int, lat0: Int, lon1: Int, lat1: Int, lon2: Int, lat2: Int): Double {
    val lonlat2m = CheapRuler.getLonLatToMeterScales(lat1)
    val lon2m = lonlat2m[0]
    val lat2m = lonlat2m[1]
    val dx10 = (lon1 - lon0) * lon2m
    val dy10 = (lat1 - lat0) * lat2m
    val dx21 = (lon2 - lon1) * lon2m
    val dy21 = (lat2 - lat1) * lat2m

    val dd = sqrt((dx10 * dx10 + dy10 * dy10) * (dx21 * dx21 + dy21 * dy21))
    if (dd == 0.0) {
      cosangle = 1.0
      return 0.0
    }
    var sinp = (dy10 * dx21 - dx10 * dy21) / dd
    val cosp = (dy10 * dy21 + dx10 * dx21) / dd
    cosangle = cosp

    var offset = 0.0
    var s2 = sinp * sinp
    if (s2 > 0.5) {
      if (sinp > 0.0) {
        offset = 90.0
        sinp = -cosp
      } else {
        offset = -90.0
        sinp = cosp
      }
      s2 = cosp * cosp
    } else if (cosp < 0.0) {
      sinp = -sinp
      offset = if (sinp > 0.0) -180.0 else 180.0
    }
    return offset + sinp * (57.4539 + s2 * (9.57565 + s2 * (4.30904 + s2 * 2.56491)))
  }

  companion object {
    fun getAngle(lon1: Int, lat1: Int, lon2: Int, lat2: Int): Double {
      var res = 0.0
      val xdiff = (lat2 - lat1).toDouble()
      val ydiff = (lon2 - lon1).toDouble()
      res = toDegrees(atan2(ydiff, xdiff))
      return res
    }

    fun getDirection(lon1: Int, lat1: Int, lon2: Int, lat2: Int): Double {
      val res = getAngle(lon1, lat1, lon2, lat2)
      return normalize(res)
    }

    fun normalize(a: Double): Double {
      return if (a >= 360) a - (360 * (a / 360).toInt())
      else if (a < 0) a - (360 * ((a / 360).toInt() - 1)) else a
    }

    fun getDifferenceFromDirection(b1: Double, b2: Double): Double {
      var r = (b2 - b1) % 360.0
      if (r < -180.0) r += 360.0
      if (r >= 180.0) r -= 360.0
      return abs(r)
    }
  }
}
