package com.eried.eucplanet.ui.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import com.eried.eucplanet.ui.studio.camera.StudioCameraHub
import com.eried.eucplanet.ui.studio.camera.StudioVideoHub
import com.eried.eucplanet.ui.studio.recording.OverlayFrameSpec
import com.eried.eucplanet.ui.studio.recording.StudioByteSink
import com.eried.eucplanet.ui.theme.AppThemeColors

/**
 * What the overlay elements need from the phone wherever they are drawn: the
 * studio, its offscreen export renderer, and the floating phone HUD window.
 * Android supplies the tile cache, the launcher icon and the build's version.
 */
interface StudioRender {
    /** The app's version name, for the App badge element ("0.24.0"). */
    val versionName: String

    /** The launcher icon as a bitmap for the App badge, or null. */
    @Composable
    fun appIcon(): ImageBitmap?

    /** A map tile already in the cache, or null. */
    fun mapTile(url: String): ImageBitmap?

    /** Fetches a map tile (or reads it from the offline map); true when it is now cached. */
    suspend fun loadMapTile(url: String, zoom: Int, x: Int, y: Int): Boolean
}

/** The renderer the elements draw with; the studio and the phone HUD window provide it. */
val LocalStudioRender = staticCompositionLocalOf<StudioRender> {
    error("No StudioRender: the studio or the HUD window provides one")
}

/**
 * The camera and microphone grants the studio asks for. Android reads and
 * requests the runtime permissions; [request] asks only for what is missing.
 */
interface StudioPermissions {
    val camera: Boolean
    val audio: Boolean
    fun request(camera: Boolean, audio: Boolean)
}

/**
 * A video file being written: the live take (with the microphone) or a replay
 * clip's MP4. Android is StudioVideoEncoder (MediaCodec + MediaMuxer).
 */
interface StudioVideoSink {
    /** The encoded frame size, set by [start]. */
    val encodeWidth: Int
    val encodeHeight: Int

    /**
     * Opens the encoder for frames of [width] x [height]. A non-zero
     * [targetDurationUs] spreads the frames over that length (offline export).
     * False when the phone cannot encode video.
     */
    fun start(width: Int, height: Int, targetDurationUs: Long = 0L): Boolean

    /** Draws one frame into the encoder's input; false when the encoder failed. */
    fun submitFrame(draw: (Canvas) -> Unit): Boolean

    /** Draws [frame] scaled over an opaque [backgroundArgb] fill; false when the encoder failed. */
    fun submitImage(frame: ImageBitmap, backgroundArgb: Long): Boolean

    /** Closes the file; the saved video's reference (a content uri on Android), or null. */
    fun finish(): String?
}

/** A gallery file the replay clip streams into (GIF / APNG), then publishes or drops. */
interface StudioPendingFile {
    /** The file's reference, for the View action. */
    val uri: String
    fun openSink(): StudioByteSink?
    fun finalize(ok: Boolean)
}

/**
 * Renders replay frames off the display's clock. Android hosts the element
 * layer in a detached ComposeView; a phone without one renders on screen.
 */
interface StudioOffscreen {
    suspend fun frame(index: Int): ImageBitmap
    suspend fun close()
}

/**
 * The phone side of the Overlay Studio screen, in the style of
 * RouteBuilderHost: the cameras, the media pickers, the encoders and the
 * gallery, the window it runs in. Android keeps all of it in
 * AndroidStudioHost; the iPhone does not show the studio yet.
 */
interface StudioHost : StudioRender {
    /**
     * The studio's window while it is up: fixed portrait, no picture in
     * picture, the screen kept on, the system bars hidden. Undone on leave.
     */
    @Composable
    fun WindowEffects()

    /** The phone's physical rotation (0, 90, 180, 270) for the counter-rotated icons. */
    @Composable
    fun deviceRotation(): Int

    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit)

    @Composable
    fun rememberPermissions(): StudioPermissions

    /**
     * An image picker. [onPicked] gets the picked image downscaled and encoded
     * for a preset (base64 PNG), or null when it could not be read; nothing is
     * called when the rider cancels.
     */
    @Composable
    fun rememberImagePicker(onPicked: (String?) -> Unit): () -> Unit

    /** A video picker for a replay face; [onPicked] gets the clip's uri. */
    @Composable
    fun rememberVideoPicker(onPicked: (String) -> Unit): () -> Unit

    /** The camera feeds for [requestedKeys] while [enabled]. */
    @Composable
    fun rememberCameraHub(requestedKeys: List<String>, enabled: Boolean): StudioCameraHub

    /** Frame access to the replay faces' video clips while [enabled]. */
    @Composable
    fun rememberVideoHub(uris: Set<String>, enabled: Boolean): StudioVideoHub

    /** A replay face's clip, playing muted in step with the replay. */
    @Composable
    fun ReplayVideoPlayer(
        uri: String,
        videoTimeMs: Long,
        playing: Boolean,
        loop: Boolean,
        speed: Float,
        fit: String,
        modifier: Modifier,
    )

    /** A video file for the live take ([withAudio] records the microphone) or a replay clip. */
    fun newVideoSink(withAudio: Boolean): StudioVideoSink

    /**
     * Saves a snapshot in [format]; JPG is flattened onto [chromaArgb] first.
     * The saved photo's reference, or null.
     */
    suspend fun savePhoto(frame: ImageBitmap, format: ReplayPhotoFormat, chromaArgb: Long): String?

    /** A new pending gallery image named [displayName], or null. */
    suspend fun newPendingImage(displayName: String, mime: String): StudioPendingFile?

    /** "EUC_20261115_010708": the name a saved clip or photo starts with. */
    fun timestampedName(): String

    /** [frame] scaled to [width] x [height] as ARGB pixels, for the GIF / APNG encoders. */
    fun framePixels(frame: ImageBitmap, width: Int, height: Int): IntArray?

    /** A small copy of [frame] for the export screen's preview. */
    fun thumbnail(frame: ImageBitmap): ImageBitmap?

    /** True when the offscreen renderer works on this phone (not on the Android emulator). */
    val offscreenSupported: Boolean

    /** An offscreen renderer for a replay export, or null to render on screen. */
    suspend fun createOffscreen(
        widthPx: Int,
        heightPx: Int,
        rotation: Int,
        themeColors: AppThemeColors,
        produce: (Int) -> OverlayFrameSpec,
    ): StudioOffscreen?

    /** Opens the phone's gallery. */
    fun openGallery()

    /** Opens one saved photo or video in the gallery. */
    fun openInGallery(uri: String, mime: String)
}

/** The host the studio screen was given, for the viewports and sheets it draws. */
val LocalStudioHost = staticCompositionLocalOf<StudioHost> {
    error("No StudioHost: the studio screen provides one")
}
