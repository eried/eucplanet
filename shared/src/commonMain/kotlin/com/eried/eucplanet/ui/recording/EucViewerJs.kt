package com.eried.eucplanet.ui.recording

/**
 * The script the embedded EUC Viewer runs once its page has loaded: wait for
 * the viewer's `window.loadFileFromBase64` hook, get the trip as base64 from
 * the platform's [source] and hand it over, then size the map and the trip
 * panel to the web view (the viewer's own layout leaves them at zero height
 * in an embedded view) and log what loaded, for the platform's console.
 */
object EucViewerJs {
    const val VIEWER_URL = "https://eucviewer.ried.no/?embedded"

    /** The path Android's WebView serves the trip CSV on, intercepted locally. */
    const val TRIP_PATH = "/__euc_trip.csv"

    /**
     * [source] is a statement that ends in `deliver(base64)`. The trip file
     * name carries no quotes, so a single-quoted literal is safe.
     */
    fun load(name: String, source: String): String = """
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
          function deliver(b64){
            Promise.resolve(
              window.loadFileFromBase64(b64, '$name')
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
          }
          function go(){
            if (typeof window.loadFileFromBase64 === 'function') {
              $source
            } else if (n++ < 150) {
              setTimeout(go, 100);
            }
          }
          go();
        })();
    """.trimIndent()

    /**
     * Android's source: fetch the intercepted [TRIP_PATH] and base64 it in the
     * page through FileReader, which takes any size.
     */
    val FETCH_SOURCE: String = "fetch('$TRIP_PATH')" +
        ".then(function(r){ return r.blob(); })" +
        ".then(function(blob){" +
        " var fr=new FileReader();" +
        " fr.onload=function(){ var s=String(fr.result); deliver(s.substring(s.indexOf(',')+1)); };" +
        " fr.readAsDataURL(blob);" +
        " })" +
        ".catch(function(e){ console.error('euc trip fetch failed: '+e); });"
}
