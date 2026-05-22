package com.eried.eucplanet.ui.studio.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A camera the device exposes. [deviceId] is the *logical* camera that gets
 * opened; [physicalId] (when set) pins a specific physical lens behind it via
 * [OutputConfiguration.setPhysicalCameraId] — that is how the ultrawide /
 * telephoto are reached, since only logical cameras appear in the id list.
 */
data class StudioCameraInfo(
    val key: String,
    val label: String,
    val front: Boolean,
    val deviceId: String,
    val physicalId: String? = null
)

/**
 * Multi-camera feed hub for the Overlay Studio.
 *
 * Built directly on Camera2 (no CameraX) so the Studio can stream *two* cameras
 * at once: a front + a back logical camera as a concurrent-camera pair, or two
 * physical lenses of one logical camera as two physically-pinned streams of a
 * single capture session. Each requested feed gets its own [ImageReader]
 * (YUV_420_888, ~1280x720) and is exposed as a Compose [ImageBitmap], so several
 * viewports / floating windows can each show a *different* camera at once.
 *
 * Where the hardware genuinely cannot co-stream a requested pair, only the first
 * camera goes live and the UI shows a placeholder for the rest.
 */
class StudioCameraHub {
    /** Every camera detected on the device. */
    var cameras by mutableStateOf<List<StudioCameraInfo>>(emptyList())
        internal set

    /** How many cameras can stream at once on this device (1 or 2). */
    var maxConcurrent by mutableStateOf(1)
        internal set

    /** True once camera enumeration has finished. */
    var ready by mutableStateOf(false)
        internal set

    /** Latest frame per logical camera key. */
    val frames = mutableStateMapOf<String, ImageBitmap?>()

    /** Logical keys currently streaming. */
    var liveKeys by mutableStateOf<Set<String>>(emptySet())
        internal set

    /** The live Camera2 binding, kept so teardown can close it directly. */
    internal var binding: CameraBinding? = null

    fun frame(key: String): ImageBitmap? = frames[key]
    fun isLive(key: String): Boolean = key in liveKeys
    fun info(key: String): StudioCameraInfo? = cameras.firstOrNull { it.key == key }
}

/**
 * Binds the cameras named in [requestedKeys] (in priority order) while
 * [enabled] is true, and returns the live hub. Re-binds whenever the requested
 * set changes — that is how switching a viewport's camera takes effect.
 */
@Composable
fun rememberStudioCameraHub(requestedKeys: List<String>, enabled: Boolean): StudioCameraHub {
    val context = LocalContext.current
    val hub = remember { StudioCameraHub() }
    // Shared CPU-bound pool the YUV->Bitmap row workers run on. Sized to the
    // device so a single frame's conversion fans out across cores.
    val rowPool = remember {
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        )
    }

    // The camera must be released while the app is backgrounded: Android hands
    // the device to whatever comes to the foreground, and a CameraDevice taken
    // away that way returns disconnected — its last frame just freezes on
    // screen. Tracking the lifecycle lets the binding tear down on STOP and
    // rebuild on START, so the feed is live again the moment the rider switches
    // back to the studio instead of being stuck on a stale frame.
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> foreground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            hub.ready = true
            return@LaunchedEffect
        }
        hub.cameras = runCatching { enumerateCameras(cm) }.getOrElse {
            Log.e(TAG, "Camera enumeration failed", it)
            emptyList()
        }
        hub.maxConcurrent = runCatching { computeMaxConcurrent(cm, hub.cameras) }
            .getOrDefault(1)
        Log.i(
            TAG,
            "Enumerated ${hub.cameras.size} cameras " +
                "${hub.cameras.map { it.key }}, maxConcurrent=${hub.maxConcurrent}"
        )
        hub.ready = true
    }

    val requestSignature = requestedKeys.distinct().joinToString(",") +
        "|" + enabled + "|" + foreground
    LaunchedEffect(hub.ready, requestSignature) {
        if (!hub.ready) return@LaunchedEffect
        // Tear the previous binding down before touching the camera subsystem —
        // a concurrent open fails while another session still holds a device,
        // and a backgrounded app must let go of the camera entirely.
        hub.binding?.close()
        hub.binding = null
        hub.frames.clear()
        hub.liveKeys = emptySet()
        if (!enabled || !foreground) return@LaunchedEffect
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return@LaunchedEffect
        val wanted = requestedKeys.distinct()
            .mapNotNull { k -> hub.cameras.firstOrNull { it.key == k } }
            .take(hub.maxConcurrent)
        if (wanted.isEmpty()) return@LaunchedEffect
        val binding = CameraBinding(cm, hub, rowPool, displayRotationDegrees(context))
        hub.binding = binding
        hub.liveKeys = try {
            binding.start(wanted)
        } catch (c: CancellationException) {
            // The request changed mid-bind — drop this binding cleanly and let
            // the next LaunchedEffect build the one the UI actually wants.
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
            rowPool.shutdownNow()
        }
    }
    return hub
}

