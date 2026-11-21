package com.eried.eucplanet.app

import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toByteArray
import com.eried.eucplanet.platform.toNSData
import com.eried.eucplanet.service.hud.HudBeaconSighting
import com.eried.eucplanet.service.hud.HudBeacons
import com.eried.eucplanet.service.hud.HudMdns
import com.eried.eucplanet.service.hud.HudNetwork
import com.eried.eucplanet.service.hud.HudNetworkEvents
import com.eried.eucplanet.service.hud.HudPhone
import com.eried.eucplanet.service.hud.HudSocket
import com.eried.eucplanet.service.hud.HudSocketEvents
import com.eried.eucplanet.service.hud.HudSockets
import com.eried.eucplanet.service.hud.HudTileHttp
import com.eried.eucplanet.service.hud.HudTiles
import com.eried.eucplanet.service.hud.hudLinkNote
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.StringResource
import platform.Foundation.NSData
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.currentLocale
import platform.Foundation.NSLocale
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
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
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.EINPROGRESS
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.O_NONBLOCK
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_SNDTIMEO
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror
import platform.posix.timeval

/*
 * The iPhone side of the HUD link's ports (shared HudLinkPorts.kt). The link
 * itself, HudServer, is the same class Android runs: this file is only what
 * it stands on here. The socket is an NSURLSessionWebSocketTask where Android
 * has OkHttp, the mDNS browse is Bonjour where Android has JmDNS, and the
 * subnet sweep and the tile listener are plain BSD sockets.
 *
 * One channel is missing: the HUD's UDP broadcast beacon. iOS hands broadcast
 * packets only to apps that hold the com.apple.developer.networking.multicast
 * entitlement, which Apple grants on request and this app does not have. The
 * HUD is still found by Bonjour, by the subnet sweep and by the typed address.
 *
 * iOS asks the rider for "local network" access the first time any of this
 * touches the LAN (NSLocalNetworkUsageDescription). Until they allow it every
 * dial fails, and the link keeps looking, so allowing it is enough.
 */

private const val TAG = "HudLink"

/** Starts and stops the shared link with its setting, as Android's WheelService does. */
internal object IosHudLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    fun start() {
        if (started) return
        started = true
        // EUC_HUD_FORCE=true runs it whatever the switch says, as Android's
        // debug.eucplanet.hud.force property does.
        val force = IosHudPhone.debug("debug.eucplanet.hud.force") == "true"
        scope.launch {
            IosAppGraph.settings.settings.map { it.hudServerEnabled || force }.distinctUntilChanged().collect { on ->
                if (on) IosAppGraph.hud.start() else IosAppGraph.hud.stop()
            }
        }
    }
}

/**
 * Links the paired ENGO glasses while their switch is on, as WheelService
 * does on Android: a new pair, Disconnect or Forget device ends the old link
 * first. The renderer keeps looking for the glasses while the link is down.
 */
internal object IosEngoLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            var linkedTo: String? = null
            IosAppGraph.settings.settings
                .map { s -> s.engoHud.pairedAddress?.takeIf { s.engoHud.enabled } }
                .distinctUntilChanged()
                .collect { target ->
                    if (linkedTo != null) IosAppGraph.engoRenderer.stop()
                    if (target != null) IosAppGraph.engoRenderer.start(target)
                    linkedTo = target
                }
        }
    }
}

/** The phone around the link: the screen, the GPS fix, its texts, the launch hooks. */
internal object IosHudPhone : HudPhone {
    override val foreground: StateFlow<Boolean> get() = IosForeground.visible

    override fun uiForeground(): Boolean = IosForeground.visible.value

    override fun location(): PhoneLocation? = IosLocation.fix.value

    // The HUD draws its map from this phone's position: the GPS stays at the
    // riding rate while that screen is in its carousel, as on Android.
    override fun setMapLinked(linked: Boolean) = IosGpsPower.setHudMap(linked)

    override fun text(res: StringResource): String = IosPhoneDevice.text(res)

    override fun text(res: StringResource, args: List<Any>): String =
        IosPhoneDevice.text(res, *args.toTypedArray())

    override fun shortTime(epochMs: Long): String =
        shortClock.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))

    private val shortClock by lazy {
        NSDateFormatter().apply {
            locale = NSLocale.currentLocale
            dateStyle = NSDateFormatterNoStyle
            timeStyle = NSDateFormatterShortStyle
        }
    }

    /**
     * Android's debug properties are launch hooks here: `debug.eucplanet.hud.peer`
     * is `EUC_HUD_PEER`, `debug.eucplanet.proto.major` is `EUC_PROTO_MAJOR`
     * (SIMCTL_CHILD_EUC_... on the simulator, docs/IOS_SYNC.md).
     */
    override fun debug(name: String): String? =
        IosLaunchHooks.env("EUC_" + name.removePrefix("debug.eucplanet.").replace('.', '_').uppercase())
}

