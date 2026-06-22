package com.eried.eucplanet.nav

import android.util.Log
import com.eried.eucplanet.data.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * A category of place along the route. **CHARGER means electric charging only**
 * — a dedicated charging station OR a fuel station that also offers electricity
 * (`fuel:electricity=yes`). A plain (petrol/diesel) fuel station is **not** a
 * charger; it falls into [STORE] with the other "stop" places (shops). The
 * non-charger kinds ([STORE], [FOOD], [REST], [SIGHTS]) are the grouped "places"
 * layer toggled together. See [PoiService.classify].
 */
enum class PoiKind { CHARGER, STORE, FOOD, REST, SIGHTS;
    companion object {
        /** The non-charger "places" categories, toggled as one group. */
        val PLACES: Set<PoiKind> = setOf(STORE, FOOD, REST, SIGHTS)
    }
}

/**
 * A place near the route the rider might want to stop at. Coordinates plus a few
 * human-readable tags pulled from OpenStreetMap, and how far the place sits from
 * the planned route (so the list can sort on-the-way places first).
 */
data class PointOfInterest(
    val id: Long,
    val lat: Double,
    val lng: Double,
    val kind: PoiKind,
    val name: String,
    val brand: String = "",
    val openingHours: String = "",
    /** Short kind-specific summary: socket types for a charger, fuels for fuel. */
    val info: String = "",
    val operator: String = "",
    val network: String = "",
    val access: String = "",
    val fee: String = "",
    val capacity: String = "",
    val phone: String = "",
    val website: String = "",
    /** Best-effort image URL from OSM tags (image / wikimedia_commons); may be blank. */
    val imageUrl: String = "",
    val distanceFromRouteM: Double = 0.0,
) {
    fun point() = GeoPoint(lat, lng)
}

/** A lat/lng bounding box. */
data class BBox(val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double)

/**
 * Fetches chargers and fuel stations near a route from the key-less Overpass
 * (OpenStreetMap) API. Same plain [HttpURLConnection] + [Dispatchers.IO] style
 * as [RoutingService]; nothing runs in the background and no telemetry is sent.
 * The layer is opt-in from the Route Builder, so this is only called when the
 * rider asks to see places along their route.
 */
@Singleton
class PoiService @Inject constructor() {

