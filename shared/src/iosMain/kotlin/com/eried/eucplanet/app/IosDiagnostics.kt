@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.diagnostics.CrashReport
import com.eried.eucplanet.diagnostics.DiagnosticsPlatform
import com.eried.eucplanet.diagnostics.WheelDiagnosticsViewModel
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.platform.formatLocalDateUs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.first
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.currentLocale
import platform.Foundation.localTimeZone
import platform.Foundation.localeIdentifier
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceStyle
import platform.posix.uname
import platform.posix.utsname

/**
 * Service Mode on the iPhone: the shared Wheel Diagnostics dialog and view
 * model over [IosDiagnosticsPlatform], and the log shared through the share
 * sheet under the same diagnostics-<stamp>.txt name Android gives it.
 */
internal object IosDiagnostics {
    fun viewModel() = WheelDiagnosticsViewModel(
        IosAppGraph.link, IosAppGraph.adapter, IosAppGraph.wheel, IosAppGraph.settings, IosDiagnosticsPlatform,
    )

    /** Writes [text] to a temp file named [fileName] and opens the share sheet on it. */
    fun share(fileName: String, text: String) {
        val dir = NSTemporaryDirectory() + "diagnostics"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val path = "$dir/$fileName"
        val ok = NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null)
        if (!ok) {
            com.eried.eucplanet.diagnostics.DiagnosticsLogger.note("share failed: could not write $fileName")
            return
        }
        shareFile(path)
    }
}

/** Opens the share sheet on the file at [path], as Android's ACTION_SEND chooser. */
internal fun shareFile(path: String) {
    val top = topViewController() ?: return
    val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
    top.presentViewController(sheet, animated = true, completion = null)
}

/** The iPhone's facts for the start of a Service Mode capture, shaped like Android's lines. */
@OptIn(ExperimentalForeignApi::class)
internal object IosDiagnosticsPlatform : DiagnosticsPlatform {
    override val listsInstalledApps = false

    override fun sessionInfo(): List<String> = buildList {
        val info = NSBundle.mainBundle.infoDictionary
        add("app v${info?.get("CFBundleShortVersionString") ?: "?"} (${info?.get("CFBundleVersion") ?: "?"})")
        add("package ${NSBundle.mainBundle.bundleIdentifier ?: "?"}")
        val device = UIDevice.currentDevice
        add("phone Apple ${device.model} (${machine()})")
        add("ios ${device.systemVersion}  abi=arm64")
        add("locale ${NSLocale.currentLocale.localeIdentifier}  tz=${NSTimeZone.localTimeZone.name}")
        val screen = UIScreen.mainScreen
        val dark = screen.traitCollection.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark
        add("display ${displaySize()} @ ${screen.scale}x (${if (dark) "dark" else "light"} theme)")
        add("memory: ${NSProcessInfo.processInfo.physicalMemory / (1024uL * 1024uL)} MB total")
        storage()?.let { add(it) }
        device.batteryMonitoringEnabled = true
        val level = device.batteryLevel
        val charging = device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull
        add("phone battery: ${if (level >= 0f) (level * 100).toInt() else -1}%${if (charging) " (charging)" else ""}")
        add("ble adapter ${if (IosAppGraph.link.bluetoothOn.value) "ON" else "OFF/missing"}")
    }

    override suspend fun trips(): List<TripRecord> = IosAppGraph.database.tripDao().observeAll().first()

    override fun installedApps(): List<String>? = null

    override fun crashes(): List<Pair<String, Long>> = IosCrashLog.list().map { it.name to it.size }

    fun displaySize(): String {
        val bounds = UIScreen.mainScreen.nativeBounds
        return bounds.useContents { "${size.width.toInt()}x${size.height.toInt()}" }
    }

    private fun storage(): String? {
        val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(IosAppGraph.documents, null) ?: return null
        val free = (attrs[NSFileSystemFreeSize] as? NSNumber)?.doubleValue ?: return null
        val total = (attrs[NSFileSystemSize] as? NSNumber)?.doubleValue ?: return null
        val gb = 1024.0 * 1024.0 * 1024.0
        return "storage: %.1f / %.1f GB free".format(free / gb, total / gb)
    }
}