/** The WebSocket client, an NSURLSessionWebSocketTask per dial. */
internal object IosHudSockets : HudSockets {
    override fun open(url: String, pingMs: Long, events: HudSocketEvents): HudSocket = Socket(url, pingMs, events)

    /** As OkHttp: a dial that has not opened in this long has failed. */
    private const val CONNECT_TIMEOUT_MS = 4_000L
    /** As OkHttp: more than this waiting to go out and the send is refused. */
    private const val MAX_QUEUED_BYTES = 16L * 1024 * 1024

    private class Socket(url: String, private val pingMs: Long, private val events: HudSocketEvents) : HudSocket {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
        private val delegate = Delegate()
        private val session = NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.ephemeralSessionConfiguration, delegate, queue,
        )
        private val task: NSURLSessionWebSocketTask? = NSURL.URLWithString(url)?.let { session.webSocketTaskWithURL(it) }

        /** One end per socket, whoever reports it first: the delegate, a ping, a send or the link itself. */
        private val ended = atomic(false)
        private val opened = atomic(false)
        private val awaitingPong = atomic(false)
        private val queuedBytes = atomic(0L)

        init {
            if (task == null) {
                fail(IllegalArgumentException("bad address $url"), null)
            } else {
                task.resume()
                receive()
                scope.launch {
                    delay(CONNECT_TIMEOUT_MS)
                    if (!opened.value) fail(RuntimeException("connect timed out"), null)
                }
            }
        }

        override fun send(text: String): Boolean {
            val t = task ?: return false
            if (ended.value) return false
            val size = text.length.toLong()
            if (queuedBytes.value + size > MAX_QUEUED_BYTES) return false
            queuedBytes.addAndGet(size)
            t.sendMessage(NSURLSessionWebSocketMessage(string = text)) { error ->
                queuedBytes.addAndGet(-size)
                if (error != null) fail(RuntimeException(error.localizedDescription), null)
            }
            return true
        }

        override fun close(code: Int, reason: String?) {
            if (!ended.compareAndSet(expect = false, update = true)) return
            task?.cancelWithCloseCode(code.toLong(), reason?.encodeToByteArray()?.toNSData())
            release()
            events.onClosed()
        }

        override fun cancel() = fail(RuntimeException("Canceled"), null)

        private fun fail(error: Throwable, httpCode: Int?) {
            if (!ended.compareAndSet(expect = false, update = true)) return
            task?.cancel()
            release()
            events.onFailure(error, httpCode)
        }

        private fun release() {
            scope.coroutineContext[Job]?.cancel()
            session.finishTasksAndInvalidate()
        }

        private fun receive() {
            task?.receiveMessageWithCompletionHandler { message, error ->
                if (error != null || ended.value) return@receiveMessageWithCompletionHandler
                message?.string?.let { events.onMessage(it) }
                receive()
            }
        }

        /**
         * OkHttp's pingInterval: a ping every [pingMs], and the link has
         * failed when the one before it is still unanswered.
         */
        private fun startPings() = scope.launch {
            while (isActive) {
                delay(pingMs)
                if (ended.value) return@launch
                if (!awaitingPong.compareAndSet(expect = false, update = true)) {
                    fail(RuntimeException("sent ping but didn't receive pong within ${pingMs}ms"), null)
                    return@launch
                }
                task?.sendPingWithPongReceiveHandler { error ->
                    if (error == null) awaitingPong.value = false
                    else fail(RuntimeException(error.localizedDescription), null)
                }
            }
        }

        private inner class Delegate : NSObject(), NSURLSessionWebSocketDelegateProtocol {
            @ObjCSignatureOverride
            override fun URLSession(
                session: NSURLSession,
                webSocketTask: NSURLSessionWebSocketTask,
                didOpenWithProtocol: String?,
            ) {
                if (ended.value) return
                opened.value = true
                events.onOpen(this@Socket)
                startPings()
            }

            @ObjCSignatureOverride
            override fun URLSession(
                session: NSURLSession,
                webSocketTask: NSURLSessionWebSocketTask,
                didCloseWithCode: NSURLSessionWebSocketCloseCode,
                reason: NSData?,
            ) {
                if (ended.value) return
                val why = reason?.toByteArray()?.decodeToString().orEmpty()
                // The HUD hung up. The link answers the close, which ends this socket.
                events.onClosing(this@Socket, didCloseWithCode.toInt(), why)
                close(1000, null)
            }

            @ObjCSignatureOverride
            override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
                val http = (task.response as? NSHTTPURLResponse)?.statusCode?.toInt()?.takeIf { it != 101 }
                fail(RuntimeException(didCompleteWithError?.localizedDescription ?: "socket ended"), http)
            }
        }
    }
}