// --- Enumeration -----------------------------------------------------------

/**
 * List every logical camera, plus each physical lens behind it. Mirrors what
 * the old CameraX path produced: one entry per logical camera (the auto-
 * switching default), then one per physical sub-camera.
 */
private fun enumerateCameras(cm: CameraManager): List<StudioCameraInfo> {
    val out = mutableListOf<StudioCameraInfo>()
    var index = 0
    cm.cameraIdList.forEach { deviceId ->
        val ch = runCatching { cm.getCameraCharacteristics(deviceId) }.getOrNull()
            ?: return@forEach
        val front = ch.get(CameraCharacteristics.LENS_FACING) ==
            CameraMetadata.LENS_FACING_FRONT
        val base = if (front) "FRONT" else "BACK"
        index++
        out.add(StudioCameraInfo(base, "$index", front, deviceId, physicalId = null))
        val physicalIds = runCatching { ch.physicalCameraIds.toList() }
            .getOrNull().orEmpty()
        physicalIds.forEach { pid ->
            index++
            out.add(StudioCameraInfo(base + "_" + pid, "$index", front, deviceId, pid))
        }
    }
    return out
}

/**
 * Two feeds are possible when the device advertises a concurrent logical-camera
 * combination, or when a logical camera has at least two physical lenses (which
 * can always be streamed together as two physically-pinned outputs).
 */
private fun computeMaxConcurrent(cm: CameraManager, cameras: List<StudioCameraInfo>): Int {
    val concurrentPairs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        runCatching { cm.concurrentCameraIds.isNotEmpty() }.getOrDefault(false)
    val multiPhysicalDevice = cameras
        .filter { it.physicalId != null }
        .groupingBy { it.deviceId }
        .eachCount()
        .any { it.value >= 2 }
    return if (concurrentPairs || multiPhysicalDevice) 2 else 1
}

/** Display rotation in degrees — the app is portrait-locked, so normally 0. */
private fun displayRotationDegrees(context: Context): Int {
    val rotation = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay?.rotation
        }
    }.getOrNull() ?: Surface.ROTATION_0
    return when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}

// --- Camera2 binding -------------------------------------------------------

/**
 * One activation of the camera stack: opens every requested camera, configures
 * its capture session and keeps it streaming until [close]. A binding is
 * single-use — every re-bind builds a fresh one.
 */
