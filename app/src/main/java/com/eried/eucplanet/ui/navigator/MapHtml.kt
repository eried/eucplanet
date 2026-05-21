package com.eried.eucplanet.ui.navigator

/**
 * The self-contained HTML document for the Route Builder's Leaflet map.
 *
 * Leaflet itself ships as bundled assets (`assets/leaflet.js` + `leaflet.css`);
 * the WebView is loaded with base URL `file:///android_asset/` so the page can
 * pull them in without a CDN — only the map tiles need the network, exactly
 * like the existing trip-detail map.
 *
 * JavaScript ↔ native bridge:
 *  - native → JS: `nativeRender(waypointsJson, geometryJson, fit)`,
 *    `nativeSetUser(lat,lng)`, `nativeRecenter(lat,lng,zoom)`,
 *    `nativeCenterOn(lat,lng)`, `nativeSetAccent(hex)`,
 *    `nativeSetMapType(type)`.
 *  - JS → native: `AndroidNav.onMapClick(lat,lng)`,
 *    `AndroidNav.onMarkerDragged(index,lat,lng)`, `AndroidNav.onSelfTap()`.
 *
 * While a pin is being dragged — and while routing is still being solved — the
 * pins are joined by a dashed connector; once a route comes back it is drawn
 * as a solid line.
 */
internal const val ROUTE_BUILDER_HTML: String = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="stylesheet" href="leaflet.css"/>
<script src="leaflet.js"></script>
<style>
  html,body,#map{margin:0;padding:0;width:100%;height:100%;background:#0b0f19;}
  .wp{
    width:28px;height:28px;border-radius:50% 50% 50% 0;
    transform:rotate(-45deg);
    border:2px solid #000;box-sizing:border-box;
    display:flex;align-items:center;justify-content:center;
    box-shadow:0 1px 4px rgba(0,0,0,0.6);
  }
  .wp span{
    transform:rotate(45deg);color:#000;font-weight:700;
    font-family:sans-serif;font-size:13px;
  }
  .user-dot{
    width:24px;height:24px;border-radius:50%;box-sizing:border-box;
    background:#2196F3;border:3px solid #000;
    box-shadow:0 1px 5px rgba(0,0,0,0.75);
  }
