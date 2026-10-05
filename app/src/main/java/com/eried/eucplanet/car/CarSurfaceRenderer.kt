package com.eried.eucplanet.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Draws a Compose tree onto the car screen.
 *
 * Android Auto hands a navigation app a bare Surface. The usual way to put
 * real views on it, and the one map SDKs use for their car extensions, is a
 * private virtual display on that surface with a Presentation showing the
 * views. That lets the car screen reuse the same WebView map and the same
 * Compose stat boxes, colored from the same theme tokens, as the phone.
 *
 * [visibleArea] is the part Android Auto leaves uncovered by its own chrome
 * (action strip, turn card); the content keeps its stats inside it.
 */
class CarSurfaceRenderer(
    private val carContext: CarContext,
    private val content: @Composable (visibleArea: Rect?) -> Unit,
) : SurfaceCallback {

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var owner: ComposeOwner? = null
    private val visibleArea = mutableStateOf<Rect?>(null)

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "EucPlanetCar", container.width, container.height, container.dpi,
            surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        display = vd
        val o = ComposeOwner().also { it.start() }
        owner = o
        presentation = Presentation(carContext, vd.display).apply {
            val view = ComposeView(context).apply {
                setContent { content(visibleArea.value) }
            }
            window?.decorView?.let {
                it.setViewTreeLifecycleOwner(o)
                it.setViewTreeSavedStateRegistryOwner(o)
            }
            setContentView(view)
            try {
                show()
            } catch (t: Throwable) {
                Log.e(TAG, "Car presentation refused", t)
            }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea.value = visibleArea
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    fun release() {
        runCatching { presentation?.dismiss() }
        presentation = null
        owner?.stop()
        owner = null
        display?.release()
        display = null
    }

    /** Compose outside an Activity needs these two owners on the window. */
    private class ComposeOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val TAG = "CarSurface"
    }
}