internal class CameraBinding(
    private val cm: CameraManager,
    private val hub: StudioCameraHub,
    private val rowPool: ExecutorService,
    private val displayRotation: Int
) {
    @Volatile
    private var closed = false
    private val devices = mutableListOf<DeviceSession>()

    /**
     * Open + configure [wanted], returning the keys that ended up streaming.
     *
     * Feeds are grouped by their backing logical camera: feeds that share one
     * (two physical lenses of the same module) become a single device with one
     * capture session carrying several [OutputConfiguration]s; feeds on
     * different logical cameras become independent devices opened concurrently.
     */
    suspend fun start(wanted: List<StudioCameraInfo>): Set<String> {
        val groups = wanted.groupBy { it.deviceId }.entries
            .map { (deviceId, cams) -> deviceId to cams }

        // Concurrent operation across *distinct* logical cameras only works for
        // pairs the HAL advertises. A single-device group (physical lenses of
        // one logical camera) is never subject to that restriction.
        val usableGroups = if (groups.size >= 2) {
            val ids = groups.map { it.first }.toSet()
            if (concurrentPairSupported(ids)) {
                groups
            } else {
                Log.w(TAG, "Concurrent pair $ids unsupported — using first camera only")
                groups.take(1)
            }
        } else {
            groups
        }

        // Phase 1 — open every camera device *before* any session is configured.
        // The framework needs all concurrent devices open up front so the HAL
        // can split limited hardware resources across them.
        for ((deviceId, cams) in usableGroups) {
            if (closed) break
            try {
                openDeviceSession(deviceId, cams)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Open failed for camera $deviceId", t)
            }
        }
        if (closed) {
            close()
            return emptySet()
        }

        // Phase 2 — configure a capture session + repeating request per device.
        val live = mutableSetOf<String>()
        for (ds in devices.toList()) {
            if (closed) break
            if (ds.device == null) continue
            try {
                configureDevice(ds)
                live += ds.feeds.map { it.info.key }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Configure failed for camera ${ds.deviceId}", t)
            }
        }
        if (closed) {
            close()
            return emptySet()
        }
        return live
    }

    fun close() {
        closed = true
        devices.forEach { it.close() }
        devices.clear()
    }

    private fun concurrentPairSupported(deviceIds: Set<String>): Boolean {
        if (deviceIds.size < 2) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            cm.concurrentCameraIds.any { combo -> combo.containsAll(deviceIds) }
        }.getOrDefault(false)
    }

    /**
     * Spin up a HandlerThread, open the [CameraDevice] and build its [Feed]s.
     * The [DeviceSession] is registered up front so a teardown that races the
     * open still tears the thread and device down exactly once.
     */
    private suspend fun openDeviceSession(deviceId: String, cams: List<StudioCameraInfo>) {
        val thread = HandlerThread("StudioCam-$deviceId").apply { start() }
        val handler = Handler(thread.looper)
        val ds = DeviceSession(deviceId, thread, handler)
        devices.add(ds)
        ds.device = openCameraDevice(deviceId, ds)
        if (closed) {
            ds.close()
            return
        }
        ds.feeds = try {
            cams.map { cam -> Feed(cam, cm, rowPool, hub, displayRotation, handler) }
        } catch (t: Throwable) {
            ds.close()
            throw t
        }
        Log.i(TAG, "Opened camera $deviceId for ${cams.map { it.key }}")
    }

    /** Configure the capture session and start the repeating request. */
    private suspend fun configureDevice(ds: DeviceSession) {
        val device = ds.device ?: return
        val outputs = ds.feeds.map { it.outputConfiguration() }
        val executor = Executor { ds.handler.post(it) }
        val session = createSession(device, outputs, executor)
        ds.session = session
        // A single repeating request feeds every output of this device — the
        // HAL routes each physically-pinned surface from its own physical lens.
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            .apply {
                ds.feeds.forEach { addTarget(it.surface) }
                // Pin the capture rate as high as the lens sustains; left to
                // auto-exposure it sags to ~30 fps, or ~15 in dim light, and
                // the recording inherits that judder.
                bestFpsRange(ds.deviceId)?.let {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
            }
            .build()
        session.setRepeatingRequest(request, null, ds.handler)
        Log.i(TAG, "Session streaming for ${ds.deviceId}: ${ds.feeds.map { it.info.key }}")
    }

    /** Highest sustained capture-rate range the logical camera advertises. */
    private fun bestFpsRange(deviceId: String): Range<Int>? {
        val ranges = runCatching {
            cm.getCameraCharacteristics(deviceId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }.getOrNull()?.toList().orEmpty()
        if (ranges.isEmpty()) return null
        // Highest ceiling, then highest floor — a fixed [60,60]-style range
        // keeps video at a steady rate instead of letting it dip.
        val best = ranges.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
        Log.i(TAG, "Camera $deviceId fps range -> $best")
        return best
    }

    @SuppressLint("MissingPermission") // Caller binds only when CAMERA is granted.
    private suspend fun openCameraDevice(deviceId: String, ds: DeviceSession): CameraDevice =
        suspendCancellableCoroutine { cont ->
            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    // If nobody is waiting any more (binding torn down, or the
                    // bind coroutine cancelled), close the camera and quit the
                    // thread that was kept alive only for this callback.
                    if (closed || ds.abandoned || !cont.isActive) {
                        ds.discardLateOpen(camera)
                        return
                    }
                    cont.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    ds.discardLateOpen(camera)
                    if (cont.isActive) {
                        cont.resumeWith(
                            Result.failure(IllegalStateException("camera $deviceId disconnected"))
                        )
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    ds.discardLateOpen(camera)
                    if (cont.isActive) {
                        cont.resumeWith(
                            Result.failure(IllegalStateException("camera $deviceId error $error"))
                        )
                    }
                }
            }
            try {
                cm.openCamera(deviceId, callback, ds.handler)
            } catch (t: Throwable) {
                runCatching { ds.thread.quitSafely() }
                if (cont.isActive) cont.resumeWith(Result.failure(t))
            }
        }

    private suspend fun createSession(
        device: CameraDevice,
        outputs: List<OutputConfiguration>,
        executor: Executor
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (closed || !cont.isActive) {
                    runCatching { session.close() }
                    return
                }
                cont.resume(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                runCatching { session.close() }
                if (cont.isActive) {
                    cont.resumeWith(
                        Result.failure(IllegalStateException("session configure failed"))
                    )
                }
            }
        }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, executor, callback
        )
        try {
            device.createCaptureSession(config)
        } catch (t: Throwable) {
            if (cont.isActive) cont.resumeWith(Result.failure(t))
        }
    }
}

