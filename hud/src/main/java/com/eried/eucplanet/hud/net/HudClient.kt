package com.eried.eucplanet.hud.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.eried.eucplanet.hud.protocol.HudCommand
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.hud.protocol.HudState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * HUD-side client of the phone's [com.eried.eucplanet.service.hud.HudServer].
 *
 * Discovers the phone via mDNS on the WiFi hotspot subnet, opens a long-lived
 * SSE connection to /state, and exposes the decoded [HudState] as a StateFlow
 * the Compose UI collects. Reconnects with bounded backoff when the hotspot
 * blips or the rider walks out of range.
 *
 * Owned by the [com.eried.eucplanet.hud.HudActivity] (single-Activity app);
 * created in onCreate, stopped in onDestroy. No service is needed because the
 * HUD app is always the foreground app on the HUD device — there's no other
 * process competing for it.
 */
class HudClient(private val context: Context) {

    companion object {
        private const val TAG = "HudClient"
        private const val MULTICAST_LOCK_TAG = "eucplanet-hud-discovery"
        // Backoff: 1s, 2s, 4s, capped at 5s. Below 5s the rider gets a fresh
        // try every time they walk back into range; above it the HUD feels
        // stuck.
        private const val BACKOFF_MIN_MS = 1_000L
        private const val BACKOFF_MAX_MS = 5_000L
    }

    /** Connection / pairing state surfaced to the UI status banner. */
    enum class Status { SEARCHING, PAIRED, DISCONNECTED }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http: OkHttpClient = OkHttpClient.Builder()
        // SSE is infinite; disable the read timeout so the stream isn't
        // killed during a wheel-paused gap in updates.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val sseFactory = EventSources.createFactory(http)

    private val _state = MutableStateFlow(HudState())
    val state: StateFlow<HudState> = _state.asStateFlow()

    private val _status = MutableStateFlow(Status.SEARCHING)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Host:port the SSE stream is currently connected to, null when not paired. */
    private val _peer = MutableStateFlow<String?>(null)
    val peer: StateFlow<String?> = _peer.asStateFlow()

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var sse: EventSource? = null
    private var sseJob: Job? = null
    private var hudId: String = ""
    private var hudVersion: String = ""
    /** Test override: if non-null, skip mDNS and connect directly to this
     *  `host:port`. Set via a system property at boot; see HudActivity. */
    private var fixedPeer: String? = null
    /** Rider-entered manual peer override, persisted via [HudPeerStore]. When
     *  present, supersedes mDNS so the HUD works even on hotspots that filter
     *  multicast or enforce client isolation against discovery traffic. */
    private val _manualPeer = MutableStateFlow<String?>(null)
    val manualPeer: StateFlow<String?> = _manualPeer.asStateFlow()

    fun start(hudId: String, hudVersion: String, fixedPeer: String? = null, manualPeer: String? = null) {
        this.hudId = hudId
        this.hudVersion = hudVersion
        this.fixedPeer = fixedPeer
        _manualPeer.value = manualPeer?.takeIf { it.isNotBlank() }
        scope.launch { discoverAndConnect() }
    }

    /** Update the rider-entered manual peer. Pass null/blank to clear and fall
     *  back to mDNS. The active SSE is dropped so the outer loop reconnects
     *  against the new address without waiting out a backoff window. */
    fun setManualPeer(peer: String?) {
        val cleaned = peer?.trim()?.takeIf { it.isNotBlank() }
        if (_manualPeer.value == cleaned) return
        _manualPeer.value = cleaned
        try { sse?.cancel() } catch (_: Throwable) {}
    }

    fun stop() {
        try { sse?.cancel() } catch (_: Throwable) {}
        sse = null
        sseJob?.cancel(); sseJob = null
        try { jmdns?.close() } catch (_: Throwable) {}
        jmdns = null
        try { multicastLock?.release() } catch (_: Throwable) {}
        multicastLock = null
    }

