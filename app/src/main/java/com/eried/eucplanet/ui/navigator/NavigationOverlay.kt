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
import androidx.compose.ui.graphics.luminance
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
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentOrange
import com.eried.eucplanet.ui.theme.AccentRed
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

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // Timed-out or manually minimized → compact pill at the top. Hidden
        // during the "start riding" wait so nothing lingers at the top before
        // the rider gets moving.
        AnimatedVisibility(
            visible = state.active && !state.waiting &&
                (state.minimized || !popupShown),
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            MinimizedPill(state, onExpand = {
                viewModel.setMinimized(false)
                popGen++ // re-pop the centred card and restart its timeout
            })
        }
        // Active cue → big centred translucent popup.
        AnimatedVisibility(
            visible = state.active && !state.minimized && popupShown,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            CenterPopup(
                state = state,
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
                }) { Text(stringResource(R.string.nav_end_confirm), color = AccentRed) }
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
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    // The popup is the inverse of the app background so it stands out: a
    // white card with black content over the dark dashboard (and vice versa).
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val panel = if (dark) Color.White else Color(0xFF11151C)
    val ink = if (dark) Color(0xFF11151C) else Color.White
    val cue = when {
        state.offRoute -> AccentRed
        state.arrived -> AccentGreen
        else -> ink
    }
    val angle by animateFloatAsState(state.arrowAngleDeg(), tween(350), label = "arrow")

    Surface(
        modifier = Modifier
            .width(340.dp)
            .padding(8.dp)
            // Swallow gestures so they don't fall through to the app underneath.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
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
                val stopsLeft = state.goalCount - state.goalIndex
                Text(
                    when {
                        // A 1-stop route has no "Last stop" line — it is just
                        // noise; the count only earns its place from 2 stops up.
                        state.arrived || state.goalCount <= 1 -> ""
                        stopsLeft >= 2 -> stringResource(R.string.nav_stops_left, stopsLeft)
                        else -> stringResource(R.string.nav_last_stop)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ink.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onMinimize, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Remove, stringResource(R.string.nav_minimize),
                        tint = ink, modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Close, stringResource(R.string.nav_close),
                        tint = AccentRed, modifier = Modifier.size(22.dp)
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
            Text(
                state.primaryText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = ink,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (state.distanceText.isNotBlank()) {
                Text(
                    state.distanceText,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    textAlign = TextAlign.Center
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

@Composable
private fun MinimizedPill(state: NavState, onExpand: () -> Unit) {
    val cue = cueColor(state, MaterialTheme.colorScheme.primary)
    val angle by animateFloatAsState(state.arrowAngleDeg(), tween(350), label = "pillArrow")
    Surface(
        modifier = Modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(50))
            .clickable { onExpand() },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(cue.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (state.arrived) Icons.Default.Flag else Icons.Default.Navigation,
                    contentDescription = null,
                    tint = cue,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (state.arrived) 0f else angle)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                state.distanceText.ifBlank { state.primaryText },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = cue
            )
        }
    }
}

/** Colour the cue takes: red when off-route, green on arrival, warmth-tinted in Treasure Hunt. */
@Composable
private fun cueColor(state: NavState, accent: Color): Color = when {
    state.offRoute -> AccentRed
    state.arrived -> AccentGreen
    state.mode == NavMode.TREASURE_HUNT -> when (state.proximity) {
        Proximity.HOT -> AccentRed
        Proximity.WARM -> AccentOrange
        Proximity.COLD -> AccentBlue
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
