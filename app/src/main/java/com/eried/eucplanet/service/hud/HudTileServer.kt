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
 * link is on. What a request and a response look like is [HudTileHttp]'s,
 * shared with the iPhone's listener.
 */
class HudTileServer(
    private val port: Int,
    private val tile: (z: Int, x: Int, y: Int) -> ByteArray?,
    private val log: (String) -> Unit = {},
) : HudTiles {
    private var socket: ServerSocket? = null
    private var workers = Executors.newFixedThreadPool(WORKERS) { r -> Thread(r, "HudTileWorker").apply { isDaemon = true } }
    @Volatile private var running = false

    val boundPort: Int get() = socket?.localPort ?: -1

    @Synchronized
    override fun start(): Boolean {
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
    override fun stop() {
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
                val body = HudTileHttp.parse(line)?.let { (z, x, y) -> runCatching { tile(z, x, y) }.getOrNull() }
                val out = it.getOutputStream()
                out.write(HudTileHttp.head(body?.size).toByteArray(Charsets.US_ASCII))
                if (body != null) out.write(body)
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
    }
}
