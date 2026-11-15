package com.eried.eucplanet.ui.studio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.view.OrientationEventListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.map.MapTileCache
import com.eried.eucplanet.ui.studio.camera.StudioCameraHub
import com.eried.eucplanet.ui.studio.camera.StudioVideoHub
import com.eried.eucplanet.ui.studio.camera.rememberStudioCameraHub
import com.eried.eucplanet.ui.studio.camera.rememberStudioVideoHub
import com.eried.eucplanet.ui.studio.recording.OverlayFrameSpec
import com.eried.eucplanet.ui.studio.recording.StudioByteSink
import com.eried.eucplanet.ui.studio.recording.StudioCapture
import com.eried.eucplanet.ui.studio.recording.StudioFramePixels
import com.eried.eucplanet.ui.studio.recording.StudioOffscreenSession
import com.eried.eucplanet.ui.studio.recording.StudioVideoEncoder
import com.eried.eucplanet.ui.studio.recording.asStudioByteSink
import com.eried.eucplanet.ui.theme.AppThemeColors
import kotlinx.coroutines.launch

/** The studio's Android host, built from the screen's context. */
@Composable
fun rememberAndroidStudioHost(): StudioHost {
    val context = LocalContext.current
    return remember(context) { AndroidStudioHost(context) }
}

/** The elements' renderer for a window outside the studio (the phone HUD). */
@Composable
fun rememberAndroidStudioRender(): StudioRender {
    val context = LocalContext.current
    return remember(context) { AndroidStudioRender(context) }
}

/**
 * The tile cache, the launcher icon and the build's version: what the overlay
 * elements read wherever they draw.
 */
open class AndroidStudioRender(protected val context: Context) : StudioRender {

    override val versionName: String = BuildConfig.VERSION_NAME

    @Composable
    override fun appIcon(): ImageBitmap? = remember {
        runCatching {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(192, 192)
                .asImageBitmap()
        }.getOrNull()
    }

    override fun mapTile(url: String): ImageBitmap? = MapTileCache.get(url)

    // With no signal, the downloaded map draws the tile.
    override suspend fun loadMapTile(url: String, zoom: Int, x: Int, y: Int): Boolean =
        MapTileCache.load(context, url, MapTileCache.TileXyz(zoom, x, y))
}

/**
 * Everything the Overlay Studio does through the Android framework: Camera2,
 * ExoPlayer, MediaCodec, MediaStore, the activity's window and the system
 * pickers. Each member is the code the screen used to run itself.
 */
private class AndroidStudioHost(context: Context) : AndroidStudioRender(context), StudioHost {

    private val activity: Activity? get() = context as? Activity