/**
 * The mDNS browse, on Bonjour. Each call is its own browser on the main run
 * loop, stopped when the answer is in or the time is up. Needs the
 * `_eucplanet._tcp` entry in Info.plist's NSBonjourServices.
 */
internal object IosHudMdns : HudMdns {
    override suspend fun resolve(timeoutMs: Long, serviceInfoTimeoutMs: Long): String? {
        val found = CompletableDeferred<String?>()
        val search = withContext(Dispatchers.Main) { Search(serviceInfoTimeoutMs, found).also { it.start() } }
        try {
            return withTimeoutOrNull(timeoutMs) { found.await() }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { search.stop() }
        }
    }

    override fun release() = Unit

    /** "_eucplanet._tcp." of the shared "_eucplanet._tcp.local.". */
    private val type = HudDiscovery.SERVICE_TYPE.removeSuffix("local.")

    private class Search(
        private val resolveTimeoutMs: Long,
        private val found: CompletableDeferred<String?>,
    ) : NSObject(), NSNetServiceBrowserDelegateProtocol, NSNetServiceDelegateProtocol {
        private val browser = NSNetServiceBrowser()
        /** Held while they resolve: Bonjour keeps no reference of its own. */
        private val services = mutableListOf<NSNetService>()

        fun start() {
            browser.delegate = this
            browser.searchForServicesOfType(type, inDomain = "local.")
        }

        fun stop() {
            browser.stop()
            browser.delegate = null
            services.forEach {
                it.stop()
                it.delegate = null
            }
            services.clear()
        }

        @ObjCSignatureOverride
        override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
            services += didFindService
            didFindService.delegate = this
            didFindService.resolveWithTimeout(resolveTimeoutMs / 1000.0)
        }

        @ObjCSignatureOverride
        override fun netServiceBrowser(browser: NSNetServiceBrowser, didRemoveService: NSNetService, moreComing: Boolean) = Unit

        override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
            // -72008 with no Info.plist entry, -65570 while the rider has not allowed the local network.
            hudLinkNote(TAG, "mDNS browse failed: $didNotSearch")
        }

        override fun netServiceDidResolveAddress(sender: NSNetService) {
            val ip = sender.addresses.orEmpty().firstNotNullOfOrNull { ipv4Of(it as? NSData) } ?: return
            val version = sender.TXTRecordData()?.let { NSNetService.dictionaryFromTXTRecordData(it) }
                ?.get(HudDiscovery.TXT_VERSION)?.let { (it as? NSData)?.toByteArray()?.decodeToString() }
            if (HudMdns.speaks(version)) found.complete("$ip:${sender.port}")
        }

        override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) = Unit

        /** The dotted address of a `sockaddr_in` (length, family, port, four bytes), null for IPv6. */
        private fun ipv4Of(data: NSData?): String? {
            val b = data?.toByteArray() ?: return null
            if (b.size < 8 || (b[1].toInt() and 0xFF) != AF_INET) return null
            return (4..7).joinToString(".") { (b[it].toInt() and 0xFF).toString() }
        }
    }
}

/** No beacon on the iPhone, see the top of this file. */
internal object IosHudBeacons : HudBeacons {
    override val latest: StateFlow<HudBeaconSighting?> = MutableStateFlow<HudBeaconSighting?>(null).asStateFlow()
    override val totalReceived: Long = 0L
    override val lastReceiveAtMs: Long = 0L
    override val lastBindError: String = "the iPhone does not listen for the beacon"

    override fun start() {
        hudLinkNote(TAG, "Beacon listener: not on the iPhone (iOS keeps broadcast for apps with the multicast entitlement)")
    }

    override fun stop() = Unit
}

/** The iPhone's Wi-Fi and hotspot: their subnets, a TCP probe, and a watch for them coming and going. */
@OptIn(ExperimentalForeignApi::class)
internal object IosHudNetwork : HudNetwork {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null

