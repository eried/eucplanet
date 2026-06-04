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
    data class PermanentFailure(val code: Int, val body: String) : UploadResult  // 400/413/422
    data class AuthFailure(val code: Int) : UploadResult                          // 401 (re-mint)
    data class Retry(val code: Int, val retryAfterSec: Long?) : UploadResult      // 429/5xx/network
}

/**
 * Contract for the eucstats HTTP API. Extracted as an interface so
 * [EucStatsRepository] can be tested with a fake implementation on the JVM
 * without a live server or OkHttp.
 */
interface EucStatsApiContract {
    fun registerRider(payload: JSONObject): JSONObject?
    fun getCard(storeId: String): RiderCard?
    fun getProfile(storeId: String): RiderProfile?
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

    override fun registerRider(payload: JSONObject): JSONObject? {
        val req = Request.Builder().url("${baseUrl()}/riders")
            .post(payload.toString().toRequestBody(json)).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return if (resp.isSuccessful && body.isNotEmpty()) JSONObject(body) else null
        }
    }

    override fun getCard(storeId: String): RiderCard? {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId/card").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = JSONObject(resp.body?.string().orEmpty())
            return RiderCard(
                displayName = o.optString("display_name").ifEmpty { null },
                flag = o.optString("flag").ifEmpty { null },
                hasAvatar = o.optBoolean("has_avatar"),
                avatarUrl = o.optString("avatar_url").ifEmpty { null },
                totalKm = o.optDouble("total_km", 0.0), trips = o.optInt("trips", 0),
                topSpeedKmh = o.optDouble("top_speed_kmh", 0.0), maxGforce = o.optDouble("max_gforce", 0.0),
                mileageRank = if (o.isNull("mileage_rank")) null else o.optInt("mileage_rank"),
                country = o.optString("country").ifEmpty { null },
            )
        }
    }

    override fun getProfile(storeId: String): RiderProfile? {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = JSONObject(resp.body?.string().orEmpty())
            return RiderProfile(
                displayName = o.optString("display_name").ifEmpty { null },
                flag = o.optString("flag").ifEmpty { null },
                hasAvatar = o.optBoolean("has_avatar"),
                avatarUrl = o.optString("avatar_url").ifEmpty { null },
                canChangeNameAfter = o.optString("can_change_name_after").ifEmpty { null },
                canChangeFlagAfter = o.optString("can_change_flag_after").ifEmpty { null },
                canChangeAvatarAfter = o.optString("can_change_avatar_after").ifEmpty { null },
            )
        }
    }

    override fun patchRider(storeId: String, payload: JSONObject): Int {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId")
            .patch(payload.toString().toRequestBody(json)).build()
        client.newCall(req).execute().use { return it.code }
    }

    override fun deleteRider(storeId: String): Boolean {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId").delete().build()
        client.newCall(req).execute().use { return it.isSuccessful }
    }

    override fun exportRider(storeId: String): String? {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId/export").get().build()
        client.newCall(req).execute().use { return if (it.isSuccessful) it.body?.string() else null }
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
                    400, 413, 422 -> UploadResult.PermanentFailure(resp.code, text)
                    429 -> UploadResult.Retry(429, resp.header("Retry-After")?.toLongOrNull())
                    else -> UploadResult.Retry(resp.code, null)
                }
            }
        } catch (e: Exception) {
            UploadResult.Retry(0, null) // network error
        }
    }
}
