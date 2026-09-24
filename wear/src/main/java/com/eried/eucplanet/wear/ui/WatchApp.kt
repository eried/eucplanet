package com.eried.eucplanet.wear.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.eried.eucplanet.wear.bridge.WatchMapRepository
import com.eried.eucplanet.wear.bridge.WatchState
import com.eried.eucplanet.wear.bridge.WatchStateRepository
import com.eried.eucplanet.wear.ui.components.DetailsScreen
import com.eried.eucplanet.wear.ui.components.DetailsScreenPreview
import com.eried.eucplanet.wear.ui.components.MainScreen
import com.eried.eucplanet.wear.ui.components.MainScreenPreview
import com.eried.eucplanet.wear.ui.components.WatchMapPreview
import com.eried.eucplanet.wear.ui.components.WatchMapScreen
import com.eried.eucplanet.wear.ui.components.preview.WatchLargeRoundPreview
import com.eried.eucplanet.wear.ui.components.preview.WatchPreviewFixtures
import com.eried.eucplanet.wear.ui.utils.LocalWatchColors
import com.eried.eucplanet.wear.ui.utils.WatchTheme
import com.eried.eucplanet.wear.ui.utils.vibrate
import kotlinx.coroutines.flow.distinctUntilChanged


/**
 * Two dashboard pages plus the optional phone-backed map page.
 */
@Composable
fun WatchApp() {
    val state by WatchStateRepository.state.collectAsStateWithLifecycle()
    val mapSnapshot by WatchMapRepository.snapshot.collectAsStateWithLifecycle()
    val mapZoom by WatchMapRepository.zoom.collectAsStateWithLifecycle()
    val mapForeground by WatchMapRepository.foreground.collectAsStateWithLifecycle()
    WatchTheme(state) { accent ->
        val colors = LocalWatchColors.current
        Scaffold(timeText = {}) {
            Box(modifier = Modifier.fillMaxSize()) {
                var renderedPageCount by remember {
                    mutableIntStateOf(if (state.watchMapEnabled) 3 else 2)
                }
                var renderMapContent by remember { mutableStateOf(state.watchMapEnabled) }
                val hPager = rememberPagerState(pageCount = { renderedPageCount })

                LaunchedEffect(state.watchMapEnabled) {
                    if (state.watchMapEnabled) {
                        renderedPageCount = 3
                        renderMapContent = true
                    } else {
                        WatchMapRepository.setMapPageSelected(false)
                        renderMapContent = false
                        if (hPager.currentPage >= 2) hPager.scrollToPage(0)
                        renderedPageCount = 2
                    }
                }
                LaunchedEffect(hPager, state.watchMapEnabled) {
                    snapshotFlow { hPager.settledPage }
                        .distinctUntilChanged()
                        .collect { page ->
                            WatchMapRepository.setMapPageSelected(
                                state.watchMapEnabled && page == 2,
                            )
                        }
                }

                HorizontalPager(state = hPager, modifier = Modifier.fillMaxSize()) { page ->
                    when (page) {
                        0 -> MainScreen(state, accent)
                        1 -> DetailsScreen(state, accent)
                        2 -> if (state.watchMapEnabled && renderMapContent) {
                            WatchMapScreen(
                                snapshot = mapSnapshot,
                                zoom = mapZoom,
                                onZoomChange = WatchMapRepository::setZoom,
                                showTelemetry = state.mapShowTelemetry,
                            )
                        } else {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(colors.background)
                            )
                        }
                    }
                }

                LaunchedEffect(
                    state.watchMapEnabled,
                    mapForeground,
                    mapSnapshot.linkLive,
                    mapSnapshot.frame?.navigationSessionId,
                    mapSnapshot.frame?.navigationActive,
                ) {
                    val frame = mapSnapshot.frame
                    val sessionId = frame?.navigationSessionId.orEmpty()
                    if (state.watchMapEnabled && mapForeground && mapSnapshot.linkLive &&
                        frame?.enabled == true && frame.navigationActive &&
                        sessionId.isNotBlank() &&
                        WatchMapRepository.claimAutoOpen(sessionId)
                    ) {
                        renderedPageCount = 3
                        renderMapContent = true
                        hPager.animateScrollToPage(2)
                    }
                }

                MapCueHaptics(
                    enabled = state.watchMapEnabled,
                    foreground = mapForeground,
                    snapshot = mapSnapshot,
                )

                AnimatedVisibility(
                    visible = state.navActive && !state.watchMapEnabled,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    NavWatchOverlay(state, accent)
                }
            }
        }
    }
}

@WatchLargeRoundPreview
@Composable
private fun WatchAppMainPreview() {
    MainScreenPreview(WatchPreviewFixtures.live)
}

@WatchLargeRoundPreview
@Composable
private fun WatchAppDetailsPreview() {
    DetailsScreenPreview(WatchPreviewFixtures.live)
}

@WatchLargeRoundPreview
@Composable
private fun WatchAppMapPreview() {
    WatchMapPreview(WatchPreviewFixtures.live)
}


@Composable
private fun MapCueHaptics(
    enabled: Boolean,
    foreground: Boolean,
    snapshot: com.eried.eucplanet.hud.protocol.WatchMapSnapshot,
) {
    val context = LocalContext.current
    var baseline by remember { mutableStateOf<String?>(null) }
    var lastVibrateAt by remember { mutableStateOf(0L) }
    val primary = snapshot.frame?.cue?.primary.orEmpty()
    val eligible = enabled && foreground && snapshot.linkLive && snapshot.fixLive &&
            primary.isNotBlank()
    LaunchedEffect(eligible, primary) {
        if (!eligible) {
            baseline = null
            return@LaunchedEffect
        }
        val previous = baseline
        if (previous == null) {
            baseline = primary
            return@LaunchedEffect
        }
        if (previous != primary) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastVibrateAt >= 1_500L) {
                vibrate(context, 160L)
                lastVibrateAt = now
            }
            baseline = primary
        }
    }
}

/**
 * The watch's simplified navigation popup: a full-screen card with the big
 * direction arrow and two text lines pushed down from the phone. The wrist
 * buzzes whenever the instruction changes.
 */
@Composable
private fun NavWatchOverlay(state: WatchState, accent: Color) {
    val context = LocalContext.current
    val colors = LocalWatchColors.current
    val angle by animateFloatAsState(
        targetValue = if (state.navArrived) 0f else state.navAngle,
        animationSpec = tween(durationMillis = 350),
        label = "navArrow"
    )
    var lastPrimary by remember { mutableStateOf("") }
    var lastVibrateAt by remember { mutableStateOf(0L) }
    LaunchedEffect(state.navPrimary) {
        val now = System.currentTimeMillis()
        if (state.navPrimary.isNotBlank() && lastPrimary.isNotBlank() &&
            state.navPrimary != lastPrimary && now - lastVibrateAt > 1500L
        ) {
            vibrate(context, 160L)
            lastVibrateAt = now
        }
        lastPrimary = state.navPrimary
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            // Consume every gesture so a swipe can't reach the pager behind
            // the popup while navigation is showing.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = if (state.navArrived) Icons.Filled.Flag
                else Icons.Filled.Navigation,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .size(74.dp)
                    .rotate(if (state.navArrived) 0f else angle)
            )
            Spacer(Modifier.height(8.dp))
            if (state.navPrimary.isNotBlank()) {
                Text(
                    text = state.navPrimary,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    fontSize = 17.sp,
                    maxLines = 2
                )
            }
            if (state.navDistance.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.navDistance,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
            }
        }
    }
}
