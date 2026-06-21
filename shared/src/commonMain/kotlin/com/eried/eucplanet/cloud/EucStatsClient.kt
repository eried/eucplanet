package com.eried.eucplanet.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared EucStats REST client — a Ktor port of Android's `data.eucstats.EucStatsApi`
 * (rider register / card / trip upload). Lives in `com.eried.eucplanet.cloud` (NOT
 * `data.eucstats`) so the FQN can't shadow Android's class on the split-package
 * shared classpath (same lesson as [[AlarmRule]] / UnitFormat).
 *
 * Attestation: iOS uses the **stub** token (empty `play_integrity`) which the dev
 * server accepts — Play Integrity is Android-only + config-gated off. App Attest
 * can be wired later. Test endpoint: `https://dev.eucstats.ried.no`.
 */
class EucStatsClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: HttpClient = HttpClient(),
) {
    suspend fun registerRider(payloadJson: String): RegisterResult = try {
        val resp = client.post("$baseUrl/riders") {
            // Must use contentType(): setBody(String) otherwise tags the body as
            // text/plain and the engine ignores a header()-set Content-Type, so the
            // JSON endpoint would 400/415.
            contentType(ContentType.Application.Json)
            setBody(payloadJson)
        }
        val body = resp.bodyAsText()
        when {
            resp.status.value in 200..299 && body.isNotEmpty() -> RegisterResult.Ok
            resp.status.value == 429 -> RegisterResult.RateLimited
            else -> RegisterResult.Failed(
                resp.status.value,
                runCatching { JSON.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.contentOrNull }.getOrNull(),
            )
        }
    } catch (e: Throwable) {
        RegisterResult.Failed(0, null) // network / no response
    }

    /** GET /riders/{id}/card — stats + leaderboard ranks. null on any failure. */
    suspend fun getCard(storeId: String): RiderCard? = try {
        val resp = client.get("$baseUrl/riders/$storeId/card")
        if (resp.status.value !in 200..299) {
            null
        } else {
            val o = JSON.parseToJsonElement(resp.bodyAsText()).jsonObject
            val stats = o["stats"]?.jsonObject
            val ranks = o["ranks"]?.jsonObject
            val hasAvatar = o["has_avatar"]?.jsonPrimitive?.booleanOrNull ?: false
            RiderCard(
                displayName = o["display_name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                flag = o["flag"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                hasAvatar = hasAvatar,
                avatarUrl = if (hasAvatar) "$baseUrl/riders/$storeId/avatar" else null,
                totalKm = stats?.get("total_km")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                trips = stats?.get("trips")?.jsonPrimitive?.intOrNull ?: 0,
                topSpeedKmh = stats?.get("best_speed_kmh")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                maxGforce = stats?.get("best_gforce")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                mileageRank = ranks?.get("distance")?.jsonPrimitive?.intOrNull,
                country = o["country"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
            )
        }
    } catch (e: Throwable) {
        null
    }

    /** GET /riders/{id} — profile + per-field change cooldowns (ISO yyyy-MM-dd
     *  dates, null = changeable now). null return = request failed. */
    suspend fun getProfile(storeId: String): RiderProfile? = try {
        val resp = client.get("$baseUrl/riders/$storeId")
        if (resp.status.value !in 200..299) {
            null
        } else {
            val o = JSON.parseToJsonElement(resp.bodyAsText()).jsonObject
            RiderProfile(
                displayName = o["display_name"]?.jsonPrimitive?.contentOrNull,
                flag = o["flag"]?.jsonPrimitive?.contentOrNull,
                hasAvatar = o["has_avatar"]?.jsonPrimitive?.booleanOrNull ?: false,
                avatarUrl = "$baseUrl/riders/$storeId/avatar",
                canChangeNameAfter = o["can_change_name_after"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                canChangeFlagAfter = o["can_change_flag_after"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                canChangeAvatarAfter = o["can_change_avatar_after"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
            )
        }
    } catch (e: Throwable) {
        null
    }

    /** PATCH /riders/{id} — edit display_name / flag / avatar_png_base64. */
    suspend fun patchProfile(storeId: String, payloadJson: String): EditResult = try {
        val resp = client.patch("$baseUrl/riders/$storeId") {
            contentType(ContentType.Application.Json)
            setBody(payloadJson)
        }
        val body = resp.bodyAsText()
        when {
            resp.status.value in 200..299 -> EditResult.Ok
            resp.status.value == 429 -> EditResult.RateLimited
            else -> EditResult.Failed(
                resp.status.value,
                runCatching { JSON.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.contentOrNull }.getOrNull(),
            )
        }
    } catch (e: Throwable) {
        EditResult.Failed(0, null)
    }

    /** DELETE /riders/{id} — permanent account + data deletion. true on 2xx. */
    suspend fun deleteRider(storeId: String): Boolean = try {
        client.delete("$baseUrl/riders/$storeId").status.value in 200..299
    } catch (e: Throwable) {
        false
    }

    /** GET /riders/{id}/export — GDPR JSON dump, or null on failure. */
    suspend fun exportData(storeId: String): String? = try {
        val resp = client.get("$baseUrl/riders/$storeId/export")
        if (resp.status.value in 200..299) resp.bodyAsText() else null
    } catch (e: Throwable) {
        null
    }

    /** true = rider exists (2xx), false = 404, null = couldn't tell. */
    suspend fun riderExists(storeId: String): Boolean? = try {
        val resp = client.get("$baseUrl/riders/$storeId")
        when {
            resp.status.value in 200..299 -> true
            resp.status.value == 404 -> false
            else -> null
        }
    } catch (e: Throwable) {
        null
    }

    /** POST /trips multipart: meta (JSON string) + trip (gzipped CSV bytes). */
    suspend fun uploadTrip(metaJson: String, gzippedCsv: ByteArray): UploadResult = try {
        val resp = client.post("$baseUrl/trips") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("meta", metaJson)
                        append(
                            "trip", gzippedCsv,
                            Headers.build {
                                append(HttpHeaders.ContentType, "application/gzip")
                                append(HttpHeaders.ContentDisposition, "filename=\"trip.csv.gz\"")
                            },
                        )
                    },
                ),
            )
        }
        val text = resp.bodyAsText()
        when (resp.status.value) {
            200, 201, 202 -> {
                val o = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
                UploadResult.Ok(
                    o?.get("validation_status")?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                    o?.get("duplicate")?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
            409 -> UploadResult.Ok(null, true)
            401 -> UploadResult.AuthFailure(401)
            400, 403, 413, 422 -> UploadResult.PermanentFailure(resp.status.value, text)
            429 -> UploadResult.Retry(429, null)
            else -> UploadResult.Retry(resp.status.value, null)
        }
    } catch (e: Throwable) {
        UploadResult.Retry(0, null) // network error → keep queued + retry
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://dev.eucstats.ried.no"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** Rider card: profile + lifetime stats + leaderboard ranks. */
data class RiderCard(
    val displayName: String?,
    val flag: String?,
    val hasAvatar: Boolean,
    val avatarUrl: String?,
    val totalKm: Double,
    val trips: Int,
    val topSpeedKmh: Double,
    val maxGforce: Double,
    val mileageRank: Int?,
    val country: String?,
)

sealed interface UploadResult {
    data class Ok(val validationStatus: String?, val duplicate: Boolean) : UploadResult
    data class PermanentFailure(val code: Int, val body: String) : UploadResult // 400/403/413/422
    data class AuthFailure(val code: Int) : UploadResult                         // 401 (re-mint)
    data class Retry(val code: Int, val retryAfterSec: Long?) : UploadResult     // 429/5xx/network
}

sealed interface RegisterResult {
    data object Ok : RegisterResult
    data object RateLimited : RegisterResult
    data class Failed(val code: Int, val detail: String?) : RegisterResult
}

/** Full profile (from GET /riders/{id}) with per-field change cooldowns. */
data class RiderProfile(
    val displayName: String?,
    val flag: String?,
    val hasAvatar: Boolean,
    val avatarUrl: String?,
    /** ISO yyyy-MM-dd the field becomes editable again; null = editable now. */
    val canChangeNameAfter: String?,
    val canChangeFlagAfter: String?,
    val canChangeAvatarAfter: String?,
)

/** Result of a profile PATCH. */
sealed interface EditResult {
    data object Ok : EditResult
    data object RateLimited : EditResult
    data class Failed(val code: Int, val detail: String?) : EditResult
}
