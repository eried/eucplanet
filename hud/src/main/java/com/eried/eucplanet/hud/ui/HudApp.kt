package com.eried.eucplanet.hud.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
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
import com.eried.eucplanet.hud.ui.screens.CustomOverlayScreen
import com.eried.eucplanet.hud.ui.screens.DashboardScreen
import com.eried.eucplanet.hud.ui.screens.MapScreen
import com.eried.eucplanet.hud.ui.screens.NavScreen
import com.eried.eucplanet.hud.ui.screens.TelemetryScreen
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
    tileCache: com.eried.eucplanet.hud.net.HudTileCache,
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
                    transitionSpec = {
                        // Slide direction follows the rider's swipe: moving
                        // forward in the carousel (Dashboard → Camera → …)
                        // brings the new screen in from the RIGHT; going
                        // back brings it in from the LEFT. Compare ordinals
                        // because the enum's declaration order is the
                        // canonical carousel order.
                        val forward = targetState.ordinal > initialState.ordinal
                        if (forward) {
                            slideInHorizontally(initialOffsetX = { it }) togetherWith
                                slideOutHorizontally(targetOffsetX = { -it })
                        } else {
                            slideInHorizontally(initialOffsetX = { -it }) togetherWith
                                slideOutHorizontally(targetOffsetX = { it })
                        }
                    },
                    label = "hud-screen"
                ) { screen ->
                    when (screen) {
                        HudUiController.Screen.Dashboard ->
                            DashboardScreen(hud = hud, gpsView = controller.dashboardGpsView)
                        HudUiController.Screen.Camera ->
                            CameraScreen(hud = hud)
                        HudUiController.Screen.Telemetry ->
                            TelemetryScreen(hud = hud)
                        HudUiController.Screen.Custom ->
                            CustomOverlayScreen(hud = hud, withCamera = false)
                        HudUiController.Screen.CustomCam ->
                            CustomOverlayScreen(hud = hud, withCamera = true)
                        HudUiController.Screen.Map ->
                            MapScreen(hud = hud, zoom = controller.mapZoom, peer = pr, cache = tileCache)
                        HudUiController.Screen.Nav ->
                            NavScreen(hud = hud)
                    }
                }

                // Brief toast when the rider switches screens, top-left,
                // matching the disconnected badge's chrome (clipped corner,
                // dark fill + gray stroke, same font sizes).
                ScreenChangeToast(
                    screen = controller.current,
                    index1Based = controller.currentIndex1Based(),
                    total = controller.totalScreens(),
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                )

                // Ambient wall clock, bottom-left, same chrome as the
                // disconnect / screen-change badges. Skipped on the two
                // Custom-overlay screens because that's the rider's
                // canvas; we shouldn't paint app chrome on top of their
                // preset.
                val showClock = controller.current != HudUiController.Screen.Custom &&
                    controller.current != HudUiController.Screen.CustomCam
                if (showClock) {
                    WallClockBadge(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                    )
                }

                // Disconnect chrome renders LAST so it sits in front of
                // the wall clock and screen toast -- the rider's
                // "your phone isn't talking to me" signal needs to win
                // over the ambient overlays.
                //
                // 5-second grace at boot so the dialog doesn't flash up
                // during the normal "HUD started, phone hasn't dialled
                // in yet" gap. Once we've been connected at least once,
                // subsequent disconnects show immediately -- those are
                // real events, not boot-time noise.
                var everConnected by remember { mutableStateOf(false) }
                if (st == HudServer.Status.CONNECTED) everConnected = true
                var bootGraceElapsed by remember { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(DISCONNECT_BOOT_GRACE_MS)
                    bootGraceElapsed = true
                }
                val showDisconnect = st != HudServer.Status.CONNECTED &&
                    (everConnected || bootGraceElapsed)
                if (showDisconnect) {
                    if (controller.disconnectedModalDismissed) {
                        DisconnectedBadge(
                            localIp = ip,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                        )
                    } else {
                        DisconnectedDialog(localIp = ip)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenChangeToast(
    screen: HudUiController.Screen,
    index1Based: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(screen) {
        visible = true
        kotlinx.coroutines.delay(SCREEN_TOAST_DURATION_MS)
        visible = false
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = modifier
    ) {
        val (titleRes, descRes) = when (screen) {
            HudUiController.Screen.Dashboard ->
                R.string.hud_screen_dashboard to R.string.hud_screen_dashboard_desc
            HudUiController.Screen.Camera ->
                R.string.hud_screen_camera to R.string.hud_screen_camera_desc
            HudUiController.Screen.Telemetry ->
                R.string.hud_screen_telemetry to R.string.hud_screen_telemetry_desc
            HudUiController.Screen.Custom ->
                R.string.hud_screen_custom to R.string.hud_screen_custom_desc
            HudUiController.Screen.CustomCam ->
                R.string.hud_screen_custom_cam to R.string.hud_screen_custom_cam_desc
            HudUiController.Screen.Map ->
                R.string.hud_screen_map to R.string.hud_screen_map_desc
            HudUiController.Screen.Nav ->
                R.string.hud_screen_nav to R.string.hud_screen_nav_desc
        }
        // Same chrome as DisconnectedBadge: 8.dp rounded corners, 0xE6111111
        // fill, 0xFF6B6B6B 1.dp border, same horizontal/vertical padding.
        // Stacked title + description because the screen name and the
        // contents blurb don't fit comfortably on one line, but the typography
        // (13.sp title / 11.sp description) matches the badge exactly.
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xE6111111))
                .border(1.dp, Color(0xFF6B6B6B), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // Title row: "<screen name>  <index>/<total>"
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ctx.getString(titleRes),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = "  $index1Based/$total",
                    color = Color(0xFF808080),
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            Text(
                text = ctx.getString(descRes),
                color = Color(0xFFB0B0B0),
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

private const val SCREEN_TOAST_DURATION_MS: Long = 3_000L
/** Boot-time grace before the disconnect chrome can appear. Long enough
 *  that a phone dialling in within a few seconds of HUD start never
 *  triggers the modal. Resets only on process restart. */
private const val DISCONNECT_BOOT_GRACE_MS: Long = 5_000L

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
                // wrapContentSize sizes to the inner content (icon, title,
                // instruction, IP/PORT block). Capped at 70% of the panel
                // so a wide dev emulator doesn't sprawl the dialog edge-to-
                // edge -- the cells now wrap to a content-driven width,
                // so the dialog is naturally a lot tighter than before.
                .widthIn(max = (maxWidth.value * 0.7f).dp)
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
            // 2×2 grid: column 1 = labels, column 2 = cells. Both columns
            // share fixed widths so corners line up cleanly. The inner pad
            // (side * 0.05f) gives the block visual margin against the
            // dialog edge so it doesn't feel crammed against the border.
            Box(modifier = Modifier.padding(horizontal = (side * 0.05f).dp)) {
                IpPortMatrix(
                    ipText = ipText,
                    port = port,
                    accent = frameColor,
                    side = side
                )
            }
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
    // Slightly smaller font so a long IP like "192.168.43.142" fits with
    // headroom on real-device panels. Earlier 0.085×side clipped the last
    // digit on a tester's Motoeye E6 -- the dialog width is constrained by
    // the panel aspect and the cell ran out of room.
    val cellFont = (side * 0.075f).sp
    val labelFont = (side * 0.075f).sp
    // Visible gap between IP and PORT so they read as two distinct fields
    // instead of one stacked block. Earlier 0.006×side made them visually
    // touch on the real HUD panel; ~0.03 gives a clean breathing space.
    val rowGap = (side * 0.03f).dp
    val labelGap = (side * 0.025f).dp
    val cornerR = (side * 0.014f).dp
    val borderW = (side * 0.0045f).coerceAtLeast(1f).dp
    val innerHPad = (side * 0.035f).dp
    val labelColW = (side * 0.18f).dp
    // Cells sized for a typical IPv4 address ("192.168.111.111", 15 chars
    // monospace + horizontal padding) -- prior weight(1f) made each cell
    // expand to consume all remaining row width, padding the dialog with
    // ~150 dp of empty space on each side. Fixed cell width keeps both
    // rows perfectly aligned AND lets the wrapping dialog shrink to a
    // tight bounding box.
    val cellW = (side * 0.55f).dp

    // wrapContentWidth so the column sizes to its widest row instead of
    // stretching to fill the parent. The parent dialog Column is itself
    // wrapContentSize, so reducing this layer reduces the whole dialog.
    Column(
        modifier = Modifier.wrapContentWidth(),
        verticalArrangement = Arrangement.spacedBy(rowGap)
    ) {
        AddressRow(
            label = "IP",
            value = ipText,
            labelColW = labelColW,
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
            labelColW = labelColW,
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
    labelColW: androidx.compose.ui.unit.Dp,
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
    Row(
        // wrapContentWidth so the row sizes to (label + gap + cell) only.
        // Previously fillMaxWidth + weight(1f) on the cell padded the
        // dialog with ~150 dp of empty space either side.
        modifier = Modifier.wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFFB0B0B0),
            fontSize = labelFont,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(labelColW)
        )
        Spacer(Modifier.width(labelGap))
        Box(
            modifier = Modifier
                // Fixed cell width sized for a typical IPv4 address. Both
                // IP and PORT cells share this width so they line up on
                // both edges; the PORT cell has empty space inside, which
                // is the same alignment the original weight(1f) layout
                // had -- just inside a much tighter dialog.
                .width(cellW)
                .heightIn(min = cellHMin)
                .clip(RoundedCornerShape(cornerR))
                .background(Color(0xFF2F2F2F))
                .border(borderW, border.copy(alpha = 0.55f), RoundedCornerShape(cornerR))
                .padding(horizontal = innerHPad),
            contentAlignment = Alignment.CenterStart
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

/**
 * Compact corner badge shown after the rider dismisses the full
 * disconnected modal with a button press. Keeps the icon + IP + port
 * visible so the rider can still copy them onto the phone, but takes only
 * a corner of the screen so the underlying dashboard / camera / map are
 * usable.
 */
@Composable
private fun DisconnectedBadge(localIp: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ipText = localIp ?: ctx.getString(R.string.hud_status_ip_unknown)
    val port = HudDiscovery.DEFAULT_PORT

    var bright by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(BLINK_INTERVAL_MS)
            bright = !bright
        }
    }
    val tint = if (bright) Color(0xFFEF5350) else Color(0xFF7F0000)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xE6111111))
            .border(1.dp, Color(0xFF6B6B6B), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.PhonelinkOff,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$ipText:$port",
            color = Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** Ambient wall clock shown across screens. Matches the disconnect badge
 *  chrome (8.dp rounded, 0xE6111111 fill, 0xFF6B6B6B 1.dp stroke) for
 *  visual consistency with the other persistent overlays. Updates every
 *  15 s -- the rider doesn't need second-precision out of the corner of
 *  their eye. Uses the system's 24h / 12h preference. */
@Composable
private fun WallClockBadge(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val use24h = android.text.format.DateFormat.is24HourFormat(ctx)
    val pattern = if (use24h) "HH:mm" else "h:mm a"
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(15_000L)
        }
    }
    val formatter = remember(pattern) {
        java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xE6111111))
            .border(1.dp, Color(0xFF6B6B6B), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = formatter.format(java.util.Date(nowMs)),
            color = Color.White,
            fontSize = 13.sp,
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
