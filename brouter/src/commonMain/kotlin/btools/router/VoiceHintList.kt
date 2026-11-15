/**
 * Container for a voice hint
 * (both input- and result data for voice hint processing)
 *
 * @author ab
 */
package btools.router

import kotlin.jvm.JvmField

open class VoiceHintList {

  private var transportMode: Int = TRANS_MODE_BIKE
  @JvmField internal var turnInstructionMode: Int = 0
  @JvmField var list: MutableList<VoiceHint> = ArrayList()

  // EUC Planet: read access for the app.
  fun getList(): MutableList<VoiceHint> {
    return list
  }

  fun setTransportMode(isCar: Boolean, isBike: Boolean) {
    transportMode = if (isCar) TRANS_MODE_CAR else (if (isBike) TRANS_MODE_BIKE else TRANS_MODE_FOOT)
  }

  fun setTransportMode(mode: Int) {
    transportMode = mode
  }

  fun getTransportMode(): String {
    val ret: String
    when (transportMode) {
      TRANS_MODE_FOOT -> ret = "foot"
      TRANS_MODE_CAR -> ret = "car"
      TRANS_MODE_BIKE -> ret = "bike"
      else -> ret = "bike"
    }
    return ret
  }

  fun transportMode(): Int {
    return transportMode
  }

  fun getLocusRouteType(): Int {
    if (transportMode == TRANS_MODE_CAR) {
      return 0
    }
    if (transportMode == TRANS_MODE_BIKE) {
      return 5
    }
    return 3 // foot
  }

  companion object {
    internal const val TRANS_MODE_NONE = 0
    internal const val TRANS_MODE_FOOT = 1
    internal const val TRANS_MODE_BIKE = 2
    internal const val TRANS_MODE_CAR = 3
  }
}
