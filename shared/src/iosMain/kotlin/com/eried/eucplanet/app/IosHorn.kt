@file:OptIn(ExperimentalForeignApi::class)

package com.eried.eucplanet.app

import com.eried.eucplanet.audio.HornImportResult
import com.eried.eucplanet.data.repository.PhoneHorn
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.ui.settings.HornSound
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.darwin.NSObject

/**
 * The rider's own horn sound on the iPhone, Android's HornPlayer: the picked
 * file is copied into the app, so it survives the original being moved, and
 * kept prepared in an AVAudioPlayer, since a horn that waits on a decoder
 * sounds late. The same limits as Android (5 s, 2 MB). While it plays it
 * holds the audio session ducking other audio, as HornPlayer's transient
 * may-duck focus, and lets go when it ends. [dir] keeps the copy.
 */
internal class IosHorn(private val dir: String) : PhoneHorn, HornSound {
    private var player: AVAudioPlayer? = null
    private var releaseJob: Job? = null
    private var holding = false

    init {
        load()
    }

    /** The saved sound, whatever extension it came with (AVAudioPlayer reads the type from it). */
    private fun saved(): String? =
        (NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null) as? List<*>)
            ?.filterIsInstance<String>()
            ?.firstOrNull { it.startsWith(FILE_NAME) }
            ?.let { "$dir/$it" }

    override val isReady: Boolean get() = player != null

    /** [uri] is the path of the copy the document picker made. */
    override fun import(uri: String): HornImportResult {
        val fm = NSFileManager.defaultManager
        val size = (fm.attributesOfItemAtPath(uri, null)?.get(NSFileSize) as? NSNumber)?.longLongValue
            ?: return HornImportResult.UNREADABLE
        if (size > MAX_FILE_BYTES) return HornImportResult.TOO_BIG
        val probe = runCatching { AVAudioPlayer(NSURL.fileURLWithPath(uri), null) }.getOrNull()
            ?: return HornImportResult.UNREADABLE
        if (probe.duration <= 0.0) return HornImportResult.UNREADABLE
        if (probe.duration > MAX_DURATION_S) return HornImportResult.TOO_LONG
        fm.createDirectoryAtPath(dir, true, null, null)
        saved()?.let { fm.removeItemAtPath(it, null) }
        val ext = uri.substringAfterLast('/').substringAfterLast('.', "")
        val dest = "$dir/$FILE_NAME" + if (ext.isNotEmpty()) ".$ext" else ""
        if (!fm.copyItemAtPath(uri, dest, null)) return HornImportResult.UNREADABLE
        load()
        return if (isReady) HornImportResult.OK else HornImportResult.UNREADABLE
    }

    private fun load() {
        player = saved()?.let { path ->
            // Preparing buffers it ahead; play() still works if it could not.
            runCatching { AVAudioPlayer(NSURL.fileURLWithPath(path), null) }.getOrNull()
                ?.also { if (!it.prepareToPlay()) Log.w(TAG, "could not prepare $path ahead") }
        }
    }

    /** Plays the sound once; false when none is ready, so the caller falls back to the wheel's horn. */
    override fun play(): Boolean {
        val p = player ?: return false
        if (!holding) {
            holding = true
            IosAudioSession.acquire(IosAudioSession.Others.DUCK)
        }
        p.currentTime = 0.0
        if (!p.play()) {
            letGo()
            return false
        }
        releaseJob?.cancel()
        releaseJob = MainScope().launch {
            delay((p.duration * 1000).toLong() + 150L)
            letGo()
        }
        return true
    }

    private fun letGo() {
        if (holding) {
            holding = false
            IosAudioSession.release(IosAudioSession.Others.DUCK)
        }
    }

    override fun externalOutputActive(): Boolean = IosAudioSession.externalOutputActive()

    companion object {
        private const val TAG = "HornPlayer"
        private const val MAX_DURATION_S = 5.0
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private const val FILE_NAME = "horn_sound"

        // UIDocumentPickerViewController holds its delegate weakly: the open one lives here.
        private var pickerDelegate: PickerDelegate? = null

        /** The Files picker for one audio file; [onPicked] gets the app's copy and its name. */
        fun pick(onPicked: (path: String, name: String) -> Unit) {
            val top = topViewController() ?: return
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeAudio), asCopy = true)
            picker.allowsMultipleSelection = false
            val d = PickerDelegate(onPicked) { pickerDelegate = null }
            pickerDelegate = d
            picker.delegate = d
            top.presentViewController(picker, animated = true, completion = null)
        }

        private class PickerDelegate(
            private val onPicked: (String, String) -> Unit,
            private val onDone: () -> Unit,
        ) : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                onDone()
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
                val path = url.path ?: return
                Log.i(TAG, "picked ${url.lastPathComponent}")
                onPicked(path, url.lastPathComponent ?: path.substringAfterLast('/'))
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onDone()
        }
    }
}
