package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.ui.studio.camera.StudioCameraHub
import com.eried.eucplanet.ui.studio.camera.StudioCameraInfo
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDeviceFormat
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDevicePositionUnspecified
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTelephotoCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTripleCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInUltraWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureInputPort
import platform.AVFoundation.AVCaptureMultiCamSession
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.position
import platform.AVFoundation.deviceType
import platform.AVFoundation.multiCamSupported
import platform.AVFoundation.uniqueID
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMVideoFormatDescriptionGetDimensions
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

// The Overlay Studio's camera feeds on the iPhone, Android's StudioCameraHub
// over AVFoundation: the cameras the phone has, and the newest frame of each
// one streaming, as Compose images the viewports draw.

private const val TAG = "StudioCamera"

/**
 * The studio's camera hub. Cameras are listed once: the back camera (the
 * phone's own lens-switching camera where it has one) as "BACK", each back
 * lens on its own after it, and the front one as "FRONT", the keys Android
 * gives its logical cameras, so a preset made on either phone opens on the
 * same side. Two feeds stream at once where the phone supports a
 * multi-camera session for that pair, otherwise only the first, as on
 * Android. The session lets go while the app is not in front.
 */
@Composable
internal fun rememberIosCameraHub(requestedKeys: List<String>, enabled: Boolean): StudioCameraHub {
    val hub = remember { StudioCameraHub() }
    var foreground by remember {
        mutableStateOf(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)
    }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val resign = center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            foreground = false
        }
        val active = center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            foreground = true
        }
        onDispose {
            center.removeObserver(resign)
            center.removeObserver(active)
        }
    }
    LaunchedEffect(Unit) {
        hub.cameras = runCatching { IosCameras.list() }.getOrElse {
            Log.e(TAG, "Camera list failed", it)
            emptyList()
        }
        hub.maxConcurrent = if (AVCaptureMultiCamSession.isMultiCamSupported()) 2 else 1
        Log.i(TAG, "Cameras ${hub.cameras.map { it.key }}, maxConcurrent=${hub.maxConcurrent}")
        hub.ready = true
    }
    val signature = requestedKeys.distinct().joinToString(",") + "|" + enabled + "|" + foreground
    LaunchedEffect(hub.ready, signature) {
        if (!hub.ready) return@LaunchedEffect
        hub.binding?.close()
        hub.binding = null
        hub.frames.clear()
        hub.liveKeys = emptySet()
        if (!enabled || !foreground) return@LaunchedEffect
        val wanted = requestedKeys.distinct()
            .mapNotNull { k -> hub.cameras.firstOrNull { it.key == k } }
            .take(hub.maxConcurrent)
        if (wanted.isEmpty()) return@LaunchedEffect
        val binding = IosCameraBinding(hub)
        hub.binding = binding
        hub.liveKeys = try {
            // startRunning blocks until the session runs.
            withContext(Dispatchers.IO) { binding.start(wanted) }
        } catch (c: CancellationException) {
            binding.close()
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Camera binding failed", t)
            binding.close()
            emptySet()
        }
        Log.i(TAG, "Live cameras: ${hub.liveKeys}")
    }
    DisposableEffect(Unit) {
        onDispose {
            hub.binding?.close()
            hub.binding = null
        }
    }
    return hub
}

