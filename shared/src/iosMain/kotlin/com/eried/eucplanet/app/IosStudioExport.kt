package com.eried.eucplanet.app

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asSkiaBitmap
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toNSData
import com.eried.eucplanet.ui.studio.ReplayPhotoFormat
import com.eried.eucplanet.ui.studio.StudioPendingFile
import com.eried.eucplanet.ui.studio.StudioVideoSink
import com.eried.eucplanet.ui.studio.recording.StudioByteSink
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusReading
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoAllowFrameReorderingKey
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoExpectedSourceFrameRateKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoMaxKeyFrameIntervalKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.tracksWithMediaType
import platform.AVFoundation.formatDescriptions
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMClockGetHostTimeClock
import platform.CoreMedia.CMClockGetTime
import platform.CoreMedia.CMSampleBufferCreateCopyWithNewTiming
import platform.CoreMedia.CMSampleBufferGetNumSamples
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMSampleTimingInfo
import platform.CoreMedia.CMTimeCompare
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.kCMTimeInvalid
import platform.CoreMedia.kCMTimeZero
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferPoolCreatePixelBuffer
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceCreationOptions
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_sync
import platform.darwin.dispatch_get_main_queue
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.usleep
import platform.Foundation.NSThread
import kotlin.uuid.Uuid

// The Overlay Studio's files on the iPhone: the H.264 take and replay clip
// (AVAssetWriter), the photo, the GIF and APNG clips, all saved to the photo
// library, Android's gallery. Each saved item's reference is its Photos
// local identifier.

private const val TAG = "StudioExport"

/** The longest edge an exported video gets, Android's hardware encoder cap. */
private const val MAX_LONG_EDGE = 1920

/**
 * A replay clip's frames are written this far apart, then spread evenly over
 * the clip's length once their number is known (Android's remuxEvenlyTimed).
 */
private const val PROVISIONAL_STEP_US = 100_000L

