package com.eried.eucplanet.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toByteArray
import com.eried.eucplanet.ui.studio.camera.StudioVideoHub
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readValue
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.addOutput
import platform.AVFoundation.removeOutput
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setMuted
import platform.AVFoundation.setRate
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.kCMTimeZero
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSItemProvider
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.uuid.Uuid

// The Overlay Studio's media on the iPhone: the frame conversions between
// CoreVideo, CoreGraphics and Skia, the replay faces' clips (AVAsset for the
// frames, AVPlayer for the preview) and the photo library pickers.

private const val TAG = "StudioMedia"

// --- Frames ------------------------------------------------------------------

/** A BGRA pixel buffer (a camera frame, a decoded clip frame) as a Compose image. */
@OptIn(ExperimentalForeignApi::class)
internal fun CVPixelBufferRef.toImageBitmap(): ImageBitmap? {
    CVPixelBufferLockBaseAddress(this, kCVPixelBufferLock_ReadOnly)
    try {
        val w = CVPixelBufferGetWidth(this).toInt()
        val h = CVPixelBufferGetHeight(this).toInt()
        val rowBytes = CVPixelBufferGetBytesPerRow(this).toInt()
        val base = CVPixelBufferGetBaseAddress(this) ?: return null
        if (w <= 0 || h <= 0) return null
        val bytes = ByteArray(rowBytes * h)
        bytes.usePinned { platform.posix.memcpy(it.addressOf(0), base, (rowBytes * h).convert()) }
        val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        return SkImage.makeRaster(info, bytes, rowBytes).toComposeImageBitmap()
    } finally {
        CVPixelBufferUnlockBaseAddress(this, kCVPixelBufferLock_ReadOnly)
    }
}

/** A CoreGraphics image as a Compose image, drawn into BGRA premultiplied pixels. */
@OptIn(ExperimentalForeignApi::class)
internal fun CGImageRef.toImageBitmap(): ImageBitmap? {
    val w = CGImageGetWidth(this).toInt()
    val h = CGImageGetHeight(this).toInt()
    if (w <= 0 || h <= 0) return null
    val rowBytes = w * 4
    val bytes = ByteArray(rowBytes * h)
    val space = CGColorSpaceCreateDeviceRGB()
    bytes.usePinned { pinned ->
        val ctx = CGBitmapContextCreate(
            pinned.addressOf(0), w.convert(), h.convert(), 8u, rowBytes.convert(), space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little,
        )
        CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), this)
        CGContextRelease(ctx)
    }
    CGColorSpaceRelease(space)
    val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    return SkImage.makeRaster(info, bytes, rowBytes).toComposeImageBitmap()
}

/** [frame] drawn scaled into a new Skia surface of [width] x [height]. */
internal fun scaledSurface(frame: ImageBitmap, width: Int, height: Int): Surface {
    val surface = Surface.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
    val image = SkImage.makeFromBitmap(frame.asSkiaBitmap())
    surface.canvas.drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(width.toFloat(), height.toFloat()),
        SamplingMode.LINEAR,
        Paint(),
        true,
    )
    image.close()
    return surface
}

/**
 * [frame] scaled to [width] x [height] as unpremultiplied ARGB ints, what
 * Android's Bitmap.getPixels hands the GIF and APNG encoders.
 */
internal fun framePixels(frame: ImageBitmap, width: Int, height: Int, into: IntArray): Boolean = runCatching {
    if (into.size < width * height) return false
    val surface = scaledSurface(frame, width, height)
    val bitmap = org.jetbrains.skia.Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
    val ok = surface.readPixels(bitmap, 0, 0)
    surface.close()
    if (!ok) return false
    val bytes = bitmap.readPixels() ?: return false
    bitmap.close()
    // BGRA in memory is the little-endian ARGB int.
    var b = 0
    for (i in 0 until width * height) {
        into[i] = (bytes[b].toInt() and 0xFF) or
            ((bytes[b + 1].toInt() and 0xFF) shl 8) or
            ((bytes[b + 2].toInt() and 0xFF) shl 16) or
            ((bytes[b + 3].toInt() and 0xFF) shl 24)
        b += 4
    }
    true
}.getOrElse {
    Log.w(TAG, "Frame read failed", it)
    false
}

