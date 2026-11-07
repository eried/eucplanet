package com.eried.eucplanet.data.eucstats

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.sha256
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

object MetaBuilder {
    /** `yyyy-MM-dd'T'HH:mm:ss'Z'` in UTC, whole seconds, as SimpleDateFormat wrote it. */
    @OptIn(ExperimentalTime::class)
    internal fun iso(ms: Long): String = Instant.fromEpochSeconds(ms.floorDiv(1000L)).toString()

    private fun sha256Hex(b: ByteArray): String = sha256(b).toLowerHex()

    /**
     * Build the meta envelope WITHOUT the attestation field (added later).
     * [tzId] and [tzOffsetMs] are the rider's time zone (its offset at any
     * instant, from which the trip's start is read). [platform] is the store
     * the server reads the OS from: "google_play" or "apple".
     */
    fun build(
        trip: TripRecord, storeId: String, appVersion: String, osVersion: String,
        tzId: String, tzOffsetMs: (Long) -> Int, csvBytes: ByteArray,
        platform: String = "google_play",
    ): JSONObject {
        val start = trip.startTime
        val offsetMin = tzOffsetMs(start) / 60000
        val meta = JSONObject()
        meta.put("store_id", storeId)
        meta.put("platform", platform)
        meta.put("trip_uuid", trip.tripUuid)
        meta.put("source_app", "eucplanet")
        meta.put("schema_version", "eucplanet-v3-gforce")
        meta.put("start_utc", iso(start))
        meta.put("end_utc", iso(trip.endTime ?: start))
        meta.put("tz", tzId)
        meta.put("tz_offset_min", offsetMin)
        meta.put("tz_known", true)
        meta.put("is_mock_location", trip.isMockLocation)
        meta.put("app_version", appVersion)
        meta.put("os_version", osVersion)
        meta.put("sample_count", trip.sampleCount)
        meta.put("file_sha256", sha256Hex(csvBytes))
        // NOTE: distance_km_client (a float) is intentionally OMITTED. It is optional
        // per the contract and unused by the server, and floats risk client/server
        // request_hash mismatch (Python json.dumps(18.0)="18.0" vs Kotlin="18"). Keeping
        // the envelope float-free makes the canonical hash unambiguous.
        meta.put("wheel", trip.wheelMetaJson?.let { JSONObject(it) } ?: JSONObject())
        return meta
    }
}
