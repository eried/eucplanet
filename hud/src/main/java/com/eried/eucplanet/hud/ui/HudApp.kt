package com.eried.eucplanet.hud.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eried.eucplanet.hud.R
import com.eried.eucplanet.hud.net.HudClient
import com.eried.eucplanet.hud.protocol.HudCommand
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.screens.CameraScreen
import com.eried.eucplanet.hud.ui.screens.DashboardScreen
import com.eried.eucplanet.hud.ui.screens.MapScreen
import com.eried.eucplanet.hud.ui.screens.NavScreen
import kotlinx.coroutines.flow.StateFlow

/**
 * Root composable: fades between the four HUD screens driven by the
 * [HudUiController] and overlays a small status banner when the phone isn't
 * paired.
 *
 * Always-dark theme. The HUD display is a transflective module behind a
 * windscreen — bright UI is unusable in daylight glare, so we don't even
 * give the rider a light-mode option.
 */
@Composable
fun HudApp(
    state: StateFlow<HudState>,
    status: StateFlow<HudClient.Status>,
    peer: StateFlow<String?>,
    controller: HudUiController,
    onCommand: (HudCommand) -> Unit
) {
    val hud by state.collectAsStateWithLifecycle()
    val st by status.collectAsStateWithLifecycle()
    val pr by peer.collectAsStateWithLifecycle()

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
                    status = st, peer = pr,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(
    status: HudClient.Status,
    peer: String?,
    modifier: Modifier = Modifier
) {
    // Show only while not happily paired. A "PAIRED" frame keeps full screen
    // real estate for the dashboard dial.
    if (status == HudClient.Status.PAIRED) return
    val ctx = LocalContext.current
    val text = when (status) {
        HudClient.Status.SEARCHING -> ctx.getString(R.string.hud_status_searching)
        HudClient.Status.DISCONNECTED -> ctx.getString(R.string.hud_status_disconnected)
        HudClient.Status.PAIRED -> ctx.getString(R.string.hud_status_paired, peer ?: "")
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xAA000000))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White)
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
