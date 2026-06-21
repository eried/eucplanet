package com.eried.eucplanet.hudlink

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phone-side HUD dialer — the iOS counterpart to Android's `HudServer`. Opens a
 * WebSocket to `ws://<host>:<port>/state`, streams a [HudState] JSON frame every
 * 200 ms (5 Hz), decodes [HudCommand] frames the HUD sends back, and reconnects
 * with backoff. Pure Kotlin Multiplatform (Ktor client) — no platform deps, so
 * Android could reuse it too, but Android keeps its own OkHttp-based dialer.
 *
 * @param snapshot  builds the current frame on demand (called at 5 Hz).
 * @param onCommand routed a decoded HUD button intent (light / horn / ...).
 */
class HudClient(
    private val scope: CoroutineScope,
    private val snapshot: () -> HudState,
    private val onCommand: (HudCommand) -> Unit,
) {
    enum class Status { Disabled, Connecting, Connected, Error }

    private val _status = MutableStateFlow(Status.Disabled)
    val status: StateFlow<Status> = _status

    // Match HudServer's Json: tolerate unknown keys + NaN floats (no-GPS frames).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    private var client: HttpClient? = null
    private var job: Job? = null
    private var host = ""
    private var port = HudDiscovery.DEFAULT_PORT

    /** Start (or retarget) the link. Idempotent for an unchanged host/port. */
    fun start(host: String, port: Int) {
        val h = host.trim()
        if (job != null && h == this.host && port == this.port) return
        stop()
        if (h.isEmpty()) { _status.value = Status.Disabled; return }
        this.host = h
        this.port = if (port in 1..65535) port else HudDiscovery.DEFAULT_PORT
        client = HttpClient { install(WebSockets) }
        job = scope.launch { dialLoop() }
    }

    fun stop() {
        job?.cancel(); job = null
        try { client?.close() } catch (_: Throwable) {}
        client = null
        _status.value = Status.Disabled
    }

    private suspend fun dialLoop() {
        var attempt = 0
        while (true) {
            _status.value = Status.Connecting
            val clean = try {
                streamOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
            if (clean) {
                attempt = 0
            } else {
                _status.value = Status.Error
                delay(backoff(attempt++))
            }
        }
    }

    /** One WebSocket session: pump frames out at 5 Hz, read commands in, until
     *  it closes. Returns true on a clean close, false on transport error. */
    private suspend fun streamOnce(): Boolean {
        val c = client ?: return false
        c.webSocket(host = host, port = port, path = HudDiscovery.PATH_STATE) {
            _status.value = Status.Connected
            val sender = launch {
                while (isActive) {
                    send(Frame.Text(json.encodeToString(snapshot())))
                    delay(PUBLISH_INTERVAL_MS)
                }
            }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val cmd = try { json.decodeFromString<HudCommand>(text) } catch (_: Throwable) { null }
                        if (cmd != null) onCommand(cmd)
                    }
                }
            } finally {
                sender.cancel()
            }
        }
        return true
    }

    private fun backoff(attempt: Int): Long =
        (BACKOFF_MIN_MS shl attempt.coerceAtMost(3)).coerceAtMost(BACKOFF_MAX_MS)

    companion object {
        private const val PUBLISH_INTERVAL_MS = 200L
        private const val BACKOFF_MIN_MS = 1_000L
        private const val BACKOFF_MAX_MS = 5_000L
    }
}
