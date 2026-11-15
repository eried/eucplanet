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
 * keyed by pane index; a missing key or a `null` value both read as "no clip
 * frame for this pane" (out of the clip's covered span, no clip bound, or not
 * a video face), and [videoFills] then says whether that pane is left empty,
 * filled black or given the No-media placeholder -- resolved on the producing side so the
 * offscreen frame clock never calls into a [com.eried.eucplanet.ui.studio.camera.StudioVideoHub]
 * retriever itself.
 */
data class OverlayFrameSpec(
    val elements: List<OverlayElement>,
    val data: StudioElementData,
    val preset: OverlayPreset,
    val videoFrames: Map<Int, ImageBitmap?> = emptyMap(),
    /**
     * For each pane with a VIDEO face, what it shows on this frame: the clip
     * frame, black (outside the clip with the Black edge) or the No-media
     * placeholder (no usable clip). Keyed by pane index.
     */
    val videoFills: Map<Int, com.eried.eucplanet.ui.studio.ReplayVideoFill> = emptyMap(),
)