    /**
     * Every private IPv4 the phone has, as `ip/prefix`: the Wi-Fi it joined
     * (en0) and its Personal Hotspot (bridge100, 172.20.10.1/28). Mobile data
     * (pdp_ip), VPN tunnels (utun, ipsec) and AirDrop's links (awdl, llw)
     * never host the HUD.
     */
    override fun candidateIpv4Cidrs(): List<String> = memScoped {
        val out = LinkedHashSet<String>()
        val list = alloc<kotlinx.cinterop.CPointerVar<ifaddrs>>()
        if (getifaddrs(list.ptr) != 0) {
            hudLinkNote(TAG, "interface enumeration failed: ${strerror(errno)?.toKString()}")
            return emptyList()
        }
        var it = list.value
        while (it != null) {
            val ifa = it.pointed
            it = ifa.ifa_next
            val flags = ifa.ifa_flags.toInt()
            if (flags and IFF_UP == 0 || flags and IFF_LOOPBACK != 0) continue
            val name = ifa.ifa_name?.toKString()?.lowercase().orEmpty()
            if (SKIPPED.any { name.startsWith(it) }) continue
            val addr = ifa.ifa_addr ?: continue
            if (addr.pointed.sa_family.toInt() != AF_INET) continue
            val ip = addr.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr.octets()
            if (!siteLocal(ip)) continue
            val mask = ifa.ifa_netmask?.reinterpret<sockaddr_in>()?.pointed?.sin_addr?.s_addr ?: continue
            out += "${ip.joinToString(".")}/${mask.countOneBits()}"
        }
        freeifaddrs(list.value)
        out.toList()
    }

    private val SKIPPED = listOf("pdp_ip", "utun", "ipsec", "awdl", "llw", "lo")

    /** An address in network order (as it sits in memory) to its four numbers. */
    private fun UInt.octets(): List<Int> = List(4) { ((this shr (8 * it)) and 0xFFu).toInt() }

    /** 10/8, 172.16/12 and 192.168/16, as java.net's isSiteLocalAddress on Android. */
    private fun siteLocal(ip: List<Int>): Boolean =
        ip[0] == 10 || (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 192 && ip[1] == 168)

    /** A connect that gives up after [timeoutMs]: non-blocking, then a poll for the answer. */
    override fun tryTcp(host: String, port: Int, timeoutMs: Int): Boolean = memScoped {
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { p -> p.size == 4 && p.all { it in 0..255 } }
            ?: return false
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return false
        try {
            fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) or O_NONBLOCK)
            val addr = alloc<sockaddr_in>().apply { fill(parts, port) }
            if (connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) return true
            if (errno != EINPROGRESS) return false
            val wait = alloc<pollfd>().apply {
                this.fd = fd
                events = POLLOUT.toShort()
            }
            if (poll(wait.ptr, 1u, timeoutMs) <= 0) return false
            val error = alloc<IntVar>()
            val size = alloc<socklen_tVar>().apply { value = sizeOf<IntVar>().convert() }
            getsockopt(fd, SOL_SOCKET, SO_ERROR, error.ptr, size.ptr) == 0 && error.value == 0
        } finally {
            close(fd)
        }
    }

    /**
     * iOS has no callback for one Wi-Fi network coming or going that this
     * needs, so the phone's own subnets are read every few seconds: a new one
     * is a network to search, a gone one is a network lost. Which of them is
     * the rider's own Wi-Fi (Android's interference signal for a hotspot that
     * shares one radio) is not told apart here.
     */
    override fun watch(events: HudNetworkEvents) {
        if (watching != null) return
        watching = scope.launch {
            var before = candidateIpv4Cidrs().toSet()
            while (isActive) {
                delay(WATCH_EVERY_MS)
                val now = candidateIpv4Cidrs().toSet()
                if ((now - before).isNotEmpty()) events.onAvailable()
                if ((before - now).isNotEmpty()) events.onLost(wasOwnWifi = false)
                before = now
            }
        }
    }

    override fun unwatch() {
        watching?.cancel()
        watching = null
    }

    // iOS has no Wi-Fi lock; the radio follows the app being awake.
    override fun holdRadioAwake(hold: Boolean) = Unit

    private const val WATCH_EVERY_MS = 3_000L
}

/** [parts] and [port] into a `sockaddr_in`, both in network order. */
@OptIn(ExperimentalForeignApi::class)
private fun sockaddr_in.fill(parts: List<Int>, port: Int) {
    sin_len = sizeOf<sockaddr_in>().convert()
    sin_family = AF_INET.convert()
    sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()
    sin_addr.s_addr = parts[0].toUInt() or (parts[1].toUInt() shl 8) or (parts[2].toUInt() shl 16) or (parts[3].toUInt() shl 24)
}

