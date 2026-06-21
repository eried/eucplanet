package com.eried.eucplanet.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Placeholder — the Android app renders its own map; the shared module only
 *  needs this to compile for the Android target. */
@Composable
actual fun MapView(modifier: Modifier, style: String, accentHex6: String, lat: Double?, lng: Double?, recenterKey: Int) {
    Box(modifier.background(Color(0xFF0D0D0D)), contentAlignment = Alignment.Center) {
        Text("Map", color = Color(0xFF888888))
    }
}
