@file:OptIn(ExperimentalEncodingApi::class)

package com.eried.eucplanet.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import com.eried.eucplanet.ui.ScreenTopBar
import com.eried.eucplanet.ui.theme.appColors
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Hosts the EUC Viewer (eucviewer.ried.no) in a WebView and feeds it the trip's
 * CSV — port of Android's EucViewerScreen. Android intercepts a same-origin
 * request for the CSV (`shouldInterceptRequest`); WKWebView can't intercept HTTPS
 * sub-resources, so iOS injects the base64 CSV directly (chunked, to dodge the
 * evaluateJavaScript size limit) and calls the viewer's `loadFileFromBase64` hook.
 */
@Composable
fun EucViewerScreen(csv: String, fileName: String, onBack: () -> Unit) {
    val c = MaterialTheme.appColors
    val payloadBase64 = remember(csv) { Base64.encode(csv.encodeToByteArray()) }
    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, "View online", onBack)
        EucViewerWeb(payloadBase64, fileName, Modifier.fillMaxSize())
    }
}

/** The embedded viewer URL + the JS hook the viewer exposes (see its INTEGRATION.md). */
internal const val EUC_VIEWER_URL = "https://eucviewer.ried.no/?embedded"

/** Loader injected after the page loads: poll for the hook, then hand it the CSV. */
internal fun eucViewerLoaderJs(fileName: String): String {
    val safeName = fileName.replace("'", "")
    return """
        (function(){
          var n=0;
          function go(){
            if (typeof window.loadFileFromBase64 === 'function') {
              try { window.loadFileFromBase64(window.__eucTrip || '', '$safeName'); } catch(e){}
              var fix=function(){
                var h=window.innerHeight+'px', w=window.innerWidth+'px';
                var m=document.getElementById('map'); if(m){m.style.setProperty('width',w,'important');m.style.setProperty('height',h,'important');}
                var p=document.getElementById('trip-panel'); if(p){p.style.setProperty('height',h,'important');}
                window.dispatchEvent(new Event('resize'));
              };
              [0,300,800,1500].forEach(function(d){ setTimeout(fix,d); });
            } else if (n++ < 150) { setTimeout(go, 100); }
          }
          go();
        })();
    """.trimIndent()
}

@Composable
expect fun EucViewerWeb(payloadBase64: String, fileName: String, modifier: Modifier)
