package com.eried.eucplanet.data.eucstats

import com.eried.eucplanet.data.model.TripRecord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class MetaBuilderTest {
    private val trip = TripRecord(
        id = 1, startTime = 1_717_273_471_000L, endTime = 1_717_276_202_000L,
        fileName = "trip_x.csv", distanceKm = 18.59f, tripUuid = "uuid-1",
        isMockLocation = false, sampleCount = 2731,
        wheelMetaJson = """{"brand":"Begode","model":"Master","serial":"F4E0","ble_mac":"F4:E0","ble_name":"GW","firmware":"1.07"}""",
    )

    @Test fun buildsEnvelopeWithAllRequiredFields() {
        val csv = "Date,Speed\n01.06.2026 20:24:31.204,5\n".toByteArray()
        val meta = MetaBuilder.build(
            trip, storeId = "store-1", appVersion = "3.4.1", osVersion = "16",
            tz = TimeZone.getTimeZone("Europe/Oslo"), csvBytes = csv,
        )
        assertEquals("store-1", meta.getString("store_id"))
        assertEquals("google_play", meta.getString("platform"))
        assertEquals("uuid-1", meta.getString("trip_uuid"))
        assertEquals("eucplanet", meta.getString("source_app"))
        assertEquals("eucplanet-v3-gforce", meta.getString("schema_version"))
        assertTrue(meta.getString("start_utc").endsWith("Z"))
        assertEquals(true, meta.getBoolean("tz_known"))
        assertEquals(false, meta.getBoolean("is_mock_location"))
        assertEquals(2731, meta.getInt("sample_count"))
        assertEquals(64, meta.getString("file_sha256").length) // hex sha256
        assertEquals("Begode", meta.getJSONObject("wheel").getString("brand"))
        assertEquals("Master", meta.getJSONObject("wheel").getString("model"))
    }
}
