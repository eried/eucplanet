package com.eried.eucplanet.ui.recording

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.studio.formatReplayClock
import com.eried.eucplanet.ui.studio.handlePentagon
import com.eried.eucplanet.ui.theme.appColors
import kotlin.math.abs

/** Matches the half width [handlePentagon] draws, so the track can inset by it. */
private const val HANDLE_HALF_WIDTH_PX = 16f

/**
 * How close a touch must be to a handle for the handle to win over a pan.
 *
 * Wider than the handle is drawn, because a finger is wider than a pentagon.
 * On a span narrower than twice this, the handles simply own the whole
 * selection, which is the right outcome: there is nothing meaningful to pan.
 *
 * In dp, not px: the old raw 30 px was barely 11 dp on a modern phone, which
 * is why the handles felt ungrabbable at the sides. 24 dp each way makes the
 * platform-minimum 48 dp touch target.
 */
private val HANDLE_GRAB = 24.dp

private const val DRAG_START = 0
private const val DRAG_END = 1
private const val DRAG_PAN = 2

/**
 * The trim strip the funnel toggles open above Trip Details.
 *
 * Deliberately the same object as Overlay Studio's replay trimmer: one track,
 * the selected span picked out in the accent colour, and two pointed handles
 * resting on it. A rider who has trimmed a replay already knows how to use
 * this, and [handlePentagon] is shared rather than redrawn so the two cannot
 * drift apart.
 *
 * The Studio version also carries a playhead, which is why its handles live in
 * the top half and a drag lower down scrubs. There is no playhead here, so the
 * whole strip is free for gestures: a drag near either handle moves that end,
 * and a drag on the selection between them slides the whole window without
 * resizing it, for "same length, later in the ride".
 *
 * That middle drag is deliberately NOT offered in the Studio. There a drag
 * across the middle already scrubs the playhead, and one gesture cannot mean
 * both. Here there is no playhead to confuse it with.
 *
 * Below the track: reset on the left, the live span in the middle (tap to type
 * exact times), and the ride's full length on the right as a quiet reference,
 * so "how much of the ride am I looking at" is answerable at a glance.
 */
