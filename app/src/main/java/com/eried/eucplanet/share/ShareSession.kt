package com.eried.eucplanet.share

import android.util.Log
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.data.eucstats.EucStatsApiContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var joinOrder = 0
    private val colorByPeer = HashMap<String, String>()
    private var closing = false

    suspend fun resolveDefaultIdentity(): Identity {
        val s = settingsRepository.get()
        val mode = runCatching { IdentityMode.valueOf(s.share.lastIdentityMode) }.getOrDefault(IdentityMode.ANON)
        return identityFor(mode, s.share.lastSessionName, s.share.shareStatsDefault)
    }

    suspend fun identityFor(mode: IdentityMode, sessionName: String, shareStats: Boolean): Identity {
        return when (mode) {
            IdentityMode.ANON -> Identity(mode, "Rider #${(1000..9999).random()}", PeerPalette.colorFor(0), null, null, null, shareStats)
            IdentityMode.SESSION -> Identity(mode, sessionName.ifBlank { "Rider" }, PeerPalette.colorFor(0), null, null, null, shareStats)
            IdentityMode.PROFILE -> {
                val storeId = syncManager.readRiderIdFile()
                val p = storeId?.let { runCatching { eucStatsApi.getProfile(it) }.getOrNull() }
                if (p == null) identityFor(IdentityMode.SESSION, sessionName, shareStats)
                else Identity(mode, p.displayName ?: "Rider", PeerPalette.colorFor(0), null, p.avatarUrl, p.flag, shareStats)
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
    }

    private fun connect(link: ShareLink) {
        val relay = kotlinx.coroutines.runBlocking { settingsRepository.get().share.relayUrl }.trimEnd('/')
        val req = Request.Builder().url("$relay/ws/${link.roomId}").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: okhttp3.Response) { update { it.copy(connected = true, error = null) }; lastPubMs = 0L; publishTick() }
            override fun onMessage(w: WebSocket, text: String) { onFrame(text) }
            override fun onFailure(w: WebSocket, t: Throwable, r: okhttp3.Response?) { update { it.copy(connected = false, error = t.message) }; scheduleReconnect(link) }
            override fun onClosed(w: WebSocket, code: Int, reason: String) { update { it.copy(connected = false) }; if (!closing) scheduleReconnect(link) }
        })
    }

    private fun scheduleReconnect(link: ShareLink) {
        if (closing) return
        scope.launch { delay(3_000); if (!closing && _state.value is ShareState.Joined) connect(link) }
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
                update { st ->
                    val prev = st.peers[from]
                    val trail = prev?.trail ?: Trail(trailMaxAgeMs())
                    trail.add(p.lat, p.lng, p.t)
                    val color = colorByPeer.getOrPut(from) { PeerPalette.colorFor(++joinOrder) }
                    st.copy(peers = st.peers + (from to PeerState(p.copy(color = color), now, trail, Freshness.FRESH, false)))
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

    /** Called every telemetry tick by WheelService. Publishes at most every 3 s or on >10 m. */
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
        ws?.send(JSONObject().put("type", "leave").put("from", myId).toString())
        ws?.close(1000, "leave"); ws = null; key = null
        colorByPeer.clear(); joinOrder = 0; lastLat = Double.NaN
        _state.value = ShareState.Idle
    }

    private fun trailMaxAgeMs(): Long = kotlinx.coroutines.runBlocking { settingsRepository.get().share.trailMinutes } * 60_000L
    private inline fun update(f: (ShareState.Joined) -> ShareState.Joined) { (_state.value as? ShareState.Joined)?.let { _state.value = f(it) } }
    private fun distanceM(a: Double, b: Double, c: Double, d: Double): Double {
        val r = FloatArray(1); android.location.Location.distanceBetween(a, b, c, d, r); return r[0].toDouble() }
}
