package com.eried.eucplanet.share

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.data.eucstats.EucStatsApiContract
import com.eried.eucplanet.service.WheelService
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class Identity(val mode: IdentityMode, val name: String, val color: String, val icon: String?,
                    val avatarUrl: String?, val flag: String?, val shareStats: Boolean)

data class PeerState(val last: SharePayload, val lastSeenMs: Long, val trail: Trail,
                     val freshness: Freshness, val left: Boolean)

sealed class ShareState {
    object Idle : ShareState()
    /**
     * [nowMs] is the clock the whole group view is rendered against, refreshed
     * by every [ShareSession.ageTick]. It is part of the state on purpose:
     * re-classifying freshness alone produces an equal object once a peer has
     * settled into STALE, the CAS write is then a no-op and nothing emits, so
     * "15 s ago" used to sit frozen for the whole 105 s until LOST. With the
     * tick's clock in the state every tick is a real change, and readers show
     * an age computed from it rather than from a fresh currentTimeMillis()
     * sampled at composition time (which the recomposition never reached).
     */
    data class Joined(val link: ShareLink, val me: Identity, val peers: Map<String, PeerState>,
                      val connected: Boolean, val error: String?,
                      val nowMs: Long = System.currentTimeMillis()) : ShareState()
}

/**
 * The riders who are in the group right now: everyone in [ShareState.Joined.peers]
 * except those who announced they left and those whose last fix aged out to
 * [Freshness.LOST]. Self is never in the map (peers are keyed by the remote
 * sender id), so this is always "the others".
 *
 * One definition on purpose. The navigator's Share badge, the group dialog's
 * status line and its "no one else here yet" line all read this, so they cannot
 * show 2, 2 and "nobody" for the same room.
 */
val ShareState.Joined.activePeers: List<PeerState>
    get() = peers.values.filter { !it.left && it.freshness != Freshness.LOST }

