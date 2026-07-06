package com.eried.eucplanet.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height cap for scrollable content inside a dialog. A fixed cap (474 dp
 * etc.) taller than the screen pushes the dialog's button row off tiny
 * displays, flip covers are only ~374 dp tall, so the preferred cap shrinks
 * to what the screen can host after [reservedDp] of dialog chrome (title,
 * buttons, paddings). Never returns less than 96 dp so at least a couple of
 * rows stay visible and scrollable.
 */
@Composable
fun dialogContentMaxHeight(preferredDp: Int, reservedDp: Int = 180): Dp {
    val screenH = LocalConfiguration.current.screenHeightDp
    return minOf(preferredDp, screenH - reservedDp).coerceAtLeast(96).dp
}
