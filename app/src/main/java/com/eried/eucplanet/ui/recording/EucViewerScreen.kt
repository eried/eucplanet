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
            webView?.evaluateJavascript(
                "window.loadFileFromBase64('${p.first}','${p.second}')", null
            )
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