    fun send(cmd: HudCommand) {
        val baseUrl = _peer.value ?: return
        scope.launch {
            try {
                val req = Request.Builder()
                    .url("http://$baseUrl${HudDiscovery.PATH_COMMAND}")
                    .post(json.encodeToString(cmd).toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "command ${cmd::class.simpleName} -> ${resp.code}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "command ${cmd::class.simpleName} failed: ${t.message}")
            }
        }
    }

    private suspend fun discoverAndConnect() {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }

        var attempt = 0
        while (scope.isActiveScope()) {
            // Precedence: debug system-prop > rider-set manual IP > mDNS.
            // The manual IP is the rider's escape hatch when the hotspot
            // blocks discovery; trust it on every attempt so they don't have
            // to relaunch after editing.
            val peerUrl = fixedPeer ?: _manualPeer.value ?: try {
                resolvePeer()
            } catch (t: Throwable) {
                Log.w(TAG, "discovery failed: ${t.message}")
                null
            }
            if (peerUrl == null) {
                _status.value = Status.SEARCHING
                val wait = backoff(attempt++)
                Log.i(TAG, "no phone found, retry in ${wait}ms")
                delay(wait)
                continue
            }
            attempt = 0
            _peer.value = peerUrl
            // DON'T flip status to PAIRED here. We only know we're paired
            // once the SSE handshake completes, which streamUntilClosed
            // signals via the onOpen callback (look for the call to
            // _status.value = Status.PAIRED there). Premature PAIRED
            // status hides the "Phone disconnected" banner during attempts
            // that ultimately fail, leaving the rider with no on-screen
            // signal that anything is wrong. Stay in SEARCHING until SSE
            // actually opens.
            _status.value = Status.SEARCHING
            // Re-send the Pair command on every fresh connection so the phone
            // log shows the HUD reappearing after a hotspot blip.
            send(HudCommand.Pair(hudId, hudVersion))
            val sseClosedCleanly = streamUntilClosed(peerUrl)
            _status.value = Status.DISCONNECTED
            _peer.value = null
            if (!sseClosedCleanly) {
                delay(backoff(attempt++))
            }
        }
    }

    /** Open SSE to peerUrl and pump frames into [_state]. Returns when the
     *  stream ends; true if it closed cleanly, false on transport error. */
    private suspend fun streamUntilClosed(peerUrl: String): Boolean {
        var cleanClose = false
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val req = Request.Builder()
            .url("http://$peerUrl${HudDiscovery.PATH_STATE}")
            .header("Accept", "text/event-stream")
            .build()
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "SSE open: $peerUrl")
                // Now that the server has accepted us we can claim PAIRED.
                // discoverAndConnect stays in SEARCHING during the attempt;
                // this is the single point where we flip.
                _status.value = Status.PAIRED
            }
            override fun onEvent(
                eventSource: EventSource, id: String?, type: String?, data: String
            ) {
                try {
                    val frame = json.decodeFromString<HudState>(data)
                    if (frame.protocolVersion in 1..HudState.PROTOCOL_VERSION) {
                        _state.value = frame
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "decode failed: ${t.message}")
                }
            }
            override fun onClosed(eventSource: EventSource) {
                cleanClose = true
                Log.i(TAG, "SSE closed: $peerUrl")
                done.complete(Unit)
            }
            override fun onFailure(
                eventSource: EventSource, t: Throwable?, response: Response?
            ) {
                Log.w(TAG, "SSE failure: ${t?.message} (${response?.code})")
                done.complete(Unit)
            }
        }
        sse = sseFactory.newEventSource(req, listener)
        done.await()
        try { sse?.cancel() } catch (_: Throwable) {}
        sse = null
        return cleanClose
    }

    private suspend fun resolvePeer(): String? {
        val md = JmDNS.create().also { jmdns = it }
        val resolved = kotlinx.coroutines.CompletableDeferred<String?>()
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // Request a resolve; the actual address arrives via serviceResolved.
                md.requestServiceInfo(event.type, event.name, 1_000L)
            }
            override fun serviceRemoved(event: ServiceEvent) {}
            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val ipv4 = info.inet4Addresses?.firstOrNull()
                if (ipv4 != null) {
                    val versionOk = (info.getPropertyString(HudDiscovery.TXT_VERSION)
                        ?.toIntOrNull() ?: 1) <= HudState.PROTOCOL_VERSION
                    if (versionOk) {
                        resolved.complete("${ipv4.hostAddress}:${info.port}")
                    }
                }
            }
        }
        md.addServiceListener(HudDiscovery.SERVICE_TYPE, listener)

        // Most hotspots resolve in well under a second, but give a generous
        // ceiling so a flaky multicast path doesn't immediately fall back.
        val winner = kotlinx.coroutines.withTimeoutOrNull(5_000L) { resolved.await() }
        try { md.removeServiceListener(HudDiscovery.SERVICE_TYPE, listener) } catch (_: Throwable) {}
        return winner
    }

    private fun backoff(attempt: Int): Long {
        val expanded = BACKOFF_MIN_MS shl attempt.coerceAtMost(3)
        return expanded.coerceAtMost(BACKOFF_MAX_MS)
    }

    private fun CoroutineScope.isActiveScope(): Boolean = coroutineContext[Job]?.isActive == true
}

