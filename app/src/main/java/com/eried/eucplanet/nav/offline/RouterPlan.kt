package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.OfflineRoutingMode
import com.eried.eucplanet.data.model.TravelMode

enum class Backend { OFFLINE, ONLINE }

/** Which router a leg tries, in order. */
object RouterPlan {

    /** A leg can route offline when its mode has a profile, its tiles are
     *  downloaded, and it is short enough for a phone. */
    fun offlineEligible(mode: TravelMode, covered: Boolean, legKm: Double): Boolean =
        BrouterMapping.profileFor(mode) != null && covered && legKm < RoutingTiles.MAX_LEG_KM

    /** The squares a leg needs downloaded: the ones its two ends are in.
     *  Not padded: near a coast the padding reached sea squares the server
     *  does not have, and the leg never routed offline. A road that bulges
     *  into a missing square makes BRouter fail, and the next router runs. */
    fun legTiles(a: GeoPoint, b: GeoPoint): Set<String> = RoutingTiles.tilesForLeg(a, b, padDeg = 0.0)

    /** Legs joined only when every one of them routed; otherwise the route
     *  failed, rather than turning into straight lines nobody asked for. */
    fun allRouted(parts: List<NavRoute?>): List<NavRoute>? =
        if (parts.isNotEmpty() && parts.all { it != null }) parts.filterNotNull() else null

    fun order(mode: OfflineRoutingMode, eligible: Boolean): List<Backend> = when {
        !eligible -> listOf(Backend.ONLINE)
        mode == OfflineRoutingMode.PREFER -> listOf(Backend.OFFLINE, Backend.ONLINE)
        else -> listOf(Backend.ONLINE, Backend.OFFLINE)
    }
}
