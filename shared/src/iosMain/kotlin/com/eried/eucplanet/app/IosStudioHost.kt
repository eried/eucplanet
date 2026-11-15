package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import com.eried.eucplanet.platform.HttpClient
import com.eried.eucplanet.platform.HttpRequest
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.formatLocalDateUs
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.ic_launcher_foreground
import com.eried.eucplanet.ui.studio.ReplayPhotoFormat
import com.eried.eucplanet.ui.studio.StudioHost
import com.eried.eucplanet.ui.studio.StudioOffscreen
import com.eried.eucplanet.ui.studio.StudioPendingFile
import com.eried.eucplanet.ui.studio.StudioPermissions
import com.eried.eucplanet.ui.studio.StudioRender
import com.eried.eucplanet.ui.studio.StudioVideoSink
import com.eried.eucplanet.ui.studio.camera.StudioCameraHub
import com.eried.eucplanet.ui.studio.camera.StudioVideoHub
import com.eried.eucplanet.ui.studio.recording.OverlayFrameSpec
import com.eried.eucplanet.ui.theme.AppThemeColors
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.Surface
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSBundle
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * What the overlay elements read wherever they draw, as AndroidStudioRender:
 * the build's version, the launcher icon and the map tiles.
 */
internal open class IosStudioRender : StudioRender {
    override val versionName: String =
        NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "?"

    /**
     * The launcher icon as Android's getApplicationIcon draws it: the
     * foreground art on its round dark backdrop, 192 px.
     */
    @Composable
    override fun appIcon(): ImageBitmap? {
        val painter = painterResource(Res.drawable.ic_launcher_foreground)
        val density = LocalDensity.current
        return remember(painter) {
            runCatching {
                val bitmap = ImageBitmap(ICON_PX, ICON_PX)
                val size = Size(ICON_PX.toFloat(), ICON_PX.toFloat())
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), size) {
                    drawCircle(LAUNCHER_BACKGROUND)
                    with(painter) { draw(size) }
                }
                bitmap
            }.getOrNull()
        }
    }

    override fun mapTile(url: String): ImageBitmap? = IosMapTiles.get(url)

    override suspend fun loadMapTile(url: String, zoom: Int, x: Int, y: Int): Boolean = IosMapTiles.load(url)

    private companion object {
        const val ICON_PX = 192

        // The launcher icon's own backdrop (ic_launcher_background), as IosDashboardHost.appLogoBackground.
        val LAUNCHER_BACKGROUND = Color(0xFF0D0D0D)
    }
}

/**
 * The map tiles the studio's map element draws, as Android's MapTileCache:
 * the last 64 decoded in memory, each fetched once however many maps ask,
 * the HTTP cache under it (NSURLSession's). A gray base tile gets its
 * reference labels drawn over it. There is no offline map on the iPhone.
 */
internal object IosMapTiles {
    private const val DECODED_TILE_COUNT = 64
    private const val USER_AGENT = "EUCPlanet-Maps/1.0 (github.com/eried/eucplanet)"

    private val lock = SynchronizedObject()
    private val decoded = LinkedHashMap<String, ImageBitmap>()
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { HttpClient() }

    fun get(url: String): ImageBitmap? = synchronized(lock) {
        // Least recently used goes first: a read moves the tile to the end.
        decoded.remove(url)?.also { decoded[url] = it }
    }

    suspend fun load(url: String): Boolean {
        get(url)?.let { return true }
        val job = synchronized(lock) {
            inFlight.getOrPut(url) {
                scope.async {
                    fetch(url)?.also { tile ->
                        synchronized(lock) {
                            decoded[url] = tile
                            while (decoded.size > DECODED_TILE_COUNT) decoded.remove(decoded.keys.first())
                        }
                    }.also { synchronized(lock) { inFlight.remove(url) } }
                }
            }
        }
        return job.await() != null
    }

