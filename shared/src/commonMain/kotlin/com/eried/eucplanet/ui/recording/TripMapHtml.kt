package com.eried.eucplanet.ui.recording

import com.eried.eucplanet.map.MapLayersJs
import com.eried.eucplanet.platform.JSONArray
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.jsonQuote

/**
 * The trip viewer's Leaflet page, for every phone. Android's WebView and the
 * iPhone's WKWebView load the same document and call the same hooks
 * (setTrace, setMapType, updateLivePoint, updateScrubPoint,
 * updateComparePoint and their clear calls).
 */
object TripMapHtml {

    /** Leaflet from the CDN, as Android's trip map has always loaded it. */
    const val LEAFLET_CDN_HEAD =
        "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\"/>\n" +
            "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>"

    /**
     * Defines eucOfflineMap(map) for a phone with no downloaded map: a layer
     * with nothing in it, never preferred, and a watch that does nothing, so
     * the page runs the same calls either way.
     */
    const val NO_OFFLINE_MAP_JS =
        "window.eucOfflineMap = function(map, extra) {\n" +
            "  return { layer: L.featureGroup(), prefer: false, watch: function(layer) {} };\n" +
            "};"

    /** The points' fixes as the page's `[[lat,lon],...]` body, brackets left out. */
    fun coordsJson(points: List<TripDataPoint>): String =
        points.joinToString(",") { "[${it.latitude},${it.longitude}]" }

    /**
     * Wheel-change markers and their popups, JSON-encoded so rider-controlled
     * BLE names can never break out of the script block.
     */
    fun switchesJson(switches: List<WheelSwitchMarker>): String {
        val arr = JSONArray()
        for (s in switches) {
            arr.put(JSONObject()
                .put("idx", s.gpsIndex)
                .put("lat", s.lat).put("lon", s.lon)
                .put("label", s.label)
                .put("change", s.isChange))
        }
        return arr.toString()
    }

    /** Same encoding as [switchesJson]. No idx: these never cut a trace colour. */
    fun fadedSwitchesJson(switches: List<WheelSwitchMarker>): String {
        val arr = JSONArray()
        for (s in switches) {
            arr.put(JSONObject()
                .put("lat", s.lat).put("lon", s.lon)
                .put("change", s.isChange))
        }
        return arr.toString()
    }

    /** [text] as a JS string literal, quotes included. */
    fun quote(text: String): String = jsonQuote(text)

