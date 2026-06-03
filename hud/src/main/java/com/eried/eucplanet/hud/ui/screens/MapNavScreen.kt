package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.hud.R
import com.eried.eucplanet.hud.net.HudTileCache
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.parseHexColor

/**
 * Combined Map + Navigation screen — the "Garmin dashboard GPS" look. The
 * map is the always-on base layer (same renderer as [MapScreen]); when
 * `hud.navActive` is true the next-turn glyph + distance + street name
 * overlay as a compact badge at the top of the screen, the way Edge /
 * DriveSmart do it. When the rider arrives, the arrow swaps for an
 * "Arrived" pill so the underlying map context (where they ended up)
 * stays visible.
 *
 * Lives alongside the dedicated [MapScreen] and [NavScreen] so riders
 * who prefer the full-screen turn arrow can keep them in their carousel
 * via the phone Personalize list. This screen ships enabled by default;
 * the standalone ones default to opt-in.
 */
@Composable
fun MapNavScreen(hud: HudState, zoom: Float, peer: String?, cache: HudTileCache) {
    Box(Modifier.fillMaxSize()) {
        // Base layer is the same MapScreen renderer riders already know —
        // shares the tile cache, accent colour, contrast/brightness
        // settings, GPS-fix gating. No duplication.
        MapScreen(hud = hud, zoom = zoom, peer = peer, cache = cache)

        // Overlay only renders when an active route is set; otherwise the
        // map shows clean. Two states: arrived shows a centred banner
        // (the map stays useful as "you ended up here"), otherwise the
        // top-centre turn badge mirrors the Garmin Edge layout.
        if (hud.navActive) {
            if (hud.navArrived) {
                ArrivedBanner(accent = parseHexColor(hud.accentArgb))
            } else {
                NavTurnBadge(hud = hud)
            }
        }
    }
}

/**
 * Top-centre badge — small turn arrow + distance + next-turn text.
 * Sized so a long street name like "Karl Johans gate" fits without
 * pushing the badge off the map. Same chrome family as the other HUD
 * overlays (8.dp rounded, semi-opaque black fill).
 */
@Composable
private fun BoxScope.NavTurnBadge(hud: HudState) {
    val accent = parseHexColor(hud.accentArgb)
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.size(44.dp)) {
            TurnArrow(angleDeg = hud.navArrowAngleDeg, color = accent)
        }
        Column {
            Text(
                text = hud.navDistance,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = hud.navPrimary,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 200.dp)
            )
        }
    }
}

/**
 * "Arrived" pill — replaces the turn badge once the route ends. Centred
 * because there's no next-turn to point at, and kept compact so the
 * underlying map (where the rider physically arrived) stays the focus.
 */
@Composable
private fun BoxScope.ArrivedBanner(accent: Color) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6000000))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = ctx.getString(R.string.hud_nav_arrived),
            color = accent,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
