package com.eried.eucplanet.ui.studio.recording

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.eried.eucplanet.hud.protocol.OverlayElement
import com.eried.eucplanet.hud.protocol.OverlayPreset
import com.eried.eucplanet.ui.studio.LocalStudioRotation
import com.eried.eucplanet.ui.studio.StudioElementData
import com.eried.eucplanet.ui.studio.StudioElementLayer
import com.eried.eucplanet.ui.studio.StudioViewportLayer
import com.eried.eucplanet.ui.studio.camera.StudioCameraHub
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

/**
 * The elements + telemetry for one replay frame, plus what the viewport
 * (pane) layer needs to draw underneath: the layout/dividers/pane configs
 * (via [preset]) and, for any pane whose replay face is VIDEO, the frame
 * already decoded for this export frame's cursor position. [videoFrames] is
 * keyed by pane index; a missing key or a `null` value both read as "draw
 * nothing for this pane's video face" (out of the clip's covered span, no
 * clip bound, or not a video face) -- resolved on the producing side so the
 * offscreen frame clock never calls into a [com.eried.eucplanet.ui.studio.camera.StudioVideoHub]
 * retriever itself.
 */
data class OverlayFrameSpec(
    val elements: List<OverlayElement>,
    val data: StudioElementData,
    val preset: OverlayPreset,
    val videoFrames: Map<Int, ImageBitmap?> = emptyMap(),
)

/**
 * Renders the replay overlay off the display's vsync clock.
 *
 * The on-screen replay export advances `replayPosMs`, waits two display frames
 * for Compose to recompose + draw, then reads back the live GraphicsLayer -- so
 * it is gated to ~2 vsyncs per frame (33 ms at 60 Hz). This hosts the same
 * [StudioViewportLayer] (in replay mode) under a [StudioElementLayer] in a
 * detached [ComposeView] driven by a [BroadcastFrameClock] we tick ourselves,
 * so each frame composes + draws as fast as the CPU/GPU allow, with no vsync
 * wait.
 *
 * The viewport layer draws each pane's replay face (transparent/solid/
 * gradient/image/video) exactly as the on-screen replay does, underneath the
 * elements -- a transparent face leaves that pane's pixels untouched (alpha
 * carries through to PNG/GIF/APNG), a solid/gradient/image/video face fills
 * it. Video frames are never decoded here: [OverlayFrameSpec.videoFrames] is
 * pre-resolved by the caller for this frame's cursor position, so the frame
 * clock only ever draws an already-decoded bitmap.
 *
 * Everything runs on the main thread (Compose requires it); the caller should
 * hand each returned bitmap to the encoder on a background dispatcher.
 */
