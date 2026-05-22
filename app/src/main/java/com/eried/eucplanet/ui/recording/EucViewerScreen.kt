package com.eried.eucplanet.ui.recording

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Hosts the EUC Viewer (eucviewer.ried.no) embedded in a WebView and hands it
 * the trip's CSV via the documented `window.loadFileFromBase64` JS hook — no
 * upload, the data is injected client-side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EucViewerScreen(
    tripId: Long,
    onBack: () -> Unit,
    viewModel: EucViewerViewModel = hiltViewModel()
) {
    var payload by remember { mutableStateOf<Pair<String, String>?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    LaunchedEffect(tripId) { payload = viewModel.tripPayload(tripId) }
    LaunchedEffect(pageReady, payload) {
        val p = payload
        if (pageReady && p != null) {
            // onPageFinished fires before the viewer's own scripts have run, so
            // window.loadFileFromBase64 often isn't defined yet — poll for the
            // hook (up to ~15 s) before handing over the CSV. base64 + the trip
            // file name contain no quotes, so a single-quoted literal is safe.
            val js = """
                (function(){
                  var b64='${p.first}', name='${p.second}', n=0;
                  function go(){
                    if (typeof window.loadFileFromBase64 === 'function') {
                      window.loadFileFromBase64(b64, name);
                    } else if (n++ < 150) {
                      setTimeout(go, 100);
                    }
                  }
                  go();
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js, null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("View online") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(pad),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Desktop mode, zoomed out — the viewer's full layout fits
                    // the screen instead of a cramped mobile view.
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.userAgentString =
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady = true
                        }
                    }
                    loadUrl("https://eucviewer.ried.no/?embedded")
                    webView = this
                }
            },
            onRelease = { wv ->
                wv.loadUrl("about:blank")
                wv.destroy()
            }
        )
    }
}
