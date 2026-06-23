package com.eried.eucplanet.nav

import android.util.Log
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.Maneuver
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.TurnType
import com.eried.eucplanet.data.model.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** One geocoder search hit. */
data class GeoResult(val name: String, val lat: Double, val lng: Double)

/**
 * Route avoidance preferences. All false (the default) means "avoid nothing",
 * which is exactly the historic behaviour and keeps routing on the fast OSRM
 * backend. Any flag set switches the request to Valhalla (the only key-less
 * FOSSGIS backend that honours avoidances). Which flags actually bite depends
 * on the travel mode's Valhalla costing -- see RoutingService.valhallaCosting.
 */
data class RouteAvoidances(
    val highways: Boolean = false,
    val tolls: Boolean = false,
    val ferries: Boolean = false,
    val unpaved: Boolean = false,
) {
    /** True when nothing is being avoided -> stay on OSRM. */
    val none: Boolean get() = !highways && !tolls && !ferries && !unpaved

    /** True when at least one avoidance is requested -> route via Valhalla. */
    val any: Boolean get() = !none

    companion object {
        val NONE = RouteAvoidances()

        fun from(s: com.eried.eucplanet.data.model.AppSettings) = RouteAvoidances(
            highways = s.navAvoidHighways,
            tolls = s.navAvoidTolls,
            ferries = s.navAvoidFerries,
            unpaved = s.navAvoidUnpaved,
        )
    }
}

/**
 * The Navigator's only network code. Talks to free, key-less OpenStreetMap
 * services, Nominatim for address search / reverse lookup, and the FOSSGIS
 * OSRM service (routing.openstreetmap.de, the very router that powers
 * openstreetmap.org's own directions) for turn-by-turn routing, over plain
 * [HttpURLConnection] (no Retrofit/OkHttp dependency added).
 *
 * It is only ever called while the rider is actively building a route or while
 * the engine re-routes mid-navigation; nothing here runs in the background and
 * no telemetry is sent. Endpoints are caller-supplied so they stay configurable.
 */
@Singleton
class RoutingService @Inject constructor() {