/** The phone's cameras, by the keys Android uses. */
@OptIn(ExperimentalForeignApi::class)
internal object IosCameras {
    fun list(): List<StudioCameraInfo> {
        val types = listOfNotNull(
            AVCaptureDeviceTypeBuiltInTripleCamera,
            AVCaptureDeviceTypeBuiltInDualWideCamera,
            AVCaptureDeviceTypeBuiltInDualCamera,
            AVCaptureDeviceTypeBuiltInWideAngleCamera,
            AVCaptureDeviceTypeBuiltInUltraWideCamera,
            AVCaptureDeviceTypeBuiltInTelephotoCamera,
        )
        val devices = AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
            types, AVMediaTypeVideo, AVCaptureDevicePositionUnspecified,
        ).devices.mapNotNull { it as? AVCaptureDevice }
        fun ofType(type: String?, position: Long) =
            devices.firstOrNull { it.deviceType == type && it.position == position }
        val out = mutableListOf<StudioCameraInfo>()
        var index = 0
        // The back camera as the phone's camera app uses it: the lens-switching
        // one where there is one, the wide lens otherwise.
        val backLogical = listOf(
            AVCaptureDeviceTypeBuiltInTripleCamera,
            AVCaptureDeviceTypeBuiltInDualWideCamera,
            AVCaptureDeviceTypeBuiltInDualCamera,
            AVCaptureDeviceTypeBuiltInWideAngleCamera,
        ).firstNotNullOfOrNull { ofType(it, AVCaptureDevicePositionBack) }
        if (backLogical != null) {
            out += StudioCameraInfo("BACK", "${++index}", front = false, deviceId = backLogical.uniqueID)
            // Each lens on its own, as Android lists the physical cameras behind a logical one.
            if (backLogical.deviceType != AVCaptureDeviceTypeBuiltInWideAngleCamera) {
                for ((type, name) in listOf(
                    AVCaptureDeviceTypeBuiltInWideAngleCamera to "WIDE",
                    AVCaptureDeviceTypeBuiltInUltraWideCamera to "ULTRAWIDE",
                    AVCaptureDeviceTypeBuiltInTelephotoCamera to "TELE",
                )) {
                    val lens = ofType(type, AVCaptureDevicePositionBack) ?: continue
                    out += StudioCameraInfo("BACK_$name", "${++index}", front = false, deviceId = lens.uniqueID, physicalId = name)
                }
            }
        }
        ofType(AVCaptureDeviceTypeBuiltInWideAngleCamera, AVCaptureDevicePositionFront)?.let {
            out += StudioCameraInfo("FRONT", "${++index}", front = true, deviceId = it.uniqueID)
        }
        return out
    }

    fun device(info: StudioCameraInfo): AVCaptureDevice? = AVCaptureDevice.deviceWithUniqueID(info.deviceId)
}