/**
 * The live take ([withAudio] adds the microphone) or a replay clip's MP4, as
 * Android's StudioVideoEncoder: H.264 sized to the frame (16-aligned, at most
 * 1920 on the long edge), the take's frames stamped with the host clock
 * (which the microphone's buffers share), a replay clip retimed over its
 * target length when it finishes. The finished file goes to the photo
 * library and its temporary copy is removed.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosVideoSink(private val withAudio: Boolean) : StudioVideoSink {
    private var writer: AVAssetWriter? = null
    private var input: AVAssetWriterInput? = null
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor? = null
    private var mic: IosStudioMic? = null
    private val file = NSTemporaryDirectory() + "studio_${Uuid.random()}.mp4"
    private var targetDurationUs = 0L
    private var frames = 0
    private var failed = false

    // AVAssetWriter cannot keep writing once the app leaves the front, as
    // Android's take stops when the activity does: a resign ends the take,
    // which is then saved like any other.
    private var interrupted = false
    private var resignObserver: Any? = null

    override var encodeWidth: Int = 0
        private set
    override var encodeHeight: Int = 0
        private set

    override fun start(width: Int, height: Int, targetDurationUs: Long): Boolean {
        if (width <= 0 || height <= 0) return false
        this.targetDurationUs = targetDurationUs.coerceAtLeast(0L)
        val longEdge = maxOf(width, height)
        val scale = if (longEdge > MAX_LONG_EDGE) MAX_LONG_EDGE.toFloat() / longEdge else 1f
        encodeWidth = ((width * scale).toInt() / 16) * 16
        encodeHeight = ((height * scale).toInt() / 16) * 16
        if (encodeWidth < 16 || encodeHeight < 16) return false
        val w = writer(file) ?: return false
        val bitRate = (encodeWidth.toLong() * encodeHeight * 9).coerceIn(6_000_000L, 32_000_000L)
        val settings = mapOf<Any?, Any?>(
            AVVideoCodecKey to AVVideoCodecTypeH264,
            AVVideoWidthKey to encodeWidth,
            AVVideoHeightKey to encodeHeight,
            AVVideoCompressionPropertiesKey to mapOf<Any?, Any?>(
                AVVideoAverageBitRateKey to bitRate,
                AVVideoExpectedSourceFrameRateKey to 60,
                AVVideoMaxKeyFrameIntervalKey to 60,
                // Presentation order is decode order, so a clip can be retimed sample by sample.
                AVVideoAllowFrameReorderingKey to false,
            ),
        )
        val video = AVAssetWriterInput(mediaType = AVMediaTypeVideo!!, outputSettings = settings)
        video.expectsMediaDataInRealTime = this.targetDurationUs == 0L
        val pixels = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput = video,
            sourcePixelBufferAttributes = mapOf<Any?, Any?>(
                // kCVPixelBufferPixelFormatTypeKey, kCVPixelBufferWidthKey, kCVPixelBufferHeightKey
                "PixelFormatType" to kCVPixelFormatType_32BGRA,
                "Width" to encodeWidth,
                "Height" to encodeHeight,
            ),
        )
        if (!w.canAddInput(video)) return false
        w.addInput(video)
        if (withAudio) {
            mic = IosStudioMic.create(w)
            if (mic == null) Log.w(TAG, "No microphone: the take records video only")
        }
        if (!w.startWriting()) {
            Log.e(TAG, "Writer did not start: ${w.error?.localizedDescription}")
            mic?.stop()
            return false
        }
        writer = w
        input = video
        adaptor = pixels
        resignObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue,
        ) { _ ->
            if (this.targetDurationUs == 0L) {
                Log.i(TAG, "App left the front: the take ends")
                interrupted = true
            }
        }
        Log.i(TAG, "Encoding ${encodeWidth}x$encodeHeight, audio=${mic != null}")
        return true
    }

    /**
     * Draws one frame. The studio's layer is recorded on the main thread, so
     * it is replayed there too, into the pixel buffer; the encode is not.
     */
    override fun submitFrame(draw: (Canvas) -> Unit): Boolean = append { surface ->
        val canvas = surface.canvas.asComposeCanvas()
        if (NSThread.isMainThread) draw(canvas)
        else dispatch_sync(dispatch_get_main_queue()) { draw(canvas) }
    }

    override fun submitImage(frame: ImageBitmap, backgroundArgb: Long): Boolean = append { surface ->
        surface.canvas.clear(backgroundArgb.toInt())
        val image = SkImage.makeFromBitmap(frame.asSkiaBitmap())
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(encodeWidth.toFloat(), encodeHeight.toFloat()),
            SamplingMode.LINEAR,
            Paint(),
            true,
        )
        image.close()
    }

    private fun append(paint: (Surface) -> Unit): Boolean {
        val w = writer ?: return false
        val video = input ?: return false
        val pixels = adaptor ?: return false
        if (failed || interrupted || w.status != AVAssetWriterStatusWriting) return false
        val pts = if (targetDurationUs > 0L) CMTimeMake(frames * PROVISIONAL_STEP_US, 1_000_000)
        else CMClockGetTime(CMClockGetHostTimeClock())
        if (frames == 0) {
            w.startSessionAtSourceTime(pts)
            mic?.begin(pts)
        }
        // A live take drops a frame the encoder is not ready for; an export waits.
        var waited = 0
        while (!video.readyForMoreMediaData) {
            if (targetDurationUs == 0L || waited > 2_000) return true
            usleep(1_000u)
            waited++
        }
        val pool = pixels.pixelBufferPool ?: return false
        val ok = memScoped {
            val ref = alloc<CVPixelBufferRefVar>()
            if (CVPixelBufferPoolCreatePixelBuffer(null, pool, ref.ptr) != 0) return@memScoped false
            val pb = ref.value ?: return@memScoped false
            try {
                CVPixelBufferLockBaseAddress(pb, 0u)
                val base = CVPixelBufferGetBaseAddress(pb)
                if (base == null) {
                    CVPixelBufferUnlockBaseAddress(pb, 0u)
                    return@memScoped false
                }
                val surface = Surface.makeRasterDirect(
                    ImageInfo(encodeWidth, encodeHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL),
                    base.rawValue,
                    CVPixelBufferGetBytesPerRow(pb).toInt(),
                )
                // MP4 has no alpha: whatever the layer leaves clear is black.
                surface.canvas.clear(BLACK)
                paint(surface)
                surface.close()
                CVPixelBufferUnlockBaseAddress(pb, 0u)
                pixels.appendPixelBuffer(pb, pts)
            } finally {
                CFRelease(pb)
            }
        }
        if (!ok) {
            failed = true
            Log.e(TAG, "Frame $frames not written: ${w.error?.localizedDescription}")
            return false
        }
        frames++
        return true
    }

    override fun finish(): String? {
        resignObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        resignObserver = null
        mic?.stop()
        val w = writer ?: return null.also { remove(file) }
        writer = null
        input?.markAsFinished()
        mic?.finish()
        if (frames == 0) {
            w.cancelWriting()
            remove(file)
            return null
        }
        val done = dispatch_semaphore_create(0)
        w.finishWritingWithCompletionHandler { dispatch_semaphore_signal(done) }
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        if (w.status != AVAssetWriterStatusCompleted || failed) {
            Log.e(TAG, "Video not finished: ${w.error?.localizedDescription}")
            remove(file)
            return null
        }
        val publish = if (targetDurationUs > 0L) {
            retime(file, frames, targetDurationUs).also { remove(file) } ?: return null
        } else file
        Log.i(TAG, "Encoded $frames frames, ${encodeWidth}x$encodeHeight")
        return IosPhotoLibrary.saveFile(publish, PHAssetResourceTypeVideo, "${timestampedName()}.mp4")
            .also { remove(publish) }
    }

    private companion object {
        const val BLACK = -0x1000000 // 0xFF000000
    }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun writer(path: String): AVAssetWriter? = memScoped {
    remove(path)
    val err = alloc<ObjCObjectVar<NSError?>>()
    AVAssetWriter(uRL = NSURL.fileURLWithPath(path), fileType = AVFileTypeMPEG4!!, error = err.ptr).also {
        if (err.value != null) Log.e(TAG, "Writer failed: ${err.value?.localizedDescription}")
    }.takeIf { err.value == null }
}