    companion object {
        private const val TAG = "RoutingService"

        /** Default geocoder, overridable from Settings (`navGeocoderUrl`). */
        const val DEFAULT_GEOCODER = "https://nominatim.openstreetmap.org/search"

        /**
         * Default router base, overridable from Settings (`navRouterUrl`).
         * Three OSRM engines live under it (routed-bike / routed-foot /
         * routed-car), picked per [TravelMode]. This is the router that powers
         * openstreetmap.org's own directions: reliable and key-less.
         */
        const val DEFAULT_ROUTER = "https://routing.openstreetmap.de"

        /**
         * Avoidance-capable backend. The OSRM service above rejects every
         * `exclude=` flag, so when the rider opts into an avoidance the request
         * goes here instead: the key-less FOSSGIS Valhalla, same provider
         * family, which exposes per-costing avoidance weights.
         */
        const val DEFAULT_VALHALLA = "https://valhalla1.openstreetmap.de/route"

        // Nominatim's usage policy asks for an identifying User-Agent.
        private const val USER_AGENT = "EUCPlanet-Navigator/1.0 (github.com/eried/eucplanet)"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 20_000

        /** OSRM engine path segment for each travel mode. */
        private fun engineFor(mode: TravelMode): String = when (mode) {
            TravelMode.DRIVING -> "routed-car"
            TravelMode.WALKING -> "routed-foot"
            else -> "routed-bike" // CYCLING/EUC; STRAIGHT never reaches the router
        }

        /**
         * Resolves the configured router URL. A blank value, or a stale URL
         * left over from the retired Valhalla backend, falls back to
         * [DEFAULT_ROUTER] so existing installs migrate transparently.
         */
        fun effectiveRouterUrl(stored: String): String =
            if (stored.isBlank() || stored.contains("valhalla", ignoreCase = true))
                DEFAULT_ROUTER else stored

        /**
         * Extracts a sensible short label from a Nominatim display_name: the
         * first comma-separated part that is not just a house / road number, so
         * a pin reads "Karl Johans gate" rather than a meaningless "1". Returns
         * "" when the whole address is numeric, the caller then shows the
         * pin's role label on its own.
         */
        fun placeLabel(displayName: String): String {
            val parts = displayName.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return parts.firstOrNull { !isJustNumber(it) } ?: ""
        }

        private fun isJustNumber(s: String): Boolean {
            val core = s.trim('(', ')', '#', ' ', '-', '.')
            return core.isNotEmpty() && core.all { it.isDigit() }
        }

        /**
         * Builds a route that simply joins the waypoints with straight lines.
         * Used for [TravelMode.STRAIGHT] and as the fallback when a routing
         * request fails so the rider always gets *something* drawable.
         */
        fun straightLineRoute(name: String, waypoints: List<Waypoint>): NavRoute {
            val geometry = waypoints.map { it.point() }
            return NavRoute(
                name = name,
                waypoints = waypoints,
                travelMode = TravelMode.STRAIGHT,
                geometry = geometry,
                maneuvers = emptyList(),
                totalDistanceM = GeoMath.polylineLengthM(geometry)
            )
        }

        /** Valhalla costing string for a travel mode (STRAIGHT never routes). */
        fun valhallaCosting(mode: TravelMode): String = when (mode) {
            TravelMode.DRIVING -> "auto"
            TravelMode.WALKING -> "pedestrian"
            else -> "bicycle" // CYCLING / EUC
        }

        /**
         * Builds the Valhalla `costing_options` object for one costing from the
         * rider's avoidances. Valhalla expresses avoidances as 0..1 weights, so
         * "avoid X" is `use_x = 0` (and `avoid_bad_surfaces = 1` for unpaved).
         * Flags the costing has no knob for are simply omitted: a car costing
         * has no surface knob, a bike/pedestrian costing has no highway/toll
         * knob -- the toggle is still stored, it just doesn't bite in that mode.
         * Returns null when nothing applies (caller then omits costing_options).
         */
        fun valhallaCostingOptions(costing: String, avoid: RouteAvoidances): JSONObject? {
            val opts = JSONObject()
            if (avoid.ferries) opts.put("use_ferry", 0)
            when (costing) {
                "auto" -> {
                    if (avoid.highways) opts.put("use_highways", 0)
                    if (avoid.tolls) opts.put("use_tolls", 0)
                }
                "bicycle" -> {
                    if (avoid.unpaved) opts.put("avoid_bad_surfaces", 1)
                }
            }
            if (opts.length() == 0) return null
            return JSONObject().put(costing, opts)
        }

        /**
         * Maps a Valhalla maneuver `type` (integer enum) to our normalised
         * [TurnType]. Valhalla's enum is documented at
         * valhalla.github.io/valhalla/api/turn-by-turn/api-reference (maneuver
         * type table); the cases below cover the road/ramp/roundabout/ferry set
         * the bicycle / auto / pedestrian costings emit.
         */
        fun mapValhallaManeuver(type: Int): TurnType = when (type) {
            1, 2, 3 -> TurnType.DEPART                       // start / start right / start left
            4, 5, 6 -> TurnType.ARRIVE                       // destination(s)
            9 -> TurnType.SLIGHT_RIGHT
            10, 18, 20, 23, 37 -> TurnType.RIGHT             // right / ramp-right / exit-right / stay-right / merge-right
            11 -> TurnType.SHARP_RIGHT
            12, 13 -> TurnType.UTURN
            14 -> TurnType.SHARP_LEFT
            15, 19, 21, 24, 38 -> TurnType.LEFT              // left / ramp-left / exit-left / stay-left / merge-left
            16 -> TurnType.SLIGHT_LEFT
            26, 27 -> TurnType.ROUNDABOUT                    // roundabout enter / exit
            else -> TurnType.CONTINUE                        // continue / becomes / ramp-straight / stay-straight / merge / ferry / transit
        }
    }

