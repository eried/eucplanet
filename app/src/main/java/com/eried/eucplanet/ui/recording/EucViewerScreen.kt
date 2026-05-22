package com.eried.eucplanet.ui.recording

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

/** Path the embedded viewer fetches the trip CSV from — intercepted locally. */
private const val TRIP_PATH = "/__euc_trip.csv"
private const val VIEWER_URL = "https://eucviewer.ried.no/?embedded"
private const val TAG = "EucViewer"

/**
 * Hosts the EUC Viewer (eucviewer.ried.no) embedded in a WebView and feeds it
 * the trip's CSV.
 *
 * The CSV is **not** inlined into an injected script — a long ride is several MB
 * of base64, and that overflowed `evaluateJavascript`, leaving a truncated
 * (syntax-broken) script that silently did nothing. Instead the app intercepts
 * a same-origin request for [TRIP_PATH] and serves the bytes directly; the
 * injected script just `fetch`es that URL and hands the data to the viewer's
 * documented `window.loadFileFromBase64` hook (see the viewer's INTEGRATION.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EucViewerScreen(
    tripId: Long,
    onBack: () -> Unit,
    viewModel: EucViewerViewModel = hiltViewModel()
) {
    // Read by shouldInterceptRequest on a background thread — hence atomic.
    val payloadRef = remember { AtomicReference<Pair<ByteArray, String>?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    LaunchedEffect(tripId) {
        val p = viewModel.tripPayload(tripId)
        payloadRef.set(p)
        fileName = p?.second
        Log.i(TAG, "trip $tripId payload: ${p?.first?.size ?: -1} bytes, name=${p?.second}")
    }

    LaunchedEffect(pageReady, fileName) {
        val name = fileName
        if (pageReady && name != null) {
            // Tiny script: poll for the hook, then fetch the intercepted CSV and
            // base64 it client-side via FileReader (handles any file size). The
            // trip file name carries no quotes, so a single-quoted literal is safe.
            val js = """
                (function(){
                  var n=0;
                  function go(){
                    if (typeof window.loadFileFromBase64 === 'function') {
                      fetch('$TRIP_PATH')
                        .then(function(r){ return r.blob(); })
                        .then(function(blob){
                          var fr=new FileReader();
                          fr.onload=function(){
                            var s=String(fr.result);
                            Promise.resolve(
                              window.loadFileFromBase64(s.substring(s.indexOf(',')+1), '$name')
                            ).then(function(res){
                              console.log('euc loadFileFromBase64 -> '+JSON.stringify(res));
                              // This WebView leaves #map at 0 height. Force it
                              // to fill the viewport with inline !important
                              // styles (nothing can override those), nudge
                              // Leaflet to re-measure, and collapse the viewer's
                              // Trip Explorer panel so the route is the focus.
                              function fix(){
                                var m=document.getElementById('map');
                                if(m){
                                  var s=m.style;
                                  s.setProperty('position','fixed','important');
                                  s.setProperty('top','0','important');
                                  s.setProperty('left','0','important');
                                  s.setProperty('width',window.innerWidth+'px','important');
                                  s.setProperty('height',window.innerHeight+'px','important');
                                }
                                var p=document.getElementById('trip-panel');
                                if(p) p.classList.remove('open');
                                window.dispatchEvent(new Event('resize'));
                              }
                              [0,200,500,1000,1700].forEach(function(d){
                                setTimeout(fix,d);
                              });
                              setTimeout(function(){
                                var m=document.getElementById('map');
                                var cs=m?getComputedStyle(m):null;
                                console.log('euc env inner='+window.innerWidth+'x'
                                  +window.innerHeight+' map='
                                  +(m?m.offsetWidth+'x'+m.offsetHeight:'none')
                                  +' cs='+(cs?cs.position+'/'+cs.height+'/'+cs.display:'?'));
                              },1900);
                            });
                          };
                          fr.readAsDataURL(blob);
                        })
                        .catch(function(e){ console.error('euc trip fetch failed: '+e); });
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
                title = { Text(stringResource(R.string.trip_action_view_online)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.studio_replay_cd_back)
                        )
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
                    // useWideViewPort=true gave the page a broken 731px-wide
                    // layout viewport and a 0-height #map. Off, the WebView
                    // uses its own size as the CSS viewport — a clean 1:1
                    // mobile layout, which is what the responsive viewer wants.
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (request?.url?.path == TRIP_PATH) {
                                val p = payloadRef.get()
                                if (p != null) {
                                    return WebResourceResponse(
                                        "text/csv", "UTF-8",
                                        ByteArrayInputStream(p.first)
                                    ).apply {
                                        responseHeaders =
                                            mapOf("Access-Control-Allow-Origin" to "*")
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady = true
                        }
                    }
                    // Surfaces the viewer's own console output (and our fetch
                    // errors) so a future breakage is diagnosable from logcat.
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            Log.i(TAG, "viewer: ${m.message()} (${m.lineNumber()})")
                            return true
                        }
                    }
                    loadUrl(VIEWER_URL)
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
