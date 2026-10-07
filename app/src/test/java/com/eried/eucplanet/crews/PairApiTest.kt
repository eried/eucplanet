package com.eried.eucplanet.crews

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The two calls that are the whole of the phone's part in crews, against a real socket.
 *
 * `PairLinkTest` covers what a scanned string parses into. This covers what happens next, and
 * it exists because the half of the handshake that cannot be reached on a device without a
 * linked eucstats account is exactly the half that sends the rider's store_id: `confirm`.
 * Driving the UI proved `describe` against a live server, including a genuine 410 for a code
 * that had never existed, and then stopped at "You're not on eucstats yet" — correctly, and
 * before the interesting line of code.
 *
 * The shapes asserted here are the server's, read off `web/crews_api.py` in the eucstats
 * repo: `pair/describe` answers `{purpose, scope, expires_in, grants}` and raises 410 through
 * `_perr` for an expired, used or unknown code; `pair/confirm` takes `{code, store_id}` and
 * answers 410 the same way, or 429 when either rate limit trips.
 */
class PairApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: PairApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = PairApi(
            OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A link pointing at the mock server, with the `/api/v1` the real one carries. */
    private fun link(code: String = "AB12CD") =
        PairLink(host = server.url("/").toString().trimEnd('/'), code = code)

    // --- describe -------------------------------------------------------------------------

    @Test
    fun `describe reads scope grants and expiry off the body`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"purpose":"rider","scope":"crew","expires_in":179,
                   |"grants":["crew_membership"]}""".trimMargin(),
            ),
        )
        val offer = api.describe(link())
        assertEquals("crew", offer?.scope)
        assertEquals(listOf("crew_membership"), offer?.grants)
        assertEquals(179, offer?.expiresInSeconds)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        // The code goes in the query of the URL the LINK carries, not a compiled-in base.
        assertEquals("/api/v1/pair/describe?code=AB12CD", req.path)
    }

    @Test
    fun `describe returns null on the 410 an expired code gets`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(410)
                .setBody("""{"code":"expired","detail":"That code has expired."}"""),
        )
        assertNull(api.describe(link()))
    }

    @Test
    fun `describe returns null rather than throwing when the body is not json`() = runBlocking {
        // A captive portal, a proxy error page, a truncated response: the screen shows
        // "that code's gone stale" rather than taking the whole activity down.
        server.enqueue(MockResponse().setBody("<html>504 Gateway Time-out</html>"))
        assertNull(api.describe(link()))
    }

    @Test
    fun `describe survives a body with the fields missing`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val offer = api.describe(link())
        // Defaults rather than nulls, so the confirm screen still has something to print.
        assertEquals("crew", offer?.scope)
        assertEquals(emptyList<String>(), offer?.grants)
        assertEquals(0, offer?.expiresInSeconds)
    }

    // --- confirm --------------------------------------------------------------------------

    @Test
    fun `confirm posts the code and the store_id and nothing else`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val result = api.confirm(link("ZCLZ3Z"), "sim-7-000")
        assertEquals(PairResult.Ok, result)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/pair/confirm", req.path)
        assertTrue(
            "content type should be json, was ${req.getHeader("Content-Type")}",
            req.getHeader("Content-Type")?.startsWith("application/json") == true,
        )
        val sent = JSONObject(req.body.readUtf8())
        assertEquals("ZCLZ3Z", sent.getString("code"))
        assertEquals("sim-7-000", sent.getString("store_id"))
        // The payload is exactly two fields. Anything else here would be a rider id leaving
        // the phone in a shape the server never asked for.
        assertEquals(2, sent.length())
    }

    @Test
    fun `confirm maps 410 to expired`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(410)
                .setBody("""{"code":"used","detail":"That code has been used."}"""),
        )
        assertEquals(PairResult.Expired, api.confirm(link(), "sim-7-000"))
    }

    @Test
    fun `confirm maps 429 to rate limited`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"detail":"rate_limited:pair_confirm"}"""),
        )
        assertEquals(PairResult.RateLimited, api.confirm(link(), "sim-7-000"))
    }

    @Test
    fun `confirm carries the servers own detail through on any other failure`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"detail":"code and store_id are required"}"""),
        )
        val r = api.confirm(link(), "sim-7-000")
        assertEquals(PairResult.Failed(400, "code and store_id are required"), r)
    }

    @Test
    fun `confirm reports a failure with no readable detail rather than inventing one`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
            assertEquals(PairResult.Failed(500, null), api.confirm(link(), "sim-7-000"))
        }

    @Test
    fun `confirm reports unreachable when there is nothing on the other end`() = runBlocking {
        val dead = PairLink(host = server.url("/").toString().trimEnd('/'), code = "AB12CD")
        server.shutdown()
        assertEquals(PairResult.Unreachable, api.confirm(dead, "sim-7-000"))
    }

    @Test
    fun `describe reports nothing when there is nothing on the other end`() = runBlocking {
        val dead = link()
        server.shutdown()
        assertNull(api.describe(dead))
    }
}
