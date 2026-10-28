package com.eried.eucplanet.hud.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.eried.eucplanet.hud.R
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.HudUnits
import com.eried.eucplanet.hud.ui.parseHexColor
import kotlinx.coroutines.delay

/**
 * Rear-camera screen. Live preview from the HUD's rear camera with a small
 * speed readout pinned bottom-right.
 *
 * The HUD's rear camera is exposed through the standard camera2 HAL on every
 * E6-class device we've seen, so we use CameraX which picks the rear lens
 * automatically. If the device doesn't expose a camera (e.g. an emulator
 * during development), we show a static "unavailable" placeholder rather
 * than crash, the rider can switch screens with LEFT/RIGHT regardless.
 */
@Composable
fun CameraScreen(
    hud: HudState,
    cameraIndex: Int = 0,
    rotation: Int = 270,
    onCamerasFound: (Int) -> Unit = {},
    cameraHandedOff: Boolean = false,
    cameraDiag: String = "",
    onCameraTaken: () -> Unit = {},
    onOtherAppCamera: (Boolean) -> Unit = {},
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-evaluate every recomposition, not just once: the rider may grant
    // the permission via the launch-time dialog AFTER this screen first
    // composed (cold start: dashboard first, camera screen entered later).
    val hasPermission = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    var cameraReady by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    val accent = parseHexColor(hud.accentArgb)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RearCameraPreview(
            Modifier.fillMaxSize(),
            cameraIndex = cameraIndex,
            rotation = rotation,
            onCamerasFound = onCamerasFound,
            handedOff = cameraHandedOff,
            diag = cameraDiag,
            onCameraTaken = onCameraTaken,
            onOtherAppCamera = onOtherAppCamera
        )

        // Speed overlay (bottom-right). Same unit code as the dashboard.
        val displaySpeed = HudUnits.speed(hud.speedKmh, hud.unitSpeed)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .background(Color(0xAA000000))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "%.0f %s".format(displaySpeed, HudUnits.speedSuffix(hud.unitSpeed)),
                color = accent,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    LaunchedEffect(hasPermission) {
        // No-op; the AndroidView update lambda is the bind point. This
        // LaunchedEffect just makes hasPermission a key the composer tracks
        // when the rider grants the permission mid-session (rare on HUDs
        // since they sideload, but harmless to handle.)
    }
}

/**
 * Just the rear-camera Preview surface, no overlays. Shared by both the
 * standalone [CameraScreen] and the camera-backed Custom overlay screen
 * (see HudApp). Falls back to a centered text placeholder if the camera
 * permission was denied or no camera is available.
 *
 * [cameraIndex] picks from the device's cameras, back ones first, wrapping at
 * the count; a new index rebinds. [rotation] turns the picture clockwise.
 * [onCamerasFound] hears how many cameras there are once the provider is up.
 * [onCameraTaken] hears that another app has the camera open.
 * [onOtherAppCamera] hears, while handed off, whether other apps hold a camera.
 */
@Composable
fun RearCameraPreview(
    modifier: Modifier = Modifier,
    cameraIndex: Int = 0,
    rotation: Int = 270,
    onCamerasFound: (Int) -> Unit = {},
    handedOff: Boolean = false,
    diag: String = "",
    onCameraTaken: () -> Unit = {},
    onOtherAppCamera: (Boolean) -> Unit = {},
) {
    // Handed to the MotoEye recorder: the live preview leaves composition,
    // and with it its camera (see LiveCameraPreview's DisposableEffect).
    if (handedOff) {
        val ctx = LocalContext.current
        OtherAppCameraWatch(onOtherAppCamera)
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = ctx.getString(R.string.hud_camera_handed_off),
                    color = Color.White.copy(alpha = 0.7f)
                )
                // Test builds: what keeps the picture off, for a tester's photo.
                if (diag.isNotEmpty()) {
                    Text(
                        text = diag,
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp)
                    )
                }
            }
        }
    } else {
        LiveCameraPreview(modifier, cameraIndex, rotation, onCamerasFound, onCameraTaken)
    }
}

