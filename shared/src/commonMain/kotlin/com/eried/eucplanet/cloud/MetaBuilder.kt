package com.eried.eucplanet.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the EucStats trip `meta` envelope — a port of Android's `MetaBuilder`,
 * kept pure (all platform-specific values are passed in) so it's fully unit-
 * testable. The server validates `file_sha256` against the uploaded CSV.
 *
 * NOTE: `distance_km_client` is intentionally omitted (optional + float → risks a
 * client/server canonical-hash mismatch), exactly like the Android builder.
 */
object MetaBuilder {
    private val JSON = Json { ignoreUnknownKeys = true }

    fun build(
        storeId: String,
        tripUuid: String,
        startMs: Long,
        endMs: Long,
        sampleCount: Int,
        csvBytes: ByteArray,
        wheelJson: String = "{}",
        platform: String = "ios",
        appVersion: String = "0.1",
        osVersion: String = "iOS",
        tzId: String = "UTC",
        tzOffsetMin: Int = 0,
        tzKnown: Boolean = false,
        isMock: Boolean = false,
    ): String {
        val wheel = runCatching { JSON.parseToJsonElement(wheelJson) }.getOrNull()
        val obj = buildJsonObject {
            put("store_id", storeId)
            put("platform", platform)
            put("trip_uuid", tripUuid)
            put("source_app", "eucplanet")
            put("schema_version", "eucplanet-v3-gforce")
            put("start_utc", isoUtc(startMs))
            put("end_utc", isoUtc(endMs))
            put("tz", tzId)
            put("tz_offset_min", tzOffsetMin)
            put("tz_known", tzKnown)
            put("is_mock_location", isMock)
            put("app_version", appVersion)
            put("os_version", osVersion)
            put("sample_count", sampleCount)
            put("file_sha256", sha256Hex(csvBytes))
            if (wheel is JsonObject) put("wheel", wheel) else put("wheel", buildJsonObject {})
        }
        return JSON.encodeToString(JsonObject.serializer(), obj)
    }
}

/** Epoch-millis → `yyyy-MM-dd'T'HH:mm:ss'Z'` (UTC). Pure Kotlin (no java.time /
 *  NSDate), so it's identical on Android + iOS. Ride times are always positive. */
fun isoUtc(ms: Long): String {
    val days = ms / 86_400_000L
    val msOfDay = ms % 86_400_000L
    // Howard Hinnant's civil-from-days.
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    val hh = (msOfDay / 3_600_000L).toInt()
    val mi = ((msOfDay / 60_000L) % 60).toInt()
    val ss = ((msOfDay / 1000L) % 60).toInt()
    fun p(n: Long) = n.toString().padStart(2, '0')
    fun pi(n: Int) = n.toString().padStart(2, '0')
    return "$year-${p(m)}-${p(d)}T${pi(hh)}:${pi(mi)}:${pi(ss)}Z"
}
