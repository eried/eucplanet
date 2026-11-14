package com.eried.eucplanet.app

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.eucstats.EucStatsApi
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.platform.HttpClient
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.platform.distanceAndBearing
import com.eried.eucplanet.share.Identity
import com.eried.eucplanet.share.IdentityMode
import com.eried.eucplanet.share.LiveShare
import com.eried.eucplanet.share.PeerPalette
import com.eried.eucplanet.share.PeerState
import com.eried.eucplanet.share.ShareCipher
import com.eried.eucplanet.share.ShareLink
import com.eried.eucplanet.share.ShareLinks
import com.eried.eucplanet.share.SharePayload
import com.eried.eucplanet.share.ShareState
import com.eried.eucplanet.share.ShareStats
import com.eried.eucplanet.share.Staleness
import com.eried.eucplanet.share.Trail
import com.eried.eucplanet.share.shareStatsOf
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketTask
import platform.darwin.NSObject

/**
 * The live location share on the iPhone, the same room protocol as Android's
 * ShareSession: the relay's WebSocket (NSURLSessionWebSocketTask where Android
 * has OkHttp), each position sealed with the shared [ShareCipher] (Android's
 * javax AES-GCM, byte for byte), published every 3 s or 10 m, peers aged and
 * coloured in arrival order. An iPhone and an Android phone ride in one group.
 *
 * Everything runs on the main thread: the socket's delegate and its receive
 * callbacks are on the main queue, and so are the heartbeat and the age tick.
 * The GPS stays at the riding rate while joined ([IosGpsPower.setSharing]),
 * which also keeps the app running with the phone locked.
 */
