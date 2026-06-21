package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.Maneuver
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.TurnType
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.cos

/** One geocoder search hit. */
data class GeoResult(val name: String, val lat: Double, val lng: Double)

/**
 * The Navigator's only network code. Talks to free, key-less OpenStreetMap
 * services — Nominatim for address search / reverse lookup and the FOSSGIS
 * OSRM service (routing.openstreetmap.de) for turn-by-turn routing. Faithful
 * port of Android's RoutingService; HttpURLConnection → shared Ktor client,
 * org.json → kotlinx.serialization.
 */
class RoutingService {

    private val client: HttpClient by lazy {
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = READ_TIMEOUT_MS
            }
        }
    }

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

        // Nominatim's usage policy asks for an identifying User-Agent.
        private const val USER_AGENT = "EUCPlanet-Navigator/1.0 (github.com/eried/eucplanet)"
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val READ_TIMEOUT_MS = 20_000L

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
         * first comma-separated part that is not just a house / road number.
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
                totalDistanceM = GeoMath.polylineLengthM(geometry),
            )
        }

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    /**
     * Address / place search. Returns up to a handful of hits, best-match
     * first as Nominatim ranks them. Empty list on any failure.
     */
    suspend fun geocode(
        query: String,
        endpoint: String = DEFAULT_GEOCODER,
        near: GeoPoint? = null,
    ): List<GeoResult> {
        if (query.isBlank()) return emptyList()
        return try {
            var url = "$endpoint?q=${enc(query)}&format=jsonv2&limit=6&addressdetails=0"
            if (near != null) {
                // Restrict results to roughly a 50 km box around the rider.
                val latD = 0.45
                val lngD = 0.45 / cos(near.lat * PI / 180.0).coerceAtLeast(0.05)
                url += "&bounded=1&viewbox=" +
                    "${near.lng - lngD},${near.lat - latD}," +
                    "${near.lng + lngD},${near.lat + latD}"
            }
            val body = httpGet(url) ?: return emptyList()
            val arr = JSON.parseToJsonElement(body) as? JsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val lat = o.str("lat")?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = o.str("lon")?.toDoubleOrNull() ?: return@mapNotNull null
                GeoResult(o.str("display_name") ?: query, lat, lng)
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
        searchEndpoint: String = DEFAULT_GEOCODER,
    ): String? {
        return try {
            // Derive the Nominatim /reverse endpoint from the configured /search one.
            val base = if (searchEndpoint.endsWith("/search"))
                searchEndpoint.removeSuffix("/search") + "/reverse"
            else "https://nominatim.openstreetmap.org/reverse"
            val url = "$base?lat=$lat&lon=$lng&format=jsonv2&zoom=18&addressdetails=0"
            val body = httpGet(url) ?: return null
            val o = JSON.parseToJsonElement(body) as? JsonObject ?: return null
            o.str("display_name")?.ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "reverseGeocode failed: ${e.message}")
            null
        }
    }

    /**
     * Resolves an ordered list of waypoints into a navigable route with
     * geometry and turn-by-turn maneuvers via OSRM. Returns `null` if the
     * request fails; the caller falls back to [straightLineRoute].
     */
    suspend fun route(
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        endpoint: String = DEFAULT_ROUTER,
    ): NavRoute? {
        if (waypoints.size < 2) return null
        if (mode == TravelMode.STRAIGHT) return straightLineRoute(name, waypoints)
        return try {
            val base = endpoint.trimEnd('/')
            // OSRM coordinates are lon,lat pairs, semicolon-separated, in the path.
            val coords = waypoints.joinToString(";") { "${it.lng},${it.lat}" }
            val url = "$base/${engineFor(mode)}/route/v1/driving/$coords" +
                "?overview=full&geometries=polyline6&steps=true"
            val body = httpGet(url) ?: return null
            val json = JSON.parseToJsonElement(body) as? JsonObject ?: return null
            parseOsrm(json, name, waypoints, mode)
        } catch (e: Exception) {
            Log.w(TAG, "route failed: ${e.message}")
            null
        }
    }

    // --- OSRM response parsing ----------------------------------------------------

    private fun parseOsrm(
        json: JsonObject,
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode,
    ): NavRoute? {
        if (json.str("code") != "Ok") return null
        val routes = json.arr("routes") ?: return null
        if (routes.isEmpty()) return null
        val route = routes[0] as? JsonObject ?: return null
        val geometry = decodePolyline6(route.str("geometry") ?: "")
        if (geometry.size < 2) return null
        val dist = route.dbl("distance") ?: 0.0
        val totalM = if (dist > 0.0) dist else GeoMath.polylineLengthM(geometry)

        val maneuvers = ArrayList<Maneuver>()
        var cumulativeM = 0.0
        val legs = route.arr("legs") ?: JsonArray(emptyList())
        for (li in legs.indices) {
            val steps = (legs[li] as? JsonObject)?.arr("steps") ?: continue
            for (si in steps.indices) {
                val step = steps[si] as? JsonObject ?: continue
                val m = step["maneuver"] as? JsonObject
                val loc = m?.get("location") as? JsonArray
                if (m != null && loc != null && loc.size >= 2) {
                    val type = mapOsrmManeuver(m.str("type") ?: "", m.str("modifier") ?: "")
                    val isFirst = li == 0 && si == 0
                    val isLast = li == legs.size - 1 && si == steps.size - 1
                    // Keep DEPART only at the very start and ARRIVE only at the
                    // very end; the per-leg ones at intermediate stops are noise.
                    val keep = when (type) {
                        TurnType.DEPART -> isFirst
                        TurnType.ARRIVE -> isLast
                        else -> true
                    }
                    if (keep) {
                        val lng = (loc[0] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                        val lat = (loc[1] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                        maneuvers.add(
                            Maneuver(
                                point = GeoPoint(lat, lng),
                                type = type,
                                instruction = "",
                                streetName = step.str("name") ?: "",
                                distanceFromStartM = cumulativeM,
                            )
                        )
                    }
                }
                // Each step's distance is the length from its maneuver to the next.
                cumulativeM += step.dbl("distance") ?: 0.0
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

    private fun enc(s: String): String = s.encodeURLParameter()

    private suspend fun httpGet(url: String): String? {
        return try {
            val resp = client.get(url) {
                header("User-Agent", USER_AGENT)
                header("Accept", "application/json")
            }
            if (resp.status.isSuccess()) resp.bodyAsText()
            else { Log.w(TAG, "GET $url -> HTTP ${resp.status.value}"); null }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        }
    }
}

// --- kotlinx JSON accessors mirroring org.json's opt* surface ---
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
