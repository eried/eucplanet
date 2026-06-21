package com.eried.eucplanet.ui.navigator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Placeholder for the shared Android target: the Android app ships its own
 * navigator (this shared route-builder map is consumed only by the iOS app).
 */
@Composable
actual fun NavMapView(
    mapType: String,
    controller: NavMapController,
    callbacks: NavMapCallbacks,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize().background(Color(0xFF15151B)))
}
