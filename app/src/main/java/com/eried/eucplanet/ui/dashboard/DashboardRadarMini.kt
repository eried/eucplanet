package com.eried.eucplanet.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.data.model.RadarFrame
import com.eried.eucplanet.data.model.ThreatLevel
import com.eried.eucplanet.ui.radar.RadarOverlayViewModel
import com.eried.eucplanet.ui.theme.appColors

/**
 * Compact radar lane shown inside the dashboard's dial Box. Replaces the
 * earlier full-screen edge bar so the rider's peripheral attention isn't
 * pulled across every screen ,  the overlay only matters while looking at
 * the speedo anyway, and the dashboard already has natural gutters either
 * side of the dial where a thin lane fits without competing with anything.
 *
 * Mount this inside the same [androidx.compose.foundation.layout.BoxWithConstraints]
 * that holds the [SpeedGauge] + corner glyph clusters. The two mounts use
 * [androidx.compose.ui.BiasAlignment] with vertical bias 0.3 so they sit
 * a bit BELOW the dial centre, vertically aligned with the Map (bottom-
 * start) and Studio (bottom-end) corner glyphs that they sit above.
 *
 * Visual model: a thin rounded card with a colour-coded square per
 * detected vehicle (red / orange / green by threat level), the distance
 * in metres painted big inside each square (bigger for shorter numbers
 * so the closest threat reads strongest), and a battery tick centered
 * across the top. Tapping the lane opens the radar settings.
 */
private const val LANE_RANGE_M = 140  // matches Garmin Varia's advertised reach
private const val LANE_WIDTH_DP = 38
private const val LANE_HEIGHT_DP = 168
private const val SQUARE_SIZE_DP = 28

/**
 * The dashboard mounts this twice ,  once with horizontal bias -1f and
 * once with +1f (i.e. start / end edges of the parent box). Each opts in
 * to render when its side matches the user's preference, or when "BOTH"
 * is selected. The fade animation was removed because two adjacent
 * AnimatedVisibility wrappers were interacting weirdly with BoxScope
 * alignment ,  the second copy never claimed a layout slot.
 */
@Composable
fun DashboardRadarMiniForSide(
    targetSide: String,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    viewModel: RadarOverlayViewModel = hiltViewModel()
) {
    val show by viewModel.shouldShow.collectAsState()
    val side by viewModel.side.collectAsState()
    val frame by viewModel.frame.collectAsState()

    val visible = show && (side == targetSide || side == "BOTH")
    if (visible) {
        Box(
            modifier = modifier.clickable { onOpenSettings() }
        ) {
            MiniLaneBar(frame = frame)
        }
    }
}

/**
 * Pick a font size for the distance numeral based on its digit count.
 * Single-digit distances are the most urgent (rider is within metres of
 * a closer) and deserve the most visual weight; three-digit numbers
 * (100+ m) are background information and can shrink.
 */
private fun fontForDistance(distanceM: Int): TextUnit = when {
    distanceM >= 100 -> 11.sp
    distanceM >= 10 -> 16.sp
    else -> 18.sp
}

@Composable
private fun MiniLaneBar(frame: RadarFrame?) {
    val textMeasurer = rememberTextMeasurer()
    // Threat / battery tier colors captured here (composable scope) so the Canvas
    // DrawScope below — which can't read MaterialTheme — can color by status token.
    val statusDanger = MaterialTheme.appColors.statusDanger
    val statusWarn = MaterialTheme.appColors.statusWarn
    val statusGood = MaterialTheme.appColors.statusGood
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .width(LANE_WIDTH_DP.dp)
            .height(LANE_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.appColors.scrim.copy(alpha = 0x80 / 255f))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val squareSize = SQUARE_SIZE_DP.dp.toPx()

            // Lane usable range: leave a bit of room at the top for the
            // battery tick and a bit at the bottom so the closest square
            // doesn't kiss the edge.
            val laneTop = 10.dp.toPx() + squareSize / 2f
            val laneBottom = h - 6.dp.toPx() - squareSize / 2f
            val laneHeight = laneBottom - laneTop

            // Centre guideline so the squares feel like they're tracking
            // down a road instead of floating in a black void. Subtle so
            // the threats dominate the eye path.
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(w / 2f, laneTop - squareSize / 2f + 2.dp.toPx()),
                end = Offset(w / 2f, laneBottom + squareSize / 2f - 2.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )

            val threats = frame?.threats ?: emptyList()
            // Sort far-first so the closest square draws last and ends
            // up on top in the rare case two land at the same Y after
            // the distance-to-pixel projection.
            val sorted = threats.sortedByDescending { it.distanceM }
            sorted.forEach { t ->
                val clamped = t.distanceM.coerceAtMost(LANE_RANGE_M).coerceAtLeast(0)
                val ratio = clamped.toFloat() / LANE_RANGE_M.toFloat()
                val centerY = laneBottom - ratio * laneHeight
                val color = when (t.threatLevel) {
                    ThreatLevel.FAST_APPROACH -> statusDanger
                    ThreatLevel.APPROACHING -> statusWarn
                    ThreatLevel.NONE -> statusGood
                }

                // Filled colour square: the threat marker.
                drawRect(
                    color = color,
                    topLeft = Offset(
                        x = w / 2f - squareSize / 2f,
                        y = centerY - squareSize / 2f
                    ),
                    size = Size(squareSize, squareSize)
                )

                // Distance label, sized by digit count. White on
                // red/orange reads cleanly; bold for legibility at
                // glance distance.
                val layout = textMeasurer.measure(
                    text = "${t.distanceM}",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = fontForDistance(t.distanceM),
                        fontWeight = FontWeight.Bold
                    )
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = w / 2f - layout.size.width / 2f,
                        y = centerY - layout.size.height / 2f
                    )
                )
            }

            // Battery tick across the top, centred on the same vertical
            // axis as the threat squares. The bar grows out from the
            // centre in both directions so a half-full battery still
            // reads as "halfway", not "shifted left".
            frame?.batteryPercent?.let { pct ->
                val batteryColor = when {
                    pct >= 50 -> statusGood
                    pct >= 20 -> statusWarn
                    else -> statusDanger
                }
                val maxLen = w * 0.64f
                val barLen = maxLen * pct / 100f
                drawLine(
                    color = batteryColor,
                    start = Offset(w / 2f - barLen / 2f, 4.dp.toPx()),
                    end = Offset(w / 2f + barLen / 2f, 4.dp.toPx()),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
