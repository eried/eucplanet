package com.eried.eucplanet.data.eucstats

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EucStatsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: EucStatsApi

    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = EucStatsApi(OkHttpClient()) { server.url("/api/v1").toString().trimEnd('/') }
    }
    @After fun teardown() { server.shutdown() }

    @Test fun uploadTrip_201_returnsValidated() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"trip_uuid":"t1","validation_status":"validated","duplicate":false}"""))
        val r = api.uploadTrip("{\"store_id\":\"s\"}", ByteArray(10))
        assertTrue(r is UploadResult.Ok); r as UploadResult.Ok
        assertEquals("validated", r.validationStatus); assertFalse(r.duplicate)
    }

    @Test fun uploadTrip_duplicate200_isOk() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"duplicate":true}"""))
        assertTrue(api.uploadTrip("{}", ByteArray(1)) is UploadResult.Ok)
    }

    @Test fun uploadTrip_422_isPermanentFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("unparseable"))
        val r = api.uploadTrip("{}", ByteArray(1))
        assertTrue(r is UploadResult.PermanentFailure)
    }

    @Test fun uploadTrip_503_isRetryable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(api.uploadTrip("{}", ByteArray(1)) is UploadResult.Retry)
    }

    @Test fun getCard_parsesFields() = runBlocking {
        // Mirrors the live server contract: stats nested under "stats", ranks
        // under "ranks" (the avatar is served at /riders/{id}/avatar, no url field).
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"display_name":"Erwin","flag":"NO","country":"NO","has_avatar":true,"stats":{"total_km":1284.0,"trips":12,"best_speed_kmh":62.0,"best_gforce":3.1},"ranks":{"distance":7}}"""))
        val c = api.getCard("s")!!
        assertEquals("Erwin", c.displayName); assertEquals(7, c.mileageRank); assertEquals(12, c.trips)
        assertEquals(62.0, c.topSpeedKmh, 0.001); assertTrue(c.hasAvatar)
    }

    /** Regression: an unreachable server must NOT throw (which previously crashed
     *  the app when registering with no connectivity) — it returns safe defaults. */
    @Test fun unreachableServer_returnsSafeDefaults_neverThrows() = runBlocking {
        val dead = EucStatsApi(
            OkHttpClient.Builder()
                .connectTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
        ) { "http://127.0.0.1:1/api/v1" } // port 1: connection refused
        // A network failure is terminal (not retryable) for registration.
        assertEquals(RegisterResult.Failed, dead.registerRider(org.json.JSONObject().put("store_id", "s")))
        assertNull(dead.getCard("s"))
        assertNull(dead.getProfile("s"))
        assertNull(dead.riderExists("s"))            // "couldn't tell", not false
        assertEquals(0, dead.patchRider("s", org.json.JSONObject()))
        assertFalse(dead.deleteRider("s"))
        assertNull(dead.exportRider("s"))
        assertTrue(dead.uploadTrip("{}", ByteArray(1)) is UploadResult.Retry)
    }
}