    @Composable
    override fun WindowEffects() {
        // The Studio owns its orientation: fixed portrait, camera-app style (the
        // round buttons counter-rotate their icons instead). Applied here as well
        // as in the MainActivity route policy because a task restored straight
        // into the Studio can race the route-based lock into landscape.
        DisposableEffect(Unit) {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // Entering PIP drops this screen from composition, taking the camera
            // and any running recording with it, so the Studio opts out for as
            // long as it is up.
            com.eried.eucplanet.util.PipHost.suppressPip = true
            onDispose { com.eried.eucplanet.util.PipHost.suppressPip = false }
        }
        // Keep the screen awake and run immersive while the studio is open.
        val view = LocalView.current
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            val window = activity?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            controller?.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            onDispose {
                view.keepScreenOn = false
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    @Composable
    override fun deviceRotation(): Int {
        var deviceRotation by remember { mutableStateOf(0) }
        // Track physical rotation so the control icons can counter-rotate (the
        // layout itself never rotates; that would scramble the viewport panes).
        //
        // The naive `((orientation + 45) / 90) * 90` snap flipped on the 45 deg
        // line, so a phone tilted just past portrait-into-landscape would
        // bounce the rotation back and forth as the rider's grip drifted by a
        // degree or two. We now require the orientation to land >65 deg away
        // from the CURRENT rotation before flipping -- a 20 deg hysteresis
        // band on either side of each 45 deg quadrant edge. The rider has to
        // actually rotate the phone, not just hold it slightly off-axis.
        DisposableEffect(Unit) {
            val listener = object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                    val candidate = (((orientation + 45) / 90) * 90) % 360
                    if (candidate == deviceRotation) return
                    // Shortest signed angular distance from `orientation` to
                    // `deviceRotation`, in (-180, 180].
                    val diff = ((orientation - deviceRotation + 540) % 360) - 180
                    if (kotlin.math.abs(diff) > 65) {
                        deviceRotation = candidate
                    }
                }
            }
            if (listener.canDetectOrientation()) listener.enable()
            onDispose { listener.disable() }
        }
        return deviceRotation
    }

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
        androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)

    @Composable
    override fun rememberPermissions(): StudioPermissions {
        val state = remember { PermissionsState(context) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            state.cameraGranted = grants[android.Manifest.permission.CAMERA] == true ||
                state.cameraGranted
            state.audioGranted = grants[android.Manifest.permission.RECORD_AUDIO] == true ||
                state.audioGranted
        }
        state.launch = { launcher.launch(it) }
        return state
    }

    @Composable
    override fun rememberImagePicker(onPicked: (String?) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        // Embeds the picked image in the preset: downscaled, base64 PNG.
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch { onPicked(StudioImages.encodeForPreset(context, uri)) }
        }
        return {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    @Composable
    override fun rememberVideoPicker(onPicked: (String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // The uri is kept regardless; StudioVideoHub will fail to open it
                // without the grant and the pane falls back to "No media", the
                // same honest state as any other unreadable clip.
            }
            onPicked(uri.toString())
        }
        return { launcher.launch(arrayOf("video/*")) }
    }

    @Composable
    override fun rememberCameraHub(requestedKeys: List<String>, enabled: Boolean): StudioCameraHub =
        rememberStudioCameraHub(requestedKeys, enabled)

    @Composable
    override fun rememberVideoHub(uris: Set<String>, enabled: Boolean): StudioVideoHub =
        rememberStudioVideoHub(uris, enabled)

    @Composable
    override fun ReplayVideoPlayer(
        uri: String,
        videoTimeMs: Long,
        playing: Boolean,
        loop: Boolean,
        speed: Float,
        fit: String,
        modifier: Modifier,
    ) = com.eried.eucplanet.ui.studio.ReplayVideoPlayer(
        uri = uri,
        videoTimeMs = videoTimeMs,
        playing = playing,
        loop = loop,
        speed = speed,
        fit = fit,
        modifier = modifier,
    )

    override fun newVideoSink(withAudio: Boolean): StudioVideoSink =
        AndroidVideoSink(StudioVideoEncoder(context, withAudio = withAudio))

    override suspend fun savePhoto(
        frame: ImageBitmap,
        format: ReplayPhotoFormat,
        chromaArgb: Long,
    ): String? {
        val src = frame.asAndroidBitmap()
        val uri = when (format) {
            ReplayPhotoFormat.PNG -> StudioCapture.savePng(context, src)
            ReplayPhotoFormat.WEBP -> StudioCapture.saveWebp(context, src)
            ReplayPhotoFormat.JPG ->
                // JPEG has no alpha; flatten onto the chroma colour first.
                StudioCapture.saveJpeg(context, flattenOntoChroma(src, chromaArgb))
        }
        return uri?.toString()
    }

    override suspend fun newPendingImage(displayName: String, mime: String): StudioPendingFile? {
        val pending = StudioCapture.newPendingImage(context, displayName, mime) ?: return null
        return object : StudioPendingFile {
            override val uri: String = pending.uri.toString()
            override fun openSink(): StudioByteSink? = pending.openStream()?.asStudioByteSink()
            override fun finalize(ok: Boolean) = pending.finalize(ok)
        }
    }

    override fun timestampedName(): String = StudioCapture.timestampedName()

    override fun framePixels(frame: ImageBitmap, width: Int, height: Int, into: IntArray): Boolean =
        StudioFramePixels.of(frame.asAndroidBitmap(), width, height, into)

    // Small software copy of a frame for the File Export preview.
    override fun thumbnail(frame: ImageBitmap): ImageBitmap? = runCatching {
        val src = frame.asAndroidBitmap()
        val sw = if (src.config == android.graphics.Bitmap.Config.HARDWARE)
            src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else src
        val tw = 240
        val th = (tw.toLong() * (sw?.height ?: 1) / (sw?.width ?: 1)).toInt().coerceAtLeast(1)
        val out = android.graphics.Bitmap.createScaledBitmap(sw!!, tw, th, true)
        if (sw !== src) sw.recycle()
        out.asImageBitmap()
    }.getOrNull()

    // The offscreen renderer hosts a Compose view in the window to draw frames
    // off the vsync clock. On the Android emulator this trips a GPU-emulation EGL
    // crash (libEGL_emulation eglDestroySurface) at render end, so emulators fall
    // back to the on-screen capture path; real-device GPU drivers handle it.
    override val offscreenSupported: Boolean =
        context is androidx.activity.ComponentActivity && !run {
            val b = android.os.Build.FINGERPRINT
            b.contains("generic", true) || b.contains("emulator", true) ||
                android.os.Build.MODEL.contains("Emulator", true) ||
                android.os.Build.MODEL.contains("Android SDK", true) ||
                android.os.Build.HARDWARE.contains("goldfish") ||
                android.os.Build.HARDWARE.contains("ranchu") ||
                android.os.Build.PRODUCT.contains("sdk", true)
        }

    override suspend fun createOffscreen(
        widthPx: Int,
        heightPx: Int,
        rotation: Int,
        themeColors: AppThemeColors,
        produce: (Int) -> OverlayFrameSpec,
    ): StudioOffscreen? {
        val activity = context as? androidx.activity.ComponentActivity ?: return null
        val session = StudioOffscreenSession.create(
            activity = activity,
            widthPx = widthPx,
            heightPx = heightPx,
            rotation = rotation,
            themeColors = themeColors,
            render = this,
            produce = produce,
        ) ?: return null
        return object : StudioOffscreen {
            override suspend fun frame(index: Int): ImageBitmap = session.frame(index).asImageBitmap()
            override suspend fun close() { session.close() }
        }
    }

    override fun openGallery() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
                }
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"
                        )
                    }
                )
            }
        }
    }

    /** Open a saved photo / video in whatever gallery app handles it. */
    override fun openInGallery(uri: String, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { android.util.Log.w("OverlayStudio", "No viewer for $uri", it) }
    }
}