/** The hardware model, "iPhone15,2" (UIDevice only says "iPhone"). */
@OptIn(ExperimentalForeignApi::class)
internal fun machine(): String = memScoped {
    val u = alloc<utsname>()
    if (uname(u.ptr) == 0) u.machine.toKString() else "?"
}

/**
 * The crash log on the iPhone, the same file Android's CrashHandler writes
 * ([CrashReport]): an uncaught Kotlin exception lands in Documents/crashes as
 * crash_<stamp>.txt, newest 20 kept, and About shares the newest.
 *
 * Only Kotlin exceptions reach this hook. A crash in Swift or the system
 * (a fatalError, a bad memory access, iOS killing the app for memory or a
 * stuck main thread) never runs Kotlin code again; those are in iOS's own
 * crash reports (Settings -> Privacy and Security -> Analytics Data, and
 * TestFlight's crash feedback). iOS has no Downloads folder for a public
 * copy, so a crash on open leaves only the in-app file and the iOS report.
 */
internal object IosCrashLog {
    class Entry(val name: String, val path: String, val size: Long, val modifiedMs: Long)

    val dir: String get() = "${IosAppGraph.documents}/crashes"

    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    fun install() {
        val previous = kotlin.native.setUnhandledExceptionHook { t ->
            runCatching { write(t) }
            // The runtime ends the app after the hook, as Android's default handler does.
        }
        if (previous != null) Log.w("CrashLog", "replaced an earlier unhandled exception hook")
    }

    fun write(t: Throwable) {
        val now = System.currentTimeMillis()
        val stamp = CrashReport.stamp(now)
        val info = NSBundle.mainBundle.infoDictionary
        val device = UIDevice.currentDevice
        val header = listOf(
            // Java's Date.toString() layout, which Android's crash header carries.
            "Time: ${formatLocalDateUs("EEE MMM dd HH:mm:ss zzz yyyy", now)}",
            "App: ${NSBundle.mainBundle.bundleIdentifier ?: "?"} ${info?.get("CFBundleShortVersionString") ?: "?"}",
            "iOS: ${device.systemVersion}",
            "Device: Apple ${device.model} (${machine()})",
            "ABI: arm64",
            "Display: ${IosDiagnosticsPlatform.displaySize()} @${UIScreen.mainScreen.scale}x",
            "Locale: ${NSLocale.currentLocale.localeIdentifier}",
            "Memory: ${NSProcessInfo.processInfo.physicalMemory / (1024uL * 1024uL)}MB device",
            "Thread: ${if (NSThread.isMainThread) "main" else (NSThread.currentThread.name?.takeIf { it.isNotBlank() } ?: "background")}",
        )
        val report = CrashReport.report(header, t.stackTraceToString())
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        NSString.create(string = report).writeToFile("$dir/${CrashReport.fileName(stamp)}", true, NSUTF8StringEncoding, null)
        val all = list()
        CrashReport.beyondRetention(all.map { it.name to it.modifiedMs })
            .forEach { NSFileManager.defaultManager.removeItemAtPath("$dir/$it", null) }
    }

    /** The saved crash logs, newest first. */
    fun list(): List<Entry> {
        val fm = NSFileManager.defaultManager
        val names = fm.contentsOfDirectoryAtPath(dir, null)?.filterIsInstance<String>() ?: return emptyList()
        return names.filter { CrashReport.isCrashLog(it) }.map { name ->
            val path = "$dir/$name"
            val attrs = fm.attributesOfItemAtPath(path, null)
            val size = (attrs?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
            val modified = ((attrs?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970 ?: 0.0) * 1000
            Entry(name, path, size, modified.toLong())
        }.sortedByDescending { it.modifiedMs }
    }

    /** Opens the share sheet on the newest crash log; false when there is none. */
    fun shareLatest(): Boolean {
        val latest = list().firstOrNull() ?: return false
        shareFile(latest.path)
        return true
    }
}
