package com.eried.eucplanet.ui.map

/** Tile URL per style — the same key-less public endpoints the Android map uses. */
internal fun mapTileUrl(style: String): String = when (style.uppercase()) {
    "SATELLITE" -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    "LIGHT", "VOYAGER" -> "https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png"
    else -> "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"
}

/**
 * Self-contained Leaflet map page (Leaflet from CDN + CARTO/ArcGIS tiles), the
 * iOS counterpart to Android's `MapHtml`. JS bridge the native side calls:
 *  - `nativeSetUser(lat,lng)` — place / move the rider dot (centers on first fix)
 *  - `nativeRecenter(lat,lng,zoom)` — jump the map to the rider
 *  - `nativeTrace(jsonLatLngs)` — draw the ride trace polyline
 */
internal fun mapHtml(style: String, accentHex6: String): String = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#map{height:100%;width:100%;margin:0;background:#0d0d0d}</style>
</head><body><div id="map"></div><script>
  var map = L.map('map',{zoomControl:false,attributionControl:false}).setView([20,0],2);
  L.tileLayer('${mapTileUrl(style)}',{maxZoom:19,subdomains:'abcd'}).addTo(map);
  var userMarker=null, accuracy=null, trace=null, centered=false;
  function nativeSetUser(lat,lng){
    if(!userMarker){
      userMarker=L.circleMarker([lat,lng],{radius:8,color:'#ffffff',weight:3,fillColor:'$accentHex6',fillOpacity:1}).addTo(map);
    } else { userMarker.setLatLng([lat,lng]); }
    if(!centered){ map.setView([lat,lng],16); centered=true; }
  }
  function nativeRecenter(lat,lng,z){ map.setView([lat,lng], z||16); centered=true; }
  function nativeTrace(json){
    try{ var pts=JSON.parse(json); if(trace){map.removeLayer(trace);} trace=L.polyline(pts,{color:'$accentHex6',weight:4,opacity:0.85}).addTo(map);}catch(e){}
  }
</script></body></html>
"""
