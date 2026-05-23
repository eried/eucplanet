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
  /* Stop marker — circle with the stop's number inside. The original
     pin/teardrop shape was nice but it conflicts visually with the rider's
     own teardrop ("you are here" + arrow); circles read as static targets
     to ride INTO, and stay distinct from the directional rider marker. */
  .wp{
    width:28px;height:28px;border-radius:50%;
    border:2px solid #000;box-sizing:border-box;
    display:flex;align-items:center;justify-content:center;
    box-shadow:0 1px 4px rgba(0,0,0,0.6);
  }
  .wp span{
    color:#000;font-weight:700;
    font-family:sans-serif;font-size:13px;
  }
  .wp-lock{
    width:14px;height:14px;border-radius:50%;
    border:2px solid #000;box-sizing:border-box;
    box-shadow:0 1px 4px rgba(0,0,0,0.7);
  }
  /* Direction chevron for STRAIGHT-mode routes — two stroked lines meeting
     at a point (shape: > pointing forward along the segment). Rendered as an
     SVG inside a rotating wrapper so we get crisp anti-aliased strokes and
     can scale the stroke width independently of the icon size. No shadow:
     the bright route line already provides enough contrast against the map. */
  .chevron-wrap{ display:block; line-height:0; }
  /* Teardrop rider marker. Dimensions are inlined from buildUserIcon in JS
     so a single value drives the marker's apparent size — smaller when the
     rider hasn't set a photo, slightly larger when they have (the photo is
     the focal point and earns the extra real estate). The CSS keeps only
     the look-and-feel: colours, borders, shadows, the teardrop's clipped
     corner, the rotating-wrapper transform. */
  .user-pin{
    position:relative;
    transition: transform 200ms ease-out;
  }
  .user-pin-body{
    position:absolute; left:0; top:0;
    border-radius:50% 50% 50% 0;
    transform: rotate(-45deg);
    background:#2196F3;
    border:3px solid #000;
    box-shadow:0 1px 5px rgba(0,0,0,0.75);
    box-sizing:border-box;
  }
  .user-pin-body-still{
    position:absolute; left:0; top:0;
    border-radius:50%;
    background:#2196F3;
    border:3px solid #000;
    box-shadow:0 1px 5px rgba(0,0,0,0.75);
    box-sizing:border-box;
  }
  /* Inner photo. Positioning and counter-rotation are inlined by
     buildUserIcon — the outer wrap puts the photo on the head's centre
     (NOT the teardrop's bounding-box centre, which would slip the photo
     down into the tail), and an inner counter-rotation keeps a face or
     other oriented photo upright as the teardrop tip swings around.
     box-sizing:border-box so the 1 px outer border doesn't push the
     visual circle 1 px off the head centre. */
  .user-pin-photo{
    border-radius:50%;
    background-size: cover; background-position: center;
    border:1px solid rgba(255,255,255,0.85);
    box-shadow: inset 0 0 0 1px rgba(0,0,0,0.6);
    box-sizing:border-box;
  }
  .place-badge{
    width:30px;height:30px;border-radius:50%;box-sizing:border-box;
    border:2px solid #000;display:flex;align-items:center;justify-content:center;
    font:700 14px sans-serif;color:#fff;box-shadow:0 1px 5px rgba(0,0,0,0.7);
  }
  .place-home{ background:#43A047; }
  .place-work{ background:#5E35B1; }
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

  // Push the current map view (centre + zoom) back to Kotlin on every
  // user-driven move / zoom. Used by the VM to remember where the rider
  // was looking, so navigating to Settings and back doesn't snap the map
  // to the world view. Debounced via a short timer so a long drag posts
  // once instead of dozens of times.
  var viewSyncTimer = null;
  map.on('moveend zoomend', function(){
    if (!window.AndroidNav) return;
    if (viewSyncTimer) clearTimeout(viewSyncTimer);
    viewSyncTimer = setTimeout(function(){
      var c = map.getCenter();
      AndroidNav.onMapViewChanged(c.lat, c.lng, map.getZoom());
    }, 120);
  });

  var markers = [];       // draggable stop pins (one per waypoint, index-aligned)
  var rings = [];         // dotted arrival-radius circles, one per stop
  var routeLine = null;   // solid: the solved route
  var connector = null;   // dashed: drag / pre-route preview
  var userMarker = null;
  var accentColor = '#4FC3F7';   // the rider's theme accent, pushed from native
  // While navigation is running we deliberately freeze the builder: pins
  // become non-draggable and don't react to map clicks, the dotted arrival
  // ring is given a thicker, brighter stroke so the rider's eye is drawn to
  // the approach AREA (the stop they have to ride INTO), and the numbered
  // pin shrinks to a small dot at the centre instead of the big draggable
  // label. The lock flag is mirrored from the native side via
  // nativeSetNavLocked() — re-call nativeRender to apply it.
  var navLocked = false;
  // Route style depends on the travel mode so the rider can tell at a glance
  // whether they are looking at a car route, a bike/walk route, or just a
  // straight-line direct path. Pushed from native via nativeSetTravelMode().
  // STRAIGHT routes also get arrow decorations because the line itself is
  // featureless (no junctions / road geometry to imply direction).
  var travelMode = 'DRIVING';
  // Arrow decorations layer for STRAIGHT mode — populated by drawArrows(),
  // cleared every render so it never lingers across mode changes.
  var arrowLayer = null;

  map.on('click', function(e){
    if (!window.AndroidNav) return;
    var pt = map.latLngToContainerPoint(e.latlng);
    // A tap on (or very near) the "you are here" dot opens the rider menu
    // instead of dropping a pin. Measured in screen pixels so it works the
    // same at every zoom level.
    if (userMarker){
      var up = map.latLngToContainerPoint(userMarker.getLatLng());
      var dx = pt.x - up.x, dy = pt.y - up.y;
      if (Math.sqrt(dx * dx + dy * dy) < 32){
        AndroidNav.onSelfTap(Math.round(up.x), Math.round(up.y));
        return;
      }
    }
    // While navigation is running, the route is locked — no pin drops.
    if (navLocked) return;
    // A tap close to an existing stop is ignored, so pins never pile up.
    for (var i = 0; i < markers.length; i++){
      var mp = map.latLngToContainerPoint(markers[i].getLatLng());
      var mdx = pt.x - mp.x, mdy = pt.y - mp.y;
      if (Math.sqrt(mdx * mdx + mdy * mdy) < 38) return;
    }
    AndroidNav.onMapClick(e.latlng.lat, e.latlng.lng);
  });

  window.nativeSetNavLocked = function(locked){
    navLocked = !!locked;
    // The render path looks at navLocked, so re-run it to swap pin styles
    // and toggle draggability. The native side passes the current waypoints
    // and geometry through render() right after toggling the lock.
  };

  function colorFor(i, n){
    if (i === 0) return '#66BB6A';
    if (i === n - 1) return '#EF5350';
    return accentColor;
  }

  function iconFor(label, color){
    return L.divIcon({
      className:'',
      html:'<div class="wp" style="background:'+color+'"><span>'+label+'</span></div>',
      // Anchor at the CENTRE of the circle now (was the bottom-tip when the
      // marker was a pin) so the stop's lat/lng lands on the disk centre.
      iconSize:[28,28], iconAnchor:[14,14]
    });
  }
  // Locked-state icon: a small filled dot at the centre of the arrival ring
  // (the ring becomes the dominant visual). Smaller hit target too, since the
  // pin is no longer draggable.
  function iconForLocked(color){
    return L.divIcon({
      className:'',
      html:'<div class="wp-lock" style="background:'+color+';border-color:'+color+'"></div>',
      iconSize:[14,14], iconAnchor:[7,7]
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
        color: routeColorFor(travelMode),
        weight: 5, opacity: 0.80, dashArray: '8,12', lineCap: 'round'
      }).addTo(map);
    }
    // While dragging, the dashed connector is the rider's only feedback for
    // where the new segments go. Reuse the chevron arrows so the direction
    // is unambiguous in every mode (Drive / Bike / Walk / Straight). Arrows
    // are auto-cleared by clearConnector() when the solved route arrives.
    drawArrows(pts, routeColorFor(travelMode));
  }
  function clearConnector(){
    if (connector){ map.removeLayer(connector); connector = null; }
  }

  // Travel-mode → polyline colour. Cool→warm activity gradient: each step up
  // in mode "energy" picks a warmer hue, with magenta reserved for the
  // off-road STRAIGHT line so the rider can tell at a glance the route does
  // NOT follow real roads. Picked to read on all three basemaps (street,
  // dark, satellite): avoids alarm-red, avoids any pure green that would
  // sink into satellite forests, all four colours have similar luminance.
  //
  //   Walk     → blue      (#03A9F4)
  //   Bike     → orange    (#FB8C00)
  //   Drive    → red       (#E53935)
  //   Straight → green     (#43A047, "direct line" through anywhere)
  //
  // The Kotlin SegmentedButton icons mirror these exact colours so the chosen
  // mode chip visually previews the line that will be drawn.
  function routeColorFor(mode){
    if (mode === 'CYCLING')  return '#FB8C00';
    if (mode === 'WALKING')  return '#03A9F4';
    if (mode === 'STRAIGHT') return '#43A047';
    if (mode === 'DRIVING')  return '#E53935';
    return accentColor;
  }

  function clearArrows(){
    if (arrowLayer){ map.removeLayer(arrowLayer); arrowLayer = null; }
  }
  // Direction chevrons for STRAIGHT-mode routes. One chevron at the centre of
  // each segment between consecutive waypoints — or TWO chevrons (at 1/3 and
  // 2/3) when a single segment is long enough on screen that one arrow would
  // be lonely in a stretch of empty line. Very short segments get nothing,
  // they're shorter than the chevron itself. Chevrons are two stroked SVG
  // lines meeting at a point (shape: ">"), rotated to match the segment's
  // bearing. No shadow; the route line is already high-contrast.
  function drawArrows(geom, color){
    clearArrows();
    if (!geom || geom.length < 2) return;
    arrowLayer = L.layerGroup();
    var pts = geom.map(function(g){ return map.latLngToContainerPoint([g[0], g[1]]); });
    var added = 0;
    for (var i = 1; i < pts.length; i++){
      var a = pts[i-1], b = pts[i];
      var dx = b.x - a.x, dy = b.y - a.y;
      var len = Math.sqrt(dx*dx + dy*dy);
      // Too short to bother — the chevron itself is ~24 px wide.
      if (len < 60) continue;
      var deg = Math.atan2(dy, dx) * 180 / Math.PI;
      // One chevron centred, OR two at 1/3 and 2/3 for long segments.
      var ts = (len > 260) ? [1/3, 2/3] : [0.5];
      for (var k = 0; k < ts.length; k++){
        var t = ts[k];
        var ll = map.containerPointToLatLng([a.x + dx * t, a.y + dy * t]);
        var svg =
          '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" ' +
          'viewBox="0 0 24 24">' +
          '<polyline points="9,4 17,12 9,20" fill="none" ' +
          'stroke="' + color + '" stroke-width="4" ' +
          'stroke-linecap="round" stroke-linejoin="round"/></svg>';
        var icon = L.divIcon({
          className: '',
          html: '<div class="chevron-wrap" style="transform:rotate(' +
                deg.toFixed(1) + 'deg)">' + svg + '</div>',
          iconSize: [24, 24], iconAnchor: [12, 12]
        });
        arrowLayer.addLayer(L.marker(ll, { icon: icon, interactive: false, keyboard: false }));
        added++;
      }
    }
    if (added > 0) arrowLayer.addTo(map);
  }
  // Arrows are rendered in screen-space, so refresh on zoom/pan-end.
  map.on('zoomend moveend', function(){
    if (travelMode === 'STRAIGHT' && routeLine){
      var geom = routeLine.getLatLngs().map(function(ll){ return [ll.lat, ll.lng]; });
      drawArrows(geom, routeColorFor(travelMode));
    }
  });

  window.nativeRender = function(wpJson, geomJson, fit){
    var wps = JSON.parse(wpJson);
    var geom = JSON.parse(geomJson);

    markers.forEach(function(m){ map.removeLayer(m); });
    rings.forEach(function(r){ map.removeLayer(r); });
    markers = [];
    rings = [];
    wps.forEach(function(w, i){
      // Dotted radius area around each stop (the arrival radius from Settings).
      // Thicker / more opaque while nav is locked — the ring IS the stop now,
      // since pins are not draggable and the rider needs to know where the
      // approach zone is.
      var ringColor = colorFor(i, wps.length);
      var ring = L.circle([w.lat, w.lng], {
        radius: w.radius || 40,
        color: ringColor,
        weight: navLocked ? 3 : 2,
        opacity: 0.9,
        fill: true,
        fillColor: ringColor,
        fillOpacity: navLocked ? 0.18 : 0.10,
        dashArray:'5 7', interactive:false
      });
      ring.addTo(map);
      rings.push(ring);
      var m = L.marker([w.lat, w.lng], {
        draggable: !navLocked,
        icon: navLocked
          ? iconForLocked(colorFor(i, wps.length))
          : iconFor(i + 1, colorFor(i, wps.length))
      });
      m.on('dragstart', function(){
        // Drop the solved route; show the dashed preview while dragging.
        if (routeLine){ map.removeLayer(routeLine); routeLine = null; }
        drawConnector();
      });
      m.on('drag', (function(idx){
        // Keep the stop's radius ring under the pin while dragging, so the
        // dashed preview tracks the pin instead of its old, now-empty spot.
        return function(ev){
          if (rings[idx]) rings[idx].setLatLng(ev.target.getLatLng());
          drawConnector();
        };
      })(i));
      m.on('dragend', (function(idx){
        return function(ev){
          var ll = ev.target.getLatLng();
          drawConnector();
          if (window.AndroidNav) AndroidNav.onMarkerDragged(idx, ll.lat, ll.lng);
        };
      })(i));
      m.on('click', (function(idx){
        return function(){
          if (!window.AndroidNav) return;
          var mp = map.latLngToContainerPoint(markers[idx].getLatLng());
          AndroidNav.onMarkerTapped(idx, Math.round(mp.x), Math.round(mp.y));
        };
      })(i));
      m.addTo(map);
      markers.push(m);
    });

    if (routeLine){ map.removeLayer(routeLine); routeLine = null; }
    clearArrows();
    if (geom.length >= 2){
      // A solved route — solid line, drop the dashed preview. Colour depends
      // on the travel mode (DRIVE/BIKE/WALK/STRAIGHT). Slightly thicker and
      // 80% opaque so the path stands out without obscuring the basemap.
      clearConnector();
      var routeColor = routeColorFor(travelMode);
      routeLine = L.polyline(geom, {color: routeColor, weight: 6, opacity: 0.80}).addTo(map);
      // STRAIGHT routes get direction arrows along the line — without road
      // geometry there's no other directional cue.
      if (travelMode === 'STRAIGHT') drawArrows(geom, routeColor);
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

  // Saved Home / Work places — always shown, drawn behind the stops.
  var placeMarkers = [];
  var placeIcons = {
    home: '<svg viewBox="0 0 24 24" width="18" height="18" fill="#fff">' +
      '<path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"/></svg>',
    work: '<svg viewBox="0 0 24 24" width="18" height="18" fill="#fff">' +
      '<path d="M20 6h-4V4c0-1.1-.9-2-2-2h-4c-1.1 0-2 .9-2 2v2H4c-1.1 0-2 ' +
      '.9-2 2v11c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-6 ' +
      '0h-4V4h4v2z"/></svg>'
  };
  window.nativeSetPlaces = function(json){
    placeMarkers.forEach(function(m){ map.removeLayer(m); });
    placeMarkers = [];
    var places = JSON.parse(json);
    places.forEach(function(p){
      var m = L.marker([p.lat, p.lng], {
        icon: L.divIcon({
          className:'', iconSize:[30,30], iconAnchor:[15,15],
          html:'<div class="place-badge place-' + p.kind + '">' +
            (placeIcons[p.kind] || '') + '</div>'
        }),
        interactive:false, zIndexOffset:-1000
      });
      m.addTo(map);
      placeMarkers.push(m);
    });
  };

  // Latest known heading in degrees (0 = north, clockwise). Held across
  // ticks so slow / stationary fixes don't make the teardrop point spin to
  // GPS noise — it sticks at the last "known good" direction.
  var userHeading = null;
  // True only while the rider's speed is over the heading threshold. While
  // stationary the marker collapses to a plain circle (no directional tail)
  // because a direction-arrow that doesn't change feels misleading.
  var userMoving = false;
  // Base64 PNG (or null) for a custom rider photo in the centre of the pin.
  var userPhotoDataUrl = null;

  // Build a CSS `url('...')` literal that is safe to embed inside an HTML
  // attribute that is ITSELF double-quoted (the inline `style="..."`).
  // JSON.stringify would wrap the URL in double quotes and break the
  // attribute parser; using single quotes for the CSS string sidesteps
  // that. Base64 data URLs don't contain single quotes, but we URI-encode
  // any just in case so a future change here doesn't break silently.
  function cssUrl(u){
    return "url('" + u.replace(/'/g, '%27') + "')";
  }

  function buildUserIcon(){
    var hasPhoto = userPhotoDataUrl != null;
    // Marker head diameter in px. Slightly smaller for the plain
    // (no-photo) marker so it sits modestly on the map, slightly larger
    // when there's a custom photo so the avatar is legible.
    var headPx = hasPhoto ? 42 : 30;
    var tailPx = Math.round(headPx * 7 / 6); // teardrop height, 1.17x head
    var photoPx = Math.round(headPx * 0.72); // inner photo: ~72% of head

    // Build the photo HTML. Two nested divs:
    //  - Outer "photo-pos": absolutely placed so its top-left is at
    //    (headCentre - photoPx/2, headCentre - photoPx/2). Direct pixel
    //    offsets, no transform — earlier the wrapper rotation interacted
    //    with translate(-50%, -50%) and shifted the photo a sub-pixel off
    //    centre in some orientations.
    //  - Inner ".user-pin-photo": counter-rotates by -wrapperRot so the
    //    photo stays upright even though it inherits the wrapper's
    //    rotation. For a round photo this is invisible, but for a face /
    //    oriented avatar it keeps the image right-side-up at every heading.
    var photoLeft = (headPx - photoPx) / 2;
    var photoTop  = (headPx - photoPx) / 2;
    function photoBlock(wrapperRot){
      if (!hasPhoto) return '';
      return '<div style="position:absolute;left:' + photoLeft +
             'px;top:' + photoTop + 'px;width:' + photoPx +
             'px;height:' + photoPx + 'px">' +
             '<div class="user-pin-photo" style="width:100%;height:100%;' +
             'transform:rotate(' + (-wrapperRot) +
             'deg);background-image:' + cssUrl(userPhotoDataUrl) +
             '"></div></div>';
    }

    if (!userMoving || userHeading == null){
      // Stationary (or no known heading yet): plain round puck — wrapper
      // and head are the same square, so the head centre is also the
      // wrapper centre; no counter-rotation needed.
      return L.divIcon({
        className: '',
        html: '<div class="user-pin" style="width:' + headPx + 'px;height:' +
              headPx + 'px">' +
              '<div class="user-pin-body-still" style="width:' + headPx +
              'px;height:' + headPx + 'px"></div>' + photoBlock(0) +
              '</div>',
        iconSize: [headPx, headPx],
        iconAnchor: [headPx / 2, headPx / 2]
      });
    }
    // Moving: full teardrop pointing in the direction of travel. The
    // wrapper rotates by heading + 45° (the teardrop body is pre-rotated
    // -45° via the CSS border-radius drop-shape trick; adding 45° here
    // makes a heading of 0 (north) point the tip straight up). Rotation
    // origin is the HEAD centre so the tail swings around the head rather
    // than the bounding-box centre.
    var rot = userHeading + 45;
    return L.divIcon({
      className: '',
      html: '<div class="user-pin" style="width:' + headPx + 'px;height:' +
            tailPx + 'px;transform-origin:' + (headPx / 2) + 'px ' +
            (headPx / 2) + 'px;transform:rotate(' + rot + 'deg)">' +
            '<div class="user-pin-body" style="width:' + headPx + 'px;height:' +
            headPx + 'px"></div>' + photoBlock(rot) + '</div>',
      iconSize: [headPx, tailPx],
      iconAnchor: [headPx / 2, headPx / 2]
    });
  }

  window.nativeSetUser = function(lat, lng){
    var p = [lat, lng];
    if (!userMarker){
      userMarker = L.marker(p, {
        icon: buildUserIcon(),
        zIndexOffset: 1000,
        interactive: false,
        keyboard: false
      }).addTo(map);
    } else {
      userMarker.setLatLng(p);
    }
  };

  // Push the rider's heading in degrees. Only called when the rider is
  // moving fast enough that GPS bearing is meaningful (see Kotlin: speed-
  // gated update). The teardrop renders for "moving" frames and rotates
  // smoothly via the CSS transition.
  window.nativeSetUserHeading = function(deg){
    if (deg === null || deg === undefined) return;
    userHeading = ((deg % 360) + 360) % 360;
    userMoving = true;
    if (userMarker) userMarker.setIcon(buildUserIcon());
  };

  // Tell the marker the rider is no longer moving fast enough to trust the
  // bearing — switches from teardrop to plain circle. The last known
  // userHeading stays cached so a brief stop doesn't lose it; the moment
  // the rider picks back up speed, the teardrop returns pointing the right
  // way.
  window.nativeSetUserStill = function(){
    if (!userMoving) return;
    userMoving = false;
    if (userMarker) userMarker.setIcon(buildUserIcon());
  };

  // Base64 data URL ("data:image/png;base64,...") for a custom rider photo,
  // or empty string to clear. The photo is shown inside the teardrop head.
  window.nativeSetUserPhoto = function(dataUrl){
    userPhotoDataUrl = (dataUrl && dataUrl.length > 0) ? dataUrl : null;
    if (userMarker) userMarker.setIcon(buildUserIcon());
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
    // Stored so any future "fallback to accent" code path still works, but
    // every travel mode now has its own fixed hue — changing the theme
    // accent no longer re-tints the route. Kept callable so existing
    // composables that push the accent don't crash.
    accentColor = hex;
  };

  window.nativeSetTravelMode = function(mode){
    if (!mode) return;
    travelMode = mode;
    var c = routeColorFor(mode);
    if (routeLine) routeLine.setStyle({color: c});
    if (connector) connector.setStyle({color: c});
    if (mode === 'STRAIGHT' && routeLine){
      var geom = routeLine.getLatLngs().map(function(ll){ return [ll.lat, ll.lng]; });
      drawArrows(geom, c);
    } else {
      clearArrows();
    }
  };
</script>
</body></html>
"""