/**
 * One activation of the cameras, single use like Android's CameraBinding:
 * a session (a multi-camera one for two feeds) whose video outputs deliver
 * 1280x720 BGRA frames, turned upright for the portrait studio and never
 * mirrored (a recording shows the true scene, the front camera included).
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosCameraBinding(private val hub: StudioCameraHub) : AutoCloseable {
    private var session: AVCaptureSession? = null
    private val delegates = mutableListOf<NSObject>()
    private val closed = atomic(false)

    /** Opens [wanted] and returns the keys that ended up streaming. */
    fun start(wanted: List<StudioCameraInfo>): Set<String> {
        val pairs = wanted.mapNotNull { info -> IosCameras.device(info)?.let { info to it } }
        if (pairs.isEmpty()) return emptySet()
        val multi = pairs.size > 1 && AVCaptureMultiCamSession.isMultiCamSupported()
        val live = if (multi) startMulti(pairs) else startSingle(pairs.first())
        if (closed.value) {
            session?.stopRunning()
            return emptySet()
        }
        return live
    }

    private fun startSingle(pair: Pair<StudioCameraInfo, AVCaptureDevice>): Set<String> {
        val (info, device) = pair
        val s = AVCaptureSession()
        val output = videoOutput(info)
        s.beginConfiguration()
        val wired = run {
            if (s.canSetSessionPreset(AVCaptureSessionPreset1280x720!!)) s.sessionPreset = AVCaptureSessionPreset1280x720!!
            val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: return@run false
            if (!s.canAddInput(input) || !s.canAddOutput(output)) return@run false
            s.addInput(input)
            s.addOutput(output)
            output.connectionWithMediaType(AVMediaTypeVideo)?.let(::upright)
            true
        }
        s.commitConfiguration()
        if (!wired) return emptySet()
        session = s
        s.startRunning()
        return if (s.running) setOf(info.key) else emptySet()
    }

    /**
     * Two feeds on a multi-camera session, each input wired to its own output.
     * A pair the phone cannot stream together drops to the first camera.
     */
    private fun startMulti(pairs: List<Pair<StudioCameraInfo, AVCaptureDevice>>): Set<String> {
        val s = AVCaptureMultiCamSession()
        val live = mutableSetOf<String>()
        s.beginConfiguration()
        for ((info, device) in pairs) {
            pickMultiCamFormat(device)
            val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: continue
            if (!s.canAddInput(input)) continue
            s.addInputWithNoConnections(input)
            val output = videoOutput(info)
            if (!s.canAddOutput(output)) continue
            s.addOutputWithNoConnections(output)
            val port = input.portsWithMediaType(AVMediaTypeVideo, device.deviceType, device.position)
                .firstOrNull() as? AVCaptureInputPort ?: continue
            val connection = AVCaptureConnection(inputPorts = listOf(port), output = output)
            if (!s.canAddConnection(connection)) continue
            s.addConnection(connection)
            upright(connection)
            live += info.key
        }
        s.commitConfiguration()
        // Over the phone's camera budget: the pair cannot stream together.
        if (s.hardwareCost > 1.0 || live.isEmpty()) {
            Log.w(TAG, "Multi-camera pair not supported (cost ${s.hardwareCost}); one feed")
            return startSingle(pairs.first())
        }
        session = s
        s.startRunning()
        return if (s.running) live else emptySet()
    }

    /** The multi-camera format closest to 1280x720, the size Android picks. */
    private fun pickMultiCamFormat(device: AVCaptureDevice) {
        val formats = device.formats.mapNotNull { it as? AVCaptureDeviceFormat }.filter { it.multiCamSupported }
        val best = formats.minByOrNull { f ->
            val px = CMVideoFormatDescriptionGetDimensions(f.formatDescription).useContents { width.toLong() * height }
            kotlin.math.abs(px - 1280L * 720L)
        } ?: return
        if (device.lockForConfiguration(null)) {
            device.activeFormat = best
            device.unlockForConfiguration()
        }
    }

    private fun upright(connection: AVCaptureConnection) {
        if (connection.supportsVideoOrientation) connection.videoOrientation = AVCaptureVideoOrientationPortrait
        if (connection.supportsVideoMirroring) {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.videoMirrored = false
        }
    }

    /** A BGRA output whose frames go to the hub, one waiting at most (the rest dropped). */
    private fun videoOutput(info: StudioCameraInfo): AVCaptureVideoDataOutput {
        val output = AVCaptureVideoDataOutput()
        output.videoSettings = mapOf<Any?, Any?>("PixelFormatType" to platform.CoreVideo.kCVPixelFormatType_32BGRA)
        output.alwaysDiscardsLateVideoFrames = true
        val pending = atomic(false)
        var first = true
        val delegate = object : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
            override fun captureOutput(
                output: AVCaptureOutput,
                didOutputSampleBuffer: CMSampleBufferRef?,
                fromConnection: AVCaptureConnection,
            ) {
                if (closed.value || pending.value) return
                val buffer = didOutputSampleBuffer?.let { CMSampleBufferGetImageBuffer(it) } ?: return
                val image = buffer.toImageBitmap() ?: return
                pending.value = true
                dispatch_async(dispatch_get_main_queue()) {
                    pending.value = false
                    if (closed.value) return@dispatch_async
                    hub.frames[info.key] = image
                    if (first) {
                        first = false
                        Log.i(TAG, "First frame for ${info.key}: ${image.width}x${image.height}")
                    }
                }
            }
        }
        delegates += delegate
        output.setSampleBufferDelegate(delegate, dispatch_queue_create("eucplanet.studio.camera.${info.key}", null))
        return output
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session?.stopRunning()
        session = null
    }
}
