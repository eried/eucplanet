package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.HudUnits
import com.eried.eucplanet.hud.ui.parseHexColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Direction-of-travel screen: big compass rose, numeric heading, GPS
 * speed below. Doesn't fetch any map tiles -- this is the "where am I
 * pointed?" screen for riders in areas without good tile coverage.
 */
@Composable
fun CompassScreen(hud: HudState) {
    val accent = parseHexColor(hud.accentArgb)
    val heading = hud.gpsHeadingDeg
    val hasHeading = !heading.isNaN()

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sideDp = min(maxWidth.value, maxHeight.value)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!hasHeading) {
                    Text(
                        text = "NO GPS SIGNAL",
                        color = Color(0xFFE53935),
                        fontSize = (sideDp * 0.06f).sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = "%03d°".format(heading.toInt().mod(360)),
                        color = Color.White,
                        fontSize = (sideDp * 0.18f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = cardinal(heading),
                        color = accent,
                        fontSize = (sideDp * 0.08f).sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Compass rose. Drawn pointing UP at all times -- the
                    // text above already names which way we're pointed.
                    Canvas(
                        modifier = Modifier
                            .padding(top = (sideDp * 0.04f).dp)
                            .size((sideDp * 0.5f).dp)
                    ) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r = min(cx, cy) * 0.95f
                        drawCircle(
                            color = Color.White.copy(alpha = 0.2f),
                            center = Offset(cx, cy),
                            radius = r,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        // Tick marks every 30 deg, longer at the
                        // cardinals. Rotate the WHOLE rose so the
                        // rider's current heading sits at the top.
                        rotate(degrees = -heading, pivot = Offset(cx, cy)) {
                            for (deg in 0 until 360 step 30) {
                                val isCardinal = deg % 90 == 0
                                val len = if (isCardinal) r * 0.18f else r * 0.10f
                                val rad = deg * PI.toFloat() / 180f
                                val outer = Offset(
                                    cx + r * sin(rad),
                                    cy - r * cos(rad)
                                )
                                val inner = Offset(
                                    cx + (r - len) * sin(rad),
                                    cy - (r - len) * cos(rad)
                                )
                                drawLine(
                                    color = if (isCardinal) accent
                                        else Color.White.copy(alpha = 0.5f),
                                    start = inner, end = outer,
                                    strokeWidth = if (isCardinal) 3.dp.toPx() else 1.dp.toPx()
                                )
                            }
                        }
                        // Fixed "you are here" pointer at the top.
                        drawLine(
                            color = Color(0xFFE53935),
                            start = Offset(cx, cy - r * 1.1f),
                            end = Offset(cx, cy - r * 0.75f),
                            strokeWidth = 4.dp.toPx()
                        )
                    }
                    Text(
                        text = "%.0f %s".format(
                            HudUnits.speed(hud.gpsSpeedKmh.takeIf { !it.isNaN() } ?: 0f, hud.unitSpeed),
                            HudUnits.speedSuffix(hud.unitSpeed)
                        ),
                        color = Color.White,
                        fontSize = (sideDp * 0.07f).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = (sideDp * 0.03f).dp)
                    )
                }
            }
        }
    }
}

/** 8-point cardinal name for a heading in degrees. */
private fun cardinal(headingDeg: Float): String {
    val n = ((headingDeg / 45f) + 0.5f).toInt().mod(8)
    return when (n) {
        0 -> "N"; 1 -> "NE"; 2 -> "E"; 3 -> "SE"
        4 -> "S"; 5 -> "SW"; 6 -> "W"; 7 -> "NW"
        else -> ""
    }
}

