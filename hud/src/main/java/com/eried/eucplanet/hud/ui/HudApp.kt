package com.eried.eucplanet.hud.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhonelinkOff
import androidx.compose.material3.Icon
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

    // Sharp slow blink between two reds. A fade was too soft -- the rider
    // wanted a clear on/off signal, not a smooth pulse. Toggle every
    // BLINK_INTERVAL_MS via a delay loop so the swap is instantaneous; using
    // animateColor with a tween would interpolate even at minimal duration.
    var bright by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(BLINK_INTERVAL_MS)
            bright = !bright
        }
    }
    val animatedTint = if (bright) Color(0xFFEF5350) else Color(0xFF7F0000)

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
                imageVector = Icons.Filled.PhonelinkOff,
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
            // Each octet + the port live in their own bounded cell, the way
            // a network engineer would draw an address on a whiteboard. Far
            // easier to read off than a single "10.0.2.15:28080" string,
            // because each number sits in its own visually-distinct frame
            // -- the rider can't miss a dot or run digits together.
            IpPortMatrix(
                ipText = ipText,
                port = port,
                accent = animatedTint,
                side = side
            )
            // Single caption combining "where to type" + "what menu path",
            // so the rider reads one continuous instruction instead of two
            // detached lines. Wraps naturally on narrow panels.
            Text(
                text = ctx.getString(R.string.hud_disconnected_instructions),
                color = Color(0xFFB0B0B0),
                fontSize = captionSize,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * "Matrix"-style address display: IP octets and port in their own bounded
 * boxes with a tiny caption under each group. Sized off `side` so the cells
 * scale with the rest of the dialog.
 *
 * Splitting the IP into four labeled cells protects against the most common
 * tester typo -- running two octets together because there's a stray space
 * before a dot. The visual grouping leaves no ambiguity about what goes
 * where on the phone form.
 */
@Composable
private fun IpPortMatrix(
    ipText: String,
    port: Int,
    accent: Color,
    side: Float
) {
    val octs = ipText.split(".").let {
        if (it.size == 4) it else List(4) { "?" }
    }
    val cellH = (side * 0.13f).dp
    val octetW = (side * 0.10f).dp
    // Port box has to fit a 5-digit number in monospace at cellFont -- needs
    // wider than an octet cell. 0.24×side gives a comfortable margin so the
    // digits don't crowd the rounded corners.
    val portW = (side * 0.24f).dp
    val cellFont = (side * 0.06f).sp
    val labelFont = (side * 0.028f).sp
    // Visible gap between the IP group and PORT group so they read as two
    // separate fields rather than one continuous number.
    val groupGap = (side * 0.06f).dp
    val intraGap = (side * 0.012f).dp
    val cornerR = (side * 0.012f).dp
    val borderW = (side * 0.0035f).coerceAtLeast(1f).dp

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(groupGap)
    ) {
        // IP group
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                octs.forEachIndexed { i, oct ->
                    if (i > 0) {
                        Text(
                            text = ".",
                            color = Color.White,
                            fontSize = cellFont,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = intraGap)
                        )
                    }
                    AddressCell(
                        text = oct,
                        widthDp = octetW,
                        heightDp = cellH,
                        fontSize = cellFont,
                        accent = accent,
                        cornerR = cornerR,
                        borderW = borderW
                    )
                }
            }
            Spacer(Modifier.height(intraGap))
            Text(
                text = "IP",
                color = Color(0xFF808080),
                fontSize = labelFont,
                fontWeight = FontWeight.SemiBold
            )
        }
        // PORT group
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AddressCell(
                text = port.toString(),
                widthDp = portW,
                heightDp = cellH,
                fontSize = cellFont,
                accent = accent,
                cornerR = cornerR,
                borderW = borderW
            )
            Spacer(Modifier.height(intraGap))
            Text(
                text = "PORT",
                color = Color(0xFF808080),
                fontSize = labelFont,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun AddressCell(
    text: String,
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    accent: Color,
    cornerR: androidx.compose.ui.unit.Dp,
    borderW: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .width(widthDp)
            .height(heightDp)
            .clip(RoundedCornerShape(cornerR))
            .background(Color(0xFF0F0F0F))
            .border(borderW, accent.copy(alpha = 0.55f), RoundedCornerShape(cornerR)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** Blink half-period: a sharp on/off swap every this many millis. 900 ms
 *  reads as "slow, deliberate" rather than urgent. */
private const val BLINK_INTERVAL_MS: Long = 900L

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
