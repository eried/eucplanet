package com.eried.eucplanet.ui.studio.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** A camera the device exposes, with a portable logical [key] (BACK / FRONT…). */
data class StudioCameraInfo(
    val key: String,
    val label: String,
    val front: Boolean,
    val deviceId: String
)

/**
 * Multi-camera feed hub for the Overlay Studio.
 *
 * Each camera a layout asks for is bound as its own headless [ImageAnalysis]
 * and exposed as a Compose [ImageBitmap], so several viewports / floating
 * windows can each show a *different* camera at once. Android caps simultaneous
 * streaming at two cameras, and only on devices with concurrent-camera support;
 * past that, extra cameras are simply not live and the UI shows a placeholder.
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

    /** Resolved CameraX provider, kept so teardown needn't block on a Future. */
    internal var provider: ProcessCameraProvider? = null

    fun frame(key: String): ImageBitmap? = frames[key]
    fun isLive(key: String): Boolean = key in liveKeys
    fun info(key: String): StudioCameraInfo? = cameras.firstOrNull { it.key == key }
}

/**
 * Binds the cameras named in [requestedKeys] (in priority order) while
 * [enabled] is true, and returns the live hub. Re-binds whenever the requested
 * set changes — that is how switching a viewport's camera takes effect.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun rememberStudioCameraHub(requestedKeys: List<String>, enabled: Boolean): StudioCameraHub {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hub = remember { StudioCameraHub() }
    val executor = remember { Executors.newFixedThreadPool(2) }

    LaunchedEffect(Unit) {
        val provider = runCatching { awaitCameraProvider(context) }.getOrNull()
            ?: return@LaunchedEffect
        hub.provider = provider
        hub.cameras = enumerateCameras(provider)
        hub.maxConcurrent = runCatching {
            if (provider.availableConcurrentCameraInfos.isNotEmpty()) 2 else 1
        }.getOrDefault(1)
        hub.ready = true
    }

    val requestSignature = requestedKeys.distinct().joinToString(",") + "|" + enabled
    LaunchedEffect(hub.ready, requestSignature) {
        if (!hub.ready) return@LaunchedEffect
        val provider = runCatching { awaitCameraProvider(context) }.getOrNull()
            ?: return@LaunchedEffect
        runCatching { provider.unbindAll() }
        hub.frames.clear()
        if (!enabled) {
            hub.liveKeys = emptySet()
            return@LaunchedEffect
        }
        val wanted = requestedKeys.distinct()
            .mapNotNull { k -> hub.cameras.firstOrNull { it.key == k } }
            .take(hub.maxConcurrent)
        hub.liveKeys = if (wanted.isEmpty()) emptySet()
        else bindCameras(provider, lifecycleOwner, wanted, hub, executor)
    }

    DisposableEffect(Unit) {
        onDispose {
            // Use the already-resolved provider so teardown never blocks the
            // main thread on a ListenableFuture.
            runCatching { hub.provider?.unbindAll() }
            executor.shutdown()
        }
    }
    return hub
}

@OptIn(ExperimentalCamera2Interop::class)
private fun enumerateCameras(provider: ProcessCameraProvider): List<StudioCameraInfo> {
    var backCount = 0
    var frontCount = 0
    return provider.availableCameraInfos.mapNotNull { info ->
        val deviceId = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
            ?: return@mapNotNull null
        val front = info.lensFacing == CameraSelector.LENS_FACING_FRONT
        val key: String
        val label: String
        if (front) {
            frontCount++
            key = if (frontCount == 1) "FRONT" else "FRONT$frontCount"
            label = if (frontCount == 1) "Front camera" else "Front camera $frontCount"
        } else {
            backCount++
            key = if (backCount == 1) "BACK" else "BACK$backCount"
            label = if (backCount == 1) "Back camera" else "Back camera $backCount"
        }
        StudioCameraInfo(key, label, front, deviceId)
    }
}

@OptIn(ExperimentalCamera2Interop::class)
private fun selectorFor(deviceId: String): CameraSelector =
    CameraSelector.Builder().addCameraFilter { infos ->
        infos.filter { Camera2CameraInfo.from(it).cameraId == deviceId }
    }.build()

private fun newAnalysis(
    cam: StudioCameraInfo,
    hub: StudioCameraHub,
    executor: Executor
): ImageAnalysis {
    val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()
    analysis.setAnalyzer(executor) { proxy ->
        try {
            hub.frames[cam.key] = proxy.toUprightBitmap(cam.front).asImageBitmap()
        } catch (e: Throwable) {
            Log.w(TAG, "Frame convert failed for ${cam.key}", e)
        } finally {
            proxy.close()
        }
    }
    return analysis
}

/** Bind [cameras], returning the keys that ended up actually streaming. */
private fun bindCameras(
    provider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    cameras: List<StudioCameraInfo>,
    hub: StudioCameraHub,
    executor: Executor
): Set<String> {
    if (cameras.size == 1) {
        return runCatching {
            val cam = cameras[0]
            provider.bindToLifecycle(
                lifecycleOwner, selectorFor(cam.deviceId), newAnalysis(cam, hub, executor)
            )
            setOf(cam.key)
        }.getOrElse {
            Log.e(TAG, "Single camera bind failed", it)
            emptySet()
        }
    }
    // Two cameras — concurrent camera mode.
    val concurrent = runCatching {
        val configs = cameras.map { cam ->
            ConcurrentCamera.SingleCameraConfig(
                selectorFor(cam.deviceId),
                UseCaseGroup.Builder().addUseCase(newAnalysis(cam, hub, executor)).build(),
                lifecycleOwner
            )
        }
        provider.bindToLifecycle(configs)
        cameras.map { it.key }.toSet()
    }.getOrNull()
    if (concurrent != null) return concurrent
    // Device cannot co-stream — fall back to the first camera only.
    Log.w(TAG, "Concurrent camera unavailable, using one camera")
    runCatching { provider.unbindAll() }
    return runCatching {
        val cam = cameras[0]
        provider.bindToLifecycle(
            lifecycleOwner, selectorFor(cam.deviceId), newAnalysis(cam, hub, executor)
        )
        setOf(cam.key)
    }.getOrElse { emptySet() }
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { cont.resumeWith(Result.success(it)) }
                    .onFailure { cont.resumeWith(Result.failure(it)) }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

/** Convert an analysis frame to an upright ARGB bitmap; mirror the front lens. */
private fun ImageProxy.toUprightBitmap(front: Boolean): Bitmap {
    val raw = toBitmap()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0 && !front) return raw
    val matrix = Matrix().apply {
        postRotate(rotation.toFloat())
        if (front) postScale(-1f, 1f)
    }
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    if (rotated != raw) raw.recycle()
    return rotated
}

private const val TAG = "StudioCameraHub"
