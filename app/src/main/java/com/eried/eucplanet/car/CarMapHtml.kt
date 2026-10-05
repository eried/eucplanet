package com.eried.eucplanet.car

import com.eried.eucplanet.ui.navigator.mapLayersJson
import com.eried.eucplanet.ui.navigator.mapTypeInitialBg

/**
 * The car screen's map: the rider's chosen layer, their position and heading,
 * and the route being followed. Nothing to tap or edit, Android Auto allows a
 * driver none of that, so it is a small page of its own rather than the route
 * builder's.
 *
 * Kotlin drives it through three calls: setPos(lat, lng, bearing),
 * setRoute([[lat, lng], ...]) and setLayer(id). Loaded with the asset base URL
 * so the bundled leaflet.js resolves, as the navigator's page does. The two
 * colors come from the theme tokens, passed in as #RRGGBB.
 */
internal fun carMapHtml(mapType: String, routeHex: String, markerRingHex: String): String = """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no"/>
<link rel="stylesheet" href="leaflet.css"/>
<script src="leaflet.js"></script>
<style>
  html, body, #map { margin:0; padding:0; width:100%; height:100%; background:${mapTypeInitialBg(mapType)}; }
  .leaflet-control-attribution { font-size:10px; }
  .me { width:28px; height:28px; }
</style>
</head><body>
<div id="map"></div>
<script>
  var LAYERS = ${mapLayersJson()};
  var map = L.map('map', { zoomControl:false, attributionControl:true }).setView([0, 0], 3);
  var base = null, ref = null;
  function setLayer(id) {
    var l = LAYERS[id] || LAYERS['OSM'];
    if (base) map.removeLayer(base);
    if (ref) { map.removeLayer(ref); ref = null; }
    base = L.tileLayer(l.url, { maxNativeZoom:l.maxNative, maxZoom:20, subdomains:l.subs || 'abc',
                                detectRetina:l.retina, attribution:l.attr }).addTo(map);
    if (l.ref) ref = L.tileLayer(l.ref, { maxNativeZoom:l.maxNative, maxZoom:20 }).addTo(map);
  }
  setLayer('$mapType');
  var arrow = L.divIcon({ className:'', iconSize:[28,28], iconAnchor:[14,14],
    html:'<svg class="me" viewBox="0 0 28 28"><circle cx="14" cy="14" r="13" fill="$markerRingHex"/>' +
         '<path id="dir" d="M14 4 L22 22 L14 18 L6 22 Z" fill="$routeHex"/></svg>' });
  var me = null, route = null, first = true;
  function setPos(lat, lng, bearing) {
    if (!me) me = L.marker([lat, lng], { icon:arrow, interactive:false }).addTo(map);
    me.setLatLng([lat, lng]);
    var el = me.getElement();
    if (el) el.querySelector('svg').style.transform = 'rotate(' + (bearing || 0) + 'deg)';
    if (first) { map.setView([lat, lng], 17); first = false; } else { map.panTo([lat, lng], { animate:true }); }
  }
  function setRoute(points) {
    if (route) { map.removeLayer(route); route = null; }
    if (points && points.length > 1)
      route = L.polyline(points, { color:'$routeHex', weight:7, opacity:0.85 }).addTo(map);
  }
</script>
</body></html>
"""
