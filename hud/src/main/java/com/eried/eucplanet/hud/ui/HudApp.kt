package com.eried.eucplanet.hud.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eried.eucplanet.hud.R
import com.eried.eucplanet.hud.net.HudServer
import com.eried.eucplanet.hud.protocol.HudCommand
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.screens.CameraScreen
import com.eried.eucplanet.hud.ui.screens.DashboardScreen
import com.eried.eucplanet.hud.ui.screens.MapScreen
import com.eried.eucplanet.hud.ui.screens.NavScreen
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min

/**
 * Root composable: fades between the four HUD screens driven by the
 * [HudUiController] and overlays a center modal when the phone isn't
 * connected. The disconnected modal carries the HUD's own IP so the rider
 * can type it into the phone app without leaving the helmet.
 *
 * Always-dark theme. The HUD display is a transflective module behind a
 * windscreen — bright UI is unusable in daylight glare, so we don't even
 * give the rider a light-mode option.
 */
@Composable
fun HudApp(
    state: StateFlow<HudState>,
    status: StateFlow<HudServer.Status>,
    peer: StateFlow<String?>,
    localIp: StateFlow<String?>,
    controller: HudUiController,
    onCommand: (HudCommand) -> Unit
) {
    val hud by state.collectAsStateWithLifecycle()
    val st by status.collectAsStateWithLifecycle()
    val pr by peer.collectAsStateWithLifecycle()
    val ip by localIp.collectAsStateWithLifecycle()

    val accent = remember(hud.accentArgb) { parseHexColor(hud.accentArgb) }

    val colors = darkColorScheme(
        primary = accent,
        background = Color.Black,
        surface = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White
    )

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = controller.current,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "hud-screen"
                ) { screen ->
                    when (screen) {
                        HudUiController.Screen.Dashboard ->
                            DashboardScreen(hud = hud, gpsView = controller.dashboardGpsView)
                        HudUiController.Screen.Camera ->
                            CameraScreen(hud = hud)
                        HudUiController.Screen.Map ->
                            MapScreen(hud = hud, zoom = controller.mapZoom, peer = pr)
                        HudUiController.Screen.Nav ->
                            NavScreen(hud = hud)
                    }
                }

                if (st != HudServer.Status.CONNECTED) {
                    DisconnectedDialog(localIp = ip)
                }
            }
        }
    }
}

/**
 * Center modal shown while no phone is connected. Carries the HUD's local
 * IP so the rider can type it into the phone app, plus the menu breadcrumb
 * to find the input field. Replaces the earlier alternating top banner --
 * that needed to cycle phases to fit two pieces of info, this single dialog
 * shows everything at once and pulses a red icon so a rider glancing up
 * always reads the link as DOWN at a distance.
 *
 * Sizing is derived from screen constraints (see [BoxWithConstraints]) so the
 * dialog reads cleanly on the Motoeye E6's compact panel and the 800×480 dev
 * emulator without per-device tuning.
 */
@Composable
private fun DisconnectedDialog(localIp: String?) {
    val ctx = LocalContext.current
    val ipText = localIp ?: ctx.getString(R.string.hud_status_ip_unknown)
    val port = HudDiscovery.DEFAULT_PORT

    // Slow pulse between two reds so a rider's peripheral vision picks up
    // "link broken" without the icon being so loud it distracts when the
    // rider's busy. 1800 ms / direction = full cycle 3.6 s, comfortable
    // for glancing.
    val pulse = rememberInfiniteTransition(label = "disconnect-pulse")
    val animatedTint by pulse.animateColor(
        initialValue = Color(0xFFB71C1C), // dark red
        targetValue = Color(0xFFEF5350),  // bright red
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tint"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Dim the underlying screen so the dialog reads as modal even
            // when the dashboard behind it is busy. Not fully opaque -- the
            // rider should still see the dial sweep if telemetry comes back.
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        val side = min(maxWidth.value, maxHeight.value)
        val dialogW = (maxWidth.value * 0.7f).coerceAtMost(maxHeight.value * 1.6f).dp
        val iconSize = (side * 0.18f).dp
        val titleSize = (side * 0.07f).sp
        val ipSize = (side * 0.075f).sp
        val captionSize = (side * 0.035f).sp
        val pad = (side * 0.04f).dp

        Column(
            modifier = Modifier
                .width(dialogW)
                .clip(RoundedCornerShape((side * 0.025f).dp))
                .background(Color(0xE6111111))
                .border(
                    width = (side * 0.004f).coerceAtLeast(1f).dp,
                    color = animatedTint.copy(alpha = 0.7f),
                    shape = RoundedCornerShape((side * 0.025f).dp)
                )
                .padding(PaddingValues(horizontal = pad, vertical = pad)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((side * 0.018f).dp)
        ) {
            Icon(
                imageVector = Icons.Filled.SignalWifiOff,
                contentDescription = null,
                tint = animatedTint,
                modifier = Modifier.size(iconSize)
            )
            Text(
                text = ctx.getString(R.string.hud_disconnected_title),
                color = Color.White,
                fontSize = titleSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = ctx.getString(R.string.hud_status_ready_label),
                color = Color(0xFFB0B0B0),
                fontSize = captionSize,
                textAlign = TextAlign.Center
            )
            Text(
                text = "$ipText:$port",
                color = Color.White,
                fontSize = ipSize,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            Text(
                text = ctx.getString(R.string.hud_status_enable_hint),
                color = Color(0xFF808080),
                fontSize = captionSize,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Parse `#AARRGGBB` (or `#RRGGBB`) into a Compose [Color], falling back to
 *  the accent default if the wire payload is malformed. */
internal fun parseHexColor(hex: String): Color {
    val v = hex.removePrefix("#")
    return try {
        when (v.length) {
            6 -> Color(0xFF000000 or v.toLong(16))
            8 -> Color(v.toLong(16))
            else -> Color(0xFF00C853)
        }
    } catch (_: Throwable) {
        Color(0xFF00C853)
    }
}
