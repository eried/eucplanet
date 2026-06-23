package com.eried.eucplanet.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Placeholder for the shared Android target (the Android app ships its own EUC Viewer). */
@Composable
actual fun EucViewerWeb(payloadBase64: String, fileName: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().background(Color(0xFF15151B)))
}
