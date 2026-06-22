package com.eried.eucplanet.nav

import android.util.Log
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

/** One Open Charge Map user comment / check-in. */
data class OcmComment(
    val user: String,
    /** 1..5, or null when the comment carries no star rating. */
    val rating: Int?,
    val text: String,
    /** Check-in outcome, e.g. "Charged successfully" (may be blank). */
    val checkin: String,
    /** ISO date; the UI shows just the day. */
    val date: String,
)

/**
 * Community data for a charger from Open Charge Map: richer connector details
 * than OSM, plus user ratings, comments and photos.
 */
data class OcmCharger(
    val ocmId: Long,
    val title: String,
    val operator: String,
    val usageCost: String,
    val status: String,
    /** e.g. "Type 2 · 22 kW, CCS · 50 kW". */
    val connectors: String,
    val numberOfPoints: Int?,
    val avgRating: Double?,
    val ratingCount: Int,
    val comments: List<OcmComment>,
    val photoUrls: List<String>,
    val ocmUrl: String,
)

/**
 * Talks to the key-required Open Charge Map API. Same plain [HttpURLConnection]
 * + [Dispatchers.IO] style as [RoutingService] / [PoiService]. Only called when
 * the rider has set a (free) OCM API key and taps a charger in the flyout, so
 * nothing runs in the background.
 */
@Singleton
class OcmService @Inject constructor() {

    companion object {
        private const val TAG = "OcmService"

        /** OCM POI endpoint (an API key is mandatory). */
        const val ENDPOINT = "https://api.openchargemap.io/v3/poi/"

        private const val USER_AGENT = "EUCPlanet-Navigator/1.0 (github.com/eried/eucplanet)"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 20_000

        /** Parses the nearest (first) POI from an OCM `/poi` array response. */
        fun parseFirst(body: String): OcmCharger? {
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            return parsePoi(arr.optJSONObject(0) ?: return null)
        }

        fun parsePoi(o: JSONObject): OcmCharger? {
            val id = o.optLong("ID", -1L)
            if (id < 0) return null
            val title = o.optJSONObject("AddressInfo")?.optString("Title").orEmpty()
            val operator = o.optJSONObject("OperatorInfo")?.optString("Title").orEmpty()
            val status = o.optJSONObject("StatusType")?.optString("Title").orEmpty()
            val comments = parseComments(o.optJSONArray("UserComments"))
            val ratings = comments.mapNotNull { it.rating }
            return OcmCharger(
                ocmId = id,
                title = title,
                operator = operator,
                usageCost = o.optString("UsageCost"),
                status = status,
                connectors = summarizeConnectors(o.optJSONArray("Connections")),
                numberOfPoints = if (o.has("NumberOfPoints") && !o.isNull("NumberOfPoints"))
                    o.optInt("NumberOfPoints") else null,
                avgRating = if (ratings.isNotEmpty()) ratings.average() else null,
                ratingCount = ratings.size,
                comments = comments,
                photoUrls = parsePhotos(o.optJSONArray("MediaItems")),
                ocmUrl = "https://openchargemap.org/site/poi/details/$id",
            )
        }

        /** "Type 2 · 22 kW, CCS · 50 kW" from the Connections array (deduped). */
        fun summarizeConnectors(arr: JSONArray?): String {
            if (arr == null) return ""
            val seen = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val type = c.optJSONObject("ConnectionType")?.optString("Title").orEmpty()
                if (type.isBlank()) continue
                val kw = c.optDouble("PowerKW", 0.0)
                seen.add(if (kw > 0.0) "$type · ${fmtKw(kw)} kW" else type)
            }
            return seen.joinToString(", ")
        }

        private fun fmtKw(kw: Double): String =
            if (kw == kw.toLong().toDouble()) kw.toLong().toString() else "%.1f".format(kw)

        private fun parseComments(arr: JSONArray?): List<OcmComment> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = c.optString("Comment")
                val rating = if (c.has("Rating") && !c.isNull("Rating")) c.optInt("Rating") else null
                val checkin = c.optJSONObject("CheckinStatusType")?.optString("Title").orEmpty()
                if (text.isBlank() && rating == null && checkin.isBlank()) return@mapNotNull null
                OcmComment(
                    user = c.optString("UserName").ifBlank { "Anonymous" },
                    rating = rating,
                    text = text,
                    checkin = checkin,
                    date = c.optString("DateCreated").take(10),
                )
            }
        }

        private fun parsePhotos(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                if (m.optBoolean("IsVideo", false)) return@mapNotNull null
                m.optString("ItemThumbnailURL").ifBlank { m.optString("ItemURL") }
                    .ifBlank { null }
            }
        }
    }

    /**
     * The nearest OCM charger to [lat]/[lng] (a small search radius, since we're
     * matching one specific charging spot), enriched with comments. Returns null
     * on a blank key, no match, or any failure.
     */
    suspend fun nearestCharger(
        lat: Double,
        lng: Double,
        apiKey: String,
        distanceKm: Double = 0.5,
    ): OcmCharger? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            val url = ENDPOINT +
                "?output=json&latitude=$lat&longitude=$lng" +
                "&distance=$distanceKm&distanceunit=KM&maxresults=1" +
                "&includecomments=true&compact=false&verbose=false" +
                "&key=${enc(apiKey)}"
            val body = httpGet(url) ?: return@withContext null
            parseFirst(body)
        } catch (e: Exception) {
            Log.w(TAG, "nearestCharger failed: ${e.message}")
            null
        }
    }

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
                Log.w(TAG, "GET OCM -> HTTP ${conn.responseCode}")
                runCatching { conn.errorStream?.use { it.readBytes() } }
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
