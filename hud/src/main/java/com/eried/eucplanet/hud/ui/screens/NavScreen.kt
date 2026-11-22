package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import kotlin.math.min
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.hud.R
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.parseHexColor

/**
 * Navigator turn-arrow screen.
 *
 * This is the differentiator over the competitor's 3-screen HUD app, they
 * have a map but no live turn-by-turn. We mirror the phone's existing
 * [com.eried.eucplanet.nav.NavigationEngine] state: arrow angle (0=straight,
 * positive=clockwise), primary text ("Turn left onto Storgata"), distance
 * ("120 m"), arrived flag.
 *
 * When `navActive` is false we show a friendly "no route" message instead of
 * a stale arrow; otherwise the rider could glance at a frozen turn-left from
 * yesterday's ride.
 */
@Composable
fun NavScreen(hud: HudState) {
    val ctx = LocalContext.current
    val accent = parseHexColor(hud.accentArgb)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerH = maxHeight.value
        val side = min(maxWidth.value, containerH)
        val pad = (side * 0.04f).dp

        if (!hud.navActive) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = ctx.getString(R.string.hud_nav_idle),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = (side * 0.06f).sp,
                    maxLines = 1
                )
            }
            return@BoxWithConstraints
        }

        if (hud.navArrived) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = ctx.getString(R.string.hud_nav_arrived),
                    color = accent,
                    fontSize = (side * 0.10f).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            return@BoxWithConstraints
        }

        Row(
            modifier = Modifier.fillMaxSize().padding(pad),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(0.9f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                TurnArrow(angleDeg = hud.navArrowAngleDeg, color = accent)
            }
            Spacer(Modifier.width(pad))
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = hud.navDistance,
                    color = Color.White,
                    fontSize = (containerH * 0.18f).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = hud.navPrimary,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = (containerH * 0.06f).sp,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

/**
 * Big chevron-style turn arrow. We render in Compose Canvas so it scales
 * cleanly across the unpredictable HUD DPIs we see in the wild, no XML
 * vector + size-pinned imageVector pulling pixel hair from imageBitmap.
 *
 * The arrow points straight up at angle=0 and rotates clockwise for
 * right-hand turns, matching `NavState.arrowAngleDeg`.
 */
@Composable
internal fun TurnArrow(angleDeg: Float, color: Color) {
    // The same chevron as the phone's navigation popup: Material's
    // "navigation" icon (M12 2 L4.5 20.29 l.71 .71 L12 18 l6.79 3 .71-.71 z
    // on a 24 grid), filled in the cue colour and turned to the next move.
    Canvas(modifier = Modifier.fillMaxSize()) {
        rotate(degrees = angleDeg) {
            val u = minOf(size.width, size.height) / 24f
            val ox = size.width / 2f - 12f * u
            val oy = size.height / 2f - 12f * u
            fun x(v: Float) = ox + v * u
            fun y(v: Float) = oy + v * u
            val path = Path().apply {
                moveTo(x(12f), y(2f))
                lineTo(x(4.5f), y(20.29f))
                lineTo(x(5.21f), y(21f))
                lineTo(x(12f), y(18f))
                lineTo(x(18.79f), y(21f))
                lineTo(x(19.5f), y(20.29f))
                close()
            }
            drawPath(path = path, color = color)
        }
    }
}
