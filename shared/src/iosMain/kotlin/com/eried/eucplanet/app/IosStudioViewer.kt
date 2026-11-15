package com.eried.eucplanet.app

import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.CoreFoundation.CFURLRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.ImageIO.CGAnimateImageAtURLWithBlock
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIBarButtonSystemItem
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UINavigationController
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewContentMode
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController
import platform.UIKit.navigationController
import platform.UIKit.navigationItem
import platform.UIKit.popoverPresentationController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The last few photos and clips the studio saved, kept in the app's caches
 * next to their photo library copy. The library is add only, so the app cannot
 * read an asset back, and iOS has no URL that opens one asset in Photos; the
 * View action shows this copy in the app instead, as Android opens the exact
 * MediaStore item. Each file is named "<saved ms>_<identifier>.<ext>", so the
 * oldest sort first and go once more than [keep] are held.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosSavedMedia(private val dir: String, private val keep: Int = KEEP) {

    /** Moves [path] (the file just saved as [id], named [name]) into the folder. */
    fun keepFile(id: String, path: String, name: String, nowMs: Long = now()) {
        if (!ensureDir()) return
        val target = "$dir/${fileName(id, name, nowMs)}"
        if (!NSFileManager.defaultManager.moveItemAtPath(path, toPath = target, error = null)) {
            Log.w(TAG, "Saved copy not kept: $name")
        }
        prune()
    }

    /** Writes [bytes] (the photo just saved as [id], named [name]) into the folder. */
    fun keepBytes(id: String, bytes: ByteArray, name: String, nowMs: Long = now()) {
        if (!ensureDir()) return
        if (!bytes.toNSData().writeToFile("$dir/${fileName(id, name, nowMs)}", atomically = true)) {
            Log.w(TAG, "Saved copy not written: $name")
        }
        prune()
    }

    /** The kept copy of the item saved as [id], or null when it is gone. */
    fun pathFor(id: String): String? {
        val tag = "_${safe(id)}."
        return files().lastOrNull { it.contains(tag) }?.let { "$dir/$it" }
    }

    private fun prune() {
        val all = files()
        all.take((all.size - keep).coerceAtLeast(0)).forEach {
            NSFileManager.defaultManager.removeItemAtPath("$dir/$it", null)
        }
    }

    private fun files(): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null)
            ?.filterIsInstance<String>()
            .orEmpty()
            .sorted()

    private fun ensureDir(): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(dir) ||
            NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)

    companion object {
        private const val TAG = "OverlayStudio"

        // A take can be large; the View action only needs the newest.
        const val KEEP = 2

        val shared: IosSavedMedia by lazy {
            val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: platform.Foundation.NSTemporaryDirectory()
            IosSavedMedia("$caches/studio_saved")
        }

        /** "1763164028000_ABC_L0_001.png": a fixed-width time, the identifier made file safe, the extension. */
        fun fileName(id: String, name: String, nowMs: Long): String {
            val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 } ?: "bin"
            return nowMs.toString().padStart(15, '0') + "_" + safe(id) + "." + ext.lowercase()
        }

        private fun safe(id: String): String = id.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")

        private fun now(): Long = com.eried.eucplanet.platform.System.currentTimeMillis()
    }
}

/**
 * Shows a saved photo or clip in the app: the image (a GIF or APNG animated)
 * or the video with the system player, under a bar that stays up with Done
 * to close it and the system share button. Quick Look was tried first, but
 * it opens these full screen with its bar hidden until the rider taps.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosMediaViewer {
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v")

    /** Presents [path]; false when there is no screen to present it on. */
    fun show(path: String): Boolean {
        val top = topViewController() ?: return false
        val video = path.substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS
        val nav = UINavigationController(rootViewController = ViewerController(NSURL.fileURLWithPath(path), video))
        nav.modalPresentationStyle = UIModalPresentationFullScreen
        top.presentViewController(nav, animated = true, completion = null)
        return true
    }

    /**
     * SIMCTL_CHILD_EUC_STUDIO_VIEW=1 opens each item once it is saved, as the
     * snackbar's View does, for scripted runs (the simulator cannot be tapped).
     */
    fun openIfScripted(id: String) {
        if (NSProcessInfo.processInfo.environment["EUC_STUDIO_VIEW"] != "1") return
        dispatch_async(dispatch_get_main_queue()) {
            IosSavedMedia.shared.pathFor(id)?.let { show(it) }
        }
    }

    private class ViewerController(private val url: NSURL, private val video: Boolean) :
        UIViewController(nibName = null, bundle = null) {
        private var player: AVPlayer? = null
        private var stopAnimation = false

        override fun viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = UIColor.blackColor
            navigationItem.leftBarButtonItem = UIBarButtonItem(
                barButtonSystemItem = UIBarButtonSystemItem.UIBarButtonSystemItemDone,
                target = this, action = NSSelectorFromString("done"),
            )
            navigationItem.rightBarButtonItem = UIBarButtonItem(
                barButtonSystemItem = UIBarButtonSystemItem.UIBarButtonSystemItemAction,
                target = this, action = NSSelectorFromString("share"),
            )
            if (video) showVideo() else showImage()
        }

        private fun showVideo() {
            val p = AVPlayer(uRL = url)
            player = p
            val pc = AVPlayerViewController()
            pc.player = p
            addChildViewController(pc)
            pc.view.setFrame(view.bounds)
            pc.view.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            view.addSubview(pc.view)
            pc.didMoveToParentViewController(this)
            p.play()
        }

        private fun showImage() {
            val image = UIImageView(frame = view.bounds)
            image.contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
            image.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            view.addSubview(image)
            image.image = UIImage.imageWithContentsOfFile(url.path ?: "")
            // A GIF or APNG plays its frames; a still image is one frame.
            val cfUrl = CFBridgingRetain(url) as CFURLRef?
            CGAnimateImageAtURLWithBlock(cfUrl, null) { _, frame, stop ->
                if (stopAnimation) stop?.pointed?.value = true
                else if (frame != null) image.image = UIImage.imageWithCGImage(frame)
            }
            CFBridgingRelease(cfUrl)
        }

        override fun viewWillDisappear(animated: Boolean) {
            super.viewWillDisappear(animated)
            if (isBeingDismissed() || navigationController?.isBeingDismissed() == true) {
                stopAnimation = true
                player?.pause()
            }
        }

        @ObjCAction
        fun done() {
            dismissViewControllerAnimated(true, completion = null)
        }

        @ObjCAction
        fun share() {
            val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
            sheet.popoverPresentationController?.barButtonItem = navigationItem.rightBarButtonItem
            presentViewController(sheet, animated = true, completion = null)
        }
    }
}