/** One opened logical camera and the feeds streaming from it. */
private class DeviceSession(
    val deviceId: String,
    val thread: HandlerThread,
    val handler: Handler
) {
    @Volatile
    var device: CameraDevice? = null

    /** Set once teardown has run — a still-pending open must self-discard. */
    @Volatile
    var abandoned = false

    var feeds: List<Feed> = emptyList()
    var session: CameraCaptureSession? = null

    /** A camera that finished opening after teardown — close it and stop. */
    fun discardLateOpen(camera: CameraDevice) {
        runCatching { camera.close() }
        runCatching { thread.quitSafely() }
    }

    fun close() {
        abandoned = true
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        feeds.forEach { it.close() }
        val dev = device
        if (dev != null) {
            runCatching { dev.close() }
            runCatching { thread.quitSafely() }
        } else {
            // The open is still in flight — leave the thread alive so the state
            // callback can still close the camera; a delayed quit guards the
            // rare case where that callback never arrives.
            runCatching {
                handler.postDelayed({ runCatching { thread.quitSafely() } }, 5_000L)
            }
        }
    }
}

/**
 * One streaming camera: an [ImageReader] producing YUV_420_888 frames that are
 * converted to an upright [Bitmap] off the camera thread and pushed onto the
 * hub. Pinned to a physical lens when [StudioCameraInfo.physicalId] is set.
 */
