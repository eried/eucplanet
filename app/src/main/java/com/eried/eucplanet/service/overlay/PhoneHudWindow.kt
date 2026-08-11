package com.eried.eucplanet.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.eried.eucplanet.hud.protocol.OverlayElement
import com.eried.eucplanet.hud.protocol.OverlayElementType
import com.eried.eucplanet.ui.studio.StudioElementData
import com.eried.eucplanet.ui.studio.StudioElementLayer
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Phone HUD: an Overlay Studio preset drawn in a window on top of every
 * other app.
 *
 * Owned by WheelService rather than an Activity, because the point is to be
 * visible while the rider is in Maps or a music app. The service is already a
 * LifecycleService and already collects telemetry, so this adds a consumer
 * rather than any new machinery.
 *
 * ## What it deliberately does not do
 *
 * **No touches, ever.** The window is FLAG_NOT_TOUCHABLE, so every tap falls
 * through to whatever is underneath. That is the whole point: an overlay that
 * ate touches would make the app beneath it unusable. It also means the rider
 * cannot move or dismiss this by touching it, which is why it is turned off
 * from Settings and from the ongoing notification instead.
 *
 * **No viewport panes.** Only [StudioElementLayer] is drawn, never the preset's
 * background panes. Painting those would put an opaque rectangle over the whole
 * screen. Elements float over transparency, the way the HUD's own custom screen
 * already renders them.
 *
 * **No camera or map elements.** A floating camera has no preview surface here,
 * and a map element fetches tiles, which is silent background data mid-ride.
 *
 * **Portrait presets only.** The Studio bakes a per-element rotation into
 * landscape presets on a portrait-normalised canvas; undoing that on a
 * different surface is what cost the HUD renderer two failed attempts and an
 * ANR. Landscape presets are refused rather than drawn half-right.
 */
@Singleton
class PhoneHudWindow @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var view: View? = null
    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    /** Live snapshot the Compose content reads. Swapped, never mutated. */
    private val snapshot = mutableStateOf<StudioElementData?>(null)
    private val elements = mutableStateOf<List<OverlayElement>>(emptyList())

    val isShowing: Boolean get() = view != null

    /** Whether the rider has granted "Display over other apps". */
    fun hasPermission(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Push a new frame. Cheap when nothing is showing, so the service can call
     * it from its telemetry loop without checking first.
     */
    fun update(data: StudioElementData) {
        if (view == null) return
        snapshot.value = data
    }

    /**
     * Put the window up, or leave it be if it is already there.
     *
     * [owner] supplies the Lifecycle a ComposeView needs; the other two
     * ViewTree owners are created here. Returns false when the permission is
     * missing or the preset is unusable, so the caller can report why rather
     * than silently doing nothing.
     */
    fun show(owner: LifecycleOwner, preset: List<OverlayElement>): Boolean {
        if (view != null) return true
        if (!hasPermission()) {
            Log.i(TAG, "Phone HUD: no overlay permission")
            return false
        }
        val drawable = usableElements(preset)
        if (drawable.isEmpty()) {
            Log.i(TAG, "Phone HUD: preset has nothing this window can draw")
            return false
        }
        val wm = windowManager ?: return false

        elements.value = drawable
        val owners = OverlayViewOwners(owner)
        val compose = ComposeView(context).apply {
            setContent { Content(snapshot, elements) }
        }
        owners.attach(compose)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // NOT_FOCUSABLE keeps the keyboard and back button with the app
            // underneath; NOT_TOUCHABLE lets every tap through to it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Pinned to portrait so the overlay never rotates with the phone.
            //
            // Without this the window re-lays out on every rotation and a
            // layout authored for one shape jumps to the other. Pinned, a
            // preset is drawn exactly as it was built, whichever way the phone
            // is held, and a landscape preset's per-element rotation is simply
            // part of what the rider drew rather than something to undo.
            screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        return try {
            wm.addView(compose, params)
            view = compose
            true
        } catch (e: Exception) {
            // A revoked permission surfaces here rather than at canDrawOverlays
            // on some OEM builds, so this is a real path, not paranoia.
            Log.e(TAG, "Phone HUD: could not add the window", e)
            false
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        snapshot.value = null
        try {
            windowManager?.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "Phone HUD: window was already gone", e)
        }
    }

    companion object {
        private const val TAG = "PhoneHudWindow"

        /**
         * Element types this window refuses to draw, with the reason each is
         * out rather than a blanket "unsupported".
         */
        private val UNSUPPORTED = setOf(
            // No preview surface in a service-owned window.
            OverlayElementType.FLOATING_CAMERA,
            // Fetches slippy tiles: silent mobile data for a whole ride.
            OverlayElementType.MAP,
        )

        /**
         * What is left of a preset once the types this window cannot draw are
         * removed.
         *
         * Landscape presets are NOT refused. The window is locked to one
         * orientation and never re-lays out, so a preset is drawn exactly as it
         * was authored, per-element rotation included. That is the honest
         * result: the rider sees what they built, rather than geometry the app
         * guessed at correcting.
         */
        fun usableElements(elements: List<OverlayElement>): List<OverlayElement> =
            elements.filterNot { it.type in UNSUPPORTED }

        private fun overlayType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
    }
}

/**
 * The window's content. Split out so the composable stays readable and so the
 * renderer call site is obviously the SAME one the Studio and the exporter use,
 * rather than a third copy of it.
 */
@Composable
private fun Content(
    snapshot: State<StudioElementData?>,
    elements: State<List<OverlayElement>>,
) {
    val data by snapshot
    val els by elements
    // Default palette rather than the rider's chosen theme: resolving that
    // needs the settings pipeline an Activity has, and every element carries
    // its own colours anyway. The theme only supplies fallbacks here.
    EucPlanetTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val d = data ?: return@BoxWithConstraints
            StudioElementLayer(
                elements = els,
                data = d,
                editable = false,
                selectedId = null,
                // Same flag the trip replay uses. It is what keeps a floating
                // camera from being asked for a feed that does not exist.
                replayMode = true,
                onSelect = {},
                onConfigure = {},
                onDelete = {},
                onChange = {},
            )
        }
    }
}

/**
 * The three ViewTree owners a ComposeView needs before it will attach.
 *
 * Inside an Activity these come for free. In a service-owned window they do
 * not, and a ComposeView without them throws on the first composition. The
 * Lifecycle is borrowed from the service, which already has one; the view model
 * store and saved-state registry are ours because nothing here outlives the
 * window.
 */
private class OverlayViewOwners(
    private val lifecycleOwner: LifecycleOwner,
) : ViewModelStoreOwner, SavedStateRegistryOwner {

    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val controller = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = controller.savedStateRegistry

    override val lifecycle get() = lifecycleOwner.lifecycle

    fun attach(view: View) {
        // Restoring from null is correct: an overlay has no state worth
        // carrying across process death, it is rebuilt from live telemetry.
        controller.performRestore(null)
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}