@Singleton
class ShareSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val wheelRepository: WheelRepository,
    private val syncManager: SyncManager,
    private val eucStatsApi: EucStatsApiContract,
) {
    companion object {
        const val PUBLISH_INTERVAL_MS = 3_000L
        const val PUBLISH_MOVE_M = 10.0
        /** The relay closes with 1013 when a room already holds its maximum
         *  number of riders. Carried as a typed marker in [ShareState.Joined.error]
         *  so the UI can say "this group is full" without matching on words
         *  that a relay could reword or localize. */
        const val ERR_ROOM_FULL = "room_full"
        /** WebSocket close code the relay uses for "try again later": here it
         *  only ever means the room is at capacity. */
        const val CLOSE_ROOM_FULL = 1013
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
    /** Written from the OkHttp reader thread as peers arrive and cleared from
     *  whatever thread calls [leave] (the UI's). A plain HashMap can corrupt
     *  its table under that overlap, so this one is concurrent. */
    private val colorByPeer = ConcurrentHashMap<String, String>()
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

    /**
     * Build the identity the group sees.
     *
     * The payload's `icon` stays null for every mode, the navigator's
     * customized rider marker included. That marker is a base64 PNG data URL
     * held on this phone (NavMarkerStore), tens of kilobytes of it, and the
     * payload is re-sent every three seconds: putting it on the wire would
     * multiply each rider's traffic by orders of magnitude to redraw a dot.
     * The custom marker therefore stays what it already is, the LOCAL rider's
     * own marker on their own map, drawn by nativeSetUserPhoto; friends see
     * the palette dot (or the profile avatar, which is a real https URL).
     */
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

    /** Join [link] as [identity]. */
    suspend fun join(link: ShareLink, identity: Identity) {
        closeSocket()
        closing = false
        key = ShareCrypto.deriveKey(link.key)
        // Before the first suspension point, so this still runs on the tap that
        // opened the group: Android 12+ refuses a foreground-service start once
        // the app has slipped into the background.
        ensureForegroundService()
        // How the rider chose to appear is remembered for the next group, the
        // stats toggle included: re-picking it every time was the one part of
        // the identity the dialog kept forgetting.
        settingsRepository.update { it.copy(share = it.share.copy(
            lastIdentityMode = identity.mode.name,
            shareStatsDefault = identity.shareStats,
            lastSessionName = if (identity.mode == IdentityMode.SESSION) identity.name else it.share.lastSessionName)) }
        _state.value = ShareState.Joined(link, identity, emptyMap(), connected = false, error = null)
        connect(link)
        startHeartbeat()
    }

    /**
     * Sharing is about the phone's position, not the wheel's, so a rider can
     * be sharing with nothing connected and "Keep app running" off. That
     * leaves a plain background process, and Doze freezes the heartbeat with
     * it: the rider's dot stops moving on everyone else's map while their own
     * screen still says Connected. Bringing WheelService up as a foreground
     * service for the duration keeps the beat running with the phone in a
     * pocket. WheelService's own teardown gate counts an active share as a
     * reason to stay up, so this is not undone the moment the rider turns
     * "Keep app running" off.
     */
    private fun ensureForegroundService() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, WheelService::class.java))
        }
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
            /**
             * The peer sent a Close frame: a relay redeploy, an nginx reload, or
             * a 1013 for a room that is already full. OkHttp only calls
             * [onClosed] once THIS side has enqueued its own close, so without
             * this the session sat at connected = true publishing into a dead
             * socket and never reconnecting. Answering the close hands control
             * to onClosed, which owns the reconnect decision.
             */
            override fun onClosing(w: WebSocket, code: Int, reason: String) {
                if (gen != connectGen) { w.close(1000, null); return }
                if (code == CLOSE_ROOM_FULL) update { it.copy(connected = false, error = ERR_ROOM_FULL) }
                w.close(1000, null)
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                if (gen != connectGen) return
                // A full room is not a transient fault: retrying every 3 s would
                // hammer the relay for a seat that is not coming free on its own.
                // The rider is told, and reconnecting is left to them.
                val roomFull = (_state.value as? ShareState.Joined)?.error == ERR_ROOM_FULL
                update { it.copy(connected = false) }
                if (!closing && !roomFull) scheduleReconnect(link)
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

    /**
     * Re-classify freshness from the local clock; call from a UI ticker every
     * second. [ShareState.Joined.nowMs] is always advanced, not just the
     * freshness: once a peer has settled into STALE the re-classified peer map
     * is equal to the old one, the CAS write is a no-op, and no collector ever
     * hears the tick. Carrying the tick's clock in the state makes every
     * second a real emission, which is what keeps "15 s ago" counting up
     * instead of freezing until the peer goes LOST.
     */
    fun ageTick() { val now = System.currentTimeMillis()
        update { st -> st.copy(nowMs = now,
            peers = st.peers.mapValues { (_, ps) -> ps.copy(freshness = Staleness.of(now - ps.lastSeenMs)) }) } }

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
        // The same connection the dashboard reads: no wheel, no stats, however
        // the rider set the toggle.
        val stats = shareStatsOf(
            shareStats = st.me.shareStats,
            wheelConnected = wheelRepository.connectionState.value == ConnectionState.CONNECTED,
            wheel = wheelRepository.wheelData.value,
        )
        val payload = SharePayload(myId, st.me.name, st.me.mode, st.me.color, st.me.icon, st.me.avatarUrl, st.me.flag,
            loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing else null, now, stats)
        val k = key ?: return
        val ct = ShareCrypto.b64u(ShareCrypto.encrypt(k, st.link.roomId, payload.toJson().toByteArray()))
        sock.send(JSONObject().put("from", myId).put("ct", ct).toString())
        lastPubMs = now; lastLat = loc.latitude; lastLng = loc.longitude
    }

    /**
     * The rider walks out. Nothing is remembered: the relay drops a room a
     * short while after its last socket closes, and every member holds the
     * full link, so a rider who wants back in is handed the QR or the link by
     * someone still in the ride. There is no creator role to come back to.
     */
    fun leave() {
        closeSocket()
    }

    /** Tear the socket down. Switching rooms goes through here too; it is the
     *  same teardown, only the rider tapping Leave is a leave. */
    private fun closeSocket() {
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
