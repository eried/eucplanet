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
    const val ATTRIBUTION = "Offline map · © OpenStreetMap contributors"

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

    /**
     * Defines `eucOfflineMap(map)` on the page. It adds the offline layer and
     * returns `{layer, watch(onlineLayer)}`: call `watch` with each online
     * base layer so the credit switches to the offline one while that
     * layer's tiles fail, and back once they load again.
     */
    val JS = """
window.eucOfflineMap = function(map, extra) {
  var OFF = '$ATTRIBUTION';
  var off = L.tileLayer('https://$HOST/{z}/{x}/{y}.png', Object.assign({maxZoom: 21, maxNativeZoom: 20, zIndex: 0}, extra || {})).addTo(map);
  var lastErr = 0, shown = false, attr = '', taken = 0;
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
    watch: function(layer) {
      attr = layer.options.attribution || '';
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
