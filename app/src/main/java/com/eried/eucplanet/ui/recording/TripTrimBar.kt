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
 * whole strip grabs the nearer handle and the gesture is simpler.
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
    onReset: () -> Unit,
    onEditExact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dur = durationMs.coerceAtLeast(1L)
    // Captured in composable scope: a Canvas DrawScope cannot read the theme.
    val accent = MaterialTheme.appColors.primary
    val track = MaterialTheme.appColors.gaugeTrack
    val handle = MaterialTheme.appColors.textPrimary

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)) {
            var wPx by remember {
                mutableStateOf(constraints.maxWidth.toFloat().coerceAtLeast(1f))
            }
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
                if (abs(x - xOf(curStart)) <= abs(x - xOf(curEnd))) 0 else 1

            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { wPx = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(dur) {
                        detectTapGestures { o -> apply(nearer(o.x), o.x) }
                    }
                    .pointerInput(dur) {
                        detectDragGestures(
                            onDragStart = { o ->
                                dragHandle = nearer(o.x)
                                apply(dragHandle, o.x)
                            },
                            onDragEnd = { dragHandle = -1 },
                            onDragCancel = { dragHandle = -1 },
                        ) { change, _ ->
                            change.consume()
                            apply(dragHandle, change.position.x)
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
            // The span itself is the button: tapping the numbers to type exact
            // ones is where a rider looks first.
            Text(
                "${formatReplayClock(startMs)} - ${formatReplayClock(endMs)}",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEditExact)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.appColors.textPrimary,
            )
            Text(
                formatReplayClock(dur),
                modifier = Modifier.padding(end = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.appColors.textSecondary,
            )
        }
    }
}
