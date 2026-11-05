package com.eried.eucplanet.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
actual fun screenGeometry(): ScreenGeometry {
    val c = LocalConfiguration.current
    return ScreenGeometry(
        widthDp = c.screenWidthDp,
        heightDp = c.screenHeightDp,
        landscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )
}
