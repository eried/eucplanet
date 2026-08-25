package com.eried.eucplanet.amazfit

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The smallest HTTP/1.1 responder that satisfies a Zepp OS Side Service:
 * one request per connection, `Content-Length` bodies, JSON in and out,
 * `Connection: close`. Bound to the loopback address only, so nothing off the
 * phone can reach it; the Zepp app lives on the same phone.
 *
 * Deliberately built on `java.net` rather than pulling in a server library.
 * The app already ships OkHttp as a client and a WebSocket client for the
 * HUD, but no server, and a dependency for two routes is not worth its
 * weight. Pure JVM, no Android imports, so it runs under plain unit tests;
 * the caller supplies [log] so the bridge can route warnings to logcat.
 */
class AmazfitLocalServer(
    private val port: Int,
    private val handler: (Request) -> Response,
    private val log: (String) -> Unit = {}
) {
    data class Request(val method: String, val path: String, val body: String)
    data class Response(val status: Int, val body: String)

    companion object {
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val SOCKET_TIMEOUT_MS = 3_000
        private const val BACKLOG = 8
        private val KNOWN_METHODS = setOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS")
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "AmazfitHttpWorker").apply { isDaemon = true }
    }
    @Volatile private var running = false

    /** The port actually bound (useful when constructed with port 0). */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    /** Binds and starts accepting. Returns false, without throwing, when the
     *  port is taken; the caller decides whether to retry. */
    fun start(): Boolean {
        if (running) return true
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), BACKLOG)
            }
        } catch (e: IOException) {
            log("bind 127.0.0.1:$port failed: ${e.message}")
            return false
        }
        serverSocket = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "AmazfitHttpAccept").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) log("accept failed: ${e.message}")
                if (ss.isClosed) return
                continue
            }
            try {
                workers.execute { serve(client) }
            } catch (_: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = SOCKET_TIMEOUT_MS
                c.tcpNoDelay = true
                val input = BufferedInputStream(c.getInputStream())
                val request = readRequest(input)
                val response = if (request == null) {
                    Response(400, "{\"error\":\"bad request\"}")
                } else {
                    try {
                        handler(request)
                    } catch (e: Exception) {
                        log("handler failed on ${request.method} ${request.path}: ${e.message}")
                        Response(500, "{\"error\":\"internal\"}")
                    }
                }
                write(c, response)
            }
        } catch (e: IOException) {
            // A client that hung up mid-request is routine; keep serving.
            log("connection dropped: ${e.message}")
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.trim().split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')
        if (method !in KNOWN_METHODS || !path.startsWith("/")) return null
        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            if (name == "content-length") {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: return null
            }
        }
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) return null
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return Request(method, path, String(body, 0, read, Charsets.UTF_8))
    }

    /** One header line without its terminator, or null on EOF / oversize. */
    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.append(b.toChar())
            if (buf.length > MAX_LINE_BYTES) return null
        }
        return buf.toString()
    }

    private fun write(client: Socket, response: Response) {
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val head = "HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }
}
