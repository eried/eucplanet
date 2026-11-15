package com.eried.eucplanet.ui.settings.offline

import com.eried.eucplanet.hud.protocol.MapLayers
import com.eried.eucplanet.nav.offline.RoutingTiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Leaflet page behind the offline areas map: the rider's map style with
 * the 5 degree squares drawn over it. Kotlin sends the squares with
 * `setTiles(json)` and their colours with `setColors(json)`; a tap calls
 * `AndroidOffline.onTile(name)`.
 */
internal object OfflineGridHtml {

    fun page(mapType: String, bg: String, lat: Double, lng: Double, zoom: Int): String {
        val layer = MapLayers.ALL.firstOrNull { it.id == mapType } ?: MapLayers.ALL.first()
        val attr = layer.attribution.replace("\\", "\\\\").replace("'", "\\'")
        return TEMPLATE
            .replace("__BG__", bg)
            .replace("__LAT__", lat.toString())
            .replace("__LNG__", lng.toString())
            .replace("__ZOOM__", zoom.toString())
            .replace("__URL__", layer.urlTemplate)
            .replace("__MAXN__", layer.maxNativeZoom.toString())
            .replace("__SUBS__", layer.subdomains.ifEmpty { "abc" })
            .replace("__ATTR__", attr)
            .replace("__OFFLINE_JS__", com.eried.eucplanet.nav.offline.map.OfflineMapWeb.JS)
    }

    /** The squares as the page wants them: name, state, bounds, label. */
    fun tilesJson(rows: Collection<TileRow>, label: (TileRow) -> String?): String =
        JSONArray().also { arr ->
            rows.forEach { r ->
                val b = RoutingTiles.bounds(r.name)
                arr.put(JSONObject().apply {
                    put("n", r.name)
                    put("s", r.status.name)
                    put("b", JSONArray(b.toList()))
                    label(r)?.let { put("l", it) }
                })
            }
        }.toString()

    private val TEMPLATE = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"/>
<link rel="stylesheet" href="leaflet.css"/>
<script src="leaflet.js"></script>
<style>
html,body,#map{margin:0;padding:0;height:100%;width:100%;background:__BG__;}
.lbl{background:rgba(0,0,0,0.6);color:#fff;border:none;box-shadow:none;font:600 11px sans-serif;padding:1px 4px;}
.lbl:before{display:none;}
</style></head><body><div id="map"></div><script>
var map = L.map('map', {preferCanvas: true, zoomControl: false, worldCopyJump: true, minZoom: 2, maxZoom: 12})
  .setView([__LAT__, __LNG__], __ZOOM__);
__OFFLINE_JS__
var offlineMap = eucOfflineMap(map, {maxZoom: 12});
offlineMap.watch(L.tileLayer('__URL__', {maxNativeZoom: __MAXN__, maxZoom: 12, subdomains: '__SUBS__', attribution: '__ATTR__'}).addTo(map));
var colors = {};
var squares = L.layerGroup().addTo(map);
function setColors(c) { colors = c; }
function setTiles(list) {
  squares.clearLayers();
  list.forEach(function(t) {
    var c = colors[t.s] || {stroke: '#888', fill: '#888', op: 0.1};
    var r = L.rectangle([[t.b[0], t.b[1]], [t.b[2], t.b[3]]],
      {color: c.stroke, weight: 1, fillColor: c.fill, fillOpacity: c.op});
    r.on('click', function() { AndroidOffline.onTile(t.n); });
    if (t.l) r.bindTooltip(t.l, {permanent: true, direction: 'center', className: 'lbl'});
    r.addTo(squares);
  });
}
</script></body></html>
"""
}
