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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
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
        val iconSize = (side * 0.18f).dp
        val titleSize = (side * 0.07f).sp
        val captionSize = (side * 0.038f).sp

        // Dialog frame: static neutral border (Material outline gray)
        // so only the icon draws the eye. Cells use the same gray. One
        // source of motion is enough; the rider's gaze lands on the icon
        // first, then walks down to the IP/PORT.
        val frameColor = Color(0xFF6B6B6B)
        val pad = (side * 0.04f).dp

        Column(
            modifier = Modifier
                // wrapContentSize lets the dialog grow to fit IP/PORT cells
                // on small panels where they otherwise would push past a
                // fixed 70%-of-width box. Capped by widthIn so it never
                // takes the whole screen on a wide dev emulator.
                .widthIn(min = (maxWidth.value * 0.5f).dp, max = (maxWidth.value * 0.92f).dp)
                .wrapContentSize()
                .clip(RoundedCornerShape((side * 0.025f).dp))
                .background(Color(0xE6111111))
                .border(
                    width = (side * 0.004f).coerceAtLeast(1f).dp,
                    color = frameColor.copy(alpha = 0.7f),
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
            // Instruction goes ABOVE the values so the rider reads the
            // sentence first, then the things to type appear right below
            // it -- natural top-down flow.
            Text(
                text = ctx.getString(R.string.hud_disconnected_instructions),
                color = Color(0xFFB0B0B0),
                fontSize = captionSize,
                textAlign = TextAlign.Center
            )
            // Two cells, one per phone-side input field. Cells size to their
            // own text content via wrapContentSize so a panel half the width
            // of the dev emulator still renders both numbers without
            // clipping -- no hardcoded widths anywhere in the cell.
            IpPortMatrix(
                ipText = ipText,
                port = port,
                accent = frameColor,
                side = side
            )
        }
    }
}

/**
 * IP and PORT each laid out as one row: a left-side label ("IP" / "PORT")
 * followed by a bounded cell containing the value. Stacked vertically so
 * the rider reads top-to-bottom -- IP first, port underneath -- matching
 * the order of the fields on the phone form.
 *
 * Cells use wrapContentSize so any digit string fits at any panel size.
 * Labels share a single fixed-width column so both cells line up on the
 * same x coordinate.
 */
@Composable
private fun IpPortMatrix(
    ipText: String,
    port: Int,
    accent: Color,
    side: Float
) {
    val cellHMin = (side * 0.18f).dp
    // Font sized so 15 monospace chars ("255.255.255.255") fit comfortably
    // inside the common cell width below. Tuned by inspecting renders at
    // 800×480; the previous 0.10×side was too aggressive and clipped
    // "10.0.2.15" because monospace char width at that size landed close
    // to cellW/9.
    val cellFont = (side * 0.085f).sp
    // Labels bumped up so they read as equal partners with the values
    // rather than tiny captions. The rider scans the label first.
    val labelFont = (side * 0.075f).sp
    // Tight vertical gap so IP and PORT read as a single block of
    // information instead of two unrelated lines.
    val rowGap = (side * 0.006f).dp
    val labelGap = (side * 0.025f).dp
    val cornerR = (side * 0.014f).dp
    val borderW = (side * 0.0045f).coerceAtLeast(1f).dp
    val innerHPad = (side * 0.035f).dp
    val labelMinW = (side * 0.13f).dp
    // Common cell width sized for the longest possible IPv4 + a comfortable
    // margin. PORT inherits the same width so both right edges align and
    // the two cells form a clean column.
    val cellW = (side * 0.70f).dp

    Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
        AddressRow(
            label = "IP",
            value = ipText,
            labelMinW = labelMinW,
            labelGap = labelGap,
            cellW = cellW,
            cellHMin = cellHMin,
            innerHPad = innerHPad,
            valueFont = cellFont,
            labelFont = labelFont,
            cornerR = cornerR,
            borderW = borderW,
            border = accent
        )
        AddressRow(
            label = "PORT",
            value = port.toString(),
            labelMinW = labelMinW,
            labelGap = labelGap,
            cellW = cellW,
            cellHMin = cellHMin,
            innerHPad = innerHPad,
            valueFont = cellFont,
            labelFont = labelFont,
            cornerR = cornerR,
            borderW = borderW,
            border = accent
        )
    }
}

/** A single label-on-left, value-cell-on-right row. Both labels share
 *  [labelMinW] and both cells share [cellW] so the two rows form a clean
 *  aligned grid. */
@Composable
private fun AddressRow(
    label: String,
    value: String,
    labelMinW: androidx.compose.ui.unit.Dp,
    labelGap: androidx.compose.ui.unit.Dp,
    cellW: androidx.compose.ui.unit.Dp,
    cellHMin: androidx.compose.ui.unit.Dp,
    innerHPad: androidx.compose.ui.unit.Dp,
    valueFont: androidx.compose.ui.unit.TextUnit,
    labelFont: androidx.compose.ui.unit.TextUnit,
    cornerR: androidx.compose.ui.unit.Dp,
    borderW: androidx.compose.ui.unit.Dp,
    border: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color(0xFFB0B0B0),
            fontSize = labelFont,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.widthIn(min = labelMinW)
        )
        Spacer(Modifier.width(labelGap))
        Box(
            modifier = Modifier
                .width(cellW)
                .heightIn(min = cellHMin)
                .clip(RoundedCornerShape(cornerR))
                .background(Color(0xFF0F0F0F))
                .border(borderW, border.copy(alpha = 0.55f), RoundedCornerShape(cornerR))
                .padding(horizontal = innerHPad),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value,
                color = Color.White,
                fontSize = valueFont,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
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
