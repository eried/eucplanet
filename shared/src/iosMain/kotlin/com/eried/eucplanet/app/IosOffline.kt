package com.eried.eucplanet.app

import btools.util.joinPath
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.OfflineNav
import com.eried.eucplanet.nav.RouteAvoidances
import com.eried.eucplanet.nav.offline.BrouterProfiles
import com.eried.eucplanet.nav.offline.FileSink
import com.eried.eucplanet.nav.offline.OfflineFiles
import com.eried.eucplanet.nav.offline.OfflineRouter
import com.eried.eucplanet.nav.offline.OfflineTileStore
import com.eried.eucplanet.nav.offline.map.OfflineMapDrawer
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toByteArray
import com.eried.eucplanet.platform.toNSData
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.ui.settings.offline.OfflineDataPorts
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.Network.nw_path_get_status
import platform.WebKit.WKContentRuleList
import platform.WebKit.WKContentRuleListStore
import platform.WebKit.WKUserContentController
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.Foundation.NSThread
import platform.Foundation.NSQualityOfServiceUserInitiated
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.darwin.dispatch_get_global_queue
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * [OfflineFiles] on the iPhone: NSFileManager for what a folder holds and
 * the dates, POSIX for renames and the downloads' writes.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosOfflineFiles : OfflineFiles {
    private val fm get() = NSFileManager.defaultManager

    private fun attrs(path: String): Map<Any?, *>? = fm.attributesOfItemAtPath(path, null)

    override fun isFile(path: String): Boolean = attrs(path)?.get(NSFileType) == NSFileTypeRegular

    override fun length(path: String): Long = (attrs(path)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    override fun lastModified(path: String): Long =
        (attrs(path)?.get(NSFileModificationDate) as? NSDate)?.let { (it.timeIntervalSince1970 * 1000).toLong() } ?: 0L

    override fun setLastModified(path: String, ms: Long): Boolean =
        fm.setAttributes(mapOf(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)), path, null)

    override fun list(dir: String): List<String> =
        fm.contentsOfDirectoryAtPath(dir, null).orEmpty().map { it.toString() }.filter { isFile(joinPath(dir, it)) }

    override fun delete(path: String): Boolean = fm.fileExistsAtPath(path) && fm.removeItemAtPath(path, null)

    override fun rename(from: String, to: String): Boolean = platform.posix.rename(from, to) == 0

    override fun mkdirs(dir: String) {
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    }

    override fun readText(path: String): String? = NSData.dataWithContentsOfFile(path)?.toByteArray()?.decodeToString()

    override fun writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())

    override fun writeBytes(path: String, bytes: ByteArray) {
        check(bytes.toNSData().writeToFile(path, atomically = true)) { "could not write $path" }
    }

    override fun copy(from: String, to: String) {
        delete(to)
        check(fm.copyItemAtPath(from, to, null)) { "could not copy $from" }
    }

    override fun openWrite(path: String, append: Boolean): FileSink {
        val f = fopen(path, if (append) "ab" else "wb") ?: throw btools.util.IOException("cannot write $path")
        return PosixSink(f)
    }

    override fun freeBytes(dir: String): Long =
        (fm.attributesOfFileSystemForPath(dir, null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L

    private class PosixSink(private val f: kotlinx.cinterop.CPointer<FILE>) : FileSink {
        override fun write(buf: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            val n = buf.usePinned { fwrite(it.addressOf(off), 1u, len.toULong(), f) }
            if (n.toInt() != len) throw btools.util.IOException("short write")
        }

        override fun close() {
            fclose(f)
        }
    }
}

/**
 * Where the offline areas live on the iPhone: Application Support/offline/
 * routing, excluded from backup (Android keeps them in the app's own
 * storage, not backed up either), with the router's profiles beside them.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosOfflineStorage {
    val dir: String by lazy {
        val base = NSFileManager.defaultManager.URLForDirectory(
            NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
        ) as NSURL
        val offline = base.URLByAppendingPathComponent("offline", isDirectory = true)!!
        NSFileManager.defaultManager.createDirectoryAtURL(offline, true, null, null)
        if (!offline.setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)) {
            Log.w("OfflineTiles", "could not exclude the offline folder from backup")
        }
        val routing = offline.URLByAppendingPathComponent("routing", isDirectory = true)!!
        NSFileManager.defaultManager.createDirectoryAtURL(routing, true, null, null)
        routing.path!!
    }

    val store: OfflineTileStore by lazy { OfflineTileStore(dir, IosOfflineFiles) }
}

/**
 * BRouter's profiles on the iPhone: written from the app's resources (the
 * same files Android ships in its APK, copied in by generateIosAppArt) into
 * the tile folder once per run, so an update brings a new wheel profile. The
 * tag table downloaded with the tiles wins over the bundled one, as on
 * Android (AndroidBrouterProfiles).
 */
internal class IosBrouterProfiles(private val store: OfflineTileStore) : BrouterProfiles {
    private val lock = SynchronizedObject()
    private var written = false

    @OptIn(ExperimentalResourceApi::class)
    override fun folder(): String = synchronized(lock) {
        val files = store.files
        val profiles = joinPath(store.dir, "profiles")
        files.mkdirs(profiles)
        if (!written) {
            runBlocking {
                for (name in BUNDLED) files.writeBytes(joinPath(profiles, name), Res.readBytes("files/brouter/$name"))
            }
            written = true
        }
        val downloaded = store.lookupsFile()
        val used = joinPath(profiles, "lookups.dat")
        if (files.isFile(downloaded) && files.lastModified(downloaded) != files.lastModified(used)) {
            files.copy(downloaded, used)
            files.setLastModified(used, files.lastModified(downloaded))
        }
        profiles
    }

    private companion object {
        val BUNDLED = listOf("wheel.brf", "hiking-mountain.brf", "lookups.dat")
    }
}

/**
 * The phone's connection, from NWPathMonitor: [online] when a path is up,
 * [unmetered] when it is not an expensive one (Wi-Fi rather than cellular
 * or a hotspot). [forcedOffline] (the EUC_NO_NETWORK launch hook) makes the
 * app act as if there were none.
 */
internal object IosNetwork {
    @Volatile var forcedOffline = false
        private set

    /** Blocks every http and https load in the app's web views. */
    @Volatile private var webBlock: WKContentRuleList? = null

    /**
     * Acts as if the phone had no connection: the app's own requests fail,
     * the web views load nothing from the network (a content rule list, so
     * the offline map's own scheme still loads), and the router and the map
     * see no connection. For a scripted check of the offline map and
     * routing on the simulator, which shares the Mac's network.
     */
    fun forceOffline() {
        forcedOffline = true
        com.eried.eucplanet.platform.IosHttpSwitch.offline = true
        var compiled = false
        WKContentRuleListStore.defaultStore()?.compileContentRuleListForIdentifier(
            "euc-no-network",
            encodedContentRuleList = "[{\"trigger\":{\"url-filter\":\"^https?://\"},\"action\":{\"type\":\"block\"}}]",
        ) { list, error ->
            if (list == null) Log.w("Network", "no web block: ${error?.localizedDescription}")
            webBlock = list
            compiled = true
        }
        // The first web view may be built in the next frame: wait here (the
        // launch, on the main thread, where the answer arrives) for the list.
        val until = NSDate.dateWithTimeIntervalSinceNow(3.0)
        while (!compiled && NSDate().timeIntervalSince1970 < until.timeIntervalSince1970) {
            NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
        }
        Log.i("Network", "offline forced, web block ${if (webBlock != null) "on" else "missing"}")
    }

    /** Adds the block to a web view's [content] while offline is forced. */
    fun blockWeb(content: WKUserContentController) {
        webBlock?.let { content.addContentRuleList(it) }
    }

    private val _online = MutableStateFlow(true)
    private val _unmetered = MutableStateFlow(true)
    private val started = SynchronizedObject()
    private var monitoring = false

    val online: StateFlow<Boolean> get() = start().let { _online.asStateFlow() }
    val unmetered: StateFlow<Boolean> get() = start().let { _unmetered.asStateFlow() }

    fun isOnline(): Boolean = !forcedOffline && online.value
    fun onWifi(): Boolean = isOnline() && unmetered.value

    private fun start() = synchronized(started) {
        if (monitoring) return@synchronized
        monitoring = true
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            _online.value = nw_path_get_status(path) == nw_path_status_satisfied
            _unmetered.value = !nw_path_is_expensive(path)
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u))
        nw_path_monitor_start(monitor)
    }
}

