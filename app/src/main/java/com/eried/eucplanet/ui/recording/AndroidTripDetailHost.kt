package com.eried.eucplanet.ui.recording

import android.annotation.SuppressLint
import android.os.Build
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.nav.offline.map.OfflineMapWeb

/** The trip viewer's Android side: the route map's WebView and the fullscreen map's window. */
@Composable
fun rememberAndroidTripDetailHost(): TripDetailHost = remember { AndroidTripDetailHost }

private object AndroidTripDetailHost : TripDetailHost {
    override val isDebugBuild: Boolean = BuildConfig.DEBUG

    override val leafletHead: String = TripMapHtml.LEAFLET_CDN_HEAD

    // Read when each page is built, as before: it carries the "prefer offline" setting.
    override val offlineMapJs: String get() = OfflineMapWeb.JS

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    @Composable
    override fun MapWebView(
        html: String,
        onPage: (TripMapPage) -> Unit,
        onLoaded: () -> Unit,
        modifier: Modifier,
    ) {
        // One page object per surface, so handing it over again on every
        // update is no change and nothing keyed on it reruns.
        val page = remember { WebViewPage() }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    val offlineMap = OfflineMapWeb.renderer()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            onLoaded()
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ) = OfflineMapWeb.intercept(offlineMap, request)
                    }
                    setBackgroundColor(android.graphics.Color.parseColor("#0b0f19"))
                    // Own drag gestures on the map: ask the Compose scroll
                    // container (which honours requestDisallowInterceptTouchEvent)
                    // not to steal them, so a one-finger drag pans the map instead
                    // of scrolling the page. Released on UP/CANCEL so a drag that
                    // starts off the map still scrolls the page normally.
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    page.view = this
                    onPage(page)
                }
            },
            // Deliberately does NOT reload on a content change. Reloading refits
            // the camera and flashes the tiles, so every trim yanked the map
            // away from wherever the rider had panned and zoomed it. Trace
            // changes go through setTrace() instead; the document is loaded
            // once, by the factory.
            update = { wv -> page.view = wv; onPage(page) },
            modifier = modifier
        )
    }

    @Composable
    override fun FullscreenMapWindow() {
        // usePlatformDefaultWidth = false alone still leaves the dialog window
        // short of the edges (most visible in landscape, where the content
        // behind shows in the system-bar insets around the map). Fill the
        // window, drop the scrim, draw edge-to-edge, and hide the system bars
        // so the map is truly immersive and nothing shows behind it. Swiping
        // from an edge brings the bars back transiently; closing restores them.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { w ->
                w.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                w.setDimAmount(0f)
                // Draw into the display cutout too, otherwise in landscape the
                // notch side stays letterboxed and the content behind shows.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    w.attributes = w.attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                WindowCompat.setDecorFitsSystemWindows(w, false)
                WindowInsetsControllerCompat(w, w.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
    }
}

private class WebViewPage : TripMapPage {
    var view: WebView? = null
    override fun evaluate(js: String) {
        view?.evaluateJavascript(js, null)
    }
}
