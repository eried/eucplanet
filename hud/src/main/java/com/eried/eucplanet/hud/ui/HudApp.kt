package com.eried.eucplanet.hud.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * Root composable: fades between the four HUD screens driven by the
 * [HudUiController] and overlays a status banner when the phone isn't
 * connected. While disconnected the banner shows the HUD's own IP -- which is
 * what the rider types into the phone app -- so a tester can recover from a
 * blocked auto-discovery without leaving the helmet.
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

                StatusBanner(
                    status = st, localIp = ip,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(
    status: HudServer.Status,
    localIp: String?,
    modifier: Modifier = Modifier
) {
    // When the phone is connected we hide the banner entirely so the dashboard
    // dial has the whole screen.
    if (status == HudServer.Status.CONNECTED) return
    val ctx = LocalContext.current

    val ipText = localIp
        ?: ctx.getString(R.string.hud_status_ip_unknown)
    val port = HudDiscovery.DEFAULT_PORT

    // Two-phase cycle so the rider sees both pieces of info without us
    // cramming them onto one cramped line:
    //   phase 0 = "HUD ready at <ip>:<port>"  -- what to type
    //   phase 1 = "EUC Planet → Settings / Integration / Motoeye HUD"
    //              -- where to type it
    // Keying LaunchedEffect on Unit (not status) keeps the cycle steady
    // across reconnect flickers.
    val phaseIp = ctx.getString(R.string.hud_status_ready_at, ipText, port)
    val phaseHint = ctx.getString(R.string.hud_status_enable_hint)
    var phase by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(BANNER_CYCLE_MS)
            phase = (phase + 1) % 2
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        // Phase 0 gets larger / monospace digits since the IP is the only
        // thing the rider has to actually read off the screen and copy. The
        // hint phase is informational and can be smaller.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (phase == 0) {
                Text(
                    text = ctx.getString(R.string.hud_status_ready_label),
                    color = Color(0xFFB0B0B0),
                    fontSize = 11.sp
                )
                Text(
                    text = "$ipText:$port",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    text = phaseHint,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** One banner phase in millis. Each phase gets this long before flipping;
 *  the full cycle is therefore 2 × this. 4500 ms gives the rider time to
 *  read an IPv4 dotted-quad without skimming. */
private const val BANNER_CYCLE_MS: Long = 4500L

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
