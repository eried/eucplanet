package com.eried.eucplanet.data.eucstats

import com.eried.eucplanet.data.model.TripRecord
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MetaBuilder {
    private fun iso(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Build the meta envelope WITHOUT the attestation field (added later). */
    fun build(
        trip: TripRecord, storeId: String, appVersion: String, osVersion: String,
        tz: TimeZone, csvBytes: ByteArray,
    ): JSONObject {
        val start = trip.startTime
        val offsetMin = tz.getOffset(start) / 60000
        val meta = JSONObject()
        meta.put("store_id", storeId)
        meta.put("platform", "google_play")
        meta.put("trip_uuid", trip.tripUuid)
        meta.put("source_app", "eucplanet")
        meta.put("schema_version", "eucplanet-v3-gforce")
        meta.put("start_utc", iso(start))
        meta.put("end_utc", iso(trip.endTime ?: start))
        meta.put("tz", tz.id)
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
