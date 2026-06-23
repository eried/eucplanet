@file:OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class, BetaInteropApi::class)

package com.eried.eucplanet.ui.recording

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/** Injects the trip CSV (chunked, to dodge the evaluateJavaScript size limit) once
 *  the viewer page has loaded, then calls its loadFileFromBase64 hook. */
private class EucViewerNav(
    private val payloadBase64: String,
    private val fileName: String,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        webView.evaluateJavaScript("window.__eucTrip='';", null)
        var i = 0
        val chunk = 60_000
        val n = payloadBase64.length
        while (i < n) {
            val end = minOf(i + chunk, n)
            // base64 alphabet has no quotes/backslashes — safe in a single-quoted literal.
            webView.evaluateJavaScript("window.__eucTrip+='${payloadBase64.substring(i, end)}';", null)
            i = end
        }
        webView.evaluateJavaScript(eucViewerLoaderJs(fileName), null)
    }
}

@Composable
actual fun EucViewerWeb(payloadBase64: String, fileName: String, modifier: Modifier) {
    val delegate = remember(payloadBase64, fileName) { EucViewerNav(payloadBase64, fileName) }
    val web = remember {
        WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = WKWebViewConfiguration()).apply {
            navigationDelegate = delegate
            NSURL.URLWithString(EUC_VIEWER_URL)?.let { loadRequest(NSURLRequest.requestWithURL(it)) }
        }
    }
    UIKitView(factory = { web }, modifier = modifier, update = {})
}
