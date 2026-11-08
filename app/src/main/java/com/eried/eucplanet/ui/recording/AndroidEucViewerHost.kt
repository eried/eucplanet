package com.eried.eucplanet.ui.recording

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

private const val TAG = "EucViewer"

/**
 * Android's side of View online: the EUC Viewer in a WebView that answers a
 * same-origin request for [EucViewerJs.TRIP_PATH] with the trip's bytes, so
 * the page fetches the CSV rather than having it inlined into a script. A
 * long ride is several MB of base64, and inlined it overflowed
 * `evaluateJavascript`, leaving a truncated script that silently did nothing.
 */
object AndroidEucViewerHost : EucViewerHost {

    @Composable
    override fun ViewerWebView(
        url: String,
        payload: () -> EucViewerPayload?,
        onPage: (TripMapPage) -> Unit,
        onLoaded: () -> Unit,
        modifier: Modifier,
    ) {
        // Read by shouldInterceptRequest on a background thread.
        val trip = rememberUpdatedState(payload)
        val loaded = rememberUpdatedState(onLoaded)
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // This WebView lays the viewer's page out at a wider
                    // viewport than the screen; overview mode scales the whole
                    // page down to fit so nothing is clipped off-screen.
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // Translucent root surface so the WebView's compositor
                    // can layer backdrop-filter over the host. Do NOT
                    // force a hardware layer on the Android View itself
                    // -- that pre-rasterises the WebView into a single
                    // texture and flattens away the internal compositor
                    // layers backdrop-filter needs to sample. The native
                    // WebView is already GPU-composited at the Chromium
                    // level; piling LAYER_TYPE_HARDWARE on top breaks
                    // backdrop-filter rather than helping it.
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (request?.url?.path == EucViewerJs.TRIP_PATH) {
                                val p = trip.value()
                                if (p != null) {
                                    return WebResourceResponse(
                                        "text/csv", "UTF-8",
                                        ByteArrayInputStream(p.bytes)
                                    ).apply {
                                        responseHeaders =
                                            mapOf("Access-Control-Allow-Origin" to "*")
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loaded.value()
                        }

                        // Resource load failures (CDN 404, CORS, etc.). The
                        // viewer pulls leaflet, jszip and its own static
                        // assets from external hosts -- any one of those
                        // failing makes the page silently render half-broken.
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            Log.w(
                                TAG,
                                "RESOURCE-ERR url=${request?.url} " +
                                    "code=${error?.errorCode} " +
                                    "desc=${error?.description}"
                            )
                        }

                        // HTTP responses with 4xx/5xx codes. Same logging
                        // path as above; without this the resource just
                        // fails quietly.
                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            Log.w(
                                TAG,
                                "HTTP-ERR url=${request?.url} " +
                                    "status=${errorResponse?.statusCode} " +
                                    "reason=${errorResponse?.reasonPhrase}"
                            )
                        }
                    }
                    // Surfaces the viewer's full console (errors + warnings
                    // included). Anything thrown inside loadFileFromBase64
                    // or the panel-open path lands here; otherwise the
                    // viewer just sits there with no visible feedback.
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            val level = when (m.messageLevel()) {
                                ConsoleMessage.MessageLevel.ERROR -> "E"
                                ConsoleMessage.MessageLevel.WARNING -> "W"
                                ConsoleMessage.MessageLevel.TIP -> "T"
                                ConsoleMessage.MessageLevel.LOG -> "I"
                                ConsoleMessage.MessageLevel.DEBUG -> "D"
                                else -> "?"
                            }
                            Log.i(
                                TAG,
                                "JS[$level] ${m.sourceId()}:${m.lineNumber()} ${m.message()}"
                            )
                            return true
                        }
                    }
                    loadUrl(url)
                    onPage(TripMapPage { js -> evaluateJavascript(js, null) })
                }
            },
            onRelease = { wv ->
                wv.loadUrl("about:blank")
                wv.destroy()
            }
        )
    }

    /** The page fetches the intercepted path; nothing to hand over first. */
    override fun tripSource(page: TripMapPage, payload: EucViewerPayload): String = EucViewerJs.FETCH_SOURCE
}