class StudioOffscreenSession private constructor(
    private val container: ViewGroup,
    private val composeView: ComposeView,
    private val recomposer: Recomposer,
    private val frameClock: BroadcastFrameClock,
    private val recomposeJob: Job,
    private val scope: CoroutineScope,
    private val state: MutableState<OverlayFrameSpec>,
    private val widthPx: Int,
    private val heightPx: Int,
    private val produce: (Int) -> OverlayFrameSpec,
) {
    private val wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
    private val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)

    /** Render frame [index] to a fresh software bitmap (ARGB_8888, with alpha). */
    suspend fun frame(index: Int): Bitmap = withContext(Dispatchers.Main.immediate) {
        state.value = produce(index)
        Snapshot.sendApplyNotifications()
        frameClock.sendFrame(index.toLong() * FRAME_NANOS)
        settle()
        composeView.measure(wSpec, hSpec)
        composeView.layout(0, 0, widthPx, heightPx)
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        composeView.draw(Canvas(bmp))
        bmp
    }

    /** Tick the clock until the recomposer has applied the pending change. */
    private suspend fun settle() {
        yield()
        var guard = 0
        while (recomposer.hasPendingWork && guard++ < SETTLE_GUARD) yield()
    }

    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        // Dispose the composition while the recomposer is alive, then detach, then
        // stop the recomposer.
        runCatching { composeView.disposeComposition() }
        runCatching { container.removeView(composeView) }
        runCatching { recomposeJob.cancel() }
        runCatching { recomposer.cancel() }
        runCatching { scope.cancel() }
    }

    companion object {
        private const val FRAME_NANOS = 16_666_666L
        private const val SETTLE_GUARD = 2_000

        /**
         * Build a session sized [widthPx] x [heightPx] (the studio's native
         * capture size). Returns null if the host can't support an offscreen
         * composition; the caller should then fall back to the on-screen path.
         */
        suspend fun create(
            activity: ComponentActivity,
            widthPx: Int,
            heightPx: Int,
            rotation: Int,
            themeColors: AppThemeColors,
            produce: (Int) -> OverlayFrameSpec,
        ): StudioOffscreenSession? = withContext(Dispatchers.Main.immediate) {
            val container = activity.findViewById<ViewGroup>(android.R.id.content)
                ?: return@withContext null
            val frameClock = BroadcastFrameClock()
            val scope = CoroutineScope(coroutineContext + SupervisorJob() + frameClock)
            val recomposer = Recomposer(scope.coroutineContext)
            val state = mutableStateOf(produce(0))
            // Never opened (no camera keys requested here), only exists to
            // satisfy StudioViewportLayer's required `hub` param -- replay
            // mode never reads it, panes draw their replay face instead.
            val unusedCameraHub = StudioCameraHub()
            val composeView = ComposeView(activity).apply {
                // INVISIBLE, not alpha 0: an alpha-0 view is still drawn by the
                // window's hardware RenderThread, which races our manual draw and
                // crashes (SIGSEGV). INVISIBLE makes the parent skip drawing it
                // entirely, while it stays attached so Compose has a ViewRootImpl
                // and composes/measures. We draw it by hand into a software bitmap
                // canvas (so no hardware RenderNode is involved either way).
                visibility = View.INVISIBLE
                setViewTreeLifecycleOwner(activity)
                setViewTreeViewModelStoreOwner(activity)
                setViewTreeSavedStateRegistryOwner(activity)
                // Compose against OUR recomposer (our frame clock), not the
                // window's vsync-driven one.
                setParentCompositionContext(recomposer)
                setContent {
                    EucPlanetTheme(colors = themeColors) {
                        CompositionLocalProvider(LocalStudioRotation provides rotation) {
                            val spec = state.value
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                // Replay faces (video/image/solid/gradient/
                                // transparent) under the elements, matching
                                // the on-screen replay's layer order exactly.
                                StudioViewportLayer(
                                    preset = spec.preset,
                                    hub = unusedCameraHub,
                                    hasCameraPermission = false,
                                    editable = false,
                                    replayMode = true,
                                    prebuiltVideoFrame = { spec.videoFrames[it] },
                                    onDividerChange = {},
                                    onConfigViewport = {},
                                    onConfigDivider = {},
                                    onTapEmpty = {},
                                    onDoubleTapEmpty = {},
                                    onLongPressEmpty = {},
                                )
                                StudioElementLayer(
                                    elements = spec.elements,
                                    data = spec.data,
                                    editable = false,
                                    selectedId = null,
                                    replayMode = true,
                                    snapToGrid = false,
                                    onSelect = {},
                                    onConfigure = {},
                                    onDelete = {},
                                    onChange = {},
                                )
                            }
                        }
                    }
                }
            }
            container.addView(composeView, ViewGroup.LayoutParams(widthPx, heightPx))
            val recomposeJob = scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val session = StudioOffscreenSession(
                container, composeView, recomposer, frameClock,
                recomposeJob, scope, state, widthPx, heightPx, produce,
            )
            // Prime: drive the very first composition before the first frame()
            // so initial layout is ready.
            session.settle()
            session.frameClock.sendFrame(0L)
            session.settle()
            session
        }
    }
}
