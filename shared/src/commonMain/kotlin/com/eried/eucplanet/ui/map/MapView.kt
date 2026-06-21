package com.eried.eucplanet.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A live map. iOS renders the Leaflet [mapHtml] in a WKWebView (via Compose
 * `UIKitView`) and pushes the rider position with `evaluateJavaScript`; Android
 * shows a placeholder in the shared module (the Android app has its own map).
 *
 * @param recenterKey bump to force a recenter on the current fix.
 */
@Composable
expect fun MapView(
    modifier: Modifier,
    style: String,
    accentHex6: String,
    lat: Double?,
    lng: Double?,
    recenterKey: Int,
)
