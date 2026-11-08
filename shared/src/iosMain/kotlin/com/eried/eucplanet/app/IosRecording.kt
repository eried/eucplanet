package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.eried.eucplanet.data.eucstats.SyncResult
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.ui.recording.ImportedFile
import com.eried.eucplanet.ui.recording.RecordingHost
import com.eried.eucplanet.ui.recording.RecordingOnline
import com.eried.eucplanet.ui.recording.RecordingPlatform
import com.eried.eucplanet.util.ZipFiles
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.StringResource
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeCommaSeparatedText
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeZIP
import platform.darwin.NSObject

/** The iPhone's side of the trip list: its location access, the share sheet and the file picker. */
@OptIn(ExperimentalForeignApi::class)
internal object IosRecordingPlatform : RecordingPlatform {
    private const val TAG = "Recording"

    override fun hasLocationPermission(): Boolean = IosLocation.authorized.value

    override fun shareTripFile(trip: TripRecord) {
        val path = "${IosTrips.tripsDir}/${trip.fileName}"
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return
        share(listOf(NSURL.fileURLWithPath(path)))
    }

    override fun shareText(text: String) = share(listOf(text))

    /**
     * As Android: every trip CSV in one plain trips_export.zip, shared through
     * the share sheet. Written to the temporary folder, not beside the trips.
     */
    override suspend fun exportAllAsZip() {
        val path = withContext(Dispatchers.Default) {
            val dir = IosTrips.tripsDir
            val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null)
                ?.filterIsInstance<String>()?.filter { it.endsWith(".csv") }.orEmpty()
            if (names.isEmpty()) return@withContext null
            val files = IosTripFiles { dir }
            val zip = ZipFiles.write(names.sorted().map { ZipFiles.Entry(it, files.file(it).readBytes()) })
            val path = NSTemporaryDirectory() + "trips_export.zip"
            val data = zip.usePinned { NSData.dataWithBytes(it.addressOf(0), zip.size.toULong()) }
            if (data.writeToFile(path, atomically = true)) path else {
                Log.w(TAG, "Export: could not write $path")
                null
            }
        } ?: return
        withContext(Dispatchers.Main) { share(listOf(NSURL.fileURLWithPath(path))) }
    }

    /** A picked CSV, or the CSVs in a .zip or .dbb (sniffed by its magic, as Android does for an unknown name). */
    override suspend fun readImport(uri: String): List<ImportedFile> {
        val url = NSURL.URLWithString(uri) ?: return emptyList()
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val data = NSData.dataWithContentsOfURL(url) ?: return emptyList()
            val n = data.length.toInt()
            val bytes = if (n == 0) ByteArray(0) else data.bytes!!.readBytes(n)
            val displayName = url.lastPathComponent?.lowercase().orEmpty()
            val isZip = displayName.endsWith(".zip") || displayName.endsWith(".dbb") ||
                (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
            if (isZip) {
                return try {
                    ZipFiles.tripCsvs(bytes).map { ImportedFile(it.name, it.bytes) }
                } catch (e: Exception) {
                    Log.w(TAG, "Import: ${url.lastPathComponent} is not a readable zip: ${e.message}")
                    emptyList()
                }
            }
            val name = url.lastPathComponent?.takeIf { it.isNotBlank() } ?: "imported_${com.eried.eucplanet.platform.System.currentTimeMillis()}.csv"
            return listOf(ImportedFile(name, bytes))
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    override fun text(res: StringResource, vararg args: Any): String = IosPhoneDevice.text(res, *args)

    private fun share(items: List<Any>) {
        val top = topViewController() ?: return
        val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
        top.presentViewController(sheet, animated = true, completion = null)
    }
}

/** The view controller on top, to present the share sheet and the picker from. */
internal fun topViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { it.windows.filterIsInstance<UIWindow>() }
        .firstOrNull { it.isKeyWindow() }
    var vc = window?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

internal object IosRecordingHost : RecordingHost {
    override val supportsTripTools = true

    // The picker holds its delegate weakly: the one open is kept alive here.
    private var pickerDelegate: PickerDelegate? = null

    // iOS has no system back button; the screen's own back arrow is the way out.
    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

    @Composable
    override fun rememberLocationPermissionRequest(onResult: () -> Unit): () -> Unit {
        val callback = rememberUpdatedState(onResult)
        // The answer arrives as a change of authorization, whenever the rider taps.
        LaunchedEffect(Unit) { IosLocation.authorized.drop(1).collect { callback.value() } }
        return { IosLocation.start() }
    }

    @Composable
    override fun rememberTripImportPicker(onPicked: (String) -> Unit): () -> Unit {
        val callback = rememberUpdatedState(onPicked)
        return remember {
            {
                val picker = UIDocumentPickerViewController(
                    forOpeningContentTypes = listOf(UTTypeCommaSeparatedText, UTTypeZIP, UTTypeData),
                    asCopy = true,
                )
                val delegate = PickerDelegate(
                    onPicked = { url -> callback.value(url) },
                    onDone = { pickerDelegate = null },
                )
                pickerDelegate = delegate
                picker.delegate = delegate
                topViewController()?.presentViewController(picker, animated = true, completion = null)
            }
        }
    }

    private class PickerDelegate(
        private val onPicked: (String) -> Unit,
        private val onDone: () -> Unit,
    ) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            (didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.absoluteString?.let(onPicked)
            onDone()
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            onDone()
        }
    }
}
