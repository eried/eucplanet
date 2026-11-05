package com.eried.eucplanet.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/** The window's own size; iOS has no separate orientation flag to read. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun screenGeometry(): ScreenGeometry {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val w = (size.width / density).toInt()
    val h = (size.height / density).toInt()
    return ScreenGeometry(widthDp = w, heightDp = h, landscape = w > h)
}
