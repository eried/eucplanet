package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * This is the differentiator over the competitor's 3-screen HUD app — they
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

    if (!hud.navActive) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = ctx.getString(R.string.hud_nav_idle),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 28.sp
            )
        }
        return
    }

    if (hud.navArrived) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = ctx.getString(R.string.hud_nav_arrived),
                color = accent,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(0.9f).fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            TurnArrow(angleDeg = hud.navArrowAngleDeg, color = accent)
        }
        Spacer(Modifier.width(24.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = hud.navDistance,
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = hud.navPrimary,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 22.sp,
                textAlign = TextAlign.Start
            )
        }
    }
}

/**
 * Big chevron-style turn arrow. We render in Compose Canvas so it scales
 * cleanly across the unpredictable HUD DPIs we see in the wild — no XML
 * vector + size-pinned imageVector pulling pixel hair from imageBitmap.
 *
 * The arrow points straight up at angle=0 and rotates clockwise for
 * right-hand turns, matching `NavState.arrowAngleDeg`.
 */
@Composable
private fun TurnArrow(angleDeg: Float, color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        rotate(degrees = angleDeg) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val h = (size.height * 0.7f) / 2f
            val w = (size.width * 0.55f) / 2f
            val shaftW = w * 0.45f

            val path = Path().apply {
                // Tip at top, then the two flares of the chevron, then down
                // the shaft. All coordinates relative to (cx, cy).
                moveTo(cx, cy - h)
                lineTo(cx + w, cy - h * 0.05f)
                lineTo(cx + shaftW, cy - h * 0.05f)
                lineTo(cx + shaftW, cy + h)
                lineTo(cx - shaftW, cy + h)
                lineTo(cx - shaftW, cy - h * 0.05f)
                lineTo(cx - w, cy - h * 0.05f)
                close()
            }
            drawPath(path = path, color = color)
            drawPath(
                path = path,
                color = Color.White,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        @Suppress("UNUSED_VARIABLE") val anchorAvoidLint = Offset.Zero
    }
}