/** The camera and microphone grants, as state the screen recomposes on. */
private class PermissionsState(context: Context) : StudioPermissions {
    var cameraGranted by mutableStateOf(
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    )
    var audioGranted by mutableStateOf(
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    )
    var launch: (Array<String>) -> Unit = {}
    override val camera: Boolean get() = cameraGranted
    override val audio: Boolean get() = audioGranted
    override fun request(camera: Boolean, audio: Boolean) {
        val needed = buildList {
            if (camera && !cameraGranted) add(android.Manifest.permission.CAMERA)
            if (audio && !audioGranted) add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) launch(needed.toTypedArray())
    }
}

/** StudioVideoEncoder as the shared screen's video sink. */
private class AndroidVideoSink(private val encoder: StudioVideoEncoder) : StudioVideoSink {
    override val encodeWidth: Int get() = encoder.encodeWidth
    override val encodeHeight: Int get() = encoder.encodeHeight

    override fun start(width: Int, height: Int, targetDurationUs: Long): Boolean =
        encoder.start(width, height, targetDurationUs = targetDurationUs)

    override fun submitFrame(draw: (Canvas) -> Unit): Boolean =
        encoder.submitFrame { androidCanvas -> draw(Canvas(androidCanvas)) }

    // MP4 has no alpha; every frame is composited onto the chroma colour
    // before it is drawn into the encoder.
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    override fun submitImage(frame: ImageBitmap, backgroundArgb: Long): Boolean {
        val canvasW = encoder.encodeWidth
        val canvasH = encoder.encodeHeight
        val src = frame.asAndroidBitmap()
        val sw = if (src.config == android.graphics.Bitmap.Config.HARDWARE) {
            src.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        } else src
        val drawn = encoder.submitFrame { canvas ->
            canvas.drawColor(backgroundArgb.toInt())
            if (sw != null) {
                val dst = android.graphics.Rect(0, 0, canvasW, canvasH)
                canvas.drawBitmap(sw, null, dst, paint)
            }
        }
        if (sw != null && sw !== src) sw.recycle()
        return drawn
    }

    override fun finish(): String? = encoder.finish()?.toString()
}

/**
 * Composite [src] onto an opaque [chroma] colour. JPEG carries no alpha, so the
 * transparent replay background has to be flattened to a solid fill first.
 */
private fun flattenOntoChroma(
    src: android.graphics.Bitmap,
    chroma: Long
): android.graphics.Bitmap {
    val flat = android.graphics.Bitmap.createBitmap(
        src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(flat)
    canvas.drawColor(chroma.toInt())
    // A software canvas cannot draw a HARDWARE bitmap, and toImageBitmap() may
    // hand one back.
    val sw = if (src.config == android.graphics.Bitmap.Config.HARDWARE) {
        src.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    } else src
    if (sw != null) {
        canvas.drawBitmap(sw, 0f, 0f, null)
        if (sw !== src) sw.recycle()
    }
    return flat
}
