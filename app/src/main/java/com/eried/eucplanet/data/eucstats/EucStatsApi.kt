package com.eried.eucplanet.data.eucstats

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class RiderCard(
    val displayName: String?, val flag: String?, val hasAvatar: Boolean, val avatarUrl: String?,
    val totalKm: Double, val trips: Int, val topSpeedKmh: Double, val maxGforce: Double,
    val mileageRank: Int?, val country: String?,
)

/**
 * Full rider profile from GET /riders/{storeId}.
 * Includes the `can_change_*` date strings (ISO yyyy-MM-dd or null) that gate
 * how soon the rider may edit each field again after a previous change.
 */
data class RiderProfile(
    val displayName: String?,
    val flag: String?,
    val hasAvatar: Boolean,
    val avatarUrl: String? = null,
    val canChangeNameAfter: String?,
    val canChangeFlagAfter: String?,
    val canChangeAvatarAfter: String?,
)

sealed interface UploadResult {
    data class Ok(val validationStatus: String?, val duplicate: Boolean) : UploadResult
    data class PermanentFailure(val code: Int, val body: String) : UploadResult  // 400/403/413/422
    data class AuthFailure(val code: Int) : UploadResult                          // 401 (re-mint)
    data class Retry(val code: Int, val retryAfterSec: Long?) : UploadResult      // 429/5xx/network
}

/**
 * Result of POST /riders. Per the eucstats rate-limit spec, only a 429
 * (`rate_limited:rider_create`, per IP) is retryable -- re-registering an
 * existing store_id is never limited; anything else is terminal for the attempt.
 */
sealed interface RegisterResult {
    data object Ok : RegisterResult             // 2xx with a body (store_id is client-generated)
    data object RateLimited : RegisterResult   // 429 rate_limited:rider_create
    data object Failed : RegisterResult         // network / other (terminal)
}

/**
 * Contract for the eucstats HTTP API. Extracted as an interface so
 * [EucStatsRepository] can be tested with a fake implementation on the JVM
 * without a live server or OkHttp.
 */
interface EucStatsApiContract {
    fun registerRider(payload: JSONObject): RegisterResult
    fun getCard(storeId: String): RiderCard?
    fun getProfile(storeId: String): RiderProfile?
    /** true = rider exists (200), false = not found (404, e.g. dataset reset
     *  or deleted server-side), null = couldn't tell (network/other). */
    fun riderExists(storeId: String): Boolean? = null
    fun patchRider(storeId: String, payload: JSONObject): Int
    fun deleteRider(storeId: String): Boolean
    fun exportRider(storeId: String): String?
    fun uploadTrip(metaJson: String, gzippedCsv: ByteArray): UploadResult
}