private class Feed(
    val info: StudioCameraInfo,
    cm: CameraManager,
    private val rowPool: ExecutorService,
    private val hub: StudioCameraHub,
    displayRotation: Int,
    private val listenerHandler: Handler
) {
    private val reader: ImageReader
    val surface: Surface
    private val rotationDegrees: Int

    // The drain loop is single-threaded, so one ARGB scratch buffer is enough;
    // Bitmap.createBitmap copies it out before the next frame reuses it.
    private val argb: IntArray

    private val drainExecutor = Executors.newSingleThreadExecutor()
    private val pending = AtomicReference<YuvFrame?>()
    private val draining = AtomicBoolean(false)
    private val loggedFirstFrame = AtomicBoolean(false)

    @Volatile
    private var closed = false

    init {
        // A physical lens carries its own characteristics (size list, sensor
        // orientation); fall back to the logical camera for a plain feed.
        val ch = cm.getCameraCharacteristics(info.physicalId ?: info.deviceId)
        val size = chooseSize(ch)
        argb = IntArray(size.width * size.height)
        val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val sign = if (info.front) 1 else -1
        rotationDegrees = (sensorOrientation - displayRotation * sign + 360) % 360
        reader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.YUV_420_888, /* maxImages = */ 3
        )
        surface = reader.surface
        reader.setOnImageAvailableListener({ r -> onImage(r) }, listenerHandler)
        Log.i(TAG, "Feed ${info.key}: ${size.width}x${size.height}, rotate $rotationDegrees")
    }

    /** Output for the capture session — physically pinned for a sub-camera. */
    fun outputConfiguration(): OutputConfiguration =
        OutputConfiguration(surface).apply {
            if (info.physicalId != null) setPhysicalCameraId(info.physicalId)
        }

    private fun onImage(reader: ImageReader) {
        // Copy the planes off the Image fast, then release it — conversion is
        // slow and must not stall the reader's limited buffer pool.
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        val frame = try {
            YuvFrame.copyFrom(image)
        } catch (t: Throwable) {
            Log.w(TAG, "Frame copy failed for ${info.key}", t)
            null
        } finally {
            runCatching { image.close() }
        }
        if (frame == null || closed) return
        pending.set(frame)
        if (draining.compareAndSet(false, true)) {
            runCatching { drainExecutor.execute(::drain) }
                .onFailure { draining.set(false) }
        }
    }

    /** Convert the most recent pending frame(s); stale frames are dropped. */
    private fun drain() {
        try {
            while (!closed) {
                val frame = pending.getAndSet(null) ?: break
                val bitmap = YuvConverter.toBitmap(frame, argb, rowPool, rotationDegrees)
                if (!closed) {
                    hub.frames[info.key] = bitmap.asImageBitmap()
                    if (loggedFirstFrame.compareAndSet(false, true)) {
                        Log.i(TAG, "First frame for ${info.key}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Frame convert failed for ${info.key}", t)
        } finally {
            draining.set(false)
            // A frame may have arrived between the loop exit and the flag clear.
            if (!closed && pending.get() != null &&
                draining.compareAndSet(false, true)
            ) {
                runCatching { drainExecutor.execute(::drain) }
                    .onFailure { draining.set(false) }
            }
        }
    }

    fun close() {
        closed = true
        drainExecutor.shutdownNow()
        pending.set(null)
        // The ImageReader MUST be closed on its own listener thread. Closing it
        // from another thread frees the in-flight Image's native buffer while
        // onImage may still be copying from it on the camera thread — a
        // use-after-free that crashes natively (SIGSEGV in memcpy). Posting the
        // close serialises it after any running onImage on the same Looper.
        val posted = runCatching {
            listenerHandler.post {
                runCatching { reader.setOnImageAvailableListener(null, null) }
                runCatching { reader.close() }
            }
        }.getOrDefault(false)
        // Looper already gone — no frame callback can be running, close inline.
        if (!posted) runCatching { reader.close() }
    }
}

/**
 * Pick a YUV output size near 1280x720 — the resolution guaranteed for both
 * concurrent cameras and paired physical streams. Prefers an exact match, then
 * the closest size that is not larger (going over breaks concurrent capture).
 */
private fun chooseSize(ch: CameraCharacteristics): Size {
    val target = 1280 * 720
    val sizes = runCatching {
        ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList()
    }.getOrNull().orEmpty()
    if (sizes.isEmpty()) return Size(1280, 720)
    sizes.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return it }
    val notLarger = sizes.filter { it.width * it.height <= target }
    return (notLarger.ifEmpty { sizes })
        .minByOrNull { abs(it.width * it.height - target) }
        ?: Size(1280, 720)
}

// --- YUV -> Bitmap ---------------------------------------------------------

/** A detached copy of one YUV_420_888 frame — safe to hold after Image.close(). */
private class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val yRowStride: Int,
    val yPixelStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int
) {
    companion object {
        fun copyFrom(image: Image): YuvFrame {
            val planes = image.planes
            val yP = planes[0]
            val uP = planes[1]
            val vP = planes[2]
            return YuvFrame(
                width = image.width,
                height = image.height,
                y = yP.buffer.toBytes(),
                u = uP.buffer.toBytes(),
                v = vP.buffer.toBytes(),
                yRowStride = yP.rowStride,
                yPixelStride = yP.pixelStride,
                uvRowStride = uP.rowStride,
                uvPixelStride = uP.pixelStride
            )
        }

        private fun ByteBuffer.toBytes(): ByteArray {
            rewind()
            return ByteArray(remaining()).also { get(it) }
        }
    }
}