    companion object {
        private const val TAG = "PoiService"

        /** Default Overpass endpoint (key-less, public). */
        const val DEFAULT_OVERPASS = "https://overpass-api.de/api/interpreter"

        private const val USER_AGENT = "EUCPlanet-Navigator/1.0 (github.com/eried/eucplanet)"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 25_000

        /** Bounding-box padding around the route, metres. */
        const val BBOX_PAD_M = 2_000.0
        /** Keep only places within this perpendicular distance of the route. */
        const val ROUTE_BUFFER_M = 1_500.0
        /** Cap rendered places so the map stays light. */
        const val MAX_POIS = 60

        /** Padded bounding box around a route polyline. Null for an empty line. */
        fun routeBoundingBox(geometry: List<GeoPoint>, padM: Double = BBOX_PAD_M): BBox? {
            if (geometry.isEmpty()) return null
            var minLat = Double.MAX_VALUE; var minLng = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
            for (p in geometry) {
                minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
                minLng = min(minLng, p.lng); maxLng = max(maxLng, p.lng)
            }
            val latPad = padM / 111_320.0
            val midLat = (minLat + maxLat) / 2.0
            val lngPad = padM / (111_320.0 * cos(Math.toRadians(midLat)).coerceAtLeast(0.05))
            return BBox(minLat - latPad, minLng - lngPad, maxLat + latPad, maxLng + lngPad)
        }

        /**
         * Keeps only POIs within [maxDistM] of the route polyline, fills each
         * one's [PointOfInterest.distanceFromRouteM], sorts nearest-first and
         * caps the count. Pure -> unit-tested.
         */
        fun filterNearRoute(
            pois: List<PointOfInterest>,
            geometry: List<GeoPoint>,
            maxDistM: Double = ROUTE_BUFFER_M,
            cap: Int = MAX_POIS,
        ): List<PointOfInterest> {
            if (geometry.size < 2) return emptyList()
            return pois.mapNotNull { poi ->
                val d = GeoMath.nearestOnPolyline(poi.point(), geometry)?.distanceM
                    ?: return@mapNotNull null
                if (d > maxDistM) null else poi.copy(distanceFromRouteM = d)
            }.sortedBy { it.distanceFromRouteM }.take(cap)
        }

        /**
         * Overpass node selectors per category. Note CHARGER pulls dedicated
         * charging stations AND fuel stations tagged with electricity, while
         * STORE pulls the *non-electric* fuel stations plus shops — so a petrol
         * station shows under "places", an EV-capable one under "charging".
         */
        private val CATEGORY_FILTERS: Map<PoiKind, List<String>> = mapOf(
            PoiKind.CHARGER to listOf(
                "node[\"amenity\"=\"charging_station\"]",
                "node[\"amenity\"=\"fuel\"][\"fuel:electricity\"=\"yes\"]",
            ),
            PoiKind.STORE to listOf(
                "node[\"amenity\"=\"fuel\"][\"fuel:electricity\"!=\"yes\"]",
                "node[\"shop\"=\"convenience\"]",
                "node[\"shop\"=\"supermarket\"]",
            ),
            PoiKind.FOOD to listOf(
                "node[\"amenity\"=\"cafe\"]",
                "node[\"amenity\"=\"restaurant\"]",
                "node[\"amenity\"=\"fast_food\"]",
            ),
            PoiKind.REST to listOf(
                "node[\"amenity\"=\"drinking_water\"]",
                "node[\"amenity\"=\"toilets\"]",
                "node[\"amenity\"=\"shelter\"]",
            ),
            PoiKind.SIGHTS to listOf(
                "node[\"tourism\"=\"attraction\"]",
                "node[\"tourism\"=\"viewpoint\"]",
                "node[\"tourism\"=\"artwork\"]",
                "node[\"tourism\"=\"museum\"]",
            ),
        )

        /**
         * Classifies an OSM node's tags into a [PoiKind], or null if it's not a
         * category we surface. This is where the "electric only" charger rule
         * lives: a fuel station counts as a CHARGER only when it has
         * `fuel:electricity=yes`, otherwise it's a STORE.
         */
        fun classify(tags: JSONObject): PoiKind? {
            val amenity = tags.optString("amenity")
            val shop = tags.optString("shop")
            val tourism = tags.optString("tourism")
            return when {
                amenity == "charging_station" -> PoiKind.CHARGER
                amenity == "fuel" ->
                    if (tags.optString("fuel:electricity") == "yes") PoiKind.CHARGER
                    else PoiKind.STORE
                shop == "convenience" || shop == "supermarket" -> PoiKind.STORE
                amenity == "cafe" || amenity == "restaurant" || amenity == "fast_food" -> PoiKind.FOOD
                amenity == "drinking_water" || amenity == "toilets" || amenity == "shelter" -> PoiKind.REST
                tourism == "attraction" || tourism == "viewpoint" ||
                    tourism == "artwork" || tourism == "museum" -> PoiKind.SIGHTS
                else -> null
            }
        }

        /** A bounding box of [radiusM] around a single point. */
        fun bboxAround(center: GeoPoint, radiusM: Double): BBox {
            val latPad = radiusM / 111_320.0
            val lngPad = radiusM / (111_320.0 * cos(Math.toRadians(center.lat)).coerceAtLeast(0.05))
            return BBox(center.lat - latPad, center.lng - lngPad, center.lat + latPad, center.lng + lngPad)
        }

        /** Keeps POIs within [maxDistM] of [center], nearest-first, capped. */
        fun filterNearPoint(
            pois: List<PointOfInterest>,
            center: GeoPoint,
            maxDistM: Double = ROUTE_BUFFER_M,
            cap: Int = MAX_POIS,
        ): List<PointOfInterest> = pois.mapNotNull { poi ->
            val d = GeoMath.distanceM(poi.point(), center)
            if (d > maxDistM) null else poi.copy(distanceFromRouteM = d)
        }.sortedBy { it.distanceFromRouteM }.take(cap)

        /** Builds the Overpass QL query for the selected [categories] within [b]. */
        fun overpassQuery(b: BBox, categories: Set<PoiKind>): String {
            val bbox = "${b.minLat},${b.minLng},${b.maxLat},${b.maxLng}"
            // Fetch a few times the cap (pre-buffer-filter) so the nearest cap
            // survive the route-distance filter below.
            val limit = MAX_POIS * 4
            val cats = categories.ifEmpty { setOf(PoiKind.CHARGER) }
            val body = cats.flatMap { CATEGORY_FILTERS[it].orEmpty() }
                .joinToString("") { "$it($bbox);" }
            return "[out:json][timeout:25];($body);out body $limit;"
        }

        /**
         * Parses an Overpass JSON response into POIs (no distance yet), keeping
         * only nodes whose classified kind is in [enabled].
         */
        fun parseOverpass(
            body: String,
            enabled: Set<PoiKind> = PoiKind.entries.toSet(),
        ): List<PointOfInterest> {
            val root = JSONObject(body)
            val els = root.optJSONArray("elements") ?: return emptyList()
            val out = ArrayList<PointOfInterest>(els.length())
            for (i in 0 until els.length()) {
                val e = els.optJSONObject(i) ?: continue
                if (e.optString("type") != "node") continue
                val lat = if (e.has("lat")) e.optDouble("lat") else continue
                val lng = if (e.has("lon")) e.optDouble("lon") else continue
                val tags = e.optJSONObject("tags") ?: JSONObject()
                val kind = classify(tags) ?: continue
                if (kind !in enabled) continue
                out.add(
                    PointOfInterest(
                        id = e.optLong("id"),
                        lat = lat,
                        lng = lng,
                        kind = kind,
                        name = tags.optString("name").ifBlank { tags.optString("brand") },
                        brand = tags.optString("brand"),
                        openingHours = tags.optString("opening_hours"),
                        info = summarize(kind, tags),
                        operator = tags.optString("operator"),
                        network = tags.optString("network"),
                        access = tags.optString("access"),
                        fee = tags.optString("fee"),
                        capacity = tags.optString("capacity"),
                        phone = tags.optString("phone").ifBlank { tags.optString("contact:phone") },
                        website = tags.optString("website").ifBlank { tags.optString("contact:website") },
                        imageUrl = imageUrlFrom(tags),
                    )
                )
            }
            return out
        }

        /**
         * Best-effort image URL from OSM tags. OSM rarely stores photos, but
         * when it does it's via `image` (a direct URL) or `wikimedia_commons`
         * (a "File:Name.jpg" reference), which maps to a key-less Commons
         * thumbnail. Returns "" when no usable image tag is present.
         */
        fun imageUrlFrom(tags: JSONObject): String {
            val image = tags.optString("image")
            if (image.startsWith("http")) return image
            val commons = tags.optString("wikimedia_commons")
            if (commons.startsWith("File:")) {
                val file = commons.removePrefix("File:").trim().replace(' ', '_')
                if (file.isNotEmpty()) {
                    return "https://commons.wikimedia.org/wiki/Special:FilePath/" +
                        URLEncoder.encode(file, "UTF-8").replace("+", "%20") + "?width=400"
                }
            }
            return ""
        }

        /** Short kind-specific summary from OSM tags for the details sheet. */
        private fun summarize(kind: PoiKind, tags: JSONObject): String = when (kind) {
            PoiKind.CHARGER -> {
                val sockets = tags.keys().asSequence()
                    .filter { it.startsWith("socket:") && !it.contains(":output") }
                    .map { it.removePrefix("socket:").replace('_', ' ') }
                    .distinct().toList()
                when {
                    sockets.isNotEmpty() -> sockets.joinToString(", ")
                    tags.optString("amenity") == "fuel" -> "EV charging"
                    tags.has("capacity") -> "${tags.optString("capacity")} points"
                    else -> ""
                }
            }
            PoiKind.STORE -> if (tags.optString("amenity") == "fuel") {
                buildList {
                    if (tags.optString("fuel:diesel") == "yes") add("Diesel")
                    if (tags.optString("fuel:octane_95") == "yes") add("95")
                    if (tags.optString("fuel:octane_98") == "yes") add("98")
                }.ifEmpty { listOf("Fuel") }.joinToString(", ")
            } else tags.optString("shop").replace('_', ' ').replaceFirstChar { it.uppercase() }
            PoiKind.FOOD -> tags.optString("cuisine").replace('_', ' ').replace(';', ',')
            PoiKind.REST -> tags.optString("amenity").replace('_', ' ').replaceFirstChar { it.uppercase() }
            PoiKind.SIGHTS -> tags.optString("tourism").replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Fetches chargers + fuel stations within the route's padded bounding box,
     * then keeps only those within [PoiService.ROUTE_BUFFER_M] of the route.
     * Returns an empty list on success-but-none, and **null on any failure**
     * (network / HTTP / parse) so the caller can tell "nothing nearby" apart
     * from "couldn't load" and message the rider accordingly.
     */
    suspend fun poisAlongRoute(
        geometry: List<GeoPoint>,
        endpoint: String = DEFAULT_OVERPASS,
        categories: Set<PoiKind> = setOf(PoiKind.CHARGER),
    ): List<PointOfInterest>? = withContext(Dispatchers.IO) {
        if (geometry.size < 2 || categories.isEmpty()) return@withContext emptyList()
        val bbox = routeBoundingBox(geometry) ?: return@withContext emptyList()
        try {
            val url = endpoint.ifBlank { DEFAULT_OVERPASS }
            val body = httpPost(url, "data=" + enc(overpassQuery(bbox, categories)))
            if (body == null) {
                Log.w(TAG, "poisAlongRoute: null body (network/HTTP error)")
                return@withContext null
            }
            val parsed = parseOverpass(body, categories)
            val filtered = filterNearRoute(parsed, geometry)
            Log.i(TAG, "poisAlongRoute: cats=$categories bytes=${body.length} parsed=${parsed.size} kept=${filtered.size}")
            filtered
        } catch (e: Exception) {
            Log.w(TAG, "poisAlongRoute failed: ${e.message}")
            null
        }
    }

    /**
     * Like [poisAlongRoute] but around a single [center] point (the rider's
     * location or the map centre) for when there's no route yet. Returns null on
     * failure, empty on none.
     */
    suspend fun poisAround(
        center: GeoPoint,
        radiusKm: Double = 1.5,
        endpoint: String = DEFAULT_OVERPASS,
        categories: Set<PoiKind> = setOf(PoiKind.CHARGER),
    ): List<PointOfInterest>? = withContext(Dispatchers.IO) {
        if (categories.isEmpty()) return@withContext emptyList()
        val radiusM = radiusKm * 1000.0
        val bbox = bboxAround(center, radiusM)
        try {
            val url = endpoint.ifBlank { DEFAULT_OVERPASS }
            val body = httpPost(url, "data=" + enc(overpassQuery(bbox, categories)))
            if (body == null) {
                Log.w(TAG, "poisAround: null body (network/HTTP error)")
                return@withContext null
            }
            val parsed = parseOverpass(body, categories)
            val filtered = filterNearPoint(parsed, center, radiusM)
            Log.i(TAG, "poisAround: cats=$categories parsed=${parsed.size} kept=${filtered.size}")
            filtered
        } catch (e: Exception) {
            Log.w(TAG, "poisAround failed: ${e.message}")
            null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Overpass prefers POST for queries; the body is form-encoded `data=...`. */
    private fun httpPost(endpoint: String, formBody: String): String? {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                Log.w(TAG, "POST $endpoint -> HTTP ${conn.responseCode}")
                runCatching { conn.errorStream?.use { it.readBytes() } }
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
