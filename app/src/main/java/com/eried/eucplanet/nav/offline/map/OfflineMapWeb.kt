package com.eried.eucplanet.nav.offline.map

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * The offline map in the Leaflet maps (navigator, trip details, offline
 * areas). The page asks for `https://offline-map.eucplanet/z/x/y.png`, the
 * WebView hands that to [intercept], and the phone draws the tile. The layer
 * sits under the online map, so it only shows where online tiles fail, and
 * the credit line says so while it does.
 */
object OfflineMapWeb {

    const val HOST = "offline-map.eucplanet"
    const val NAME = "Offline map"

    /** Tiles are drawn at twice the size Leaflet shows them, so text stays
     *  sharp on a phone screen. */
    private const val PIXELS = 512

    /** A drawn tile for an offline map request, an empty 404 when no square
     *  has it, or null for any other request. */
    fun intercept(renderer: OfflineMapRenderer, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.host != HOST) return null
        val parts = url.pathSegments
        val z = parts.getOrNull(0)?.toIntOrNull()
        val x = parts.getOrNull(1)?.toIntOrNull()
        val y = parts.getOrNull(2)?.substringBefore('.')?.toIntOrNull()
        val png = if (z != null && x != null && y != null) runCatching { renderer.png(z, x, y, PIXELS) }.onFailure { android.util.Log.w("OfflineMap", "$z/$x/$y: $it") }.getOrNull() else null
        return if (png != null) {
            WebResourceResponse("image/png", null, 200, "OK", HEADERS, ByteArrayInputStream(png))
        } else {
            WebResourceResponse("image/png", null, 404, "Not Found", HEADERS, ByteArrayInputStream(ByteArray(0)))
        }
    }

    /** The app's renderer, for WebViews built outside Koin's reach. */
    fun renderer(): OfflineMapRenderer = org.koin.core.context.GlobalContext.get().get()

    private val HEADERS = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")

    /** "Prefer offline" is on: downloaded squares show their own map over
     *  the online one. Kept in step with the setting by [MapTileCache]. */
    @Volatile
    var prefer = false

    /**
     * Defines `eucOfflineMap(map)` on the page. It adds the offline layer and
     * returns `{layer, prefer, watch(onlineLayer)}`: call `watch` with each
     * online base layer. While the offline map shows, the credit becomes a
     * pill reading "Offline map" in the credit's usual place, and the page
     * tells `AndroidNav.onOfflineMap(on)` if it has one. Without [prefer]
     * that is while the online tiles fail; with it the offline layer sits on
     * top (clear outside the downloaded squares) and it is while a downloaded
     * square is in view.
     */
    val JS get() = """
window.eucOfflineMap = function(map, extra) {
  var PILL = '<span class="euc-off">$NAME</span>';
  var OFF = PILL + ' · © OpenStreetMap contributors';
  var PREFER = $prefer;
  if (!document.getElementById('euc-off-css')) {
    var css = document.createElement('style');
    css.id = 'euc-off-css';
    // Over the map like the credit itself: dark plate, light text, so it
    // reads on a pale street map and on satellite imagery alike.
    css.textContent = '.euc-off{display:inline-block;background:rgba(20,24,32,0.85);color:#fff !important;' +
      'text-shadow:none !important;font:700 11px sans-serif;padding:6px 3px;border-radius:10px;margin:2px 0;' +
      'border:1px solid var(--accent, #4FC3F7);}';
    document.head.appendChild(css);
  }
  var off = L.tileLayer('https://$HOST/{z}/{x}/{y}.png', Object.assign({maxZoom: 21, maxNativeZoom: 20, zIndex: PREFER ? 1000 : 0}, extra || {})).addTo(map);
  var told = null;
  function tell(on) {
    if (on === told) return;
    told = on;
    try { if (window.AndroidNav && AndroidNav.onOfflineMap) AndroidNav.onOfflineMap(on); } catch (e) {}
  }
  if (PREFER) {
    // Both maps show (online outside the downloaded squares), so both
    // credits stay while a square is in view, without naming OpenStreetMap
    // twice.
    var credit = '', attr = '';
    function credit_(c) {
      var ac = map.attributionControl;
      if (!ac || c === credit) return;
      if (credit) ac.removeAttribution(credit);
      if (c) ac.addAttribution(c);
      credit = c;
    }
    function update() {
      var drawn = false, z = map.getZoom();
      for (var k in off._tiles) {
        var t = off._tiles[k];
        if (Math.abs(t.coords.z - z) < 1 && t.el.naturalWidth > 0) { drawn = true; break; }
      }
      credit_(drawn ? (attr.indexOf('OpenStreetMap contributors') >= 0 ? PILL : OFF) : '');
      tell(drawn);
    }
    off.on('load', update);
    off.on('tileload', update);
    return { layer: off, prefer: true, watch: function(layer) {
      attr = layer.options.attribution || '';
      update();
    } };
  }
  var lastErr = 0, shown = false, attr = '', taken = 0, base = null;
  function show(on) {
    var ac = map.attributionControl;
    if (on === shown || !ac) return;
    shown = on;
    // Label layers add the same credit again and Leaflet counts each one:
    // take all of them away, and give the same number back.
    if (on) {
      taken = attr ? (ac._attributions[attr] || 0) : 0;
      for (var i = 0; i < taken; i++) ac.removeAttribution(attr);
      ac.addAttribution(OFF);
    } else {
      ac.removeAttribution(OFF);
      for (var j = 0; j < taken; j++) ac.addAttribution(attr);
      taken = 0;
    }
    // A style's label layers (same credit as its base) keep tiles from the
    // browser cache; over the offline map they would print every name twice.
    map.eachLayer(function(l) {
      if (l !== off && l !== base && l instanceof L.TileLayer && l.options.attribution === attr) l.setOpacity(on ? 0 : 1);
    });
    tell(on);
  }
  // Leaflet never asks again for a tile that failed: while the offline map
  // shows, try the online layers again when the signal comes back, and now
  // and then in case that event never fires.
  function retry() {
    if (!shown) return;
    map.eachLayer(function(l) { if (l !== off && l instanceof L.TileLayer) l.redraw(); });
  }
  window.addEventListener('online', retry);
  setInterval(retry, 30000);
  return {
    layer: off,
    prefer: false,
    watch: function(layer) {
      attr = layer.options.attribution || '';
      base = layer;
      if (shown) {
        // A new style while offline: once its label layers are on too.
        shown = false; taken = 0;
        map.attributionControl && map.attributionControl.removeAttribution(OFF);
        setTimeout(function() { show(true); }, 0);
      }
      layer.on('tileerror', function() { lastErr = Date.now(); show(true); });
      layer.on('tileload', function() { if (Date.now() - lastErr > 8000) show(false); });
    }
  };
};
"""
}
