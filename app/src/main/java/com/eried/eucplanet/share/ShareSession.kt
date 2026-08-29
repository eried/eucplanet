package com.eried.eucplanet.share

import android.util.Log
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.data.eucstats.EucStatsApiContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class Identity(val mode: IdentityMode, val name: String, val color: String, val icon: String?,
                    val avatarUrl: String?, val flag: String?, val shareStats: Boolean)

data class PeerState(val last: SharePayload, val lastSeenMs: Long, val trail: Trail,
                     val freshness: Freshness, val left: Boolean)

sealed class ShareState {
    object Idle : ShareState()
    data class Joined(val link: ShareLink, val me: Identity, val peers: Map<String, PeerState>,
                      val connected: Boolean, val error: String?) : ShareState()
}

@Singleton
class ShareSession @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val wheelRepository: WheelRepository,
    private val syncManager: SyncManager,
    private val eucStatsApi: EucStatsApiContract,
) {
    companion object {
        private const val TAG = "ShareSession"
        const val PUBLISH_INTERVAL_MS = 3_000L
        const val PUBLISH_MOVE_M = 10.0
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private val _state = MutableStateFlow<ShareState>(ShareState.Idle)
    val state: StateFlow<ShareState> = _state.asStateFlow()

    private var ws: WebSocket? = null
    private var key: ByteArray? = null
    private val myId = ShareCrypto.b64u(ShareCrypto.randomBytes(8))
    private var lastPubMs = 0L
    private var lastLat = Double.NaN; private var lastLng = Double.NaN
    private val colorByPeer = HashMap<String, String>()
    private var closing = false
    /** Bumped on every connect and on leave. A socket whose generation is no
     *  longer the current one is a leftover: its callbacks are ignored, so a
     *  dying socket from the room the rider just left cannot take over [ws]
     *  and send this rider's position to the wrong room. */
    private var connectGen = 0
    /** Heartbeat that keeps publishing while joined. See [startHeartbeat]. */
    private var heartbeat: Job? = null

    suspend fun resolveDefaultIdentity(): Identity {
        val s = settingsRepository.get()
        val mode = runCatching { IdentityMode.valueOf(s.share.lastIdentityMode) }.getOrDefault(IdentityMode.ANON)
        return identityFor(mode, s.share.lastSessionName, s.share.shareStatsDefault)
    }

    suspend fun identityFor(mode: IdentityMode, sessionName: String, shareStats: Boolean): Identity {
        // The local rider is never a peer and must not consume an index from
        // the arrival-order sequence peers are colored from (see PeerPalette).
        // It always takes the last palette entry, yellow, so peer index 0
        // never collides with "me" on this screen.
        val myColor = PeerPalette.colorFor(PeerPalette.COLORS.size - 1)
        return when (mode) {
            IdentityMode.ANON -> Identity(mode, "Rider #${(1000..9999).random()}", myColor, null, null, null, shareStats)
            IdentityMode.SESSION -> Identity(mode, sessionName.ifBlank { "Rider" }, myColor, null, null, null, shareStats)
            IdentityMode.PROFILE -> {
                val storeId = syncManager.readRiderIdFile()
                val p = storeId?.let { runCatching { eucStatsApi.getProfile(it) }.getOrNull() }
                if (p == null) identityFor(IdentityMode.SESSION, sessionName, shareStats)
                else Identity(mode, p.displayName ?: "Rider", myColor, null, p.avatarUrl, p.flag, shareStats)
            }
        }
    }

    /** Create a new room and join it as the first rider. */
    suspend fun start(identity: Identity): ShareLink {
        val link = ShareLinks.newLink()
        join(link, identity)
        return link
    }

    suspend fun join(link: ShareLink, identity: Identity) {
        leave()
        closing = false
        key = ShareCrypto.deriveKey(link.key)
        settingsRepository.update { it.copy(share = it.share.copy(
            lastIdentityMode = identity.mode.name,
            lastSessionName = if (identity.mode == IdentityMode.SESSION) identity.name else it.share.lastSessionName)) }
        _state.value = ShareState.Joined(link, identity, emptyMap(), connected = false, error = null)
        connect(link)
        startHeartbeat()
    }

    private suspend fun connect(link: ShareLink) {
        val gen = ++connectGen
        val relay = settingsRepository.get().share.relayUrl.trimEnd('/')
        val req = Request.Builder().url("$relay/ws/${link.roomId}").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: okhttp3.Response) {
                // A socket that opened after the rider moved on belongs to the
                // room they left. Shut it rather than let it publish there.
                if (gen != connectGen) { w.close(1000, "superseded"); return }
                update { it.copy(connected = true, error = null) }; lastPubMs = 0L; publishTick()
            }
            override fun onMessage(w: WebSocket, text: String) { if (gen == connectGen) onFrame(text) }
            override fun onFailure(w: WebSocket, t: Throwable, r: okhttp3.Response?) {
                if (gen != connectGen) return
                update { it.copy(connected = false, error = t.message) }; scheduleReconnect(link)
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                if (gen != connectGen) return
                update { it.copy(connected = false) }; if (!closing) scheduleReconnect(link)
            }
        })
    }

    private fun scheduleReconnect(link: ShareLink) {
        if (closing) return
        val gen = connectGen
        scope.launch {
            delay(3_000)
            // Anything that happened in the meantime - a leave, a different room,
            // a newer socket - wins over a retry that was already in flight.
            if (closing || gen != connectGen) return@launch
            val st = _state.value as? ShareState.Joined ?: return@launch
            if (st.link.roomId != link.roomId) return@launch
            connect(link)
        }
    }

    private fun onFrame(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        when {
            j.has("ct") -> {
                val from = j.optString("from"); if (from == myId) return
                val k = key ?: return
                val roomId = (state.value as? ShareState.Joined)?.link?.roomId ?: return
                val plain = runCatching { ShareCrypto.decrypt(k, roomId, ShareCrypto.unb64u(j.getString("ct"))) }.getOrNull() ?: return
                val p = SharePayload.fromJson(String(plain)) ?: return
                // Hoisted out of the update{} lambda: a CAS retry re-runs f, and
                // trail.add / getOrPut are not idempotent, so they must run exactly
                // once regardless of how many times the CAS loop retries.
                val prev = (_state.value as? ShareState.Joined)?.peers?.get(from)
                val trail = prev?.trail ?: Trail(trailMaxAgeMs())
                trail.add(p.lat, p.lng, p.t)
                // First foreign peer observed gets index 0 (matches the web
                // viewer), second gets 1, and so on; colorByPeer.size at the
                // moment a new "from" is inserted IS that arrival index.
                val color = colorByPeer.getOrPut(from) { PeerPalette.colorFor(colorByPeer.size) }
                // The relay hands a client that just connected each rider's last
                // payload, which can be minutes old. A rider's FIRST frame is
                // therefore dated by their own stamp, so someone who stopped
                // riding long ago is not drawn live at a stale spot. Every later
                // frame really did just arrive, and a stamp from the future (a
                // wrong sender clock) falls back to now.
                val seenAt = if (prev == null && p.t in 1..now) p.t else now
                update { st ->
                    st.copy(
                        peers = st.peers + (from to PeerState(
                            p.copy(color = color), seenAt, trail, Staleness.of(now - seenAt), false
                        ))
                    )
                }
            }
            j.optString("type") == "left" -> update { st -> st.copy(peers = st.peers.mapValues { (id, ps) -> if (id == j.optString("from")) ps.copy(left = true) else ps }) }
            j.optString("type") == "peers" -> {
                val seen = j.optJSONObject("seen") ?: return
                update { st -> st.copy(peers = st.peers.mapValues { (id, ps) ->
                    val ageMs = if (seen.has(id)) seen.optLong(id) * 1000L else (now - ps.lastSeenMs)
                    ps.copy(freshness = Staleness.of(maxOf(ageMs, now - ps.lastSeenMs))) }) }
            }
        }
    }

    /** Re-classify freshness from local clock; call from a UI ticker every second. */
    fun ageTick() { val now = System.currentTimeMillis()
        update { st -> st.copy(peers = st.peers.mapValues { (_, ps) -> ps.copy(freshness = Staleness.of(now - ps.lastSeenMs)) }) } }

    /**
     * Keeps the rider on the map while they are joined.
     *
     * Publishing used to ride only on WheelService's telemetry, so a rider
     * sharing without a wheel connected (or after that service stopped) froze
     * on everyone else's map and aged out to "lost", while their own app
     * still said Connected. Location sharing is about the phone's position,
     * so it gets its own beat and telemetry only makes a publish sooner.
     */
    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                delay(PUBLISH_INTERVAL_MS)
                publishTick()
            }
        }
    }

    /** Called on every telemetry tick and by the heartbeat. Publishes at
     *  most every 3 s or on >10 m. Synchronized: the wheel thread, the
     *  socket thread and the heartbeat all reach it. */
    @Synchronized
    fun publishTick() {
        val st = _state.value as? ShareState.Joined ?: return
        val sock = ws ?: return; if (!st.connected) return
        val loc = tripRepository.currentLocation.value ?: return
        val now = System.currentTimeMillis()
        val moved = if (lastLat.isNaN()) Double.MAX_VALUE else distanceM(lastLat, lastLng, loc.latitude, loc.longitude)
        if (now - lastPubMs < PUBLISH_INTERVAL_MS && moved < PUBLISH_MOVE_M) return
        val wd = wheelRepository.wheelData.value
        val stats = if (st.me.shareStats) ShareStats(wd.speed, wd.batteryPercent, wd.maxTemperature) else null
        val payload = SharePayload(myId, st.me.name, st.me.mode, st.me.color, st.me.icon, st.me.avatarUrl, st.me.flag,
            loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing else null, now, stats)
        val k = key ?: return
        val ct = ShareCrypto.b64u(ShareCrypto.encrypt(k, st.link.roomId, payload.toJson().toByteArray()))
        sock.send(JSONObject().put("from", myId).put("ct", ct).toString())
        lastPubMs = now; lastLat = loc.latitude; lastLng = loc.longitude
    }

    fun leave() {
        closing = true
        connectGen++
        heartbeat?.cancel(); heartbeat = null
        ws?.send(JSONObject().put("type", "leave").put("from", myId).toString())
        ws?.close(1000, "leave"); ws = null; key = null
        colorByPeer.clear(); lastLat = Double.NaN
        _state.value = ShareState.Idle
    }

    private fun trailMaxAgeMs(): Long = kotlinx.coroutines.runBlocking { settingsRepository.get().share.trailMinutes } * 60_000L

    /** Atomic read-modify-write on the Joined state (CAS loop). Safe from the
     *  OkHttp reader thread and the UI ticker concurrently. No-op when Idle. */
    private inline fun update(f: (ShareState.Joined) -> ShareState.Joined) {
        while (true) {
            val prev = _state.value as? ShareState.Joined ?: return
            val next = f(prev)
            if (_state.compareAndSet(prev, next)) return
        }
    }
    private fun distanceM(a: Double, b: Double, c: Double, d: Double): Double {
        val r = FloatArray(1); android.location.Location.distanceBetween(a, b, c, d, r); return r[0].toDouble() }
}