/**
 * Hands the HUD its map pictures, as Android's HudTileServer:
 * `GET /tiles/z/x/y.png?layer=<id>` on [port], a picture, a 204 where the
 * provider has nothing that deep, or a 404. One request per connection, on
 * every interface (the HUD reaches the phone over the hotspot). What a
 * request and an answer look like is the shared [HudTileHttp].
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosHudTiles(
    private val port: Int,
    /** The picture for a tile, an empty array for "nothing this deep", or
     *  null. [layer] is null when the request named none. */
    private val tile: (z: Int, x: Int, y: Int, layer: String?) -> ByteArray?,
) : HudTiles {
    private val lock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listening: Job? = null
    /** As many tiles at a time as Android's workers: a HUD asks for a screenful at once. */
    private val drawing = Semaphore(WORKERS)

    override fun start(): Boolean = synchronized(lock) {
        if (listening != null) return true
        val fd = listen()
        if (fd < 0) {
            Log.w(TAG, "tile server bind $port failed: ${strerror(errno)?.toKString()}")
            return false
        }
        listening = scope.launch { accept(fd) }
        true
    }

    override fun stop(): Unit = synchronized(lock) {
        listening?.cancel()
        listening = null
    }

    private fun listen(): Int = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return -1
        val on = alloc<IntVar>().apply { value = 1 }
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, on.ptr, sizeOf<IntVar>().convert())
        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) or O_NONBLOCK)
        val addr = alloc<sockaddr_in>().apply { fill(listOf(0, 0, 0, 0), port) }
        if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0 || listen(fd, BACKLOG) != 0) {
            close(fd)
            return -1
        }
        fd
    }

    /** Waits for the HUD in half-second looks, so a stop is seen without a connection to wake it. */
    private suspend fun accept(fd: Int) {
        try {
            while (scope.isActive && listening?.isActive != false) {
                val ready = memScoped {
                    val wait = alloc<pollfd>().apply {
                        this.fd = fd
                        events = POLLIN.toShort()
                    }
                    poll(wait.ptr, 1u, 500)
                }
                kotlinx.coroutines.yield()
                if (ready <= 0) continue
                val c = accept(fd, null, null)
                if (c < 0) continue
                scope.launch { serve(c) }
            }
        } finally {
            close(fd)
        }
    }

    private suspend fun serve(c: Int) {
        try {
            memScoped {
                val on = alloc<IntVar>().apply { value = 1 }
                // A HUD that hung up mid-answer must not end the app with SIGPIPE.
                setsockopt(c, SOL_SOCKET, SO_NOSIGPIPE, on.ptr, sizeOf<IntVar>().convert())
                fcntl(c, F_SETFL, fcntl(c, F_GETFL, 0) and O_NONBLOCK.inv())
                val limit = alloc<timeval>().apply { tv_sec = TIMEOUT_S }
                setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, limit.ptr, sizeOf<timeval>().convert())
                setsockopt(c, SOL_SOCKET, SO_SNDTIMEO, limit.ptr, sizeOf<timeval>().convert())
            }
            val line = requestLine(c)
            val layer = HudTileHttp.layerOf(line)
            val body = HudTileHttp.parse(line)?.let { (z, x, y) ->
                drawing.withPermit { runCatching { tile(z, x, y, layer) }.getOrNull() }
            }
            val head = HudTileHttp.head(body?.size).encodeToByteArray()
            if (sendAll(c, head) && body != null && body.isNotEmpty()) sendAll(c, body)
        } finally {
            close(c)
        }
    }

    /** The first line of the request, which is all a tile request is read for. */
    private fun requestLine(c: Int): String = memScoped {
        val buf = allocArray<ByteVar>(MAX_REQUEST)
        var have = 0
        while (have < MAX_REQUEST) {
            val n = recv(c, buf + have, (MAX_REQUEST - have).convert(), 0).toInt()
            if (n <= 0) break
            have += n
            if ((0 until have).any { buf[it] == '\n'.code.toByte() }) break
        }
        ByteArray(have) { buf[it] }.decodeToString().substringBefore('\n')
    }

    private fun sendAll(c: Int, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var sent = 0
        bytes.usePinned { pinned ->
            while (sent < bytes.size) {
                val n = send(c, pinned.addressOf(sent), (bytes.size - sent).convert(), 0).toInt()
                if (n <= 0) return false
                sent += n
            }
        }
        return true
    }

    private companion object {
        // A HUD with no signal asks for every tile on its screen at once.
        const val BACKLOG = 64
        // A HUD screen is a dozen tiles and each may wait on the network.
        const val WORKERS = 6
        const val TIMEOUT_S = 8L
        const val MAX_REQUEST = 2048
    }
}