/**
 * BRouter on the downloaded tiles, the offline map's addresses and the
 * connection check, as Android's AndroidOfflineNav.
 */
internal class IosOfflineNav(
    private val router: OfflineRouter,
    private val store: OfflineTileStore,
    private val map: OfflineMapDrawer,
) : OfflineNav {
    override fun covers(tiles: Set<String>) = store.covers(tiles)

    override suspend fun route(
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        avoid: RouteAvoidances,
        heading: Double?,
    ): NavRoute? = router.route(name, waypoints, mode, avoid, heading)

    override fun addressAt(lat: Double, lng: Double): String? = map.addressAt(lat, lng)

    override fun isOnline(): Boolean = IosNetwork.isOnline()
}

/** The rider's last place ([IosLocation]) and the Wi-Fi check, for the offline areas screen. */
internal object IosOfflineDataPorts : OfflineDataPorts {
    override fun lastKnownLocation(): GeoPoint? =
        IosLocation.lastKnown.value?.let { GeoPoint(it.latitude, it.longitude) }

    override fun onWifi(): Boolean = IosNetwork.onWifi()
}

/**
 * [engine] on a thread of its own with a 64 MB stack. BRouter walks a
 * segment's nodes recursively (OsmNodesMap.minVisitIdInSubtree), deeper than
 * the half megabyte a Kotlin/Native worker thread has: on Dispatchers.IO it
 * overflowed the stack. The JVM's frames are smaller, so Android's threads do.
 */
internal fun bigStack(engine: OfflineRouter.Engine): OfflineRouter.Engine = OfflineRouter.Engine { req, ms, stop ->
    val done = dispatch_semaphore_create(0)
    var result: OfflineRouter.EngineResult? = null
    var failure: Throwable? = null
    val thread = NSThread {
        try {
            result = engine.run(req, ms, stop)
        } catch (t: Throwable) {
            failure = t
        }
        dispatch_semaphore_signal(done)
    }
    thread.stackSize = 64uL * 1024uL * 1024uL
    // Ahead of the map drawing: a reroute is waited for.
    thread.qualityOfService = NSQualityOfServiceUserInitiated
    thread.name = "BRouter"
    thread.start()
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
    failure?.let { throw it }
    result
}