</style>
</head><body>
<div id="map"></div>
<script>
  var map = L.map('map', {zoomControl:false, attributionControl:false});
  var tileLayer = null;

  // Swappable base map: dark / light streets / satellite imagery, all key-less.
  window.nativeSetMapType = function(type){
    var url, opts;
    if (type === 'SATELLITE'){
      url = 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}';
      opts = {maxZoom:19};
    } else if (type === 'LIGHT'){
      url = 'https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png';
      opts = {maxZoom:19, subdomains:'abcd'};
    } else {
      url = 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png';
      opts = {maxZoom:19, subdomains:'abcd'};
    }
    if (tileLayer){ map.removeLayer(tileLayer); }
    tileLayer = L.tileLayer(url, opts).addTo(map);
  };
  window.nativeSetMapType('DARK');

  map.setView([20,0], 2);

  var markers = [];
  var routeLine = null;   // solid: the solved route
  var connector = null;   // dashed: drag / pre-route preview
  var userMarker = null;
  var accentColor = '#4FC3F7';   // the rider's theme accent, pushed from native

  map.on('click', function(e){
    if (!window.AndroidNav) return;
    // A tap on (or very near) the "you are here" dot hints instead of
    // dropping a pin on top of the rider. Measured in screen pixels so it
    // works the same at every zoom level.
    if (userMarker){
      var pt = map.latLngToContainerPoint(e.latlng);
      var up = map.latLngToContainerPoint(userMarker.getLatLng());
      var dx = pt.x - up.x, dy = pt.y - up.y;
      if (Math.sqrt(dx * dx + dy * dy) < 30){
        AndroidNav.onSelfTap();
        return;
      }
    }
    AndroidNav.onMapClick(e.latlng.lat, e.latlng.lng);
  });

  function colorFor(i, n){
    if (i === 0) return '#66BB6A';
    if (i === n - 1) return '#EF5350';
    return accentColor;
  }

  function iconFor(label, color){
    return L.divIcon({
      className:'',
      html:'<div class="wp" style="background:'+color+'"><span>'+label+'</span></div>',
      iconSize:[28,28], iconAnchor:[14,28]
    });
  }

  function markerPts(){
    return markers.map(function(m){ var ll = m.getLatLng(); return [ll.lat, ll.lng]; });
  }

  // Dashed line straight through the current marker positions. Shown while a
  // pin is being dragged and while routing is still being solved.
  function drawConnector(){
    var pts = markerPts();
    // Start the dashed preview at the rider's position when a fix is known.
    if (userMarker){
      var u = userMarker.getLatLng();
      pts = [[u.lat, u.lng]].concat(pts);
    }
    if (pts.length < 2){ clearConnector(); return; }
    if (connector){
      connector.setLatLngs(pts);
    } else {
      connector = L.polyline(pts, {
        color:accentColor, weight:4, opacity:0.9, dashArray:'8,12', lineCap:'round'
      }).addTo(map);
    }
  }
  function clearConnector(){
    if (connector){ map.removeLayer(connector); connector = null; }
  }

  window.nativeRender = function(wpJson, geomJson, fit){
    var wps = JSON.parse(wpJson);
    var geom = JSON.parse(geomJson);

    markers.forEach(function(m){ map.removeLayer(m); });
    markers = [];
    wps.forEach(function(w, i){
      // Faint dotted ring showing the arrival radius around each stop.
      var ring = L.circle([w.lat, w.lng], {
        radius: w.radius || 40,
        color:'#ffffff', weight:1, opacity:0.55,
        fill:false, dashArray:'3 7', interactive:false
      });
      ring.addTo(map);
      markers.push(ring);
      var m = L.marker([w.lat, w.lng], {
        draggable:true,
        icon: iconFor(i + 1, colorFor(i, wps.length))
      });
      m.on('dragstart', function(){
        // Drop the solved route; show the dashed preview while dragging.
        if (routeLine){ map.removeLayer(routeLine); routeLine = null; }
        drawConnector();
      });
      m.on('drag', function(){ drawConnector(); });
      m.on('dragend', (function(idx){
        return function(ev){
          var ll = ev.target.getLatLng();
          drawConnector();
          if (window.AndroidNav) AndroidNav.onMarkerDragged(idx, ll.lat, ll.lng);
        };
      })(i));
      m.addTo(map);
      markers.push(m);
    });

    if (routeLine){ map.removeLayer(routeLine); routeLine = null; }
    if (geom.length >= 2){
      // A solved route — solid line, drop the dashed preview.
      clearConnector();
      routeLine = L.polyline(geom, {color:accentColor, weight:5, opacity:0.85}).addTo(map);
    } else if (markers.length >= 1){
      // No geometry yet (routing in progress) — keep the dashed preview,
      // which runs from the rider's position through the stops.
      drawConnector();
    } else {
      clearConnector();
    }

    if (fit){
      var pts = (geom.length >= 2) ? geom : wps.map(function(w){ return [w.lat, w.lng]; });
      if (pts.length >= 2){
        map.fitBounds(L.latLngBounds(pts).pad(0.25));
      } else if (pts.length === 1){
        map.setView(pts[0], 16);
      }
    }
  };

  window.nativeSetUser = function(lat, lng){
    var p = [lat, lng];
    if (!userMarker){
      // A real marker (not a circle path): it sits in Leaflet's marker pane,
      // always above the route lines, and zIndexOffset keeps it above stops.
      userMarker = L.marker(p, {
        icon: L.divIcon({className:'user-dot', iconSize:[24,24], iconAnchor:[12,12]}),
        zIndexOffset: 1000,
        interactive: false,
        keyboard: false
      }).addTo(map);
    } else {
      userMarker.setLatLng(p);
    }
  };

  window.nativeRecenter = function(lat, lng, zoom){
    // Re-check the container size first so the target really lands centred
    // even if the WebView resized after the map was created.
    map.invalidateSize();
    map.setView([lat, lng], zoom);
  };

  // Pan (keeping the current zoom) so a point sits in the centre of the view.
  window.nativeCenterOn = function(lat, lng){
    map.panTo([lat, lng]);
  };

  // The rider's theme accent — recolours the route line and connector.
  window.nativeSetAccent = function(hex){
    accentColor = hex;
    if (routeLine) routeLine.setStyle({color: hex});
    if (connector) connector.setStyle({color: hex});
  };
</script>
</body></html>
"""
