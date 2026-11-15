package com.eried.eucplanet.nav.offline.map

/**
 * The offline map in the Leaflet maps (navigator, trip details, offline
 * areas), on both phones. The page asks for [js]'s tileUrl, the phone draws
 * the tile from the downloaded squares (Android intercepts
 * `https://offline-map.eucplanet/`, OfflineMapWeb; the iPhone answers its
 * own `eucoffline:` scheme, IosOfflineMapScheme). The layer sits under the
 * online map, so it only shows where online tiles fail, and the credit line
 * says so while it does.
 */
object OfflineMapJs {
    const val NAME = "Offline"

    /**
     * Defines `eucOfflineMap(map)` on the page. It adds the offline layer and
     * returns `{layer, prefer, watch(onlineLayer)}`: call `watch` with each
     * online base layer. While the offline map shows, the credit starts with
     * "Offline" in bold, and the page
     * tells `AndroidNav.onOfflineMap(on)` if it has one. Without [prefer]
     * that is while the online tiles fail; with it ("Prefer offline" is on)
     * the offline layer sits on top (clear outside the downloaded squares)
     * and it is while a downloaded square is in view. [tileUrl] is the
     * offline tiles' Leaflet template, `{z}/{x}/{y}` in it.
     */
    fun js(tileUrl: String, prefer: Boolean): String = """
window.eucOfflineMap = function(map, extra) {
  var LABEL = '<span class="euc-off">$NAME</span>';
  var OFF = LABEL + ' · © OpenStreetMap contributors';
  var PREFER = $prefer;
  if (!document.getElementById('euc-off-css')) {
    var css = document.createElement('style');
    css.id = 'euc-off-css';
    // The credit's own halo carries it; bold and a little larger is enough.
    css.textContent = '.euc-off{font-weight:700;font-size:1.25em;}';
    document.head.appendChild(css);
  }
  var off = L.tileLayer('$tileUrl', Object.assign({maxZoom: 21, maxNativeZoom: 20, zIndex: PREFER ? 1000 : 0}, extra || {})).addTo(map);
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
      credit_(drawn ? (attr.indexOf('OpenStreetMap contributors') >= 0 ? LABEL : OFF) : '');
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
