package com.eried.eucplanet.ui.navigator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.NavMode
import com.eried.eucplanet.data.model.NavState
import com.eried.eucplanet.data.model.Proximity
import com.eried.eucplanet.data.model.arrowAngleDeg
import com.eried.eucplanet.ui.theme.appColors
import kotlinx.coroutines.delay

/** How long the centred cue popup stays up before it times out to the pill. */
private const val POPUP_TIMEOUT_MS = 5_000L

/**
 * The floating navigation popup. Rendered above the nav graph in
 * [com.eried.eucplanet.MainActivity], so it hovers over every screen of the
 * app while guidance is running.
 *
 * Each new cue pops a big arrow card in the centre of the screen on a rounded
 * translucent background; it times out after [POPUP_TIMEOUT_MS] to a small
 * corner pill. Tapping the pill re-opens it. The X ends navigation after a
 * confirmation. The empty area around the card does not intercept touches.
 */
@Composable
fun NavigationOverlay(
    onOpenMap: () -> Unit = {},
    // When true, the on-phone overlay is hidden but `cueVisible` is still
    // mirrored to the engine -- so the watch keeps showing nav cues even
    // though the phone is on a screen that already conveys them (the
    // navigator map). Setting `cueVisible` independent of phone visibility
    // means the watch keeps its setting honoured.
    suppressOnPhone: Boolean = false,
    viewModel: NavigationOverlayViewModel = hiltViewModel()
) {
    val state by viewModel.navState.collectAsState()
    var showEndConfirm by remember { mutableStateOf(false) }

    // The centred popup is transient: it appears on each new cue, then times
    // out to the pill. popGen bumps on every new cue (and on a manual re-open)
    // so the show-then-hide cycle restarts each time.
    var popGen by remember { mutableIntStateOf(0) }
    var popupShown by remember { mutableStateOf(false) }

    LaunchedEffect(state.primaryText, state.offRoute, state.arrived, state.popupTick) {
        if (state.active) popGen++
    }
    LaunchedEffect(popGen, state.arrived, state.active) {
        if (!state.active) {
            popupShown = false
            return@LaunchedEffect
        }
        popupShown = true
        // The arrival banner stays up; the engine clears the whole popup itself.
        if (!state.arrived) {
            delay(POPUP_TIMEOUT_MS)
            popupShown = false
        }
    }

    // Mirror the popup's on-screen state to the engine so the watch shows nav
    // only while the phone does, and clear it when this overlay leaves the tree.
    LaunchedEffect(popupShown) { viewModel.setCueVisible(popupShown) }
    DisposableEffect(Unit) { onDispose { viewModel.setCueVisible(false) } }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // Only the big centred arrow popup, no minimized pill or other widget.
        // Suppress only when the rider is on the map and NOT on an arrival
        // frame -- the final / intermediate "goal reached" banner has to
        // surface everywhere (including the map screen and the dashboard)
        // because it tells the rider the trip / leg is over.
        val shouldSuppress = suppressOnPhone && !state.arrived
        AnimatedVisibility(
            visible = !shouldSuppress && state.active && !state.minimized && popupShown,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            // Freeze the per-button disabled flags so the X / Map icons
            // don't visibly snap back to "enabled" while the popup is
            // fading out (state.arrived stays true through stop(), but
            // we still want a stable last-visible appearance during the
            // exit animation, independent of any future navState
            // mutation while the fade is in flight).
            val mapDisabledLatched = remember { mutableStateOf(suppressOnPhone) }
            val closeDisabledLatched = remember {
                mutableStateOf(state.arrived && state.goalIndex >= state.goalCount)
            }
            LaunchedEffect(popupShown, suppressOnPhone, state.arrived,
                state.goalIndex, state.goalCount) {
                if (popupShown) {
                    mapDisabledLatched.value = suppressOnPhone
                    closeDisabledLatched.value =
                        state.arrived && state.goalIndex >= state.goalCount
                }
            }
            CenterPopup(
                onOpenMap = onOpenMap,
                state = state,
                onMapScreen = mapDisabledLatched.value,
                closeDisabled = closeDisabledLatched.value,
                onMinimize = { viewModel.setMinimized(true) },
                onClose = { showEndConfirm = true }
            )
        }
    }

    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text(stringResource(R.string.nav_end_title)) },
            text = { Text(stringResource(R.string.nav_end_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showEndConfirm = false
                    viewModel.endNavigation()
                }) { Text(stringResource(R.string.nav_end_confirm), color = MaterialTheme.appColors.statusDanger) }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** The centred cue card: a big rotating arrow on a translucent rounded panel. */
@Composable
private fun CenterPopup(
    state: NavState,
    onOpenMap: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onMapScreen: Boolean = false,
    closeDisabled: Boolean = false
) {
    // The popup is the inverse of the app background so it stands out: a
    // white card with black content over the dark dashboard (and vice versa).
    val panel = MaterialTheme.appColors.navPopupPanel
    val ink = MaterialTheme.appColors.navPopupInk
    val cue = when {
        state.offRoute -> MaterialTheme.appColors.statusDanger
        state.arrived -> MaterialTheme.appColors.statusGood
        else -> ink
    }
    val angle by animateFloatAsState(state.arrowAngleDeg(), tween(350), label = "arrow")

    Surface(
        modifier = Modifier
            .width(340.dp)
            .padding(8.dp)
            // Absorb taps so they don't fall through to the app underneath. A
            // no-op clickable does this cleanly, the perpetual event-consume
            // loop used before was cancelling the popup's own button taps.
            .clickable(
                interactionSource = remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                },
                indication = null
            ) {},
        shape = RoundedCornerShape(28.dp),
        color = panel,
        contentColor = ink,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        // A 1-stop route has no "Last stop" line, it is just
                        // noise; the count only earns its place from 2 stops up.
                        state.arrived || state.goalCount <= 1 -> ""
                        // "Last stop" only when the current goal really is the
                        // final one, otherwise show how many remain to visit.
                        state.goalIndex >= state.goalCount ->
                            stringResource(R.string.nav_last_stop)
                        else -> stringResource(
                            R.string.nav_stops_left,
                            state.goalCount - state.goalIndex + 1
                        )
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ink.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
                // Minimizes the popup and opens the full map screen.
                // Greyed out when the rider is already on the map -- the
                // tap would just minimize without any visible navigation.
                // `onMapScreen` is the latched flag so the icon stays
                // stable while the popup fades out.
                val mapButtonDisabled = onMapScreen
                IconButton(
                    onClick = { onMinimize(); onOpenMap() },
                    enabled = !mapButtonDisabled,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Map, stringResource(R.string.nav_map_style),
                        tint = if (mapButtonDisabled) ink.copy(alpha = 0.35f) else ink,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onMinimize, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Remove, stringResource(R.string.nav_minimize),
                        tint = ink, modifier = Modifier.size(22.dp)
                    )
                }
                // Greyed out only on the final arrival, when there is
                // no remaining non-passed stop and "end navigation"
                // would have no effect (nav is already tearing itself
                // down on the 9 s dismiss timer). Intermediate arrivals
                // keep the X enabled so the rider can still abort with
                // stops left ahead. `closeDisabled` is the latched
                // version so the icon doesn't snap back during the
                // popup's fade-out.
                IconButton(
                    onClick = onClose,
                    enabled = !closeDisabled,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Close, stringResource(R.string.nav_close),
                        tint = if (closeDisabled) MaterialTheme.appColors.statusDanger.copy(alpha = 0.35f) else MaterialTheme.appColors.statusDanger,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Icon(
                imageVector = if (state.arrived) Icons.Default.Flag else Icons.Default.Navigation,
                contentDescription = null,
                tint = cue,
                modifier = Modifier
                    .size(132.dp)
                    .rotate(if (state.arrived) 0f else angle)
            )

            Spacer(Modifier.height(6.dp))
            // One-line, brief cue: distance + the short direction / turn
            // phrase. The old layout had a verbose primary line ("Find stop
            // 3 head behind you on your right") + a giant separate distance
            // number, eating two lines for what reads better as "300 m
            // behind you on your right". maxLines=1 with ellipsis keeps the
            // popup compact even on a small phone.
            val cueLine = listOf(state.distanceText, state.primaryText)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (cueLine.isNotBlank()) {
                Text(
                    cueLine,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val sub = subline(state)
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cue,
                    textAlign = TextAlign.Center
                )
            }
            if (state.nextStreet.isNotBlank()) {
                Text(
                    state.nextStreet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Colour the cue takes: red when off-route, green on arrival, warmth-tinted in Treasure Hunt. */
@Composable
private fun cueColor(state: NavState, accent: Color): Color = when {
    state.offRoute -> MaterialTheme.appColors.statusDanger
    state.arrived -> MaterialTheme.appColors.statusGood
    state.mode == NavMode.TREASURE_HUNT -> when (state.proximity) {
        Proximity.HOT -> MaterialTheme.appColors.statusDanger
        Proximity.WARM -> MaterialTheme.appColors.statusWarn
        Proximity.COLD -> MaterialTheme.appColors.metricVoltage
        null -> accent
    }
    else -> accent
}

@Composable
private fun subline(state: NavState): String? = when {
    state.offRoute -> stringResource(R.string.nav_off_route)
    state.mode == NavMode.TREASURE_HUNT && state.proximity != null -> stringResource(
        when (state.proximity) {
            Proximity.HOT -> R.string.nav_prox_hot
            Proximity.WARM -> R.string.nav_prox_warm
            Proximity.COLD -> R.string.nav_prox_cold
        }
    )
    else -> null
}
