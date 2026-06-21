package com.eried.eucplanet.ui.navigator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.NavMode
import com.eried.eucplanet.data.model.NavState
import com.eried.eucplanet.data.model.Proximity
import com.eried.eucplanet.data.model.arrowAngleDeg
import com.eried.eucplanet.ui.theme.appColors
import kotlinx.coroutines.delay

private const val POPUP_TIMEOUT_MS = 5_000L

/**
 * The floating navigation popup — faithful port of Android's NavigationOverlay.
 * Rendered above every screen while guidance runs: each new cue pops a big rotating
 * arrow card centred on a translucent panel, then times out to nothing (the engine
 * owns the pill/state); the X ends navigation after a confirm. Driven directly by
 * the [NavState] the [com.eried.eucplanet.nav.NavigationEngine] publishes.
 */
@Composable
fun NavigationOverlay(
    state: NavState,
    onMinimize: () -> Unit,
    onEndNav: () -> Unit,
    onOpenMap: () -> Unit,
    onCueVisible: (Boolean) -> Unit,
    suppressOnPhone: Boolean = false,
) {
    var showEndConfirm by remember { mutableStateOf(false) }
    var popGen by remember { mutableIntStateOf(0) }
    var popupShown by remember { mutableStateOf(false) }

    LaunchedEffect(state.primaryText, state.offRoute, state.arrived, state.popupTick) {
        if (state.active) popGen++
    }
    LaunchedEffect(popGen, state.arrived, state.active) {
        if (!state.active) { popupShown = false; return@LaunchedEffect }
        popupShown = true
        if (!state.arrived) { delay(POPUP_TIMEOUT_MS); popupShown = false }
    }
    LaunchedEffect(popupShown) { onCueVisible(popupShown) }
    DisposableEffect(Unit) { onDispose { onCueVisible(false) } }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val shouldSuppress = suppressOnPhone && !state.arrived
        AnimatedVisibility(
            visible = !shouldSuppress && state.active && !state.minimized && popupShown,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            CenterPopup(
                state = state,
                onOpenMap = onOpenMap,
                onMapScreen = suppressOnPhone,
                closeDisabled = state.arrived && state.goalIndex >= state.goalCount,
                onMinimize = onMinimize,
                onClose = { showEndConfirm = true },
            )
        }
    }

    if (showEndConfirm) {
        val c = MaterialTheme.appColors
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("End navigation?") },
            text = { Text("Stop guidance for the active route?") },
            confirmButton = {
                TextButton(onClick = { showEndConfirm = false; onEndNav() }) {
                    Text("End", color = c.statusDanger)
                }
            },
            dismissButton = { TextButton(onClick = { showEndConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CenterPopup(
    state: NavState,
    onOpenMap: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onMapScreen: Boolean = false,
    closeDisabled: Boolean = false,
) {
    val c = MaterialTheme.appColors
    val panel = c.navPopupPanel
    val ink = c.navPopupInk
    val cue = when {
        state.offRoute -> c.statusDanger
        state.arrived -> c.statusGood
        else -> ink
    }
    val angle by animateFloatAsState(state.arrowAngleDeg(), tween(350), label = "arrow")

    Surface(
        modifier = Modifier.width(340.dp).padding(8.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        shape = RoundedCornerShape(28.dp),
        color = panel,
        contentColor = ink,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.arrived || state.goalCount <= 1 -> ""
                        state.goalIndex >= state.goalCount -> "Last stop"
                        else -> "${state.goalCount - state.goalIndex + 1} stops left"
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = ink.copy(alpha = 0.7f), modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMinimize(); onOpenMap() }, enabled = !onMapScreen, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Map, null, tint = if (onMapScreen) ink.copy(alpha = 0.35f) else ink, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onMinimize, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Remove, null, tint = ink, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onClose, enabled = !closeDisabled, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Close, null,
                        tint = if (closeDisabled) c.statusDanger.copy(alpha = 0.35f) else c.statusDanger,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Icon(
                imageVector = if (state.arrived) Icons.Filled.Flag else Icons.Filled.Navigation,
                contentDescription = null, tint = cue,
                modifier = Modifier.size(132.dp).rotate(if (state.arrived) 0f else angle),
            )

            Spacer(Modifier.height(6.dp))
            val cueLine = listOf(state.distanceText, state.primaryText).filter { it.isNotBlank() }.joinToString(" ")
            if (cueLine.isNotBlank()) {
                Text(cueLine, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ink, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            subline(state)?.let { Text(it, fontSize = 16.sp, color = cue, textAlign = TextAlign.Center) }
            if (state.nextStreet.isNotBlank()) {
                Text(state.nextStreet, fontSize = 14.sp, color = ink.copy(alpha = 0.6f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun subline(state: NavState): String? = when {
    state.offRoute -> "Off route"
    state.mode == NavMode.TREASURE_HUNT && state.proximity != null -> when (state.proximity) {
        Proximity.HOT -> "Red hot"
        Proximity.WARM -> "Warmer"
        Proximity.COLD -> "Colder"
        null -> null
    }
    else -> null
}
