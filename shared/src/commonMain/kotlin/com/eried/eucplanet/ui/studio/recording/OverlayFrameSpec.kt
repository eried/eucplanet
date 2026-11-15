package com.eried.eucplanet.ui.studio.recording

import androidx.compose.ui.graphics.ImageBitmap
import com.eried.eucplanet.hud.protocol.OverlayElement
import com.eried.eucplanet.hud.protocol.OverlayPreset
import com.eried.eucplanet.ui.studio.StudioElementData

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
