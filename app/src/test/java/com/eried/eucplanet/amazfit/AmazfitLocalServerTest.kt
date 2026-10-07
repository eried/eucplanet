package com.eried.eucplanet.amazfit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AmazfitLocalServerTest {

    private lateinit var server: AmazfitLocalServer
    private val lastRequest = AtomicReference<AmazfitLocalServer.Request?>(null)

    @Before
    fun start() {
        server = AmazfitLocalServer(0, { req ->
            lastRequest.set(req)
            when (req.path) {
                "/state" -> AmazfitLocalServer.Response(200, """{"k":"state","c":true}""")
                "/control" -> AmazfitLocalServer.Response(200, """{"ok":true,"echo":${req.body.length}}""")
                "/boom" -> throw IllegalStateException("handler blew up")
                else -> AmazfitLocalServer.Response(404, """{"error":"not found"}""")
            }
        })
        assertTrue("bind on an ephemeral port", server.start())
        assertTrue(server.boundPort > 0)
    }

    @After
    fun stop() {
        server.stop()
    }

    private fun open(path: String, method: String = "GET", body: String? = null): HttpURLConnection {
        val c = URL("http://127.0.0.1:${server.boundPort}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 2_000
        c.readTimeout = 2_000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c
    }

    private fun HttpURLConnection.bodyText(): String =
        (if (responseCode >= 400) errorStream else inputStream).bufferedReader().use { it.readText() }

    @Test
    fun `GET returns the handler body as JSON`() {
        val c = open("/state")
        assertEquals(200, c.responseCode)
        assertTrue(c.contentType.startsWith("application/json"))
        assertEquals("""{"k":"state","c":true}""", c.bodyText())
        assertEquals("GET", lastRequest.get()!!.method)
        assertEquals("/state", lastRequest.get()!!.path)
    }

    @Test
    fun `POST delivers the body to the handler`() {
        val c = open("/control", "POST", """{"cmd":"horn"}""")
        assertEquals(200, c.responseCode)
        assertEquals("""{"ok":true,"echo":14}""", c.bodyText())
        assertEquals("""{"cmd":"horn"}""", lastRequest.get()!!.body)
    }

    @Test
    fun `query strings are stripped from the path`() {
        val c = open("/state?t=123")
        assertEquals(200, c.responseCode)
        assertEquals("/state", lastRequest.get()!!.path)
    }

    @Test
    fun `unknown paths get the handler's 404`() {
        val c = open("/nope")
        assertEquals(404, c.responseCode)
        assertEquals("""{"error":"not found"}""", c.bodyText())
    }

    @Test
    fun `a throwing handler answers 500 and the server keeps going`() {
        assertEquals(500, open("/boom").responseCode)
        assertEquals(200, open("/state").responseCode)
    }

    @Test
    fun `garbage on the socket does not take the server down`() {
        Socket("127.0.0.1", server.boundPort).use { s ->
            s.getOutputStream().write("this is not http\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val reply = s.getInputStream().bufferedReader().readLine()
            assertTrue("got: $reply", reply.startsWith("HTTP/1.1 400"))
        }
        Socket("127.0.0.1", server.boundPort).use { s ->
            s.getOutputStream().write("GET\r\n".toByteArray())
            s.getOutputStream().flush()
            val reply = s.getInputStream().bufferedReader().readLine()
            assertTrue("got: $reply", reply.startsWith("HTTP/1.1 400"))
        }
        assertEquals(200, open("/state").responseCode)
    }

    @Test
    fun `concurrent polls all succeed`() {
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..8).map { pool.submit<Int> { open("/state").responseCode } }
        val codes = futures.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(List(8) { 200 }, codes)
    }

    @Test
    fun `binding a taken port reports false instead of throwing`() {
        val second = AmazfitLocalServer(server.boundPort, { AmazfitLocalServer.Response(200, "{}") })
        assertEquals(false, second.start())
    }
}