internal class IosShareSession(
    private val settingsRepository: SettingsRepository,
    private val wheelRepository: WheelRepository,
) : LiveShare {

    override var groupTabOrdinal: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<ShareState>(ShareState.Idle)
    override val state: StateFlow<ShareState> = _state.asStateFlow()

    init {
        scope.launch {
            settingsRepository.settings.map { it.share.trailMinutes }.distinctUntilChanged()
                .collect { trailMsCached = it * 60_000L }
        }
        // The GPS tier follows being in a room, as Android's TripRepository reads ShareSession.sharing.
        scope.launch {
            _state.map { it is ShareState.Joined }.distinctUntilChanged().collect { IosGpsPower.setSharing(it) }
        }
    }

    override val myStats: StateFlow<ShareStats?> = combine(
        _state, wheelRepository.connectionState, wheelRepository.wheelData,
    ) { st, connection, wheel ->
        val me = (st as? ShareState.Joined)?.me ?: return@combine null
        shareStatsOf(shareStats = me.shareStats, wheelConnected = connection == ConnectionState.CONNECTED, wheel = wheel)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private var socket: Socket? = null
    private var key: ByteArray? = null
    private var myId: String = ShareCipher.b64u(ShareCipher.randomBytes(8))
    private var lastPubMs = 0L
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    private val colorByPeer = HashMap<String, String>()
    private var closing = false
    /** Bumped on every connect and on leave; a socket of an older one is a leftover and is ignored. */
    private var connectGen = 0
    private var heartbeat: Job? = null
    private var trailMsCached: Long = 5 * 60_000L

    private suspend fun senderIdFor(roomId: String): ByteArray {
        var secret = settingsRepository.get().share.deviceSecret
        if (secret.isBlank()) {
            secret = ShareCipher.b64u(ShareCipher.randomBytes(16))
            settingsRepository.update { it.copy(share = it.share.copy(deviceSecret = secret)) }
        }
        return ShareCipher.hmacSha256(ShareCipher.unb64u(secret), roomId.encodeToByteArray())
    }

    /** "Rider #NNNN" from this phone's sender id in the room, as Android names it. */
    private fun anonymousName(idBytes: ByteArray): String {
        val v = ((idBytes[0].toInt() and 0xFF) shl 8) or (idBytes[1].toInt() and 0xFF)
        return "Rider #${1000 + v % 9000}"
    }

    override suspend fun identityFor(mode: IdentityMode, sessionName: String, shareStats: Boolean): Identity {
        // As on Android: this rider always takes the palette's last colour, so
        // peer 0 never collides with "me"; the marker photo never goes out.
        val myColor = PeerPalette.colorFor(PeerPalette.COLORS.size - 1)
        return when (mode) {
            IdentityMode.ANON -> Identity(mode, "Rider #${(1000..9999).random()}", myColor, null, null, null, shareStats)
            IdentityMode.SESSION -> Identity(mode, sessionName.ifBlank { "Rider" }, myColor, null, null, null, shareStats)
            IdentityMode.PROFILE -> {
                val storeId = IosEucStats.riderStoreId.value
                val p = storeId?.let {
                    withContext(Dispatchers.IO) {
                        runCatching { EucStatsApi(HttpClient()) { IosEucStats.baseUrl }.getProfile(it) }.getOrNull()
                    }
                }
                if (p == null) identityFor(IdentityMode.SESSION, sessionName, shareStats)
                else Identity(mode, p.displayName ?: "Rider", myColor, null, p.avatarUrl, p.flag, shareStats)
            }
        }
    }

    override suspend fun start(identity: Identity): ShareLink {
        val link = ShareLinks.newLink()
        join(link, identity)
        return link
    }

    override suspend fun join(link: ShareLink, identity: Identity) {
        closeSocket()
        closing = false
        key = ShareCipher.deriveKey(link.key)
        val idBytes = senderIdFor(link.roomId)
        myId = ShareCipher.b64u(idBytes.copyOf(9))
        val me = if (identity.mode == IdentityMode.ANON) identity.copy(name = anonymousName(idBytes)) else identity
        // The GPS that the dot is drawn from.
        IosLocation.start()
        settingsRepository.update {
            it.copy(share = it.share.copy(
                lastIdentityMode = identity.mode.name,
                shareStatsDefault = identity.shareStats,
                lastSessionName = if (identity.mode == IdentityMode.SESSION) identity.name else it.share.lastSessionName,
            ))
        }
        _state.value = ShareState.Joined(link, me, emptyMap(), connected = false, error = null)
        connect(link)
        startHeartbeat()
    }

    private suspend fun connect(link: ShareLink) {
        val gen = ++connectGen
        val relay = settingsRepository.get().share.relayUrl.trimEnd('/')
        val url = NSURL.URLWithString("$relay/ws/${link.roomId}") ?: run {
            update { it.copy(connected = false, error = "bad relay url") }
            return
        }
        socket = Socket(url, gen, link)
    }

    private fun scheduleReconnect(link: ShareLink) {
        if (closing) return
        val gen = connectGen
        scope.launch {
            delay(3_000)
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
                val from = j.optString("from")
                if (from == myId) return
                val k = key ?: return
                val roomId = (state.value as? ShareState.Joined)?.link?.roomId ?: return
                val plain = runCatching { ShareCipher.decrypt(k, roomId, ShareCipher.unb64u(j.getString("ct"))) }
                    .getOrNull() ?: return
                val p = SharePayload.fromJson(plain.decodeToString()) ?: return
                val prev = (_state.value as? ShareState.Joined)?.peers?.get(from)
                val trail = prev?.trail ?: Trail(trailMsCached)
                trail.add(p.lat, p.lng, p.t)
                val color = colorByPeer.getOrPut(from) { PeerPalette.colorFor(colorByPeer.size) }
                // A rider's first frame (the relay's replay of their last one) is
                // dated by their own stamp; every later one just arrived.
                val seenAt = if (prev == null && p.t in 1..now) p.t else now
                update { st ->
                    st.copy(peers = st.peers + (from to PeerState(
                        p.copy(color = color), seenAt, trail, Staleness.of(now - seenAt), false,
                    )))
                }
            }
            j.optString("type") == "left" -> update { st ->
                st.copy(peers = st.peers.mapValues { (id, ps) -> if (id == j.optString("from")) ps.copy(left = true) else ps })
            }
            j.optString("type") == "peers" -> {
                val seen = j.optJSONObject("seen") ?: return
                update { st ->
                    st.copy(peers = st.peers.mapValues { (id, ps) ->
                        val ageMs = if (seen.has(id)) seen.optLong(id) * 1000L else (now - ps.lastSeenMs)
                        ps.copy(freshness = Staleness.of(maxOf(ageMs, now - ps.lastSeenMs)))
                    })
                }
            }
        }
    }

    override fun ageTick() {
        val now = System.currentTimeMillis()
        val trailMs = trailMsCached
        update { st ->
            st.copy(
                nowMs = now,
                peers = st.peers
                    .filterValues { ps -> !(ps.left && now - ps.lastSeenMs > trailMs) }
                    .mapValues { (_, ps) -> ps.copy(freshness = Staleness.of(now - ps.lastSeenMs)) },
            )
        }
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                delay(PUBLISH_INTERVAL_MS)
                publishTick()
            }
        }
    }

    /** Publishes at most every 3 s, or sooner after 10 m. */
    private fun publishTick() {
        val st = _state.value as? ShareState.Joined ?: return
        val sock = socket ?: return
        if (!st.connected) return
        val loc = IosLocation.fix.value ?: return
        val now = System.currentTimeMillis()
        val moved = if (lastLat.isNaN()) Double.MAX_VALUE
        else distanceAndBearing(lastLat, lastLng, loc.latitude, loc.longitude)[0].toDouble()
        if (now - lastPubMs < PUBLISH_INTERVAL_MS && moved < PUBLISH_MOVE_M) return
        val stats = shareStatsOf(
            shareStats = st.me.shareStats,
            wheelConnected = wheelRepository.connectionState.value == ConnectionState.CONNECTED,
            wheel = wheelRepository.wheelData.value,
        )
        val payload = SharePayload(
            myId, st.me.name, st.me.mode, st.me.color, st.me.icon, st.me.avatarUrl, st.me.flag,
            loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing else null, now, stats,
        )
        val k = key ?: return
        val ct = ShareCipher.b64u(ShareCipher.encrypt(k, st.link.roomId, payload.toJson().encodeToByteArray()))
        sock.send(JSONObject().put("from", myId).put("ct", ct).toString())
        lastPubMs = now
        lastLat = loc.latitude
        lastLng = loc.longitude
    }

    override fun leave() = closeSocket()

    private fun closeSocket() {
        closing = true
        connectGen++
        heartbeat?.cancel()
        heartbeat = null
        socket?.let { s ->
            s.send(JSONObject().put("type", "leave").put("from", myId).toString())
            s.close()
        }
        socket = null
        key = null
        colorByPeer.clear()
        lastLat = Double.NaN
        _state.value = ShareState.Idle
    }

    private inline fun update(f: (ShareState.Joined) -> ShareState.Joined) {
        while (true) {
            val prev = _state.value as? ShareState.Joined ?: return
            if (_state.compareAndSet(prev, f(prev))) return
        }
    }

    /**
     * One connection to the relay. Its delegate is on the main queue, so the
     * open, the frames and the close arrive in order on the main thread.
     */
    private inner class Socket(url: NSURL, private val gen: Int, private val link: ShareLink) {
        private val delegate = Delegate()
        private val session = NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.defaultSessionConfiguration, delegate, NSOperationQueue.mainQueue,
        )
        private val task: NSURLSessionWebSocketTask = session.webSocketTaskWithURL(url)
        private var ended = false

        init {
            task.resume()
            receive()
        }

        private val current get() = gen == connectGen

        fun send(text: String) {
            task.sendMessage(NSURLSessionWebSocketMessage(string = text)) { error ->
                if (error != null) Log.w(TAG, "send failed: ${error.localizedDescription}")
            }
        }

        fun close() {
            ended = true
            task.cancelWithCloseCode(NORMAL_CLOSURE, reason = null)
            session.finishTasksAndInvalidate()
        }

        private fun receive() {
            task.receiveMessageWithCompletionHandler { message, error ->
                if (error != null || ended) return@receiveMessageWithCompletionHandler
                if (current) message?.string?.let { onFrame(it) }
                receive()
            }
        }

        /** The socket went away: [code] when the relay closed it, else [error]. */
        private fun ended(code: Long?, error: String?) {
            if (ended) return
            ended = true
            session.finishTasksAndInvalidate()
            if (!current) return
            if (code == CLOSE_ROOM_FULL) update { it.copy(connected = false, error = LiveShare.ERR_ROOM_FULL) }
            else update { it.copy(connected = false, error = error ?: it.error) }
            // A full room is not a passing fault: the rider is told, and retrying is theirs.
            val roomFull = (_state.value as? ShareState.Joined)?.error == LiveShare.ERR_ROOM_FULL
            if (!closing && !roomFull) scheduleReconnect(link)
        }

        private inner class Delegate : NSObject(), NSURLSessionWebSocketDelegateProtocol {
            @ObjCSignatureOverride
            override fun URLSession(
                session: NSURLSession,
                webSocketTask: NSURLSessionWebSocketTask,
                didOpenWithProtocol: String?,
            ) {
                if (!current) {
                    close()
                    return
                }
                Log.i(TAG, "connected to the relay")
                update { it.copy(connected = true, error = null) }
                lastPubMs = 0L
                publishTick()
            }

            @ObjCSignatureOverride
            override fun URLSession(
                session: NSURLSession,
                webSocketTask: NSURLSessionWebSocketTask,
                didCloseWithCode: NSURLSessionWebSocketCloseCode,
                reason: NSData?,
            ) = ended(didCloseWithCode, null)

            @ObjCSignatureOverride
            override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) =
                ended(null, didCompleteWithError?.localizedDescription)
        }
    }

    companion object {
        private const val TAG = "LiveShare"
        const val PUBLISH_INTERVAL_MS = 3_000L
        const val PUBLISH_MOVE_M = 10.0
        private const val NORMAL_CLOSURE: NSURLSessionWebSocketCloseCode = 1000
        private const val CLOSE_ROOM_FULL: Long = LiveShare.CLOSE_ROOM_FULL.toLong()
    }
}
