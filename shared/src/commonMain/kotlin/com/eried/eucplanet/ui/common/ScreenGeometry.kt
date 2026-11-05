package com.eried.eucplanet.ui.common

import androidx.compose.runtime.Composable

/** The window in dp and its orientation, what Android's Configuration gives. */
data class ScreenGeometry(val widthDp: Int, val heightDp: Int, val landscape: Boolean)

@Composable
expect fun screenGeometry(): ScreenGeometry
