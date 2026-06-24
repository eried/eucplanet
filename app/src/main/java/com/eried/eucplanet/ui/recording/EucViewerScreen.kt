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

/** Path the embedded viewer fetches the trip CSV from, intercepted locally. */
private const val TRIP_PATH = "/__euc_trip.csv"
private const val VIEWER_URL = "https://eucviewer.ried.no/?embedded"
private const val TAG = "EucViewer"

/**
 * Hosts the EUC Viewer (eucviewer.ried.no) embedded in a WebView and feeds it
 * the trip's CSV.
 *
 * The CSV is **not** inlined into an injected script, a long ride is several MB
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
    // Read by shouldInterceptRequest on a background thread, hence atomic.
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
                  // Route uncaught errors + promise rejections through
                  // console.error so they reach logcat via the WebChrome-
                  // Client's onConsoleMessage. Without these, an exception
                  // thrown inside loadFileFromBase64 or the panel-open
                  // flow vanishes silently.
                  window.addEventListener('error', function(e){
                    console.error('window.error: ' + (e.message || e) +
                      ' @' + (e.filename || '?') + ':' + (e.lineno || '?'));
                  });
                  window.addEventListener('unhandledrejection', function(e){
                    var r = e && e.reason;
                    console.error('unhandledrejection: ' +
                      ((r && r.stack) ? r.stack : (r && r.message) ? r.message : r));
                  });
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
                              // Diagnostic: inspect parsed trips + panel DOM
                              // state so we can tell whether the panel is
                              // simply empty (trip has 0 timeseries points)
                              // or actually unrenderable (DOM broken).
                              setTimeout(function(){
                                try {
                                  var at = window.allTracks;
                                  var atSummary = '(missing)';
                                  if (Array.isArray(at)) {
                                    atSummary = 'len=' + at.length;
                                    if (at[0]) {
                                      var t = at[0];
                                      atSummary += ' track0={'+
                                        'date:'+JSON.stringify(t.date)+','+
                                        'name:'+JSON.stringify(t.name||t.id||'')+','+
                                        'stats:'+JSON.stringify(t.stats||{})+','+
                                        'timeseries:'+
                                          (Array.isArray(t.timeseries)?
                                            ('len='+t.timeseries.length):typeof t.timeseries)+
                                      '}';
                                    }
                                  }
                                  console.log('euc allTracks: ' + atSummary);
                                  var p = document.getElementById('trip-panel');
                                  var b = document.getElementById('panel-body');
                                  var pTab = document.getElementById('panel-tab');
                                  var m = document.getElementById('map');
                                  function info(el, name){
                                    if (!el) return name + '=null';
                                    var r = el.getBoundingClientRect();
                                    var cs = getComputedStyle(el);
                                    return name + ' cls=[' + el.className + ']'+
                                      ' rect=' + Math.round(r.left)+','+Math.round(r.top)+
                                      '/' + Math.round(r.width)+'x'+Math.round(r.height)+
                                      ' display=' + cs.display +
                                      ' visibility=' + cs.visibility +
                                      ' opacity=' + cs.opacity +
                                      ' transform=' + cs.transform +
                                      ' zIndex=' + cs.zIndex;
                                  }
                                  console.log('euc DOM ' + info(p, 'trip-panel'));
                                  console.log('euc DOM ' + info(pTab, 'panel-tab'));
                                  console.log('euc DOM ' + info(b, 'panel-body'));
                                  console.log('euc DOM ' + info(m, 'map'));
                                  console.log('euc viewport ' +
                                    window.innerWidth+'x'+window.innerHeight);
                                } catch (e) {
                                  console.error('euc diag failed: ' + e.message);
                                }
                              }, 500);
                              // WebView still leaves #map at 0 height under
                              // useWideViewPort -- inset:0 is computed
                              // against the wide-viewport layout box which
                              // doesn't always match the visible WebView.
                              // Force explicit width/height in px so the
                              // tile container resolves. We keep #map's
                              // own position:absolute (NOT fixed -- fixed
                              // pulled it out of the body's stacking
                              // context and started covering the Trip
                              // Explorer panel + tab). Crucially we do
                              // NOT touch #trip-panel: its slide-in/out
                              // is owned by the viewer's own JS.
                              // Explicit 100% scale: set the viewport meta
                              // ourselves so the WebView's own zoom math
                              // can't decide to scale the page (overview
                              // mode or auto-fit). user-scalable=no also
                              // disables the rider's accidental pinch
                              // zoom on the embedded viewer. ZOOM=1.0
                              // is native; tweak this single constant if
                              // we want to dial in zoom-out later.
                              (function(){
                                var ZOOM = 1.0;
                                var meta = document.querySelector('meta[name="viewport"]');
                                if (meta) {
                                  var w = Math.round(window.innerWidth / ZOOM);
                                  meta.setAttribute('content',
                                    'width=' + w +
                                    ', initial-scale=1.0' +
                                    ', user-scalable=no');
                                }
                              })();
                              function fix(){
                                var h = window.innerHeight + 'px';
                                var w = window.innerWidth + 'px';
                                var m=document.getElementById('map');
                                if(m){
                                  var ms=m.style;
                                  ms.setProperty('width',w,'important');
                                  ms.setProperty('height',h,'important');
                                }
                                // #trip-panel uses top:0/bottom:0 to size to
                                // viewport. WebView with useWideViewPort
                                // computes that to height:0; force it
                                // explicitly so the panel actually fills the
                                // screen (and so the panel-body's children
                                // flex out to fill, instead of collapsing).
                                var p=document.getElementById('trip-panel');
                                if(p){
                                  p.style.setProperty('height',h,'important');
                                }
                                var pb=document.getElementById('panel-body');
                                if(pb){
                                  pb.style.setProperty('height',h,'important');
                                }
                                window.dispatchEvent(new Event('resize'));
                              }
                              [0,200,500,1000,1700].forEach(function(d){
                                setTimeout(fix,d);
                              });
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
      androidx.compose.foundation.layout.Box(
          modifier = Modifier.fillMaxSize().padding(pad)
      ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
}