    private fun fetch(url: String): ImageBitmap? {
        val base = fetchImage(url) ?: return null
        val referenceUrl = url.replace("_Gray_Base/", "_Gray_Reference/")
        if (referenceUrl == url) return base.toComposeImageBitmap()
        val reference = fetchImage(referenceUrl) ?: return base.toComposeImageBitmap()
        val surface = Surface.makeRasterN32Premul(base.width, base.height)
        surface.canvas.drawImage(base, 0f, 0f)
        surface.canvas.drawImage(reference, 0f, 0f)
        return surface.makeImageSnapshot().toComposeImageBitmap().also { surface.close() }
    }

    private fun fetchImage(url: String): SkImage? = runCatching {
        val response = http.execute(
            HttpRequest(url, HttpRequest.Body.None, headers = listOf("User-Agent" to USER_AGENT)),
        )
        if (!response.isSuccessful || response.body.isEmpty()) null
        else SkImage.makeFromEncoded(response.body)
    }.onFailure { Log.w("MapTiles", "Tile failed: ${it.message}") }.getOrNull()
}

/**
 * The Overlay Studio's iPhone side, as AndroidStudioHost is Android's: the
 * window, the rotation, back, the camera and microphone grants, the photo
 * library pickers, the replay clips, the encoders and the photo library.
 */
internal class IosStudioHost : IosStudioRender(), StudioHost {

    /**
     * The window while the studio is up: the screen kept on and the status
     * bar hidden. The app is portrait only and has no picture in picture,
     * so there is nothing to lock.
     */
    @Composable
    override fun WindowEffects() {
        DisposableEffect(Unit) {
            IosDisplayPrefs.holdScreenOn(true)
            StatusBarHosts.current?.hidden = true
            onDispose {
                IosDisplayPrefs.holdScreenOn(false)
                StatusBarHosts.current?.hidden = false
            }
        }
    }

    /**
     * The phone's physical rotation, from the accelerometer as Android's
     * OrientationEventListener reads it (the same angle, unknown when the
     * phone lies flat), with the same 65 degree hysteresis: the rotation
     * flips only once the phone is turned well past the 45 degree line.
     */
    @OptIn(ExperimentalForeignApi::class)
    @Composable
    override fun deviceRotation(): Int {
        var deviceRotation by remember { mutableStateOf(0) }
        DisposableEffect(Unit) {
            val manager = CMMotionManager()
            if (manager.accelerometerAvailable) {
                // Android's SENSOR_DELAY_NORMAL, which the listener uses.
                manager.accelerometerUpdateInterval = 0.2
                manager.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
                    data?.acceleration?.useContents {
                        // Core Motion's g is Android's -m/s2 / 9.81; the listener's own
                        // negation makes X, Y, Z these values.
                        val orientation = orientationOf(x.toFloat(), y.toFloat(), z.toFloat())
                            ?: return@useContents
                        val candidate = (((orientation + 45) / 90) * 90) % 360
                        if (candidate == deviceRotation) return@useContents
                        val diff = ((orientation - deviceRotation + 540) % 360) - 180
                        if (abs(diff) > 65) deviceRotation = candidate
                    }
                }
            }
            onDispose { manager.stopAccelerometerUpdates() }
        }
        return deviceRotation
    }

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) = IosBackHandler(enabled, onBack)

    @Composable
    override fun rememberPermissions(): StudioPermissions = remember { IosStudioPermissions() }

    @Composable
    override fun rememberImagePicker(onPicked: (String?) -> Unit): () -> Unit {
        val latest by androidx.compose.runtime.rememberUpdatedState(onPicked)
        return remember { { IosStudioPicker.pickImage { latest(it) } } }
    }

    @Composable
    override fun rememberVideoPicker(onPicked: (String) -> Unit): () -> Unit {
        val latest by androidx.compose.runtime.rememberUpdatedState(onPicked)
        return remember { { IosStudioPicker.pickVideo { latest(it) } } }
    }

    @Composable
    override fun rememberCameraHub(requestedKeys: List<String>, enabled: Boolean): StudioCameraHub =
        remember { StudioCameraHub().apply { ready = true } }

    @Composable
    override fun rememberVideoHub(uris: Set<String>, enabled: Boolean): StudioVideoHub =
        rememberIosVideoHub(uris, enabled)

    @Composable
    override fun ReplayVideoPlayer(
        uri: String,
        videoTimeMs: Long,
        playing: Boolean,
        loop: Boolean,
        speed: Float,
        fit: String,
        modifier: Modifier,
    ) = IosReplayVideoPlayer(uri, videoTimeMs, playing, loop, speed, fit, modifier)

    override fun newVideoSink(withAudio: Boolean): StudioVideoSink = object : StudioVideoSink {
        override val encodeWidth: Int = 0
        override val encodeHeight: Int = 0
        override fun start(width: Int, height: Int, targetDurationUs: Long): Boolean = false
        override fun submitFrame(draw: (Canvas) -> Unit): Boolean = false
        override fun submitImage(frame: ImageBitmap, backgroundArgb: Long): Boolean = false
        override fun finish(): String? = null
    }

    override suspend fun savePhoto(frame: ImageBitmap, format: ReplayPhotoFormat, chromaArgb: Long): String? = null

    override suspend fun newPendingImage(displayName: String, mime: String): StudioPendingFile? = null

    override fun timestampedName(): String = "EUC_" + formatLocalDateUs("yyyyMMdd_HHmmss", com.eried.eucplanet.platform.System.currentTimeMillis())

    override fun framePixels(frame: ImageBitmap, width: Int, height: Int, into: IntArray): Boolean =
        com.eried.eucplanet.app.framePixels(frame, width, height, into)

    override fun thumbnail(frame: ImageBitmap): ImageBitmap? = com.eried.eucplanet.app.thumbnail(frame)

    // Replay exports render on screen, the path the Android emulator takes.
    override val offscreenSupported: Boolean = false

    override suspend fun createOffscreen(
        widthPx: Int,
        heightPx: Int,
        rotation: Int,
        themeColors: AppThemeColors,
        produce: (Int) -> OverlayFrameSpec,
    ): StudioOffscreen? = null

    // iOS opens the Photos app, not one item in it.
    override fun openGallery() {
        NSURL.URLWithString("photos-redirect://")?.let {
            UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null)
        }
    }

    override fun openInGallery(uri: String, mime: String) = openGallery()
}