/** A copy of [frame] 240 px wide, for the export screen's preview. */
internal fun thumbnail(frame: ImageBitmap): ImageBitmap? = runCatching {
    val tw = 240
    val th = (tw.toLong() * frame.height / frame.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    val surface = scaledSurface(frame, tw, th)
    val out = surface.makeImageSnapshot().toComposeImageBitmap()
    surface.close()
    out
}.getOrNull()

/** The viewport fit names as Compose scales, the same mapping as the shared viewports. */
internal fun contentScaleOf(fit: String): ContentScale = when (fit) {
    "FIT" -> ContentScale.Fit
    "CENTER" -> ContentScale.None
    "STRETCH" -> ContentScale.FillBounds
    else -> ContentScale.Crop
}

// --- Replay clips ------------------------------------------------------------

/**
 * The replay faces' clips, as AndroidStudioVideoHub reads them with
 * MediaMetadataRetriever: whether each opened, its length, and an exact frame
 * (AVAssetImageGenerator with no time tolerance) for the export.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosStudioVideoHub : StudioVideoHub {
    private val durations = mutableStateMapOf<String, Long>()
    private val invalid = mutableStateMapOf<String, Boolean>()
    private val generators = HashMap<String, AVAssetImageGenerator>()

    override fun isInvalid(uri: String): Boolean = invalid[uri] == true
    override fun durationMs(uri: String): Long = durations[uri] ?: 0L

    override fun frameAt(uri: String, timeUs: Long): ImageBitmap? {
        val gen = generators[uri] ?: return null
        val cg = gen.copyCGImageAtTime(CMTimeMake(timeUs, 1_000_000), null, null) ?: return null
        return try { cg.toImageBitmap() } finally { CGImageRelease(cg) }
    }

    suspend fun open(uris: Set<String>) = withContext(Dispatchers.IO) {
        for (uri in uris) {
            if (generators.containsKey(uri) || invalid[uri] == true) continue
            val url = NSURL.URLWithString(uri)
            val asset = url?.let { AVURLAsset.URLAssetWithURL(it, null) }
            val tracks = asset?.tracksWithMediaType(AVMediaTypeVideo).orEmpty()
            if (asset == null || tracks.isEmpty()) {
                Log.w(TAG, "Clip unreadable: $uri")
                invalid[uri] = true
                continue
            }
            val gen = AVAssetImageGenerator(asset)
            gen.appliesPreferredTrackTransform = true
            gen.requestedTimeToleranceBefore = kCMTimeZero.readValue()
            gen.requestedTimeToleranceAfter = kCMTimeZero.readValue()
            gen.maximumSize = CGSizeMake(1920.0, 1920.0)
            generators[uri] = gen
            durations[uri] = (CMTimeGetSeconds(asset.duration) * 1000.0).toLong().coerceAtLeast(0L)
        }
    }

    fun close() = generators.clear()
}

@Composable
internal fun rememberIosVideoHub(uris: Set<String>, enabled: Boolean): StudioVideoHub {
    val hub = remember { IosStudioVideoHub() }
    LaunchedEffect(uris, enabled) { if (enabled) hub.open(uris) }
    DisposableEffect(hub) { onDispose { hub.close() } }
    return hub
}

/**
 * A replay face's clip, muted, playing with the replay clock: the
 * ExoPlayer view Android shows, here an AVPlayer whose frames are drawn as a
 * Compose image (AVPlayerItemVideoOutput), so they sit in the studio layer
 * like every other pane and come along in a photo or a take. Seeks only on
 * real drift while playing, follows the cursor exactly while paused.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosReplayVideoPlayer(
    uri: String,
    videoTimeMs: Long,
    playing: Boolean,
    loop: Boolean,
    speed: Float,
    fit: String,
    modifier: Modifier,
) {
    val output = remember {
        AVPlayerItemVideoOutput(
            pixelBufferAttributes = mapOf<Any?, Any?>(
                kCVPixelBufferPixelFormatTypeKey to NSNumber(unsignedInt = kCVPixelFormatType_32BGRA),
            ),
        )
    }
    val player = remember { AVPlayer().apply { setMuted(true) } }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val loopNow by rememberUpdatedState(loop)

    DisposableEffect(uri) {
        val url = NSURL.URLWithString(uri)
        val item = url?.let { AVPlayerItem(uRL = it) }
        item?.addOutput(output)
        player.replaceCurrentItemWithPlayerItem(item)
        // LOOP repeats the clip so it keeps rolling past its own length.
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue,
        ) { _ ->
            if (loopNow) {
                player.seekToTime(kCMTimeZero.readValue())
                player.play()
            }
        }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
            item?.removeOutput(output)
            player.replaceCurrentItemWithPlayerItem(null)
        }
    }
    LaunchedEffect(playing, speed) {
        if (playing) player.setRate(speed.coerceIn(0.25f, 4f)) else player.pause()
    }
    LaunchedEffect(videoTimeMs, playing) {
        val current = (CMTimeGetSeconds(player.currentTime()) * 1000.0).toLong()
        if (playing) {
            var drift = abs(current - videoTimeMs)
            val dur = player.currentItem?.let { (CMTimeGetSeconds(it.duration) * 1000.0).toLong() } ?: 0L
            // A looping clip wraps, so near the seam the gap is the circular distance.
            if (loop && dur > 0L) drift = minOf(drift, dur - drift)
            if (drift > DRIFT_TOLERANCE_MS) seekExact(player, videoTimeMs)
        } else {
            seekExact(player, videoTimeMs)
        }
    }
    DisposableEffect(player) { onDispose { player.pause() } }
    // Each display frame takes the newest decoded picture, if there is one.
    LaunchedEffect(output) {
        while (true) {
            withFrameNanos { }
            val t = output.itemTimeForHostTime(CACurrentMediaTime())
            if (output.hasNewPixelBufferForItemTime(t)) {
                val pb = output.copyPixelBufferForItemTime(t, null)
                if (pb != null) {
                    pb.toImageBitmap()?.let { frame = it }
                    CFRelease(pb)
                }
            }
        }
    }
    Box(modifier) {
        frame?.let {
            Image(bitmap = it, contentDescription = null, contentScale = contentScaleOf(fit), modifier = modifier)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun seekExact(player: AVPlayer, ms: Long) {
    player.seekToTime(CMTimeMake(ms, 1000), kCMTimeZero.readValue(), kCMTimeZero.readValue())
}

/** Playing drift beyond this (ms) seeks, as Android's player does. */
private const val DRIFT_TOLERANCE_MS = 300L

// --- Pickers -----------------------------------------------------------------

/**
 * The photo library picker (PHPicker), one pick. [onImage] gets the image,
 * [onVideo] a picked clip copied into the app; the callbacks run on the main
 * thread, and nothing is called when the rider cancels.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosStudioPicker {
    // The picker holds its delegate weakly: the one open is kept here.
    private var delegate: NSObject? = null

    /** Picks an image, downscaled to 1024 px and encoded base64 PNG, as StudioImages.encodeForPreset does. */
    fun pickImage(onPicked: (String?) -> Unit) = present(PHPickerFilter.imagesFilter) { provider ->
        provider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            val encoded = data?.let { encodeForPreset(UIImage(data = it)) }
            dispatch_async(dispatch_get_main_queue()) { onPicked(encoded) }
        }
    }

    /**
     * Picks a clip and copies it into Application Support/studio_videos (the
     * picker's file is gone once it returns, and iOS has no lasting grant
     * like Android's persisted document permission); [onPicked] gets its file url.
     */
    fun pickVideo(onPicked: (String) -> Unit) = present(PHPickerFilter.videosFilter) { provider ->
        provider.loadFileRepresentationForTypeIdentifier("public.movie") { url, _ ->
            val copied = url?.let { copyIntoApp(it) }
            if (copied != null) dispatch_async(dispatch_get_main_queue()) { onPicked(copied) }
            else Log.w(TAG, "Picked clip could not be copied")
        }
    }

    private fun present(filter: PHPickerFilter, onItem: (NSItemProvider) -> Unit) {
        val top = topViewController() ?: return
        val config = PHPickerConfiguration()
        config.filter = filter
        config.selectionLimit = 1
        val picker = PHPickerViewController(configuration = config)
        val d = object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, completion = null)
                delegate = null
                val result = didFinishPicking.firstOrNull() as? PHPickerResult ?: return
                onItem(result.itemProvider)
            }
        }
        delegate = d
        picker.delegate = d
        top.presentViewController(picker, animated = true, completion = null)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeForPreset(image: UIImage): String? {
        val (w, h) = image.size.useContents { width to height }
        if (w <= 0.0 || h <= 0.0) return null
        val scale = minOf(1.0, MAX_EDGE / maxOf(w, h))
        val dw = (w * scale).toInt().coerceAtLeast(1).toDouble()
        val dh = (h * scale).toInt().coerceAtLeast(1).toDouble()
        // Scale 1: the points are the pixels, so the PNG is dw x dh; the
        // draw also applies the photo's orientation.
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(dw, dh), false, 1.0)
        image.drawInRect(CGRectMake(0.0, 0.0, dw, dh))
        val scaled = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        val png = scaled?.let { UIImagePNGRepresentation(it) } ?: return null
        return Base64.Default.encode(png.toByteArray())
    }

    private fun copyIntoApp(src: NSURL): String? {
        val fm = NSFileManager.defaultManager
        val support = fm.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
            ?: return null
        val dir = support.URLByAppendingPathComponent("studio_videos", isDirectory = true) ?: return null
        fm.createDirectoryAtURL(dir, true, null, null)
        // Large and re-pickable: kept out of the iCloud backup.
        dir.setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)
        val ext = src.pathExtension?.takeIf { it.isNotEmpty() } ?: "mov"
        val dst = dir.URLByAppendingPathComponent("${Uuid.random()}.$ext") ?: return null
        return if (fm.copyItemAtURL(src, dst, null)) dst.absoluteString else null
    }

    private const val MAX_EDGE = 1024.0
}