/**
 * [src]'s [frames] video samples spread evenly over [targetUs], copied as
 * they are (no re-encode) into a new file: Android's remuxEvenlyTimed. The
 * new file's path, or null.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun retime(src: String, frames: Int, targetUs: Long): String? = memScoped {
    val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(src), null)
    val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: return null
    val err = alloc<ObjCObjectVar<NSError?>>()
    val reader = AVAssetReader(asset = asset, error = err.ptr)
    val out = AVAssetReaderTrackOutput(track = track, outputSettings = null)
    reader.addOutput(out)
    val dst = NSTemporaryDirectory() + "studio_${Uuid.random()}.mp4"
    val w = writer(dst) ?: return null
    // The track's format, a CF object in an NSArray, bridged back to its CF type for the hint.
    val format = track.formatDescriptions.firstOrNull()?.let { platform.Foundation.CFBridgingRetain(it) }
    val video = AVAssetWriterInput(
        mediaType = AVMediaTypeVideo!!,
        outputSettings = null,
        sourceFormatHint = format?.reinterpret(),
    )
    format?.let { CFRelease(it) }
    w.addInput(video)
    if (!reader.startReading() || !w.startWriting()) {
        Log.e(TAG, "Retime could not start: ${reader.error?.localizedDescription ?: w.error?.localizedDescription}")
        return null
    }
    w.startSessionAtSourceTime(kCMTimeZero.readValue())
    val stepUs = targetUs / frames.coerceAtLeast(1)
    var index = 0L
    while (reader.status == AVAssetReaderStatusReading) {
        val sample: CMSampleBufferRef = out.copyNextSampleBuffer() ?: break
        // The reader hands back marker buffers with no frame in them too.
        if (CMSampleBufferGetNumSamples(sample) == 0L) {
            CFRelease(sample)
            continue
        }
        val timing = alloc<CMSampleTimingInfo>()
        CMTimeMake(stepUs, 1_000_000).place(timing.duration.ptr)
        CMTimeMake(index * stepUs, 1_000_000).place(timing.presentationTimeStamp.ptr)
        kCMTimeInvalid.readValue().place(timing.decodeTimeStamp.ptr)
        val copy = alloc<CMSampleBufferRefVar>()
        CMSampleBufferCreateCopyWithNewTiming(null, sample, 1, timing.ptr, copy.ptr)
        CFRelease(sample)
        val retimed = copy.value ?: continue
        while (!video.readyForMoreMediaData) usleep(1_000u)
        video.appendSampleBuffer(retimed)
        CFRelease(retimed)
        index++
    }
    video.markAsFinished()
    w.endSessionAtSourceTime(CMTimeMake(index * stepUs, 1_000_000))
    val done = dispatch_semaphore_create(0)
    w.finishWritingWithCompletionHandler { dispatch_semaphore_signal(done) }
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
    if (w.status != AVAssetWriterStatusCompleted) {
        Log.e(TAG, "Retime failed: ${w.error?.localizedDescription}")
        remove(dst)
        return null
    }
    Log.i(TAG, "Retimed $index frames over ${targetUs / 1000} ms")
    dst
}

@OptIn(ExperimentalForeignApi::class)
private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}

/** "EUC_20261115_010708", as Android's StudioCapture names a saved item. */
internal fun timestampedName(): String =
    "EUC_" + com.eried.eucplanet.platform.formatLocalDateUs(
        "yyyyMMdd_HHmmss", com.eried.eucplanet.platform.System.currentTimeMillis(),
    )

