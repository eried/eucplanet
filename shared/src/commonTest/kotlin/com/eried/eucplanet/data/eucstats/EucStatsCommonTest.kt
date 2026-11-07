package com.eried.eucplanet.data.eucstats

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.platform.HttpRequest
import com.eried.eucplanet.platform.JSONArray
import com.eried.eucplanet.platform.JSONNull
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.crc32
import com.eried.eucplanet.platform.gzip
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The leaderboard envelope on both phones: the request hash the server
 * recomputes, the timestamps and the gzip it unpacks. Runs on the JVM and on
 * the iOS simulator, so the iPhone signs the same bytes Android does.
 */
class EucStatsCommonTest {

    @Test fun requestHashMatchesPythonVectors() {
        // python: json.dumps(o, sort_keys=True, separators=(",",":"), ensure_ascii=False)
        assertEquals(
            "d3626ac30a87e6f7a6428233b3c68299976865fa5508e4267c5415c76af7a772",
            CanonicalJson.requestHash(JSONObject().put("b", 1).put("a", 2)),
        )
        // {"k":[1,true,null],"n":"Ærø\u0001\"\\"}
        val o = JSONObject()
            .put("n", "Ærø\u0001\"\\")
            .put("k", JSONArray().put(1).put(true).put(JSONNull))
            .put("attestation", JSONObject().put("token", "x"))
        assertEquals(
            "b3929989d9b63055ac3dad9060fcb49c69e51b804d2d2199db2ee7a05104d544",
            CanonicalJson.requestHash(o),
        )
    }

    @Test fun isoIsWholeSecondsUtc() {
        assertEquals("2024-06-01T20:24:31Z", MetaBuilder.iso(1_717_273_471_204L))
        assertEquals("1970-01-01T00:00:00Z", MetaBuilder.iso(0L))
    }

    @Test fun envelopeCarriesThePlatformAndOffset() {
        val trip = TripRecord(
            id = 1, startTime = 1_717_273_471_000L, endTime = 1_717_276_202_000L,
            fileName = "t.csv", tripUuid = "u", sampleCount = 3,
        )
        val meta = MetaBuilder.build(
            trip, storeId = "s", appVersion = "1", osVersion = "iOS 18",
            tzId = "Europe/Oslo", tzOffsetMs = { 7_200_000 }, csvBytes = "a".encodeToByteArray(),
            platform = "apple",
        )
        assertEquals("apple", meta.getString("platform"))
        assertEquals(120, meta.getInt("tz_offset_min"))
        assertEquals("Europe/Oslo", meta.getString("tz"))
        assertEquals("2024-06-01T21:10:02Z", meta.getString("end_utc"))
        // sha256("a")
        assertEquals("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb", meta.getString("file_sha256"))
    }

    @Test fun gzipHasTheHeaderAndTrailerReadersCheck() {
        val data = "Date,Speed\n01.06.2026 20:24:31.204,5\n".repeat(50).encodeToByteArray()
        val gz = gzip(data)
        assertEquals(0x1f, gz[0].toInt() and 0xFF)
        assertEquals(0x8b, gz[1].toInt() and 0xFF)
        assertEquals(8, gz[2].toInt())
        fun le32(at: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((gz[at + i].toLong() and 0xFF) shl (8 * i)) }
        assertEquals(crc32(data), le32(gz.size - 8))
        assertEquals(data.size.toLong(), le32(gz.size - 4))
    }

    @Test fun requestVerbs() {
        assertEquals("GET", HttpRequest("u", HttpRequest.Body.None).verb)
        assertEquals("POST", HttpRequest("u", HttpRequest.Body.Text("text/plain", "x")).verb)
        assertEquals("DELETE", HttpRequest("u", HttpRequest.Body.None, method = "DELETE").verb)
    }
}
