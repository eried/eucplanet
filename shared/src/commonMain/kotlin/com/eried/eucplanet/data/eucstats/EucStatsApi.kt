package com.eried.eucplanet.data.eucstats

import com.eried.eucplanet.platform.HttpClient
import com.eried.eucplanet.platform.HttpRequest
import com.eried.eucplanet.platform.JSONObject

/**
 * Contract for the eucstats HTTP API. Extracted as an interface so
 * [EucStatsRepository] can be tested with a fake implementation on the JVM
 * without a live server or an HTTP client.
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

    /** Current verdict for one trip, or null when it couldn't be read (network, or a
     *  server too old to serve GET /trips/{uuid}). Defaulted so test fakes need not
     *  implement it. */
    fun getTripStatus(tripUuid: String): TripStatus? = null
}

class EucStatsApi(
    private val client: HttpClient,
    private val baseUrl: () -> String,
) : EucStatsApiContract {
    // OkHttp adds "; charset=utf-8" to a text body, so this is the same
    // "application/json; charset=utf-8" the calls always sent.
    private fun json(o: JSONObject) = HttpRequest.Body.Text("application/json", o.toString())

    private fun get(path: String) = HttpRequest("${baseUrl()}$path", HttpRequest.Body.None)

    // Every call wraps the HTTP client in try/catch: a network failure (no connectivity,
    // timeout, unreachable host) must surface as a null/"couldn't tell" result,
    // NEVER an uncaught exception that crashes the app (e.g. registering while
    // the server is unreachable). uploadTrip already does this.

    override fun registerRider(payload: JSONObject): RegisterResult {
        val req = HttpRequest("${baseUrl()}/riders", json(payload))
        return try {
            client.execute(req).let { resp ->
                val body = resp.text()
                when {
                    // store_id is client-generated, so we don't read the body on
                    // success; a non-empty 2xx is enough.
                    resp.isSuccessful && body.isNotEmpty() -> RegisterResult.Ok
                    resp.code == 429 -> RegisterResult.RateLimited
                    else -> {
                        // Surface the server's reason. FastAPI errors are
                        // {"detail":"..."} (e.g. display_name_taken, or a
                        // "display_name must be ..." validation message).
                        val detail = runCatching {
                            JSONObject(body).optString("detail").ifEmpty { null }
                        }.getOrNull()
                        RegisterResult.Failed(resp.code, detail)
                    }
                }
            }
        } catch (e: Exception) {
            RegisterResult.Failed(0, null) // network / no response
        }
    }

    override fun getCard(storeId: String): RiderCard? {
        val req = get("/riders/$storeId/card")
        return try {
            client.execute(req).let { resp ->
                if (!resp.isSuccessful) return@let null
                val o = JSONObject(resp.text())
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
        val req = get("/riders/$storeId")
        return try {
            client.execute(req).let { resp ->
                if (!resp.isSuccessful) return@let null
                val o = JSONObject(resp.text())
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
        val req = get("/riders/$storeId")
        return try {
            client.execute(req).let { resp ->
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
        val req = HttpRequest("${baseUrl()}/riders/$storeId", json(payload), method = "PATCH")
        return try {
            client.execute(req).code
        } catch (e: Exception) {
            0 // network failure: caller treats any non-2xx as an error
        }
    }

    override fun deleteRider(storeId: String): Boolean {
        val req = HttpRequest("${baseUrl()}/riders/$storeId", HttpRequest.Body.None, method = "DELETE")
        return try {
            client.execute(req).isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    override fun exportRider(storeId: String): String? {
        val req = get("/riders/$storeId/export")
        return try {
            client.execute(req).let { if (it.isSuccessful) it.text() else null }
        } catch (e: Exception) {
            null
        }
    }

    override fun getTripStatus(tripUuid: String): TripStatus? {
        val req = get("/trips/$tripUuid")
        return try {
            client.execute(req).let { resp ->
                if (!resp.isSuccessful) return@let null
                val o = JSONObject(resp.text())
                val arr = o.optJSONArray("reasons")
                TripStatus(
                    validationStatus = o.optString("validation_status").ifEmpty { null },
                    verdict = o.optString("verdict").ifEmpty { null },
                    reasons = (0 until (arr?.length() ?: 0))
                        .mapNotNull { arr?.optString(it)?.ifEmpty { null } },
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** POST /trips multipart: meta (JSON string) + trip (gzipped CSV). */
    override fun uploadTrip(metaJson: String, gzippedCsv: ByteArray): UploadResult {
        val body = HttpRequest.Body.Multipart(listOf(
            HttpRequest.Part.field("meta", metaJson),
            HttpRequest.Part("trip", gzippedCsv, fileName = "trip.csv.gz", contentType = "application/gzip"),
        ))
        val req = HttpRequest("${baseUrl()}/trips", body)
        return try {
            client.execute(req).let { resp ->
                val text = resp.text()
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