@Composable
private fun LiveCameraPreview(
    modifier: Modifier,
    cameraIndex: Int,
    rotation: Int,
    onCamerasFound: (Int) -> Unit,
    onCameraTaken: () -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasPermission = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    var cameraReady by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    // The camera index we last kicked off a bind for -- prevents the
    // AndroidView update callback (which fires on every recomposition)
    // from launching another bindToLifecycle in the same composable
    // instance. Without this, the slide transition between screens
    // re-runs the camera init dozens of times per second and produces a
    // flood of CameraValidator: Camera LensFacing verification failed
    // errors that pegs the main thread and triggers an ANR. Only a new
    // [cameraIndex] (the rider pressed UP) binds again.
    var boundIndex by remember { mutableStateOf<Int?>(null) }
    val binding = remember { CameraBinding() }
    // Hand the camera back the moment this screen goes away. Bound to the
    // activity's lifecycle it stayed ours on every other screen, and the
    // MotoEye's own recorder could not start while the HUD app was open.
    DisposableEffect(Unit) {
        onDispose { binding.release() }
    }

    Box(modifier = modifier) {
        if (hasPermission && !cameraFailed) {
            // Rotation goes through a Compose graphicsLayer wrapping the
            // PreviewView, NOT through view.rotation. We swept the four
            // techniques on a real MotoEye E6 (camera-rotation-debug
            // branch) and PreviewView's internal transformation reapplies
            // itself on every frame, overriding any view-level rotation
            // we set inside the AndroidView. The compose layer sits
            // OUTSIDE PreviewView's reach so the 270° fix actually holds.
            //
            // Settings the rider confirmed work:
            //   technique = Compose graphicsLayer  (this Box's rotationZ)
            //   angle     = 270° (the default; DOWN turns it for other models)
            //   scale     = PreviewView.ScaleType.FILL_CENTER
            //   mirror    = none
            //
            // We still set ImplementationMode.COMPATIBLE because the
            // default SurfaceView path composites via SurfaceFlinger and
            // ignores graphicsLayer transforms entirely.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = rotation.toFloat() }
            ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PreviewView(c).also { view ->
                        view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        view.scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { view ->
                    if (boundIndex != cameraIndex) {
                        boundIndex = cameraIndex
                        cameraReady = false
                        val providerFuture = ProcessCameraProvider.getInstance(view.context)
                        providerFuture.addListener({
                            try {
                                // Slid past before the camera was ready.
                                if (binding.released) return@addListener
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = view.surfaceProvider
                                }
                                provider.unbindAll()
                                // Start at the rider's pick and fall through
                                // the rest, so a camera that will not open
                                // still leaves a picture.
                                val cameras = orderedCameras(provider.availableCameraInfos)
                                onCamerasFound(cameras.size)
                                val selectors = if (cameras.isEmpty()) {
                                    listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
                                } else {
                                    val start = cameraIndex % cameras.size
                                    cameras.indices.map { i ->
                                        val info = cameras[(start + i) % cameras.size]
                                        CameraSelector.Builder()
                                            .addCameraFilter { _ -> listOf(info) }
                                            .build()
                                    }
                                }
                                var bound = false
                                for (sel in selectors) {
                                    try {
                                        val camera = provider.bindToLifecycle(lifecycleOwner, sel, preview)
                                        binding.hold(provider, preview)
                                        // The MotoEye recorder already has
                                        // the camera: let it keep it, rather
                                        // than open it the moment it stops
                                        // and block its next start.
                                        val boundAt = SystemClock.uptimeMillis()
                                        binding.watch(camera, lifecycleOwner) { st ->
                                            if (takenByAnotherApp(st.error?.code, SystemClock.uptimeMillis() - boundAt)) {
                                                onCameraTaken()
                                            }
                                        }
                                        bound = true
                                        break
                                    } catch (_: Throwable) {
                                        // try next
                                    }
                                }
                                if (bound) cameraReady = true
                                else cameraFailed = true
                            } catch (_: Throwable) {
                                cameraFailed = true
                            }
                        }, ContextCompat.getMainExecutor(view.context))
                    }
                }
            )
            }
            // Loading state: the PreviewView mounts black and stays
            // black until the camera surface delivers its first frame.
            // [cameraReady] flips only once bindToLifecycle returns
            // successfully -- there's still a perceptible gap between
            // that and the first preview frame, but tracking the bind
            // result is the closest signal we have without subscribing
            // to CameraInfo.cameraState. Without an indicator the rider
            // sees a black void on the Camera screen and a black-bleed
            // through on the Custom + camera screen, both of which
            // read as "broken" rather than "starting up."
            if (!cameraReady) {
                CameraLoadingBadge(modifier = Modifier.align(Alignment.Center))
            }
        } else {
            val msg = when {
                !hasPermission -> ctx.getString(R.string.hud_camera_permission_denied)
                else -> ctx.getString(R.string.hud_camera_unavailable)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = msg, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/** The device's cameras, back-facing first, then front, then the rest: the
 *  order the rider steps through with UP. Index 0 is the camera the HUD
 *  always opened before the choice existed. */
private fun orderedCameras(infos: List<CameraInfo>): List<CameraInfo> =
    infos.sortedBy {
        when (it.lensFacing) {
            CameraSelector.LENS_FACING_BACK -> 0
            CameraSelector.LENS_FACING_FRONT -> 1
            else -> 2
        }
    }

/**
 * Reports whether any camera is open while the preview holds none, so the
 * preview can come back once the MotoEye lets go. Starts listening a moment
 * after the hand-off, when our own camera has closed, and reports "free"
 * only after it has stayed free for a moment: a recorder stop and the next
 * start, or a switch between cameras, should not flash the preview.
 */
@Composable
private fun OtherAppCameraWatch(onOtherAppCamera: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf<Boolean?>(null) }
    DisposableEffect(Unit) {
        val mgr = ctx.getSystemService(CameraManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        val open = mutableSetOf<String>()
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                open -= cameraId
                busy = open.isNotEmpty()
            }

            override fun onCameraUnavailable(cameraId: String) {
                open += cameraId
                busy = true
            }
        }
        var registered = false
        val start = Runnable {
            if (mgr != null) registered = runCatching { mgr.registerAvailabilityCallback(callback, handler) }.isSuccess
        }
        handler.postDelayed(start, OWN_CLOSE_MS)
        onDispose {
            handler.removeCallbacks(start)
            if (registered) runCatching { mgr?.unregisterAvailabilityCallback(callback) }
        }
    }
    LaunchedEffect(busy) {
        when (busy) {
            true -> onOtherAppCamera(true)
            false -> {
                delay(FREE_SETTLE_MS)
                onOtherAppCamera(false)
            }
            null -> Unit
        }
    }
}

