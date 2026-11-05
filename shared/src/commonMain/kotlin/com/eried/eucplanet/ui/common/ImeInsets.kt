package com.eried.eucplanet.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density

/**
 * Where the keyboard is heading, in px from the bottom: Android's
 * WindowInsets.imeAnimationTarget. iOS reports no separate target, so it
 * gives where the keyboard is, which equals the target once it settles.
 */
@Composable
expect fun imeTargetBottom(density: Density): Int
