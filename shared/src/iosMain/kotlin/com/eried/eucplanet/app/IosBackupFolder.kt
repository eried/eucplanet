package com.eried.eucplanet.app

import com.eried.eucplanet.data.sync.BackupFolder
import com.eried.eucplanet.data.sync.BackupFolderEntry
import com.eried.eucplanet.platform.Log
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkResolutionWithoutUI
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * A folder on the iPhone as the shared [BackupFolder]: plain paths under
 * [root], read and written with NSFileManager and NSData. [scope] is the
 * security-scoped URL of a folder picked in the Files app; every call is
 * wrapped in its startAccessingSecurityScopedResource and the matching stop.
 * Without one (the app's own Documents) the paths are the app's to use.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPathFolder(
    private val root: String,
    private val scope: NSURL? = null,
    private val shownAs: String? = null,
) : BackupFolder {
    private val fm get() = NSFileManager.defaultManager

    private fun abs(path: String) = if (path.isEmpty()) root else "$root/$path"

    private inline fun <T> scoped(block: () -> T): T {
        val started = scope?.startAccessingSecurityScopedResource() == true
        try {
            return block()
        } finally {
            if (started) scope.stopAccessingSecurityScopedResource()
        }
    }

    override val displayName: String? get() = shownAs ?: root.substringAfterLast('/')

    private fun isDirectory(path: String): Boolean? {
        val attrs = fm.attributesOfItemAtPath(path, null) ?: return null
        return attrs[NSFileType] == NSFileTypeDirectory
    }

    override fun list(dir: String): List<BackupFolderEntry>? = scoped {
        val path = abs(dir)
        if (isDirectory(path) != true) return@scoped null
        val names = fm.contentsOfDirectoryAtPath(path, null) ?: return@scoped null
        names.mapNotNull { it as? String }.mapNotNull { name ->
            val attrs = fm.attributesOfItemAtPath("$path/$name", null) ?: return@mapNotNull null
            BackupFolderEntry(
                name = name,
                isDirectory = attrs[NSFileType] == NSFileTypeDirectory,
                size = (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: 0L,
                lastModified = (attrs[NSFileModificationDate] as? NSDate)
                    ?.let { (it.timeIntervalSince1970 * 1000).toLong() } ?: 0L,
            )
        }
    }

    override fun read(path: String): ByteArray? = scoped {
        if (isDirectory(abs(path)) != false) return@scoped null
        NSData.dataWithContentsOfFile(abs(path))?.toBytes()
    }

    override fun readHead(path: String, max: Int): ByteArray? = scoped {
        if (isDirectory(abs(path)) != false) return@scoped null
        val handle = NSFileHandle.fileHandleForReadingAtPath(abs(path)) ?: return@scoped null
        try {
            handle.readDataOfLength(max.toULong()).toBytes()
        } finally {
            handle.closeFile()
        }
    }

    override fun write(path: String, bytes: ByteArray): Boolean = scoped {
        val target = abs(path)
        if (isDirectory(target.substringBeforeLast('/')) != true) return@scoped false
        if (isDirectory(target) == true) return@scoped false
        // Atomic: the whole file or the one that was there, never half of it.
        bytes.toNSData().writeToFile(target, true)
    }

    override fun delete(path: String): Boolean = scoped {
        if (path.isEmpty() || isDirectory(abs(path)) == null) return@scoped false
        fm.removeItemAtPath(abs(path), null)
    }

    override fun ensureDirectory(path: String): Boolean = scoped {
        when (isDirectory(abs(path))) {
            true -> true
            false -> false
            null -> fm.createDirectoryAtPath(abs(path), true, null, null)
        }
    }

    override fun moveInto(path: String, toDir: String): Boolean = scoped {
        val name = path.substringAfterLast('/')
        val dest = "${abs(toDir)}/$name"
        if (isDirectory(abs(toDir)) != true || isDirectory(dest) != null) return@scoped false
        fm.moveItemAtPath(abs(path), dest, null)
    }
}

/**
 * The backup folder on the iPhone: a folder the rider picks in the Files
 * app (On My iPhone, iCloud Drive or another provider), kept across launches
 * as a security-scoped bookmark in `AppSettings.syncFolderUri`. The value is
 * [PREFIX] and the bookmark's base64, so it is never mistaken for Android's
 * content:// tree URI when settings travel between phones (they do not
 * travel: settings sync leaves the folder out, as every device binding).
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal object IosBackupFolders {
    private const val TAG = "IosBackupFolder"
    const val PREFIX = "iosbookmark:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The app's own Documents as a folder: where themes and presets live while no folder is set. */
    val documents: BackupFolder by lazy { IosPathFolder(IosAppGraph.documents) }

    /** The settings value for the folder at [url], or null when iOS will not make a bookmark of it. */
    fun bookmarkFor(url: NSURL): String? {
        val started = url.startAccessingSecurityScopedResource()
        try {
            val data = url.bookmarkDataWithOptions(0u, null, null, null) ?: return null
            return PREFIX + data.base64EncodedStringWithOptions(0u)
        } finally {
            if (started) url.stopAccessingSecurityScopedResource()
        }
    }

    /** The folder a settings value names, or null when it is no longer reachable. */
    fun open(uri: String): BackupFolder? {
        if (!uri.startsWith(PREFIX)) return null
        val data = NSData.create(base64EncodedString = uri.removePrefix(PREFIX), options = 0u) ?: return null
        val (url, stale) = memScoped {
            val isStale = alloc<BooleanVar>()
            val resolved = NSURL.URLByResolvingBookmarkData(
                data, NSURLBookmarkResolutionWithoutUI, null, isStale.ptr, null,
            )
            resolved to isStale.value
        }
        if (url == null) {
            Log.w(TAG, "The backup folder is no longer reachable (its bookmark does not resolve)")
            return null
        }
        val path = url.path ?: return null
        val started = url.startAccessingSecurityScopedResource()
        val usable = try {
            val fm = NSFileManager.defaultManager
            fm.attributesOfItemAtPath(path, null)?.get(NSFileType) == NSFileTypeDirectory &&
                fm.isWritableFileAtPath(path)
        } finally {
            if (started) url.stopAccessingSecurityScopedResource()
        }
        if (!usable) {
            Log.w(TAG, "The backup folder is no longer reachable: $path")
            return null
        }
        if (stale) {
            // Still there, but iOS wants a fresh bookmark (the folder moved or
            // was renamed). Keep the old one if a new one cannot be made.
            bookmarkFor(url)?.let { fresh ->
                scope.launch { IosAppGraph.settings.update { it.copy(syncFolderUri = fresh) } }
            }
        }
        return IosPathFolder(path, url, shownAs(path))
    }

    /** The folder's path inside the app's container, for the launch hook and its tests. */
    fun bookmarkForPath(path: String): String? {
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        return bookmarkFor(NSURL.fileURLWithPath(path))
    }

    /** Where the folder is, as Files names its places: the place, then the folders in it. */
    internal fun shownAs(path: String): String {
        val fm = NSFileManager.defaultManager
        val parts = path.trimEnd('/').split('/')
        fun shown(upTo: Int) = fm.displayNameAtPath(parts.take(upTo + 1).joinToString("/"))
        fun below(from: Int) = (from + 1 until parts.size).map { shown(it) }
        val cloud = parts.indexOf("com~apple~CloudDocs")
        val onPhone = parts.indexOf("File Provider Storage")
        val app = IosAppGraph.documents.trimEnd('/')
        val all = when {
            // iCloud Drive: the system names its own root.
            cloud >= 0 -> listOf(shown(cloud)) + below(cloud)
            // On My iPhone: the device's own name for itself.
            onPhone >= 0 -> listOf(UIDevice.currentDevice.model) + below(onPhone)
            // The app's own folder, as Files lists it under the device.
            path.startsWith(app) -> {
                val appName = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleDisplayName") as? String
                    ?: NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleName") as? String
                listOfNotNull(UIDevice.currentDevice.model, appName) +
                    path.removePrefix(app).trim('/').split('/').filter { it.isNotEmpty() }
            }
            else -> listOf(shown(parts.size - 1))
        }
        return all.filter { it.isNotEmpty() }.joinToString(" › ")
    }

    /** The Files app, opened at the folder [uri] names, as Android opens its file manager there. */
    fun openInFiles(uri: String) = onMain {
        val data = NSData.create(base64EncodedString = uri.removePrefix(PREFIX), options = 0u) ?: return@onMain
        val path = NSURL.URLByResolvingBookmarkData(data, NSURLBookmarkResolutionWithoutUI, null, null, null)?.path
            ?: return@onMain
        // Files' own scheme for a place on the phone, the file URL's escaped path behind it.
        val fileUrl = NSURL.fileURLWithPath(path).absoluteString ?: return@onMain
        val target = NSURL.URLWithString("shareddocuments://" + fileUrl.removePrefix("file://")) ?: return@onMain
        UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any>(), null)
    }

    // The picker holds its delegate weakly: the open one lives here.
    private var delegate: PickerDelegate? = null

    /** The Files picker for one folder; [onPicked] gets its settings value. */
    fun pick(onPicked: (String) -> Unit) = onMain {
        val top = topViewController() ?: return@onMain
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFolder))
        picker.allowsMultipleSelection = false
        val d = PickerDelegate(onPicked) { delegate = null }
        delegate = d
        picker.delegate = d
        top.presentViewController(picker, animated = true, completion = null)
    }

    private class PickerDelegate(
        private val onPicked: (String) -> Unit,
        private val onDone: () -> Unit,
    ) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            onDone()
            val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
            val value = bookmarkFor(url)
            if (value == null) {
                Log.w(TAG, "No bookmark for the picked folder ${url.lastPathComponent}")
                return
            }
            Log.i(TAG, "Picked backup folder ${url.lastPathComponent}")
            onPicked(value)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onDone()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toBytes(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }
