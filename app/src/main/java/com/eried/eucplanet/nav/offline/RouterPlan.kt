package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.OfflineRoutingMode
import com.eried.eucplanet.data.model.TravelMode

enum class Backend { OFFLINE, ONLINE }

/** Which router a leg tries, in order. */
object RouterPlan {

    /** A leg can route offline when its mode has a profile, its tiles are
     *  downloaded, and it is short enough for a phone. */
    fun offlineEligible(mode: TravelMode, covered: Boolean, legKm: Double): Boolean =
        BrouterMapping.profileFor(mode) != null && covered && legKm < RoutingTiles.MAX_LEG_KM

    fun order(mode: OfflineRoutingMode, eligible: Boolean): List<Backend> = when {
        !eligible -> listOf(Backend.ONLINE)
        mode == OfflineRoutingMode.PREFER -> listOf(Backend.OFFLINE, Backend.ONLINE)
        else -> listOf(Backend.ONLINE, Backend.OFFLINE)
    }
}