/** The photo library, add only: where Android's MediaStore saves go on the iPhone. */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal object IosPhotoLibrary {
    /** Asks once (the add-only prompt); true when saving is allowed. Not on the main thread. */
    private fun allowed(): Boolean {
        var status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)
        if (status == PHAuthorizationStatusNotDetermined) {
            val done = dispatch_semaphore_create(0)
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) {
                status = it
                dispatch_semaphore_signal(done)
            }
            dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        }
        return status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited
    }

    /** Saves [path] as a photo or video resource named [name]; its local identifier, or null. */
    fun saveFile(path: String, type: Long, name: String): String? =
        save(name) { req, options -> req.addResourceWithType(type, fileURL = NSURL.fileURLWithPath(path), options = options) }

    fun saveData(bytes: ByteArray, name: String): String? =
        save(name) { req, options -> req.addResourceWithType(PHAssetResourceTypePhoto, data = bytes.toNSData(), options = options) }

    private fun save(name: String, add: (PHAssetCreationRequest, PHAssetResourceCreationOptions) -> Unit): String? {
        if (!allowed()) {
            Log.w(TAG, "No photo library access")
            return null
        }
        var id: String? = null
        val ok = memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait({
                val req = PHAssetCreationRequest.creationRequestForAsset()
                val options = PHAssetResourceCreationOptions()
                options.originalFilename = name
                add(req, options)
                id = req.placeholderForCreatedAsset?.localIdentifier
            }, err.ptr).also { if (!it) Log.e(TAG, "Save failed: ${err.value?.localizedDescription}") }
        }
        if (ok) Log.i(TAG, "Saved $name to Photos")
        return if (ok) id else null
    }

    /**
     * A snapshot in [format]: PNG and WebP keep their alpha, JPG is flattened
     * onto [chromaArgb] first, as Android's StudioCapture does.
     */
    fun savePhoto(frame: ImageBitmap, format: ReplayPhotoFormat, chromaArgb: Long): String? {
        val src = SkImage.makeFromBitmap(frame.asSkiaBitmap())
        val bytes = when (format) {
            ReplayPhotoFormat.PNG -> src.encodeToData(EncodedImageFormat.PNG, 100)
            ReplayPhotoFormat.WEBP -> src.encodeToData(EncodedImageFormat.WEBP, 100)
            ReplayPhotoFormat.JPG -> {
                val surface = Surface.makeRasterN32Premul(src.width, src.height)
                surface.canvas.clear(chromaArgb.toInt())
                surface.canvas.drawImage(src, 0f, 0f)
                val flat = surface.makeImageSnapshot()
                surface.close()
                flat.encodeToData(EncodedImageFormat.JPEG, 95).also { flat.close() }
            }
        }?.bytes
        src.close()
        if (bytes == null) {
            Log.e(TAG, "Photo not encoded as $format")
            return null
        }
        val ext = when (format) {
            ReplayPhotoFormat.PNG -> "png"
            ReplayPhotoFormat.WEBP -> "webp"
            ReplayPhotoFormat.JPG -> "jpg"
        }
        return saveData(bytes, "${timestampedName()}.$ext")
    }
}

