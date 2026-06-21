@file:OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)

package com.eried.eucplanet.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

/** Leaflet map in a WKWebView. Position is pushed via evaluateJavaScript. */
@Composable
actual fun MapView(modifier: Modifier, style: String, accentHex6: String, lat: Double?, lng: Double?, recenterKey: Int) {
    val web = remember {
        WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = WKWebViewConfiguration()).apply {
            setOpaque(false)
            loadHTMLString(mapHtml(style, accentHex6), baseURL = null)
        }
    }
    UIKitView(
        factory = { web },
        modifier = modifier,
        update = { wv ->
            if (lat != null && lng != null) {
                wv.evaluateJavaScript("nativeSetUser($lat,$lng)", null)
                if (recenterKey != 0) wv.evaluateJavaScript("nativeRecenter($lat,$lng,16)", null)
            }
        },
    )
}