class EucStatsApi(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
) : EucStatsApiContract {
    private val json = "application/json; charset=utf-8".toMediaType()

    // Every call wraps OkHttp in try/catch: a network failure (no connectivity,
    // timeout, unreachable host) must surface as a null/"couldn't tell" result,
    // NEVER an uncaught exception that crashes the app (e.g. registering while
    // the server is unreachable). uploadTrip already does this.

    override fun registerRider(payload: JSONObject): RegisterResult {
        val req = Request.Builder().url("${baseUrl()}/riders")
            .post(payload.toString().toRequestBody(json)).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    // store_id is client-generated, so we don't read the body;
                    // a non-empty 2xx is enough (no JSON parse that could throw).
                    resp.isSuccessful && body.isNotEmpty() -> RegisterResult.Ok
                    resp.code == 429 -> RegisterResult.RateLimited
                    else -> RegisterResult.Failed
                }
            }
        } catch (e: Exception) {
            RegisterResult.Failed
        }
    }

    override fun getCard(storeId: String): RiderCard? {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId/card").get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val o = JSONObject(resp.body?.string().orEmpty())
                // Stats live under "stats" and ranks under "ranks" in the response.
                val stats = o.optJSONObject("stats") ?: JSONObject()
                val ranks = o.optJSONObject("ranks") ?: JSONObject()
                RiderCard(
                    displayName = o.optString("display_name").ifEmpty { null },
                    flag = o.optString("flag").ifEmpty { null },
                    hasAvatar = o.optBoolean("has_avatar"),
                    // No avatar_url in the JSON; the image is served at
                    // /riders/{id}/avatar, so build that URL when an avatar exists.
                    avatarUrl = if (o.optBoolean("has_avatar")) "${baseUrl()}/riders/$storeId/avatar" else null,
                    totalKm = stats.optDouble("total_km", 0.0),
                    trips = stats.optInt("trips", 0),
                    topSpeedKmh = stats.optDouble("best_speed_kmh", 0.0),
                    maxGforce = stats.optDouble("best_gforce", 0.0),
                    mileageRank = if (!ranks.has("distance") || ranks.isNull("distance")) null else ranks.optInt("distance"),
                    country = o.optString("country").ifEmpty { null },
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun getProfile(storeId: String): RiderProfile? {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId").get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val o = JSONObject(resp.body?.string().orEmpty())
                RiderProfile(
                    displayName = o.optString("display_name").ifEmpty { null },
                    flag = o.optString("flag").ifEmpty { null },
                    hasAvatar = o.optBoolean("has_avatar"),
                    avatarUrl = if (o.optBoolean("has_avatar")) "${baseUrl()}/riders/$storeId/avatar" else null,
                    canChangeNameAfter = o.optString("can_change_name_after").ifEmpty { null },
                    canChangeFlagAfter = o.optString("can_change_flag_after").ifEmpty { null },
                    canChangeAvatarAfter = o.optString("can_change_avatar_after").ifEmpty { null },
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun riderExists(storeId: String): Boolean? {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId").get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> true
                    resp.code == 404 -> false
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun patchRider(storeId: String, payload: JSONObject): Int {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId")
            .patch(payload.toString().toRequestBody(json)).build()
        return try {
            client.newCall(req).execute().use { it.code }
        } catch (e: Exception) {
            0 // network failure: caller treats any non-2xx as an error
        }
    }

    override fun deleteRider(storeId: String): Boolean {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId").delete().build()
        return try {
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    override fun exportRider(storeId: String): String? {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId/export").get().build()
        return try {
            client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (e: Exception) {
            null
        }
    }

    /** POST /trips multipart: meta (JSON string) + trip (gzipped CSV). */
    override fun uploadTrip(metaJson: String, gzippedCsv: ByteArray): UploadResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("meta", metaJson)
            .addFormDataPart("trip", "trip.csv.gz",
                gzippedCsv.toRequestBody("application/gzip".toMediaType()))
            .build()
        val req = Request.Builder().url("${baseUrl()}/trips").post(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when (resp.code) {
                    200, 201, 202 -> {
                        val o = runCatching { JSONObject(text) }.getOrNull()
                        UploadResult.Ok(o?.optString("validation_status")?.ifEmpty { null },
                            o?.optBoolean("duplicate") ?: false)
                    }
                    409 -> UploadResult.Ok(null, true)
                    401 -> UploadResult.AuthFailure(401)
                    // Terminal for this upload (per the eucstats rate-limit/ban
                    // spec): 403 rider_banned / rider_not_allowlisted, 400
                    // rider_not_registered, 413 payload_too_large, 422 data
                    // problem. Never auto-retried -- only 429 (and a one-time 401
                    // re-mint) retry.
                    400, 403, 413, 422 -> UploadResult.PermanentFailure(resp.code, text)
                    // Rate limited -- temporary, keep the trip queued and retry
                    // with backoff (no Retry-After header today, so own backoff).
                    429 -> UploadResult.Retry(429, resp.header("Retry-After")?.toLongOrNull())
                    // 5xx / unexpected -> transient, retry (the trip is never dropped).
                    else -> UploadResult.Retry(resp.code, null)
                }
            }
        } catch (e: Exception) {
            UploadResult.Retry(0, null) // network error
        }
    }
}
