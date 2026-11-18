package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.GeoPoint

/**
 * Before a ride: the squares the route crosses that are not on the phone.
 * Without signal there the map goes blank and a reroute cannot be found, so
 * Start asks first (see the navigator's Start).
 */
object OfflineRideCheck {

    /**
     * Squares along [points] for which [has] is false, in the order the route
     * reaches them. [served] is what the server lists (open sea has no
     * square); null when that is not known, and then every square counts.
     */
    fun missing(points: List<GeoPoint>, has: (String) -> Boolean, served: Set<String>?): List<String> {
        if (points.size < 2) return emptyList()
        val seen = LinkedHashSet<String>()
        points.zipWithNext().forEach { (a, b) -> seen += RouterPlan.legTiles(a, b) }
        return seen.filter { !has(it) && (served == null || it in served) }
    }
}
