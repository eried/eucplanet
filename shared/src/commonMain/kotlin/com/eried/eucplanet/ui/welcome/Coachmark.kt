package com.eried.eucplanet.ui.welcome

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Registry of on-screen bounds for the welcome spotlight tour. Dashboard elements
 * tag themselves with [coachmark]; the [WelcomeWizard] reads these window-space
 * rects to cut a hole in its scrim over the real UI (Android's coachmark style).
 */
object CoachmarkTargets {
    val bounds = mutableStateMapOf<String, Rect>()
}

/** Tag a composable as a spotlight target. */
fun Modifier.coachmark(key: String): Modifier =
    this.onGloballyPositioned { CoachmarkTargets.bounds[key] = it.boundsInWindow() }
