package com.eried.eucplanet.ui.navigator

import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.util.GpxData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the route builder's view model asks of the phone: its fixes, the
 * battery, the downloaded routing squares, the GPX files the rider picks
 * and the redirects of a shortened map link. Android has TripRepository,
 * BatteryManager, the offline tile store, the document provider with the
 * XmlPullParser GPX reader and HttpURLConnection behind it
 * (AndroidRouteBuilderPorts).
 */
interface RouteBuilderPorts {
    /** The live fix, null while there is none (or it went stale). */
    val currentLocation: StateFlow<PhoneLocation?>

    /** The last position at any age, for framing the map without a live fix. */
    val lastKnownLocation: StateFlow<PhoneLocation?>

    /** Asks for fixes now (idempotent). */
    fun startLocationUpdates()

    /** The phone's battery, 0 to 100, or -1 when it cannot say. */
    fun phoneBatteryPercent(): Int

    /** The routing squares being downloaded right now. */
    val downloadingTiles: Flow<Set<String>>

    /** True when the routing square [name] is on the phone. */
    fun hasTile(name: String): Boolean

    /** The squares the tile server has, or null when that is not known yet. */
    fun servedTiles(): Set<String>?

    /**
     * The GPX file the rider picked ([file] is the picker's handle), parsed,
     * or null when it cannot be opened. Blocking; throws on a broken file.
     */
    fun readGpx(file: String): GpxData?

    /** Writes [route] as GPX to the file the rider created, keeping its .gpx name. Blocking. */
    fun writeGpx(file: String, route: NavRoute)

    /**
     * Follows the redirects of a shortened map link (up to five hops) and
     * returns where it lands, or null on any network error. Blocking.
     */
    fun resolveShortLink(url: String): String?
}