    /**
     * Address / place search. Returns up to a handful of hits, best-match
     * first as Nominatim ranks them. Empty list on any failure, callers just
     * show "no results".
     */
    suspend fun geocode(
        query: String,
        endpoint: String = DEFAULT_GEOCODER,
        near: com.eried.eucplanet.data.model.GeoPoint? = null
    ): List<GeoResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            var url = "$endpoint?q=${enc(query)}&format=jsonv2&limit=6&addressdetails=0"
            if (near != null) {
                // Restrict results to roughly a 50 km box around the rider.
                val latD = 0.45
                val lngD = (0.45 / kotlin.math.cos(Math.toRadians(near.lat))
                    .coerceAtLeast(0.05))
                url += "&bounded=1&viewbox=" +
                    "${near.lng - lngD},${near.lat - latD}," +
                    "${near.lng + lngD},${near.lat + latD}"
            }
            val body = httpGet(url) ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lng = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                GeoResult(o.optString("display_name", query), lat, lng)
            }
        } catch (e: Exception) {
            Log.w(TAG, "geocode failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Reverse-geocodes a coordinate to a human-readable address. Used to fill
     * in a name for pins the rider dropped by tapping the map. Null on failure.
     */
    suspend fun reverseGeocode(
        lat: Double,
        lng: Double,
        searchEndpoint: String = DEFAULT_GEOCODER
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Derive the Nominatim /reverse endpoint from the configured /search one.
            val base = if (searchEndpoint.endsWith("/search"))
                searchEndpoint.removeSuffix("/search") + "/reverse"
            else "https://nominatim.openstreetmap.org/reverse"
            val url = "$base?lat=$lat&lon=$lng&format=jsonv2&zoom=18&addressdetails=0"
            val body = httpGet(url) ?: return@withContext null
            JSONObject(body).optString("display_name").ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "reverseGeocode failed: ${e.message}")
            null
        }
    }

    /**
     * Resolves an ordered list of waypoints into a navigable route with
     * geometry and turn-by-turn maneuvers via OSRM. Returns `null` if the
     * request fails, the caller falls back to [straightLineRoute].
     */
    suspend fun route(
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        endpoint: String = DEFAULT_ROUTER,
        avoid: RouteAvoidances = RouteAvoidances.NONE,
    ): NavRoute? = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) return@withContext null
        if (mode == TravelMode.STRAIGHT) return@withContext straightLineRoute(name, waypoints)
        // An avoidance was requested: the OSRM service can't honour it, so solve
        // on Valhalla instead. If Valhalla fails, fall through to the OSRM
        // attempt below so the rider still gets a route (without the avoidance)
        // rather than nothing.
        if (avoid.any) {
            routeViaValhalla(name, waypoints, mode, avoid)?.let { return@withContext it }
            Log.w(TAG, "Valhalla route failed; falling back to OSRM without avoidance")
        }
        try {
            val base = endpoint.trimEnd('/')
            // OSRM coordinates are lon,lat pairs, semicolon-separated, in the path.
            val coords = waypoints.joinToString(";") { "${it.lng},${it.lat}" }
            val url = "$base/${engineFor(mode)}/route/v1/driving/$coords" +
                "?overview=full&geometries=polyline6&steps=true"
            val body = httpGet(url) ?: return@withContext null
            parseOsrm(JSONObject(body), name, waypoints, mode)
        } catch (e: Exception) {
            Log.w(TAG, "route failed: ${e.message}")
            null
        }
    }

    // --- Valhalla (avoidance-capable) routing -------------------------------------

    /**
     * Solves [waypoints] on the FOSSGIS Valhalla backend with the rider's
     * [avoid] preferences applied as costing options. Returns the same
     * [NavRoute] shape as the OSRM path, or null on any failure (caller then
     * falls back to OSRM). Uses a GET with the request as a url-encoded `json`
     * param so it reuses [httpGet] -- the payload is tiny for <=25 stops.
     */
    private fun routeViaValhalla(
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        avoid: RouteAvoidances,
    ): NavRoute? {
        return try {
            val costing = valhallaCosting(mode)
            val req = JSONObject().apply {
                put("locations", JSONArray().also { arr ->
                    waypoints.forEach { w ->
                        arr.put(JSONObject().put("lat", w.lat).put("lon", w.lng))
                    }
                })
                put("costing", costing)
                valhallaCostingOptions(costing, avoid)?.let { put("costing_options", it) }
                put("directions_options", JSONObject().put("units", "kilometers"))
            }
            val url = "$DEFAULT_VALHALLA?json=${enc(req.toString())}"
            val body = httpGet(url) ?: return null
            parseValhalla(JSONObject(body), name, waypoints, mode)?.also {
                Log.i(TAG, "routed via Valhalla ($costing, avoid=$avoid): ${it.geometry.size} pts")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Valhalla route failed: ${e.message}")
            null
        }
    }

    private fun parseValhalla(
        json: JSONObject,
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode,
    ): NavRoute? {
        val trip = json.optJSONObject("trip") ?: return null
        if (trip.optInt("status", -1) != 0) return null
        val legs = trip.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null

        val geometry = ArrayList<GeoPoint>()
        val maneuvers = ArrayList<Maneuver>()
        var cumulativeM = 0.0
        val lastLeg = legs.length() - 1
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val legShape = decodePolyline6(leg.optString("shape"))
            // Concatenate legs into one polyline; each leg's first point repeats
            // the previous leg's last point, so drop it after the first leg.
            if (legShape.isNotEmpty()) {
                if (geometry.isEmpty()) geometry.addAll(legShape)
                else geometry.addAll(legShape.drop(1))
            }
            val steps = leg.optJSONArray("maneuvers") ?: continue
            val lastMan = steps.length() - 1
            for (si in 0 until steps.length()) {
                val step = steps.getJSONObject(si)
                val type = mapValhallaManeuver(step.optInt("type", 0))
                val isFirst = li == 0 && si == 0
                val isLast = li == lastLeg && si == lastMan
                val keep = when (type) {
                    TurnType.DEPART -> isFirst
                    TurnType.ARRIVE -> isLast
                    else -> true
                }
                val idx = step.optInt("begin_shape_index", -1)
                val pt = legShape.getOrNull(idx)
                if (keep && pt != null) {
                    val streets = step.optJSONArray("street_names")
                    val street = if (streets != null && streets.length() > 0)
                        streets.optString(0) else ""
                    maneuvers.add(
                        Maneuver(
                            point = pt,
                            type = type,
                            instruction = step.optString("instruction"),
                            streetName = street,
                            distanceFromStartM = cumulativeM
                        )
                    )
                }
                // Valhalla maneuver length is in km (we requested kilometers).
                cumulativeM += step.optDouble("length", 0.0) * 1000.0
            }
        }
        if (geometry.size < 2) return null
        val summaryKm = trip.optJSONObject("summary")?.optDouble("length", 0.0) ?: 0.0
        val totalM = if (summaryKm > 0.0) summaryKm * 1000.0
        else GeoMath.polylineLengthM(geometry)
        return NavRoute(name, waypoints, mode, geometry, maneuvers, totalM)
    }

    // --- OSRM response parsing ----------------------------------------------------

    private fun parseOsrm(
        json: JSONObject,
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode
    ): NavRoute? {
        if (json.optString("code") != "Ok") return null
        val routes = json.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val route = routes.getJSONObject(0)
        val geometry = decodePolyline6(route.optString("geometry"))
        if (geometry.size < 2) return null
        val dist = route.optDouble("distance", 0.0)
        val totalM = if (dist > 0.0) dist else GeoMath.polylineLengthM(geometry)

        val maneuvers = ArrayList<Maneuver>()
        var cumulativeM = 0.0
        val legs = route.optJSONArray("legs") ?: JSONArray()
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                val step = steps.getJSONObject(si)
                val m = step.optJSONObject("maneuver")
                val loc = m?.optJSONArray("location")
                if (m != null && loc != null && loc.length() >= 2) {
                    val type = mapOsrmManeuver(m.optString("type"), m.optString("modifier"))
                    val isFirst = li == 0 && si == 0
                    val isLast = li == legs.length() - 1 && si == steps.length() - 1
                    // Keep DEPART only at the very start and ARRIVE only at the
                    // very end, the per-leg ones at intermediate stops are noise.
                    val keep = when (type) {
                        TurnType.DEPART -> isFirst
                        TurnType.ARRIVE -> isLast
                        else -> true
                    }
                    if (keep) {
                        maneuvers.add(
                            Maneuver(
                                point = GeoPoint(loc.getDouble(1), loc.getDouble(0)),
                                type = type,
                                instruction = "",
                                streetName = step.optString("name"),
                                distanceFromStartM = cumulativeM
                            )
                        )
                    }
                }
                // Each step's distance is the length from its maneuver to the next.
                cumulativeM += step.optDouble("distance", 0.0)
            }
        }
        return NavRoute(name, waypoints, mode, geometry, maneuvers, totalM)
    }

    /** Maps an OSRM maneuver `type` + `modifier` to our normalised [TurnType]. */
    private fun mapOsrmManeuver(type: String, modifier: String): TurnType = when (type) {
        "depart" -> TurnType.DEPART
        "arrive" -> TurnType.ARRIVE
        "roundabout", "rotary", "roundabout turn",
        "exit roundabout", "exit rotary" -> TurnType.ROUNDABOUT
        else -> when (modifier) {
            "left" -> TurnType.LEFT
            "right" -> TurnType.RIGHT
            "slight left" -> TurnType.SLIGHT_LEFT
            "slight right" -> TurnType.SLIGHT_RIGHT
            "sharp left" -> TurnType.SHARP_LEFT
            "sharp right" -> TurnType.SHARP_RIGHT
            "uturn" -> TurnType.UTURN
            else -> TurnType.CONTINUE // "straight" or unspecified
        }
    }

    /** Decodes a Google-style encoded polyline at precision 6 (OSRM `polyline6`). */
    private fun decodePolyline6(encoded: String): List<GeoPoint> {
        if (encoded.isEmpty()) return emptyList()
        val factor = 1e6
        val out = ArrayList<GeoPoint>()
        val len = encoded.length
        var index = 0
        var lat = 0
        var lng = 0
        while (index < len) {
            var shift = 0
            var result = 0
            var b: Int
            // Each varint runs until a byte without the continuation bit. Guard
            // every read so a truncated string stops cleanly instead of running
            // off the end (StringIndexOutOfBounds) or accepting a partial pair.
            do {
                if (index >= len) return out
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0
            result = 0
            do {
                if (index >= len) return out
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(GeoPoint(lat / factor, lng / factor))
        }
        return out
    }

    // --- HTTP --------------------------------------------------------------------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                Log.w(TAG, "GET $url -> HTTP ${conn.responseCode}")
                // Drain the error body so the socket can return to the pool.
                runCatching { conn.errorStream?.use { it.readBytes() } }
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
