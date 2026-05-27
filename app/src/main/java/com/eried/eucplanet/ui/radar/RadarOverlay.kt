package com.eried.eucplanet.ui.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.data.model.RadarFrame
import com.eried.eucplanet.data.model.ThreatLevel
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentOrange
import com.eried.eucplanet.ui.theme.AccentRed

/**
 * The floating radar threat overlay. Rendered above the nav graph in
 * [com.eried.eucplanet.MainActivity], so it hovers over every screen of the
 * app while a radar is paired + connected.
 *
 * Visual model: a thin translucent "lane" anchored to one screen edge. The
 * rider sits at the bottom of the lane (one EUC-shaped silhouette) and each
 * detected vehicle is a dot positioned by its distance ,  the further away,
 * the higher up the lane it sits, capped at [LANE_RANGE_M]. Dot colour
 * follows [ThreatLevel] (green = none, amber = approaching, red = closing
 * fast). When the lane is clear the entire overlay fades out after
 * [CLEAR_DECAY_MS] so the rider isn't staring at empty UI.
 *
 * Why not Compose Material chips: this surface has to be readable in
 * sunlight, while moving, at a glance ,  a single coloured dot is easier to
 * interpret than a "2 cars · 35 m" pill while concentrating on the road.
 */
private const val LANE_RANGE_M = 140  // matches Garmin Varia's advertised reach
private const val LANE_WIDTH_DP = 26
private const val DOT_RADIUS_DP = 9
private const val CLEAR_DECAY_MS = 3_000L

@Composable
fun RadarOverlay(
    viewModel: RadarOverlayViewModel = hiltViewModel()
) {
    val show by viewModel.shouldShow.collectAsState()
    val side by viewModel.side.collectAsState()
    val frame by viewModel.frame.collectAsState()

    val alignment = if (side == "LEFT") Alignment.CenterStart else Alignment.CenterEnd
    val slideFrom = if (side == "LEFT") -1 else 1

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = show,
            modifier = Modifier
                .align(alignment)
                .statusBarsPadding(),
            enter = fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it * slideFrom },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(220)) { it * slideFrom }
        ) {
            LaneBar(frame = frame)
        }
    }
}

@Composable
private fun LaneBar(frame: RadarFrame?) {
    val textMeasurer = rememberTextMeasurer()
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .width(LANE_WIDTH_DP.dp)
            .fillMaxWidth(0.07f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x66000000))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val riderY = h - DOT_RADIUS_DP.dp.toPx() - 4.dp.toPx()
            val topY = 12.dp.toPx()
            val laneTop = topY
            val laneBottom = riderY
            val laneHeight = laneBottom - laneTop

            // Lane centre line (subtle so dots stand out).
            drawLine(
                color = Color(0x44FFFFFF),
                start = Offset(w / 2f, laneTop),
                end = Offset(w / 2f, laneBottom),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Rider marker ,  small circle representing the EUC + rider; we
            // intentionally don't try to draw a unicycle silhouette in a 24 dp
            // lane (won't read at speed). The rider is always at the bottom.
            drawCircle(
                color = Color(0xFFE0E0E0),
                radius = DOT_RADIUS_DP.dp.toPx(),
                center = Offset(w / 2f, riderY)
            )
            // Inner accent so the rider dot is distinguishable from threat dots.
            drawCircle(
                color = Color(0xFF424242),
                radius = (DOT_RADIUS_DP - 4).dp.toPx(),
                center = Offset(w / 2f, riderY)
            )

            val threats = frame?.threats ?: emptyList()
            threats.forEach { t ->
                // Distance maps linearly to lane Y: 0 m at riderY, LANE_RANGE_M at laneTop.
                val clamped = t.distanceM.coerceAtMost(LANE_RANGE_M).coerceAtLeast(0)
                val ratio = clamped.toFloat() / LANE_RANGE_M.toFloat()
                val y = laneBottom - ratio * laneHeight
                val color = when (t.threatLevel) {
                    ThreatLevel.FAST_APPROACH -> AccentRed
                    ThreatLevel.APPROACHING -> AccentOrange
                    ThreatLevel.NONE -> AccentGreen
                }
                // Soft outer glow for fast-approach threats so the eye is
                // drawn to imminent hazards even in peripheral vision.
                if (t.threatLevel == ThreatLevel.FAST_APPROACH) {
                    drawCircle(
                        color = color.copy(alpha = 0.35f),
                        radius = (DOT_RADIUS_DP + 6).dp.toPx(),
                        center = Offset(w / 2f, y)
                    )
                }
                drawCircle(
                    color = color,
                    radius = DOT_RADIUS_DP.dp.toPx(),
                    center = Offset(w / 2f, y)
                )
                // Inset distance numeral on the closest threat only. Skipping
                // the rest keeps the bar readable; the colour already
                // communicates "danger", the closest one's distance is the
                // most actionable number.
                if (t == threats.minByOrNull { it.distanceM }) {
                    val layout = textMeasurer.measure(
                        text = "${t.distanceM}",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 9.sp
                        )
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            x = w / 2f - layout.size.width / 2f,
                            y = y - layout.size.height / 2f
                        )
                    )
                }
            }

            // Battery indicator: thin coloured tick at the very top of the
            // lane. Subtle enough to ignore, useful if the rider remembers to
            // glance. Skipped when the device hasn't reported a level yet.
            frame?.batteryPercent?.let { pct ->
                val batteryColor = when {
                    pct >= 50 -> AccentGreen
                    pct >= 20 -> AccentOrange
                    else -> AccentRed
                }
                drawLine(
                    color = batteryColor,
                    start = Offset(w * 0.25f, 4.dp.toPx()),
                    end = Offset(w * 0.25f + (w * 0.5f * pct / 100f), 4.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