/**
 * A GIF or APNG being written: a temporary file the encoder streams into,
 * then moved into the photo library (or dropped), as Android's pending
 * MediaStore image. Its reference is the saved item's local identifier.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPendingImage(private val displayName: String) : StudioPendingFile {
    private val path = NSTemporaryDirectory() + displayName
    private var savedId: String? = null

    override val uri: String get() = savedId ?: ""

    override fun openSink(): StudioByteSink? {
        val handle = fopen(path, "wb") ?: return null
        return FileByteSink(handle)
    }

    override fun finalize(ok: Boolean) {
        if (ok) savedId = IosPhotoLibrary.saveFile(path, PHAssetResourceTypePhoto, displayName)
        remove(path)
    }

    /** A buffered FILE*: the encoders write a byte at a time. */
    private class FileByteSink(private val handle: CPointer<platform.posix.FILE>) : StudioByteSink {
        private val buffer = ByteArray(64 * 1024)
        private var used = 0
        private var closed = false

        override fun write(b: Int) {
            if (used == buffer.size) flush()
            buffer[used++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > buffer.size - used) flush()
            if (len >= buffer.size) {
                b.usePinned { fwrite(it.addressOf(off), 1u, len.convert(), handle) }
                return
            }
            b.copyInto(buffer, used, off, off + len)
            used += len
        }

        override fun flush() {
            if (used == 0) return
            buffer.usePinned { fwrite(it.addressOf(0), 1u, used.convert(), handle) }
            used = 0
        }

        override fun close() {
            if (closed) return
            flush()
            fclose(handle)
            closed = true
        }
    }
}

/**
 * The take's microphone track, as Android's AudioRecord into AAC: a capture
 * session of its own with the default microphone, its buffers written from
 * the moment the first video frame starts the file. Mono AAC at 44.1 kHz and
 * 128 kbps, Android's settings.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosStudioMic private constructor(
    private val input: AVAssetWriterInput,
    private val capture: IosMicCapture,
) {
    private var start: kotlinx.cinterop.CValue<platform.CoreMedia.CMTime>? = null
    private var finished = false

    fun begin(at: kotlinx.cinterop.CValue<platform.CoreMedia.CMTime>) {
        start = at
    }

    /** One captured buffer, on the capture queue. */
    private fun onBuffer(sample: CMSampleBufferRef) {
        val from = start ?: return
        if (finished) return
        if (CMTimeCompare(CMSampleBufferGetPresentationTimeStamp(sample), from) < 0) return
        if (input.readyForMoreMediaData) input.appendSampleBuffer(sample)
    }

    fun stop() {
        capture.stop()
    }

    fun finish() {
        finished = true
        input.markAsFinished()
    }

    companion object {
        /** Adds the microphone's track to [writer], or null when there is no microphone to record. */
        fun create(writer: AVAssetWriter): IosStudioMic? {
            val audio = AVAssetWriterInput(
                mediaType = AVMediaTypeAudio!!,
                outputSettings = mapOf<Any?, Any?>(
                    // AVFormatIDKey: kAudioFormatMPEG4AAC ('aac ').
                    "AVFormatIDKey" to 0x61616320,
                    "AVSampleRateKey" to 44_100.0,
                    "AVNumberOfChannelsKey" to 1,
                    "AVEncoderBitRateKey" to 128_000,
                ),
            )
            audio.expectsMediaDataInRealTime = true
            if (!writer.canAddInput(audio)) return null
            var mic: IosStudioMic? = null
            val capture = IosMicCapture.start { sample -> mic?.onBuffer(sample) } ?: return null
            writer.addInput(audio)
            mic = IosStudioMic(audio, capture)
            return mic
        }
    }
}