@Composable
fun TripTrimBar(
    /** Whole ride length, in millis. */
    durationMs: Long,
    /** Current selection. Equal to 0..durationMs when nothing is trimmed. */
    startMs: Long,
    endMs: Long,
    onRange: (Long, Long) -> Unit,
    /** Fired when a drag or tap gesture finishes, so the caller can run the
     *  heavy recompute once instead of per frame. */
    onRangeEnd: () -> Unit = {},
    onReset: () -> Unit,
    onEditExact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dur = durationMs.coerceAtLeast(1L)
    // Captured in composable scope: a Canvas DrawScope cannot read the theme.
    val accent = MaterialTheme.appColors.primary
    val track = MaterialTheme.appColors.gaugeTrack
    val handle = MaterialTheme.appColors.textPrimary

    // No horizontal padding of its own: it now sits inside the trip content,
    // which is already inset, and padding twice would leave it narrower than
    // the tiles and charts it controls.
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
            var wPx by remember {
                mutableStateOf(constraints.maxWidth.toFloat().coerceAtLeast(1f))
            }
            val grabPx = with(LocalDensity.current) { HANDLE_GRAB.toPx() }
            // The track is inset by the handle's half width. Without it a handle
            // parked at either end is drawn centred on the edge and loses half
            // of itself off-screen, which reads as a rendering fault rather than
            // "fully open".
            val hw = HANDLE_HALF_WIDTH_PX
            fun xOf(ms: Long): Float =
                (hw + (ms.toFloat() / dur) * (wPx - 2 * hw)).coerceIn(hw, wPx - hw)
            fun msOf(x: Float): Long =
                (((x - hw) / (wPx - 2 * hw)) * dur).toLong().coerceIn(0L, dur)

            // Kept fresh WITHOUT re-keying the gesture: re-keying on the range
            // restarts the drag every update, which drops the grabbed handle
            // after a single frame. Same trap the Studio timeline documents.
            val curStart by rememberUpdatedState(startMs)
            val curEnd by rememberUpdatedState(endMs)
            var dragHandle by remember { mutableStateOf(-1) }

            fun apply(which: Int, x: Float) {
                if (which == 0) {
                    // Never let the ends cross, and keep at least a sliver of
                    // ride selected so the charts always have something to draw.
                    onRange(msOf(x).coerceIn(0L, curEnd - 1L), curEnd)
                } else {
                    onRange(curStart, msOf(x).coerceIn(curStart + 1L, dur))
                }
            }

            fun nearer(x: Float): Int =
                if (abs(x - xOf(curStart)) <= abs(x - xOf(curEnd))) DRAG_START else DRAG_END

            // Panning moves the window without resizing it, so it is measured
            // from where the drag began rather than accumulated per frame. An
            // accumulating delta loses movement to the end clamps: drag past the
            // end and back, and the window would trail the finger by however
            // much was clamped away.
            var panAnchorX by remember { mutableStateOf(0f) }
            var panStart by remember { mutableStateOf(0L) }
            var panWidth by remember { mutableStateOf(0L) }

            /**
             * Which gesture a touch at [x] begins.
             *
             * Handles take priority over the span they bound: a touch near
             * either end grabs that handle even though it is also inside the
             * selection, so the edges stay adjustable no matter how far the
             * window has been panned. Only the middle pans.
             */
            fun modeAt(x: Float): Int {
                val sx = xOf(curStart)
                val ex = xOf(curEnd)
                if (abs(x - sx) <= grabPx || abs(x - ex) <= grabPx) {
                    return nearer(x)
                }
                return if (x > sx && x < ex) DRAG_PAN else nearer(x)
            }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { wPx = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(dur) {
                        detectTapGestures { o ->
                            apply(nearer(o.x), o.x)
                            onRangeEnd()
                        }
                    }
                    .pointerInput(dur) {
                        detectDragGestures(
                            onDragStart = { o ->
                                dragHandle = modeAt(o.x)
                                if (dragHandle == DRAG_PAN) {
                                    // Grab where the finger landed. Snapping the
                                    // window to centre on the touch would jump
                                    // the selection before the drag even moves.
                                    panAnchorX = o.x
                                    panStart = curStart
                                    panWidth = curEnd - curStart
                                } else {
                                    apply(dragHandle, o.x)
                                }
                            },
                            onDragEnd = {
                                dragHandle = -1
                                onRangeEnd()
                            },
                            onDragCancel = {
                                dragHandle = -1
                                onRangeEnd()
                            },
                        ) { change, _ ->
                            change.consume()
                            if (dragHandle == DRAG_PAN) {
                                val span = (wPx - 2 * hw).coerceAtLeast(1f)
                                val movedMs =
                                    ((change.position.x - panAnchorX) / span * dur).toLong()
                                // Clamp the START and derive the end, so the
                                // window keeps its width at both extremes rather
                                // than being squashed against them.
                                val newStart = (panStart + movedMs)
                                    .coerceIn(0L, (dur - panWidth).coerceAtLeast(0L))
                                onRange(newStart, newStart + panWidth)
                            } else {
                                apply(dragHandle, change.position.x)
                            }
                        }
                    }
            ) {
                val trackY = size.height * 0.5f
                val sx = xOf(startMs)
                val ex = xOf(endMs)
                drawLine(
                    track, Offset(hw, trackY), Offset(wPx - hw, trackY),
                    strokeWidth = 5f, cap = StrokeCap.Round,
                )
                drawLine(
                    accent.copy(alpha = 0.55f), Offset(sx, trackY), Offset(ex, trackY),
                    strokeWidth = 8f, cap = StrokeCap.Round,
                )
                listOf(sx, ex).forEach { hx ->
                    drawPath(handlePentagon(hx, trackY, down = true), handle)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onReset, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.studio_replay_trim_reset))
            }
            // The span is the button that opens the exact-times editor, styled
            // exactly as Overlay Studio styles its own: parenthesised and in the
            // accent colour, which is what makes it read as tappable rather than
            // as a plain readout. Untrimmed it says "(full trip)", same as there.
            val trimmed = startMs > 0L || endMs < dur
            Text(
                if (trimmed)
                    "( ${formatReplayClock(startMs)}  -  ${formatReplayClock(endMs)} )"
                else stringResource(R.string.studio_replay_trim),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onEditExact)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.primary,
            )
            Text(
                formatReplayClock(dur),
                modifier = Modifier.padding(end = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary,
            )
        }
    }
}
