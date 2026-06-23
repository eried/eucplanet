package com.eried.eucplanet.ui.studio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Camera video-recording seam for the Overlay Studio — the shared half of
 * Android's studio recorder. The iOS Swift `StudioCameraRecorder` sets
 * [nativeStart] / [nativeStop] at launch (presents a full-screen camera capture
 * that burns the overlay into an MP4 and saves it to Photos), and pulls the live
 * overlay [drawList] for each captured frame. Android leaves the hooks null.
 */
object StudioRecorder {
    var nativeStart: (() -> Unit)? = null
    var nativeStop: (() -> Unit)? = null

    /** Set by the app: produces the current overlay draw-list at the given video size. */
    var drawListProvider: ((canvasW: Int, canvasH: Int) -> String)? = null

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    /** Begin a camera recording with the overlay burned in. */
    fun start() { nativeStart?.invoke() }

    /** Stop + finalize the recording (saved to the photo library). */
    fun stop() { nativeStop?.invoke() }

    /** Called by the platform recorder; mirrors capture state for the UI. */
    fun setRecording(value: Boolean) { _recording.value = value }

    /** Called by the platform recorder on its capture thread, per frame. */
    fun drawList(canvasW: Int, canvasH: Int): String =
        drawListProvider?.invoke(canvasW, canvasH) ?: "[]"
}
