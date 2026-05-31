package com.eried.eucplanet.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.data.model.RadarFrame
import com.eried.eucplanet.data.model.ThreatLevel
import com.eried.eucplanet.ui.radar.RadarOverlayViewModel
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentOrange
import com.eried.eucplanet.ui.theme.AccentRed

/**
 * Compact radar lane shown inside the dashboard's dial Box. Replaces the
 * earlier full-screen edge bar so the rider's peripheral attention isn't
 * pulled across every screen ,  the overlay only matters while looking at
 * the speedo anyway, and the dashboard already has natural gutters either
 * side of the dial where a thin lane fits without competing with anything.
 *
 * Mount this inside the same [androidx.compose.foundation.layout.BoxWithConstraints]
 * that holds the [SpeedGauge] + corner glyph clusters. The mini sits at
 * CenterStart (below the P/D column) and/or CenterEnd (above the Studio
 * camera glyph). The visibility, side preference and frame data come from
 * the shared [RadarOverlayViewModel] so this composable carries no state.
 *
 * Side values: LEFT / RIGHT renders one mini; BOTH renders mirrored copies
 * on both sides.
 */
private const val LANE_RANGE_M = 140  // matches Garmin Varia's advertised reach
private const val LANE_WIDTH_DP = 22
private const val LANE_HEIGHT_DP = 110
private const val DOT_RADIUS_DP = 6

/**
 * The dashboard mounts this twice ,  once at CenterStart with
 * `targetSide = "LEFT"` and once at CenterEnd with `targetSide = "RIGHT"`.
 * Each instance opts in to render when its side matches the user's
 * preference, or when "BOTH" is selected. Hiding via [AnimatedVisibility]
 * (rather than skipping the composition) lets the bar fade in/out cleanly
 * as threats appear and the lane clears.
 */
@Composable
fun DashboardRadarMiniForSide(
    targetSide: String,
    modifier: Modifier = Modifier,
    viewModel: RadarOverlayViewModel = hiltViewModel()
) {
    val show by viewModel.shouldShow.collectAsState()
    val side by viewModel.side.collectAsState()
    val frame by viewModel.frame.collectAsState()

    val visible = show && (side == targetSide || side == "BOTH")
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(180))
    ) {
        MiniLaneBar(frame = frame)
    }
}

@Composable
private fun MiniLaneBar(frame: RadarFrame?) {
    val textMeasurer = rememberTextMeasurer()
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .width(LANE_WIDTH_DP.dp)
            .height(LANE_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x66000000))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val riderY = h - DOT_RADIUS_DP.dp.toPx() - 3.dp.toPx()
            val topY = 8.dp.toPx()
            val laneTop = topY
            val laneBottom = riderY
            val laneHeight = laneBottom - laneTop

            // Centre guideline so the threats look like they're tracking
            // down a road. Subtle so the dots dominate the eye path.
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(w / 2f, laneTop),
                end = Offset(w / 2f, laneBottom),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Rider marker at the bottom, two-tone so it doesn't look like
            // a threat dot. Inner dark circle gives the donut shape.
            drawCircle(
                color = Color(0xFFE0E0E0),
                radius = DOT_RADIUS_DP.dp.toPx(),
                center = Offset(w / 2f, riderY)
            )
            drawCircle(
                color = Color(0xFF424242),
                radius = (DOT_RADIUS_DP - 3).dp.toPx(),
                center = Offset(w / 2f, riderY)
            )

            val threats = frame?.threats ?: emptyList()
            threats.forEach { t ->
                val clamped = t.distanceM.coerceAtMost(LANE_RANGE_M).coerceAtLeast(0)
                val ratio = clamped.toFloat() / LANE_RANGE_M.toFloat()
                val y = laneBottom - ratio * laneHeight
                val color = when (t.threatLevel) {
                    ThreatLevel.FAST_APPROACH -> AccentRed
                    ThreatLevel.APPROACHING -> AccentOrange
                    ThreatLevel.NONE -> AccentGreen
                }
                if (t.threatLevel == ThreatLevel.FAST_APPROACH) {
                    // Soft glow so a closing car catches the rider's eye
                    // even at the edge of their attention.
                    drawCircle(
                        color = color.copy(alpha = 0.35f),
                        radius = (DOT_RADIUS_DP + 4).dp.toPx(),
                        center = Offset(w / 2f, y)
                    )
                }
                drawCircle(
                    color = color,
                    radius = DOT_RADIUS_DP.dp.toPx(),
                    center = Offset(w / 2f, y)
                )
            }

            // Distance numeral on the closest threat only, drawn LAST so
            // it sits above the dot rather than under it.
            val closest = threats.minByOrNull { it.distanceM }
            if (closest != null) {
                val clamped = closest.distanceM.coerceAtMost(LANE_RANGE_M).coerceAtLeast(0)
                val ratio = clamped.toFloat() / LANE_RANGE_M.toFloat()
                val y = laneBottom - ratio * laneHeight
                val layout = textMeasurer.measure(
                    text = "${closest.distanceM}",
                    style = TextStyle(color = Color.White, fontSize = 8.sp)
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = w / 2f - layout.size.width / 2f,
                        y = y - layout.size.height / 2f
                    )
                )
            }

            // Battery tick across the top, same colour rules as the lane
            // bar's threat dots so the rider builds one mental palette.
            frame?.batteryPercent?.let { pct ->
                val batteryColor = when {
                    pct >= 50 -> AccentGreen
                    pct >= 20 -> AccentOrange
                    else -> AccentRed
                }
                drawLine(
                    color = batteryColor,
                    start = Offset(w * 0.2f, 3.dp.toPx()),
                    end = Offset(w * 0.2f + (w * 0.6f * pct / 100f), 3.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