/** Multi-threaded YUV_420_888 -> upright ARGB bitmap conversion. */
private object YuvConverter {
    /**
     * Convert [frame] into a [Bitmap]. YUV->RGB is per-pixel independent, so the
     * rows are split into bands run in parallel on [rowPool]; [argb] is a reused
     * scratch buffer the caller owns.
     *
     * The feed is never mirrored — a recording should show the true scene, not
     * a selfie flip, so even the front camera comes through un-mirrored.
     */
    fun toBitmap(
        frame: YuvFrame,
        argb: IntArray,
        rowPool: ExecutorService,
        rotationDegrees: Int
    ): Bitmap {
        val w = frame.width
        val h = frame.height
        val bands = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            .coerceAtMost(h)
        val rowsPerBand = (h + bands - 1) / bands
        val latch = CountDownLatch(bands)
        for (b in 0 until bands) {
            val startRow = b * rowsPerBand
            val endRow = minOf(startRow + rowsPerBand, h)
            if (startRow >= endRow) {
                latch.countDown()
                continue
            }
            try {
                rowPool.execute {
                    try {
                        convertBand(frame, argb, startRow, endRow)
                    } finally {
                        latch.countDown()
                    }
                }
            } catch (t: Throwable) {
                // Pool rejected the work (shutting down) — keep the latch sane.
                latch.countDown()
            }
        }
        latch.await()

        val bitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    /** Convert rows [[startRow], [endRow]) using the BT.601 full-range matrix. */
    private fun convertBand(f: YuvFrame, out: IntArray, startRow: Int, endRow: Int) {
        val w = f.width
        val uMax = f.u.size - 1
        val vMax = f.v.size - 1
        for (row in startRow until endRow) {
            val yRowBase = row * f.yRowStride
            val uvRowBase = (row shr 1) * f.uvRowStride
            var outIdx = row * w
            for (col in 0 until w) {
                val y = f.y[yRowBase + col * f.yPixelStride].toInt() and 0xff
                val uvCol = (col shr 1) * f.uvPixelStride
                var uIdx = uvRowBase + uvCol
                if (uIdx > uMax) uIdx = uMax
                var vIdx = uvRowBase + uvCol
                if (vIdx > vMax) vIdx = vMax
                val u = (f.u[uIdx].toInt() and 0xff) - 128
                val v = (f.v[vIdx].toInt() and 0xff) - 128

                var r = y + ((91881 * v) shr 16)
                var g = y - ((22554 * u + 46802 * v) shr 16)
                var b = y + ((116130 * u) shr 16)
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                out[outIdx++] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
        }
    }
}

private const val TAG = "StudioCameraHub"