private const val OWN_CLOSE_MS = 1_500L
private const val FREE_SETTLE_MS = 1_500L

/** True when a camera error means another app holds the camera. Too many
 *  cameras open also shows up for a moment while UP switches ours, so that
 *  one only counts once the bind has settled. */
internal fun takenByAnotherApp(code: Int?, sinceBindMs: Long): Boolean = when (code) {
    CameraState.ERROR_CAMERA_IN_USE -> true
    CameraState.ERROR_MAX_CAMERAS_IN_USE -> sinceBindMs >= SETTLE_MS
    else -> false
}

private const val SETTLE_MS = 2_000L

/** The preview one [RearCameraPreview] bound, so leaving that screen unbinds
 *  only its own use case: sliding from Camera straight to Custom + camera
 *  must not drop the camera the incoming screen just bound. */
private class CameraBinding {
    var released = false
        private set
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var state: LiveData<CameraState>? = null
    private var observer: Observer<CameraState>? = null

    fun hold(provider: ProcessCameraProvider, preview: Preview) {
        this.provider = provider
        this.preview = preview
    }

    /** Follow [camera]'s state until release; one watch at a time. */
    fun watch(camera: Camera, owner: LifecycleOwner, onState: (CameraState) -> Unit) {
        unwatch()
        val o = Observer<CameraState> { if (!released) onState(it) }
        camera.cameraInfo.cameraState.observe(owner, o)
        state = camera.cameraInfo.cameraState
        observer = o
    }

    private fun unwatch() {
        observer?.let { o -> state?.removeObserver(o) }
        state = null
        observer = null
    }

    fun release() {
        released = true
        unwatch()
        val p = preview ?: return
        runCatching { provider?.unbind(p) }
        provider = null
        preview = null
    }
}

/** Chrome-matched "starting camera" indicator shown over an empty
 *  PreviewView until the first frame arrives. Same fill, border, and
 *  type as the disconnect badge in HudApp so the HUD feels consistent
 *  across status surfaces. */
@Composable
private fun CameraLoadingBadge(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Row(
        modifier = modifier
            .clip(RectangleShape)
            .background(Color(0xE6111111))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = Color.White.copy(alpha = 0.85f),
            strokeWidth = 2.dp
        )
        Text(
            text = ctx.getString(R.string.hud_camera_loading),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