/**
 * OrientationEventListener's angle (0 upright, 90 turned left side up, 270
 * right side up) from Core Motion's acceleration in g, or null when the phone
 * lies too flat to say, as the listener's ORIENTATION_UNKNOWN.
 */
internal fun orientationOf(x: Float, y: Float, z: Float): Int? {
    if ((x * x + y * y) * 4 < z * z) return null
    val angle = atan2(-y, x) * 57.29577957855f
    var orientation = 90 - angle.roundToInt()
    while (orientation >= 360) orientation -= 360
    while (orientation < 0) orientation += 360
    return orientation
}

/**
 * The camera and microphone grants, as state the screen recomposes on. A
 * request asks for the camera first and the microphone after, one prompt at a
 * time; a refusal sticks until the rider changes it in Settings, as on Android.
 */
private class IosStudioPermissions : StudioPermissions {
    private var cameraGranted by mutableStateOf(granted(AVMediaTypeVideo))
    private var audioGranted by mutableStateOf(granted(AVMediaTypeAudio))
    override val camera: Boolean get() = cameraGranted
    override val audio: Boolean get() = audioGranted

    override fun request(camera: Boolean, audio: Boolean) {
        val askAudio = {
            if (audio && !audioGranted) ask(AVMediaTypeAudio) { audioGranted = it }
        }
        if (camera && !cameraGranted) ask(AVMediaTypeVideo) { cameraGranted = it; askAudio() } else askAudio()
    }

    private fun ask(type: String?, onResult: (Boolean) -> Unit) {
        AVCaptureDevice.requestAccessForMediaType(type) { ok ->
            dispatch_async(dispatch_get_main_queue()) { onResult(ok) }
        }
    }

    private companion object {
        fun granted(type: String?) = AVCaptureDevice.authorizationStatusForMediaType(type) == AVAuthorizationStatusAuthorized
    }
}
