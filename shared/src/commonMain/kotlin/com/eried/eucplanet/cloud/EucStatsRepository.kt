package com.eried.eucplanet.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Orchestrates EucStats: rider registration, leaderboard card, and trip upload
 * (meta + stub attestation + gzipped CSV). A thin port of Android's
 * `EucStatsRepository` for the iOS dev path — no Play Integrity (stub token).
 * Lives in `com.eried.eucplanet.cloud` to avoid the Android FQN.
 */
class EucStatsRepository(private val client: EucStatsClient = EucStatsClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Register/refresh a rider. [storeId] is generated once (see [uuid4]) and
     *  persisted by the caller; re-registering an existing id is allowed.
     *  [avatarBase64] is an optional 256×256 PNG (base64) — see the avatar picker. */
    suspend fun register(storeId: String, displayName: String, flag: String, avatarBase64: String? = null): RegisterResult {
        val payload = buildJsonObject {
            put("store_id", storeId)
            put("platform", "ios")
            put("display_name", displayName)
            put("flag", flag)
            put("consent_public", true)
            if (!avatarBase64.isNullOrBlank()) put("avatar_png_base64", avatarBase64)
        }
        return client.registerRider(withStubAttestation(json.encodeToString(JsonObject.serializer(), payload)))
    }

    /** Leaderboard card: lifetime stats + ranks. */
    suspend fun card(storeId: String): RiderCard? = client.getCard(storeId)

    suspend fun riderExists(storeId: String): Boolean? = client.riderExists(storeId)

    /** Full profile + change cooldowns (GET /riders/{id}). */
    suspend fun profile(storeId: String): RiderProfile? = client.getProfile(storeId)

    /** Edit profile; pass only the fields being changed (null = leave as-is). */
    suspend fun updateProfile(storeId: String, displayName: String?, flag: String?, avatarBase64: String?): EditResult {
        val payload = buildJsonObject {
            if (displayName != null) put("display_name", displayName)
            if (flag != null) put("flag", flag)
            if (!avatarBase64.isNullOrBlank()) put("avatar_png_base64", avatarBase64)
        }
        return client.patchProfile(storeId, json.encodeToString(JsonObject.serializer(), payload))
    }

    /** Permanently delete the rider + all uploaded data. */
    suspend fun deleteAccount(storeId: String): Boolean = client.deleteRider(storeId)

    /** GDPR export: the server's JSON dump of all rider data. */
    suspend fun exportData(storeId: String): String? = client.exportData(storeId)

    /** Build meta (+ stub attestation), gzip the CSV, upload. */
    suspend fun upload(
        storeId: String,
        tripUuid: String,
        startMs: Long,
        endMs: Long,
        sampleCount: Int,
        csv: String,
        wheelJson: String = "{}",
    ): UploadResult {
        val csvBytes = csv.encodeToByteArray()
        val meta = MetaBuilder.build(storeId, tripUuid, startMs, endMs, sampleCount, csvBytes, wheelJson)
        return client.uploadTrip(withStubAttestation(meta), gzip(csvBytes))
    }
}
