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
 * The Navigator's only network code. Talks to free, key-less OpenStreetMap
 * services — Nominatim for address search / reverse lookup, and the FOSSGIS
 * OSRM service (routing.openstreetmap.de, the very router that powers
 * openstreetmap.org's own directions) for turn-by-turn routing — over plain
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

        /** Default geocoder — overridable from Settings (`navGeocoderUrl`). */
        const val DEFAULT_GEOCODER = "https://nominatim.openstreetmap.org/search"

        /**
         * Default router base — overridable from Settings (`navRouterUrl`).
         * Three OSRM engines live under it (routed-bike / routed-foot /
         * routed-car), picked per [TravelMode]. This is the router that powers
         * openstreetmap.org's own directions: reliable and key-less.
         */
        const val DEFAULT_ROUTER = "https://routing.openstreetmap.de"

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
         * Resolves the configured router URL. A blank value — or a stale URL
         * left over from the retired Valhalla backend — falls back to
         * [DEFAULT_ROUTER] so existing installs migrate transparently.
         */
        fun effectiveRouterUrl(stored: String): String =
            if (stored.isBlank() || stored.contains("valhalla", ignoreCase = true))
                DEFAULT_ROUTER else stored

        /**
         * Extracts a sensible short label from a Nominatim display_name: the
         * first comma-separated part that is not just a house / road number, so
         * a pin reads "Karl Johans gate" rather than a meaningless "1". Returns
         * "" when the whole address is numeric — the caller then shows the
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
    }

    /**
     * Address / place search. Returns up to a handful of hits, best-match
     * first as Nominatim ranks them. Empty list on any failure — callers just
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
     * request fails — the caller falls back to [straightLineRoute].
     */
    suspend fun route(
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        endpoint: String = DEFAULT_ROUTER
    ): NavRoute? = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) return@withContext null
        if (mode == TravelMode.STRAIGHT) return@withContext straightLineRoute(name, waypoints)
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
                    // very end — the per-leg ones at intermediate stops are noise.
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