    /**
     * The whole document. [leafletHead] is the head's Leaflet tags (the CDN on
     * Android, the bundled copy inlined on iOS); [offlineMapJs] defines
     * eucOfflineMap(map), the downloaded map under the online one.
     */
    fun build(
        coordsJson: String,
        fadedCoordsJson: String,
        switchesJson: String,
        fadedSwitchesJson: String,
        startIncluded: Boolean,
        endIncluded: Boolean,
        startLabelJs: String,
        endLabelJs: String,
        isLive: Boolean,
        initialType: String,
        offlineMapJs: String,
        leafletHead: String = LEAFLET_CDN_HEAD,
    ): String = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
$leafletHead
<style>
  html,body,#map{margin:0;padding:0;width:100%;height:100%;background:#0b0f19;}
  .half-marker{
    width:18px;height:18px;border-radius:50%;border:2px solid #000;
    background: linear-gradient(to right,#66BB6A 50%,#EF5350 50%);
    box-sizing:border-box;
  }
  .live-marker{
    width:14px;height:14px;border-radius:50%;border:2px solid #fff;
    background:#FFC107;
    box-shadow:0 0 6px rgba(255,193,7,0.9);
  }
  /* Wheel-event badges: small squares, so they read differently from the round
     start / end / live markers and stay smaller than them. A same-wheel
     reconnect is grey; a genuinely different wheel is purple (matching the
     trace colour switch; wheel-change = the wheelChange token's default). */
  .wheel-badge{ border:1.5px solid #fff;box-sizing:border-box;border-radius:2px; }
  .wheel-change{ width:13px;height:13px;background:#AB47BC; }
  .wheel-reconnect{ width:10px;height:10px;background:#9E9E9E; }
  /* Identity badges outside a trimmed section: same shape, ghosted. */
  .faded-badge{ opacity:0.45; }
  /* Scrub tooltip: dark and compact so it labels the dot without covering the
     trace it is sitting on. Leaflet's default is a white box with an arrow. */
  .scrub-tip{
    background:rgba(20,22,26,0.92); color:#fff; border:none; border-radius:4px;
    padding:2px 7px; font-size:11px; font-weight:600; white-space:nowrap;
    box-shadow:0 1px 4px rgba(0,0,0,0.5);
  }
  .scrub-tip:before{ border-top-color:rgba(20,22,26,0.92); }
  /* Tile credit: required, but it should read as a footnote rather than a
     label. Leaflet's default is an opaque white box at 11px, which on a card
     this size is the loudest thing on the map. Dim and small, and moved off
     the bottom-right where the layer and fullscreen buttons sit. */
  .leaflet-control-attribution{
    background:transparent!important;
    color:rgba(255,255,255,0.88)!important;
    text-shadow:0 0 2px rgba(0,0,0,0.95),0 0 5px rgba(0,0,0,0.75)!important;
    font-size:8px!important;
    padding:2px 6px!important;
    /* Centred along the bottom edge: the map sits in a rounded card, and the
       middle of that edge is the one spot no corner curve and none of the
       buttons reach. Only the two upper corners are rounded, so it reads as a
       tab off the edge rather than a floating box. */
    position:fixed!important;
    left:50%!important;
    bottom:0!important;
    transform:translateX(-50%)!important;
    margin:0!important;
    /* One line, as wide as it needs: left:50% caps a fixed element's
       shrink-to-fit width at the remaining half of the viewport, so the
       credit word-wrapped whenever it was longer than half the card. */
    white-space:nowrap!important;
    width:max-content!important;
    pointer-events:none;
  }
</style>
</head><body>
<div id="map"></div>
<script>
  var coords=[$coordsJson];
  var fadedCoords=[$fadedCoordsJson];
  var map=L.map('map',{zoomControl:false,attributionControl:true});
  map.attributionControl.setPrefix('');
  map.attributionControl.setPosition('bottomleft');
  var baseLayer=null;
  // The downloaded map, under the online one: it shows where tiles fail.
  $offlineMapJs
  var offlineMap=eucOfflineMap(map);
  var refLayer=null;
  var refLayer2=null;
  var MAP_LAYERS = ${MapLayersJs.table()};
  window.setMapType=function(t){
    if(baseLayer) map.removeLayer(baseLayer);
    if(refLayer){ map.removeLayer(refLayer); refLayer=null; }
    if(refLayer2){ map.removeLayer(refLayer2); refLayer2=null; }
    // One table for every map in the app, credits included: see MapLayers.
    var layer = MAP_LAYERS[t] || MAP_LAYERS['OSM'];
    var opts = {maxZoom:21, maxNativeZoom:layer.maxNative, attribution:layer.attr};
    if (layer.subs) opts.subdomains = layer.subs;
    if (layer.retina) opts.detectRetina = true;
    baseLayer=L.tileLayer(layer.url, opts).addTo(map);
    // Esri Canvas labels ride on a separate reference layer; keep it under
    // the route but over the base. Leaflet collapses the duplicate credit.
    if (layer.ref2){ refLayer2=L.tileLayer(layer.ref2, opts).addTo(map); refLayer2.bringToBack(); }
    if (layer.ref){ refLayer=L.tileLayer(layer.ref, opts).addTo(map); refLayer.bringToBack(); }
    baseLayer.bringToBack();
    if (!offlineMap.prefer) offlineMap.layer.bringToBack();
    offlineMap.watch(baseLayer);
  };
  window.setMapType('$initialType');

  var hasRoute = coords.length >= 2;
  var start=null, end=null, overlap=null;
  // Every layer the trace owns, so it can be redrawn in place. Trims used to
  // reload the whole document, which threw away the rider's pan and zoom and
  // flashed the tiles; now only these layers change.
  var traceLayers=[];
  // Set the first time a real route is framed. Guards the case where the trace
  // arrives after the page loaded: that draw still gets its one framing.
  var everFit=false;
  function clearTrace(){
    for (var i=0;i<traceLayers.length;i++) map.removeLayer(traceLayers[i]);
    traceLayers=[];
  }

  function render(fit){
    clearTrace();
    if (hasRoute){
      // The rest of the ride, drawn very faint underneath so a trimmed view
      // still shows where the section sits in the whole trip. Empty when
      // untrimmed, which makes this a no-op and leaves that map unchanged.
      // Everything here is non-interactive: the solid layer owns every popup,
      // so a tap near a ghost never opens the wrong thing.
      if (fadedCoords.length >= 2){
        traceLayers.push(L.polyline(fadedCoords,
          {color:'#4FC3F7',weight:4,opacity:0.38,interactive:false}).addTo(map));
      }
      // Split the trace ONLY at genuine wheel changes (s.change): the first
      // wheel keeps the blue trace and a different wheel's stretch is drawn
      // purple. A same-wheel reconnect does NOT cut the trace - it stays one
      // colour, with just a dot marking where it dropped and resumed.
      var cuts = switches.filter(function(s){return s.change;})
        .map(function(s){return s.idx;})
        .filter(function(i){return i>0 && i<coords.length;})
        .sort(function(a,b){return a-b;});
      var prev = 0;
      for (var k=0;k<=cuts.length;k++){
        var stop = (k<cuts.length)?cuts[k]:coords.length-1;
        if (stop>prev) traceLayers.push(L.polyline(coords.slice(prev,stop+1),
          {color:k===0?'#4FC3F7':'#AB47BC',weight:4,interactive:false}).addTo(map));
        prev = stop;
      }
      // Only the FIRST draw frames the ride. After that the camera belongs to
      // the rider: trimming changes what is highlighted, never where the map
      // is looking or how far in.
      if (fit || !everFit){
        map.fitBounds(L.latLngBounds(coords).pad(0.2));
        everFit = true;
      }
      placeEndpoints();
    } else if (fit && coords.length === 1) {
      map.setView(coords[0], 17);
    } else if (fit) {
      map.setView([0,0], 2);
    }
  }

  function placeEndpoints(){
    if (!hasRoute) return;
    if (start){ map.removeLayer(start); start=null; }
    if (end){ map.removeLayer(end); end=null; }
    if (overlap){ map.removeLayer(overlap); overlap=null; }

    // Start and end always mark where the WHOLE ride began and finished, never
    // the ends of a trimmed section: those are an artefact of the view, and
    // putting the ride's start dot in the middle of a road reads as a lie.
    //
    // Each is dimmed only when it falls OUTSIDE the selection. Trimming from the
    // very beginning keeps the real start inside what is being looked at, so it
    // stays solid; it is the end, off past the edge, that ghosts.
    var track = (fadedCoords.length >= 2) ? fadedCoords : coords;
    var opA = startIncluded ? 1 : 0.45;
    var opB = endIncluded ? 1 : 0.45;
    var a = track[0], b = track[track.length-1];
    var pa = map.latLngToContainerPoint(a);
    var pb = map.latLngToContainerPoint(b);
    var dist = pa.distanceTo(pb);
    var r = 7; // circleMarker radius in px
    // Overlap is more than 50% of a marker's width: dist < 2*r*(1-0.5) = r.
    if (dist < r){
      // One combined marker, so it can only be solid when BOTH ends are in.
      overlap = L.marker(a,{opacity:Math.min(opA,opB),icon:L.divIcon({className:'half-marker',iconSize:[18,18],iconAnchor:[9,9]})}).addTo(map);
    } else {
      start = L.circleMarker(a,{radius:r,color:'#000',weight:2,opacity:opA,fillColor:'#66BB6A',fillOpacity:opA}).addTo(map);
      end   = L.circleMarker(b,{radius:r,color:'#000',weight:2,opacity:opB,fillColor:'#EF5350',fillOpacity:opB}).addTo(map);
    }
    // Start marker: the ride-start "Connected" popup. End marker: "Ride end".
    // When start and end overlap (loop rides) the single marker shows both.
    if (start && startLabel) start.bindPopup(startLabel);
    if (end && endLabel) end.bindPopup(endLabel);
    if (overlap){
      var combined = [startLabel, endLabel].filter(Boolean).join('<br>');
      if (combined) overlap.bindPopup(combined);
    }
  }

  var switches=$switchesJson;
  var fadedSwitches=$fadedSwitchesJson;
  var startIncluded=$startIncluded;
  var endIncluded=$endIncluded;
  var startLabel=$startLabelJs;
  var endLabel=$endLabelJs;

  // Live marker API (called from Kotlin via evaluateJavascript).
  var live=null, livePath=null;
  window.updateLivePoint = function(lat, lon){
    var p = [lat, lon];
    if (!live){
      live = L.marker(p,{icon:L.divIcon({className:'live-marker',iconSize:[14,14],iconAnchor:[7,7]})}).addTo(map);
      if (!hasRoute) map.setView(p, 17);
    } else {
      live.setLatLng(p);
    }
    // Grow the trace as the ride goes. The baked coords are pinned to the
    // open-time snapshot while recording (Kotlin stops feeding growth so the
    // page never reloads), so this live line carries every step after it. Seed
    // it from the last baked coord so it joins the existing trace rather than
    // starting detached.
    if (!livePath){
      var seed = coords.length ? [coords[coords.length-1], p] : [p];
      livePath = L.polyline(seed,{color:'#4FC3F7',weight:4,interactive:false}).addTo(map);
    } else {
      livePath.addLatLng(p);
    }
  };

  // Scrub marker API: a dot synced with the chart cursor. Pans into view only
  // if the point is off-screen, so scrubbing doesn't jerk the map around.
  var scrub=null;
  window.updateScrubPoint = function(lat, lon, label){
    var p = [lat, lon];
    if (!scrub){
      scrub = L.circleMarker(p,{radius:7,color:'#fff',weight:2,fillColor:'#FFC107',fillOpacity:1}).addTo(map);
      // Permanent: a finger is already busy dragging the chart, so there is no
      // second one free to hover or tap the dot for it.
      if (label) scrub.bindTooltip(label,{permanent:true,direction:'top',offset:[0,-6],className:'scrub-tip'});
    } else {
      scrub.setLatLng(p);
      if (label){
        if (scrub.getTooltip()) scrub.setTooltipContent(label);
        else scrub.bindTooltip(label,{permanent:true,direction:'top',offset:[0,-6],className:'scrub-tip'});
      } else if (scrub.getTooltip()) scrub.unbindTooltip();
    }
    if (!map.getBounds().contains(p)) map.panTo(p,{animate:true,duration:0.25});
  };
  window.clearScrubPoint = function(){
    if (scrub){ map.removeLayer(scrub); scrub=null; }
  };
  // The compared trip (B) at the same moment: its own dot, no route.
  var cmp=null;
  window.updateComparePoint = function(lat, lon, color){
    var p = [lat, lon];
    if (!cmp) cmp = L.circleMarker(p,{radius:6,color:'#fff',weight:2,fillColor:color,fillOpacity:1}).addTo(map);
    else { cmp.setLatLng(p); cmp.setStyle({fillColor:color}); }
  };
  window.clearComparePoint = function(){
    if (cmp){ map.removeLayer(cmp); cmp=null; }
  };

  var badgeLayers=[];
  function drawBadges(){
  for (var i=0;i<badgeLayers.length;i++) map.removeLayer(badgeLayers[i]);
  badgeLayers=[];
  // Wheel-identity badges from the stretches the trim cut away. Same shapes as
  // the live ones so they read as the same thing, just ghosted, and with no
  // popup: they are context, and the trimmed section owns the interaction.
  // Added before the solid badges so those stack on top where they coincide.
  fadedSwitches.forEach(function(s){
    var cls = s.change ? 'wheel-badge wheel-change' : 'wheel-badge wheel-reconnect';
    var sz = s.change ? 13 : 10;
    var icon = L.divIcon({
      className:'faded-badge', html:'<div class="'+cls+'"></div>',
      iconSize:[sz,sz], iconAnchor:[sz/2,sz/2]
    });
    badgeLayers.push(L.marker([s.lat,s.lon],{icon:icon,interactive:false}).addTo(map));
  });
  // A small square for each identity block after the ride start, each with its
  // own popup (time + wheel). A genuine wheel change is a purple square where
  // the trace colour also switches; a same-wheel reconnect is a smaller grey
  // square on the unbroken trace, flagging where it dropped and resumed. Square
  // so they don't read as another position dot, and both smaller than the
  // start/end markers. Added after render() so they stack above the trace and
  // keep their tap target.
  switches.forEach(function(s){
    var cls = s.change ? 'wheel-badge wheel-change' : 'wheel-badge wheel-reconnect';
    var sz = s.change ? 13 : 10;
    var icon = L.divIcon({
      className:'', html:'<div class="'+cls+'"></div>',
      iconSize:[sz,sz], iconAnchor:[sz/2,sz/2]
    });
    badgeLayers.push(L.marker([s.lat,s.lon],{icon:icon}).addTo(map).bindPopup(s.label));
  });
  }

  // Trace API (called from Kotlin via evaluateJavascript). Everything a trim
  // changes - the solid stretch, the ghost behind it, which badges are faded,
  // which endpoints are dimmed - is applied here WITHOUT touching the camera.
  window.setTrace = function(c, fc, sw, fsw, sIn, eIn){
    coords=c; fadedCoords=fc; switches=sw; fadedSwitches=fsw;
    startIncluded=sIn; endIncluded=eIn;
    hasRoute = coords.length >= 2;
    render(false);
    drawBadges();
  };

  render(true);
  drawBadges();
  // Bound once, not per draw: rebinding on every redraw stacked a fresh
  // listener each time and re-placed the endpoints N times per pan.
  map.on('zoomend moveend', placeEndpoints);
  ${if (isLive) "/* live mode: waiting for updateLivePoint() */" else ""}
</script></body></html>
""".trimIndent()
}
