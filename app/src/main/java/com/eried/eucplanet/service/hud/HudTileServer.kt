package com.eried.eucplanet.service.hud

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Hands the HUD map pictures from the phone's offline map. The HUD fetches
 * its own tiles over Wi-Fi; with no signal it asks the phone it is paired
 * with, `GET /tiles/z/x/y.png` on [port], and gets a PNG or a 404.
 *
 * Plain `java.net` like the Amazfit server, one request per connection. It
 * listens on every interface because the HUD reaches the phone over the
 * hotspot, and it serves nothing but map pictures. Runs only while the HUD
 * link is on.
 */
class HudTileServer(
    private val port: Int,
    private val tile: (z: Int, x: Int, y: Int) -> ByteArray?,
    private val log: (String) -> Unit = {},
) {
    private var socket: ServerSocket? = null
    private var workers = Executors.newFixedThreadPool(WORKERS) { r -> Thread(r, "HudTileWorker").apply { isDaemon = true } }
    @Volatile private var running = false

    val boundPort: Int get() = socket?.localPort ?: -1

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), BACKLOG)
            }
        } catch (e: IOException) {
            log("tile server bind $port failed: ${e.message}")
            return false
        }
        if (workers.isShutdown) {
            workers = Executors.newFixedThreadPool(WORKERS) { r -> Thread(r, "HudTileWorker").apply { isDaemon = true } }
        }
        socket = ss
        running = true
        Thread({ accept(ss) }, "HudTileAccept").apply { isDaemon = true; start() }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        workers.shutdownNow()
    }

    private fun accept(ss: ServerSocket) {
        while (running) {
            val c = try { ss.accept() } catch (e: IOException) { if (ss.isClosed) return else continue }
            try {
                workers.execute { serve(c) }
            } catch (_: RejectedExecutionException) {
                runCatching { c.close() }
            }
        }
    }

    private fun serve(c: Socket) {
        try {
            c.use {
                it.soTimeout = TIMEOUT_MS
                val line = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.US_ASCII)).readLine().orEmpty()
                val body = parse(line)?.let { (z, x, y) -> runCatching { tile(z, x, y) }.getOrNull() }
                val out = it.getOutputStream()
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                } else {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.write(body)
                }
                out.flush()
            }
        } catch (e: IOException) {
            log("tile request dropped: ${e.message}")
        }
    }

    companion object {
        private const val WORKERS = 2
        private const val BACKLOG = 8
        private const val TIMEOUT_MS = 5_000

        private val PATH = Regex("""GET /tiles/(\d{1,2})/(\d{1,7})/(\d{1,7})\.png(\?\S*)? HTTP/1\.[01]""")

        /** z, x and y from a request line, or null for anything else. */
        fun parse(requestLine: String): Triple<Int, Int, Int>? {
            val m = PATH.matchEntire(requestLine.trim()) ?: return null
            val z = m.groupValues[1].toInt()
            val x = m.groupValues[2].toInt()
            val y = m.groupValues[3].toInt()
            val n = 1 shl z.coerceAtMost(30)
            return if (z <= 22 && x < n && y < n) Triple(z, x, y) else null
        }
    }
}
