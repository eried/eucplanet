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
<!-- Rotation plugin: adds map.setBearing/getBearing and a two-finger touch
     rotate gesture. Vanilla Leaflet 1.9 has no rotation support, so this
     plugin patches Map / Marker / TileLayer to track a bearing. We
     instantiate the map with rotate:true below to opt in. -->
<script src="leaflet-rotate.js"></script>
<style>
  html,body,#map{margin:0;padding:0;width:100%;height:100%;background:#0b0f19;}
  /* Suppress all tile transitions / opacity ramps. Leaflet's default
     CSS fades each tile in via an opacity transition; with our 60 Hz
     auto-follow tween that fade is restarted on tiles entering /
     leaving the buffer ring, which the rider sees as the map "blinking"
     while it pans. Hard-pinning each tile to opaque + no transitions
     stops the flicker entirely. */
  .leaflet-tile { opacity: 1 !important; transition: none !important; }
  .leaflet-tile-container { transition: none !important; }
  .leaflet-fade-anim .leaflet-tile { opacity: 1 !important; }
  /* Force GPU compositing on the panes we animate. Without explicit
     hints, the browser would re-decide between CPU and GPU compositing
     each frame and the in-between layer was where the white/blue flash
     came from. translate3d coerces a hardware layer; backface-visibility
     hidden + transform-style preserve-3d keep the layer rendered through
     sub-pixel transform updates. */
  .leaflet-pane,
  .leaflet-tile-pane,
  .leaflet-tile-container,
  .leaflet-rotate-pane {
    backface-visibility: hidden;
    -webkit-backface-visibility: hidden;
    transform-style: preserve-3d;
    will-change: transform;
  }
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
  /* Stops the rider already reached during the active navigation. Drawn
     as a small flag so the eye can scan "future" stops past the done
     ones without confusing them with the next-stop / final pins. The
     shadow keeps the icon readable on light basemap tiles. */
  .wp-passed{
    width:24px;height:24px;
    line-height:0;
    filter: drop-shadow(0 1px 2px rgba(0,0,0,0.6));
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
  // Rotate-capable map. touchRotate enables a 2-finger twist gesture; the
  // built-in control button is hidden (we just want the gesture). bearing
  // starts at 0 (north up) and the auto-follow logic below adjusts it to
  // match userHeading while riding -- unless the rider has just manually
  // rotated, in which case we pause the auto-follow for a few seconds.
  var map = L.map('map', {
    zoomControl: false,
    attributionControl: false,
    rotate: true,
    touchRotate: true,
    bearing: 0,
    rotateControl: false,
    // Pin pinch-zoom to the map's pixel center instead of the touch
    // midpoint. The plugin processes rotate + zoom + pan together inside
    // one two-finger gesture; when the zoom anchor is the touch midpoint,
    // small unintended pinch creep shifts the center mid-rotate and the
    // rotated route line / radius circles drift away from their markers.
    // Pinning to 'center' keeps the rotation pivot fixed.
    touchZoom: 'center',
    // Allow fractional zoom so the auto-fit tween can creep smoothly
    // (default zoomSnap=1 snaps to integer steps which would jolt the
    // map between integer zoom levels in a tween loop).
    zoomSnap: 0,
    // Suppress per-tile fade-in. With the auto-follow tween calling
    // setView every animation frame, tiles that flip to a new visual
    // scale would fade in 60 times a second, reading as a constant
    // background "blink" while panning + rotating.
    fadeAnimation: false,
    // Don't run Leaflet's own zoom animator -- we already tween the
    // zoom manually each frame, and the built-in animator would layer
    // a second CSS transform on top of ours, producing a brief mid-
    // animation visual hiccup ("blink").
    zoomAnimation: false,
    markerZoomAnimation: false
  });
  var tileLayer = null;

  // --- Auto-orient: snap the map's bearing to follow the rider's heading,
  // but back off whenever the rider twists the map manually so we don't
  // fight their gesture. Leaflet-rotate uses CSS-convention bearing
  // (positive = CW). To put the rider's heading "up" on screen, world
  // direction θ has to show at screen θ - bearing; setting bearing =
  // heading parks the heading direction at the top of the screen.
  var autoFollowHeading = true;
  var lastManualRotateMs = 0;
  var twoFingerActive = false;
  // After the rider lets go of a two-finger gesture, wait this long before
  // resuming heading-up auto-follow.
  var MANUAL_ROTATE_HOLD_MS = 6000;

  // Detect manual two-finger gestures on the map container. The plugin's
  // TouchGestures handler does its own thing; we just need to know when
  // the rider is touching with two fingers so we can pause the auto-follow
  // tween (otherwise the next animation frame snaps the map back and the
  // rider's twist appears to do nothing / drift).
  var mapEl = document.getElementById('map');
  mapEl.addEventListener('touchstart', function(e){
    if (e.touches.length >= 2) {
      twoFingerActive = true;
      lastManualRotateMs = Date.now();
    }
  }, { passive: true });
  // During a 2-finger gesture, leaflet-rotate updates marker positions
  // continuously (every frame) but the SVG path layers only re-transform
  // on the moveend / zoom 'tween' boundaries. Inside the gesture, route
  // line + radius circles visually lag the markers, so the trace appears
  // to drift away from the stop pins. We force a renderer redraw on every
  // touchmove, throttled to one per animation frame so we don't melt the
  // CPU. The throttle drops back to false on the next rAF tick.
  var redrawPending = false;
  function redrawPathsNow(){
    if (routeLine) routeLine.redraw();
    rings.forEach(function(r){ r.redraw(); });
    if (connector) connector.redraw();
  }
  mapEl.addEventListener('touchmove', function(e){
    // ANY touch movement (single or two-finger) counts as the rider
    // manually moving the map -- single-finger pans should pause the
    // auto-follow camera too, otherwise the moment they release the
    // map snaps back and undoes what they were trying to see.
    lastManualRotateMs = Date.now();
    if (e.touches.length >= 2) {
      if (!redrawPending) {
        redrawPending = true;
        requestAnimationFrame(function(){
          redrawPending = false;
          redrawPathsNow();
        });
      }
    }
  }, { passive: true });
  mapEl.addEventListener('touchend', function(e){
    if (e.touches.length === 0) {
      twoFingerActive = false;
      lastManualRotateMs = Date.now();
    }
  }, { passive: true });
  mapEl.addEventListener('touchcancel', function(){
    twoFingerActive = false;
    lastManualRotateMs = Date.now();
  }, { passive: true });

  // Re-render the rider marker on bearing changes so its head keeps
  // pointing at the rider's world heading, not the screen-relative one.
  // (Markers sit in the no-rotate pane and don't rotate with the map.)
  //
  // THROTTLED: only rebuild when the bearing has moved at least 1.5 deg
  // since the last rebuild. Without this, the auto-follow tween fires
  // setBearing at 60 Hz and each fired 'rotate' event would tear down
  // and re-create the entire marker divIcon DOM -- a per-frame DOM
  // rebuild that the browser couldn't paint cleanly, producing a visible
  // flash on every pan / zoom / rotate tick.
  var lastIconBearing = NaN;
  map.on('rotate', function(){
    if (!userMarker) return;
    var b = map.getBearing();
    if (isNaN(lastIconBearing) || Math.abs(b - lastIconBearing) >= 1.5) {
      lastIconBearing = b;
      userMarker.setIcon(buildUserIcon());
    }
  });

  // Auto-follow is ONLY active while a navigation session is running
  // (navLocked == true). With no nav, the rider may be planning a route
  // and a self-rotating / self-panning map would be hostile. Within a
  // running nav we both:
  //   1) Slowly rotate the map so the rider's heading is at screen-top.
  //   2) Slowly pan the map so the rider's marker stays at the centre of
  //      the visible (non-occluded) area.
  // Both use the same heavy tween rate so the motion feels like one
  // continuous, deliberate camera move rather than two separate effects.
  //
  // ROTATE_TWEEN_RATE -- fraction of remaining delta applied per frame.
  //   0.18 used to be ~0.4 s to settle (too snappy; GPS jitter rocked
  //   the map back and forth). 0.04 is ~2 s to settle: "heavy steering
  //   wheel" feel, and short heading wobbles never propagate visibly.
  // ROTATE_START_DELAY_MS -- how long the rider must be MOVING before
  //   we start rotating at all. Prevents a quick correction at a
  //   stoplight from triggering a full half-rotation the rider would
  //   have to wait to unwind.
  var ROTATE_TWEEN_RATE = 0.015;
  var ROTATE_START_DELAY_MS = 3000;
  // Duration of the cosine-ease rotation when a new target heading
  // settles in. The tween still re-targets continuously (smoothedHeading
  // chases userHeading at ROTATE_TWEEN_RATE), but the actual map
  // setBearing call interpolates from where the bearing IS to where the
  // smoothed heading SAYS over this duration with an ease-in-ease-out
  // curve, which feels more deliberate than the exponential decay.
  var ROTATE_EASE_MS = 3500;
  // Auto-zoom is DISABLED in the tween for now (rate 0). Each tween call
  // to setView would briefly invoke GridLayer._setView, and any tween
  // step that crossed an integer rounded-zoom boundary would trigger a
  // level-container swap inside Leaflet -- the old level's tiles got
  // unmounted before the new level's tiles finished loading, so the
  // entire tile layer briefly disappeared. Rather than fight Leaflet's
  // tile bookkeeping any further during a continuous tween, we pin the
  // zoom to wherever the rider was when navigation started (or last
  // pressed Recenter). They can adjust it manually with a pinch; the
  // pan + rotate auto-follow still works.
  var ZOOM_TWEEN_RATE = 0.0;
  // Padding kept around even though auto-zoom is off, in case we re-
  // enable a constrained variant later.
  var ZOOM_FIT_PADDING_PX = 80;
  // CSS-pixel offset for vertical visible-centre. Pushed in from Kotlin
  // whenever the bottom panel / top bar size changes -- 0 until
  // measured. The pan tween puts the rider's lat/lng above the map's
  // geometric centre by this many CSS px.
  var navRecenterOffsetPx = 0;
  window.nativeSetRecenterOffset = function(px){
    navRecenterOffsetPx = px || 0;
  };
  var movingSinceMs = 0;
  // The smoothed heading the auto-follow tween chases. NaN means "no
  // value yet"; will be set the first time userMoving flips to true.
  var smoothedHeading = NaN;
  // Current ease-in-ease-out rotation animation. null means "no active
  // rotation, current bearing IS the target". Otherwise has {from,
  // delta, to, startMs}.
  var bearingEase = null;
  function tickAutoFollow(){
    var now = Date.now();
    var canFollow = autoFollowHeading && navLocked && !twoFingerActive &&
        now - lastManualRotateMs > MANUAL_ROTATE_HOLD_MS;
    if (!canFollow) {
      movingSinceMs = 0;
      smoothedHeading = NaN;
      requestAnimationFrame(tickAutoFollow);
      return;
    }
    // Auto-pan + auto-zoom. Runs even when the rider is stationary so a
    // pan-and-release drifts back to centre. We compute the desired
    // pan target and the desired zoom, then commit them in a SINGLE
    // map.setView call -- separate panTo + setZoom each frame caused
    // Leaflet to refresh its layer transforms twice per tick, which
    // showed up as the map "blinking" while the auto-pan and auto-zoom
    // tweens were both active.
    if (userMarker) {
      var riderLL = userMarker.getLatLng();
      var currentZoom = map.getZoom();
      // Target zoom: fit (rider, next stop) in view via direct Mercator
      // math (more reliable than map.getBoundsZoom on a rotated map).
      // Solve 2^Z = visible_px * 156543 * cos(lat) / distance_metres
      // for Z. Subtract 1 so the rider has a bit of breathing room
      // around both points instead of fitting them edge-to-edge.
      var newZoom = currentZoom;
      if (nextActiveMarkerIdx >= 0 && nextActiveMarkerIdx < markers.length) {
        var nextLL = markers[nextActiveMarkerIdx].getLatLng();
        var distM = riderLL.distanceTo(nextLL);
        if (distM < 1) distM = 1;
        var size = map.getSize();
        var visW = Math.max(64, size.x - 2 * ZOOM_FIT_PADDING_PX);
        var occluded = 2 * Math.abs(navRecenterOffsetPx);
        var visH = Math.max(64, size.y - occluded - 2 * ZOOM_FIT_PADDING_PX);
        var visible = Math.min(visW, visH);
        var lat = (riderLL.lat + nextLL.lat) / 2;
        var mPerPxZ0 = 156543.03392 * Math.cos(lat * Math.PI / 180);
        var targetZoom = Math.log2(visible * mPerPxZ0 / distM) - 1;
        targetZoom = Math.max(10, Math.min(19, targetZoom));
        if (Math.abs(targetZoom - currentZoom) > 0.05) {
          newZoom = currentZoom + (targetZoom - currentZoom) * ZOOM_TWEEN_RATE;
        }
      }
      // Target centre: the midpoint between the rider and the next
      // stop, so BOTH points stay symmetrically inside the visible
      // (unoccluded) area at the current zoom. When there is no next
      // stop, fall back to the rider's own position. The
      // panel-offset shift still applies so the visible centre lands
      // in the unoccluded zone (between top bar and stops flyout).
      var centerLat = riderLL.lat;
      var centerLng = riderLL.lng;
      if (nextActiveMarkerIdx >= 0 && nextActiveMarkerIdx < markers.length) {
        var nextLL2 = markers[nextActiveMarkerIdx].getLatLng();
        centerLat = (riderLL.lat + nextLL2.lat) / 2;
        centerLng = (riderLL.lng + nextLL2.lng) / 2;
      }
      var targetCenter = centerLatLngFor(
        centerLat, centerLng, newZoom, navRecenterOffsetPx
      );
      var c = map.getCenter();
      var dLat = targetCenter.lat - c.lat;
      var dLng = targetCenter.lng - c.lng;
      var lerpedCenter = [
        c.lat + dLat * ROTATE_TWEEN_RATE,
        c.lng + dLng * ROTATE_TWEEN_RATE
      ];
      var moved = Math.abs(dLat) > 1e-7 || Math.abs(dLng) > 1e-7;
      var zoomed = Math.abs(newZoom - currentZoom) > 1e-4;
      if (moved || zoomed) {
        // Tell the tile layer to freeze tile DOM mutation for this
        // tick + a small buffer. _setView still updates the SVG
        // renderer and the markers' positions, but the tile layer
        // only re-applies the visual scale -- existing tiles stay,
        // no _addTile/_removeTile churn, no level swap when crossing
        // an integer zoom boundary. The freeze auto-releases when
        // the tween stops calling setView for >300 ms.
        window.tileFreezeTillMs = Date.now() + 300;
        map.setView(lerpedCenter, newZoom, { animate: false });
      }
    }
    // Auto-rotate: only while actually moving (so a stopped rider isn't
    // being spun by GPS heading noise).
    if (!userMoving) {
      movingSinceMs = 0;
      smoothedHeading = NaN;
      requestAnimationFrame(tickAutoFollow);
      return;
    }
    if (movingSinceMs === 0) movingSinceMs = now;
    if (isNaN(smoothedHeading)) smoothedHeading = userHeading;
    var hDelta = ((userHeading - smoothedHeading + 540) % 360) - 180;
    smoothedHeading = (smoothedHeading + hDelta * ROTATE_TWEEN_RATE + 360) % 360;
    if (now - movingSinceMs < ROTATE_START_DELAY_MS) {
      requestAnimationFrame(tickAutoFollow);
      return;
    }
    // leaflet-rotate's setBearing(B) applies `rotate(B deg)` CSS to the
    // rotatePane, which rotates content CW by B. To put the rider's
    // heading H at the TOP of the screen we need B = -H.
    //
    // Ease-in-ease-out using a cosine curve, retargeted any time the
    // smoothed heading drifts >0.5 deg from the current ease target.
    // The exponential-decay tween we used before only eased OUT (fast
    // at start, slow at end); riders wanted both halves of the curve
    // so a course correction starts gently before accelerating into
    // the rotation.
    var target = -smoothedHeading;
    var current = map.getBearing();
    var delta = ((target - current + 540) % 360) - 180;
    if (Math.abs(delta) > 0.5) {
      // Retarget the ease whenever the desired bearing has shifted
      // notably from where we were heading. The from-value is what we
      // BLEND from -- the current map bearing, so the curve picks up
      // from wherever the previous tween left off without a jolt.
      if (!bearingEase ||
          Math.abs(((bearingEase.to - target + 540) % 360) - 180) > 0.5) {
        bearingEase = {
          from: current,
          delta: delta,
          to: target,
          startMs: now
        };
      }
      var p = (now - bearingEase.startMs) / ROTATE_EASE_MS;
      if (p >= 1) {
        map.setBearing(bearingEase.to);
        bearingEase = null;
      } else {
        // "Smootherstep" -- 6t^5 - 15t^4 + 10t^3. Compared to a cosine
        // ease (0.5 * (1 - cos(pi*t))) this is FLATTER at both ends and
        // STEEPER in the middle, giving the "slow slow slow ---- ROTATE
        // FAST ---- slow slow slow" feel the rider asked for.
        var eased = p * p * p * (p * (p * 6 - 15) + 10);
        map.setBearing(bearingEase.from + bearingEase.delta * eased);
      }
    } else {
      bearingEase = null;
    }
    requestAnimationFrame(tickAutoFollow);
  }
  // Kick the loop once after the page settles -- userHeading + userMoving are
  // updated by nativeSetUserHeading / nativeSetUserStill from Kotlin.
  setTimeout(function(){ requestAnimationFrame(tickAutoFollow); }, 500);

  // Swappable base map: dark / light streets / satellite imagery, all key-less.
  window.nativeSetMapType = function(type){
    var url, opts;
    // keepBuffer: 4 -- the default 2 leaves a ring of unloaded tiles JUST
    // outside the visible area. On a rotated map, those unloaded corners
    // peek INTO the visible region as the map auto-pans / auto-rotates,
    // flashing the dark background between tiles ("blue blink"). Loading
    // a fatter ring of tiles around the viewport hides the background.
    // updateWhenIdle: false -- we want tiles to keep loading while the
    // tween is in flight, not waiting for it to settle.
    if (type === 'SATELLITE'){
      url = 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}';
      opts = {maxZoom:19, keepBuffer:8, updateWhenIdle:false, updateWhenZooming:false, updateInterval:1000};
    } else if (type === 'LIGHT'){
      url = 'https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png';
      opts = {maxZoom:19, subdomains:'abcd', keepBuffer:8, updateWhenIdle:false, updateWhenZooming:false, updateInterval:1000};
    } else {
      url = 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png';
      opts = {maxZoom:19, subdomains:'abcd', keepBuffer:8, updateWhenIdle:false, updateWhenZooming:false, updateInterval:1000};
    }
    if (tileLayer){ map.removeLayer(tileLayer); }
    tileLayer = L.tileLayer(url, opts).addTo(map);
    // ---- Deep-clamp tile-layer mutation during the auto-follow tween.
    //
    // Symptoms we are fighting:
    //   * Every setView call ends up in GridLayer._setView, which
    //     reaches _updateLevels + _resetGrid + _update + _pruneTiles.
    //     At 60 Hz, this is a continuous DOM churn that makes the tile
    //     pane "blink".
    //   * When the zoom crosses an integer boundary, _updateLevels
    //     swaps the visible level container -- the old level is
    //     hidden BEFORE the new one is fully ready, so the whole
    //     layer briefly disappears.
    //
    // The fix: while the auto-follow tween is in flight (any time the
    // window flag tileFreezeTillMs is in the future), the tile layer's
    // _setView becomes a no-op that only re-applies the visual scale.
    // Tiles stay in place (they're at the integer zoom that was active
    // when the freeze started); they just stretch / compress via CSS
    // to match the fractional zoom. Once the freeze releases, normal
    // _setView resumes and tiles re-fetch at the right resolution.
    // Surgical patches on the tile layer to make continuous setView
    // calls (the auto-follow tween) gentle:
    //
    // 1) `_invalidateAll` permanently no-op'd. The default fires on
    //    viewprereset and yanks every tile DOM element, then setView
    //    adds them back -- a one-frame gap with the background visible.
    //    Real tile-source switches go through removeLayer+addLayer.
    //
    // 2) `_updateLevels` keeps ALL existing level containers in the
    //    DOM. The default removes any level with no children, and the
    //    sister-call _pruneTiles then empties the old level when zoom
    //    changes -- so the moment we cross an integer zoom boundary,
    //    the old level loses its tiles AND its container, before the
    //    new level's tiles have finished loading. With this override,
    //    the previous-zoom container (and its already-loaded tiles)
    //    stays parked behind the new level until manually replaced --
    //    no visible "blue" gap during zoom transitions.
    tileLayer._invalidateAll = function () {};
    tileLayer._updateLevels = function () {
      var zoom = this._tileZoom;
      var maxZoom = this.options.maxZoom;
      if (zoom === undefined) return undefined;
      // Just re-stack z-indices; don't remove any level container.
      for (var z in this._levels) {
        var zn = Number(z);
        this._levels[z].el.style.zIndex = maxZoom - Math.abs(zoom - zn);
        if (this._onUpdateLevel) this._onUpdateLevel(zn);
      }
      var level = this._levels[zoom];
      if (!level) {
        level = this._levels[zoom] = {};
        level.el = L.DomUtil.create(
          'div', 'leaflet-tile-container leaflet-zoom-animated', this._container
        );
        level.el.style.zIndex = maxZoom;
        level.origin = this._map.project(
          this._map.unproject(this._map.getPixelOrigin()), zoom
        ).round();
        level.zoom = zoom;
        this._setZoomTransform(level, this._map.getCenter(), this._map.getZoom());
        L.Util.falseFn(level.el.offsetWidth);
        if (this._onCreateLevel) this._onCreateLevel(level);
      }
      this._level = level;
      return level;
    };
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
  var routeLine = null;   // solid: the solved route (next leg only when nav)
  var connector = null;   // dashed: drag / pre-route preview
  var previewLine = null; // dashed: nav preview from next stop through the
                          // rest of the remaining stops (no routing, straight)
  // Index of the first non-passed marker in [markers]. -1 when every
  // marker is passed (or there are no markers). Used by the auto-follow
  // tween so it pans toward the next ACTIVE stop, never a done one.
  var nextActiveMarkerIdx = -1;
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
    // The colours assume the marker array INCLUDES passed stops at the
    // head -- the caller filters / inspects passed at the marker site
    // before deciding green vs orange / flag. This helper is the
    // baseline orange.
    return '#FFA726';
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
  // A stop the rider has already reached during this nav. Drawn as a
  // small grey flag (pole + waving banner with a check) so it clearly
  // reads as "done", but is still tappable to recentre on its old
  // position. Anchor at the pole's base so the icon sits ON the stop
  // coords (not floating above them).
  function iconForPassed(){
    return L.divIcon({
      className:'',
      html:'<div class="wp-passed">' +
        '<svg viewBox="0 0 24 24" width="24" height="24">' +
        // Pole (vertical line).
        '<rect x="4" y="3" width="2.5" height="18" fill="#3A3F4A"/>' +
        // Flag banner: white with a thin grey border.
        '<path d="M6.5 4 H19 L15.5 8 L19 12 H6.5 Z" ' +
        'fill="#FFFFFF" stroke="#3A3F4A" stroke-width="0.8"/>' +
        '</svg></div>',
      // 24x24 icon; anchor at the bottom of the pole so the flag plants
      // ON the stop's lat/lng.
      iconSize:[24,24], iconAnchor:[5,22]
    });
  }

  function markerPts(){
    // Passed stops are EXCLUDED -- the drag connector should treat them
    // as if they aren't part of the in-progress route. Keeps the dashed
    // preview line away from already-completed flags while the rider
    // re-arranges the pending stops.
    return markers
      .filter(function(m){ return !m._passed; })
      .map(function(m){ var ll = m.getLatLng(); return [ll.lat, ll.lng]; });
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

  // During navigation the solid `routeLine` only spans the active leg
  // (origin -> next stop). The remaining stops (next -> rest) are shown
  // as a straight-line dashed preview so the rider knows the
  // upcoming order without committing routing budget for legs they
  // haven't reached yet. previewLine is regenerated on every
  // nativeRender call -- cheap, runs only when navLocked && >=2 stops.
  function clearPreview(){
    if (previewLine){ map.removeLayer(previewLine); previewLine = null; }
  }
  function drawPreview(wps){
    clearPreview();
    if (!wps) return;
    // Only the NON-PASSED stops form the upcoming preview chain --
    // passed stops are done and shouldn't be connected by lines any
    // more. We need at least two of them to draw a connector at all.
    var active = wps.filter(function(w){ return !w.passed; });
    if (active.length < 2) return;
    var pts = active.map(function(w){ return [w.lat, w.lng]; });
    previewLine = L.polyline(pts, {
      color: '#FFA726',
      weight: 4,
      opacity: 0.75,
      dashArray: '8 9',
      interactive: false
    }).addTo(map);
    drawArrows(pts, '#FFA726');
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
  // Arrows are rendered in screen-space, so refresh on every event that
  // changes the projection of the route into the viewport: zoom, pan AND
  // rotate. Without 'rotate' here, the chevrons kept their pre-rotation
  // angle after each auto-follow tween step and looked tilted off the
  // route line.
  // Redraw arrows only when their CSS rotation can have meaningfully
  // changed -- i.e., the map bearing has shifted enough that the
  // chevron's screen-pixel direction is now noticeably off the segment.
  // Pure pan doesn't need a redraw (the arrow markers are anchored to
  // a lat/lng and Leaflet moves them with the map automatically).
  // Pure zoom only changes spacing, not direction.
  // The previous code redrew on every `move` -- with the auto-follow
  // tween firing setView 60 Hz, that was a full clearArrows + N new
  // divIcons per frame: blink.
  var lastArrowBearing = NaN;
  function maybeRedrawArrows() {
    if (travelMode !== 'STRAIGHT' || !routeLine) return;
    var b = map.getBearing();
    if (!isNaN(lastArrowBearing) && Math.abs(b - lastArrowBearing) < 5) return;
    lastArrowBearing = b;
    var geom = routeLine.getLatLngs().map(function (ll) { return [ll.lat, ll.lng]; });
    drawArrows(geom, routeColorFor(travelMode));
  }
  // moveend / zoomend cover the "final" redraw once the auto-follow
  // settles, so the arrows snap to the exact bearing without waiting
  // for a 5 deg threshold to trip.
  map.on('rotate moveend zoomend', maybeRedrawArrows);

  window.nativeRender = function(wpJson, geomJson, fit){
    var wps = JSON.parse(wpJson);
    var geom = JSON.parse(geomJson);
    // Cache the latest payload so refreshRouteVisibility() (called when
    // the user marker crosses a passed flag's radius boundary) can
    // re-render without waiting for a native nativeRender call.
    lastWps = wps;
    lastGeom = geom;
    userInsidePassedFlag = riderInsideAnyPassedFlag();

    markers.forEach(function(m){ if (m) map.removeLayer(m); });
    rings.forEach(function(r){ if (r) map.removeLayer(r); });
    markers = [];
    rings = [];
    // Find the FIRST non-passed stop. While navLocked, it gets the green
    // "next goal" colour; everything else (passed or future) is orange.
    var firstActiveIdx = -1;
    for (var k = 0; k < wps.length; k++) {
      if (!wps[k].passed) { firstActiveIdx = k; break; }
    }
    nextActiveMarkerIdx = firstActiveIdx;
    wps.forEach(function(w, i){
      var isPassed = !!w.passed;
      var isNext = navLocked && !isPassed && i === firstActiveIdx;
      var stopColor = isNext ? '#66BB6A' : (isPassed ? '#8C8C90' : '#FFA726');
      // Passed stops don't get a radius ring -- they're done and clutter
      // the map. Future stops get the dotted ring as before.
      if (!isPassed) {
        var ring = L.circle([w.lat, w.lng], {
          radius: w.radius || 40,
          color: stopColor,
          weight: navLocked ? 3 : 2,
          opacity: 0.9,
          fill: true,
          fillColor: stopColor,
          fillOpacity: navLocked ? 0.18 : 0.10,
          dashArray:'5 7', interactive:false
        });
        ring.addTo(map);
        rings.push(ring);
      } else {
        rings.push(null);  // keep index alignment with markers[]
      }
      var m = L.marker([w.lat, w.lng], {
        draggable: !navLocked && !isPassed,
        icon: isPassed
          ? iconForPassed()
          : (navLocked ? iconForLocked(stopColor) : iconFor(i + 1, stopColor))
      });
      m.on('dragstart', function(){
        // Drop the solved route; show the dashed preview while dragging.
        if (routeLine){ map.removeLayer(routeLine); routeLine = null; }
        drawConnector();
        // Tell Kotlin so it suppresses route recompute (GPS jitter would
        // otherwise yank the preview out from under the rider's finger).
        if (window.AndroidNav && AndroidNav.onMarkerDragStart) {
          AndroidNav.onMarkerDragStart();
        }
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
      m._passed = isPassed;
      m.addTo(map);
      markers.push(m);
    });

    // If every stop is passed, the trip is over. Drop the leftover
    // route line, dashed previews and arrows so the map shows only
    // the planted flags. (Without this, the JS would otherwise fall
    // into the 'keep previous line because markers exist' branch and
    // strand a green stub on the map.)
    var allPassed = wps.length > 0 && wps.every(function(w){ return !!w.passed; });
    // Right after the rider passes a stop they're standing INSIDE that
    // flag's arrival radius -- the new leg's origin is the same point,
    // so a line would visibly originate from the flag and read as a
    // 'leftover path to the goal'. We suppress the routeLine until the
    // rider has moved beyond the flag's radius; nativeSetUser's
    // boundary detector re-renders when that happens.
    if (allPassed) {
      if (routeLine) { map.removeLayer(routeLine); routeLine = null; }
      clearArrows();
      clearConnector();
      clearPreview();
    } else if (userInsidePassedFlag) {
      // Rider on a just-passed flag: hide the leg line + arrows; keep
      // the dashed straight-line preview through the remaining stops
      // so the rider can still see the order.
      if (routeLine) { map.removeLayer(routeLine); routeLine = null; }
      clearArrows();
      clearConnector();
      drawPreview(wps);
    } else if (geom.length >= 2){
      // A solved route — solid line, drop the dashed preview. Colour depends
      // on the travel mode (DRIVE/BIKE/WALK/STRAIGHT). Slightly thicker and
      // 80% opaque so the path stands out without obscuring the basemap.
      if (routeLine){ map.removeLayer(routeLine); routeLine = null; }
      clearArrows();
      clearConnector();
      var routeColor = routeColorFor(travelMode);
      routeLine = L.polyline(geom, {color: routeColor, weight: 6, opacity: 0.80}).addTo(map);
      // STRAIGHT routes get direction arrows along the line — without road
      // geometry there's no other directional cue.
      if (travelMode === 'STRAIGHT') drawArrows(geom, routeColor);
      // During navigation the solid leg only spans origin -> next stop.
      // Continue the visual line through the remaining stops with a
      // dashed straight-line preview so the rider knows the order
      // before reaching the next stop.
      drawPreview(wps);
    } else if (markers.length >= 1){
      // No geometry yet (routing in progress).
      //
      // The dashed preview-connector is reserved for the rider actively
      // DRAGGING a stop -- the marker drag handlers (m.on('drag', ...)
      // below) call drawConnector themselves. Everywhere ELSE -- mid-
      // recompute, mid-navigation re-solve, even just-after-add-stop --
      // we leave the previous solid routeLine in place and DON'T draw
      // dashes, because the flicker between solid and dashed was
      // distracting. The router request usually returns within a second
      // and the solid line refreshes; if it fails the rider's existing
      // line is still a reasonable approximation.
      // Intentionally NOT calling drawConnector or clearing routeLine
      // here -- routeLine + arrows + (no-op) connector stay as they
      // were.
    } else {
      if (routeLine){ map.removeLayer(routeLine); routeLine = null; }
      clearArrows();
      clearConnector();
      clearPreview();
    }

    if (fit){
      var pts = (geom.length >= 2) ? geom : wps.map(function(w){ return [w.lat, w.lng]; });
      if (pts.length >= 2){
        // maxZoom: 17 keeps the auto-fit from snapping to the highest
        // possible level on tight bounds (origin a few meters from the
        // first stop) -- without it the rider saw a 'street-level' flash
        // on first map open before the next render relaxed the zoom.
        map.fitBounds(L.latLngBounds(pts).pad(0.25), { maxZoom: 17 });
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
    // Marker head diameter in px. The plain (no-photo) marker is sized to
    // sit unobtrusively but still be tappable / visible against busy tiles;
    // the customized one is larger so the avatar inside reads clearly.
    var headPx = hasPhoto ? 42 : 26;
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
    // Moving: full teardrop with the SHARP TIP pointing in the direction
    // of travel -- riders read the pointy end as the compass-needle
    // forward direction, NOT the round head. (Putting the head forward
    // looked correct to me as the designer but every rider tester said
    // "the arrow's backwards".) The CSS rotates .user-pin-body by -45 deg
    // which puts the head at top / tail at bottom when the wrapper isn't
    // rotated, so we add 180 deg here to bring the tail to the top, then
    // add userHeading + bearing to point that tail at the heading
    // direction on the rotated map (heading-up: bearing = -heading,
    // collapses to rot = 180 with tail straight up).
    var bearing = (map && map.getBearing) ? map.getBearing() : 0;
    var rot = userHeading + bearing + 180;
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
    var wasInside = userInsidePassedFlag;
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
    // Re-evaluate "rider inside a passed flag's radius"; when this flips
    // we need to redraw so the routeLine appears (rider left the flag)
    // or disappears (rider entered another passed flag's radius).
    userInsidePassedFlag = riderInsideAnyPassedFlag();
    if (wasInside !== userInsidePassedFlag) {
      refreshRouteVisibility();
    }
  };

  // True while the rider's marker is inside ANY passed stop's arrival
  // radius. Read by nativeRender to suppress routeLine when true; the
  // user-marker update above keeps it in sync.
  var userInsidePassedFlag = false;
  var lastWps = [];
  var lastGeom = [];
  function riderInsideAnyPassedFlag(){
    if (!userMarker) return false;
    var u = userMarker.getLatLng();
    for (var i = 0; i < lastWps.length; i++) {
      var w = lastWps[i];
      if (!w.passed) continue;
      if (u.distanceTo([w.lat, w.lng]) <= (w.radius || 40)) return true;
    }
    return false;
  }
  function refreshRouteVisibility(){
    // No-op if no waypoint snapshot yet (very early init).
    if (!lastWps.length) return;
    window.nativeRender(JSON.stringify(lastWps), JSON.stringify(lastGeom), false);
  }

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

  // Compute the map-center latlng that puts a TARGET latlng at the centre
  // of the VISIBLE area. The caller passes the NET screen-pixel offset
  // by which the rider should appear ABOVE the WebView's geometric centre
  // -- this is (bottomOcclusion - topOcclusion) / 2 (or negative when the
  // top bar is taller than the panel). In our layout the translucent top
  // bar covers ~348 px and the stops panel covers a larger amount; the
  // visible centre therefore sits ABOVE the WebView centre by that net /2.
  //
  // The shift has to account for the map's current bearing -- shifting
  // the projected center "south" in world coords is only "south on
  // screen" when bearing = 0. With heading-up enabled the map is rotated;
  // pushing south in projection moves the rider toward whichever screen
  // direction "world south" currently maps to (which is why the marker
  // was landing top-right while riding east). The world-shift we need
  // for a screen-down shift of (0, offset) is (sin(B), cos(B)) * offset
  // in projection pixels, where B is the bearing in radians.
  function centerLatLngFor(lat, lng, zoom, screenOffsetPx){
    if (!screenOffsetPx) return [lat, lng];
    var pt = map.project([lat, lng], zoom);
    var bearingDeg = (map.getBearing) ? map.getBearing() : 0;
    var rad = bearingDeg * Math.PI / 180;
    pt.x += screenOffsetPx * Math.sin(rad);
    pt.y += screenOffsetPx * Math.cos(rad);
    return map.unproject(pt, zoom);
  }

  window.nativeRecenter = function(lat, lng, zoom, bottomOffsetPx){
    // Re-check the container size first so the target really lands centred
    // even if the WebView resized after the map was created.
    map.invalidateSize();
    map.setView(centerLatLngFor(lat, lng, zoom, bottomOffsetPx), zoom);
    // Count programmatic recenters as a "manual interaction" so the
    // auto-follow tween doesn't immediately yank the map back. The rider
    // tapped recenter because they want to SEE this view; auto-follow
    // resumes after the standard grace period.
    lastManualRotateMs = Date.now();
  };

  // Pan (keeping the current zoom) so a point sits in the centre of the
  // view. Takes the same optional bottomOffsetPx as nativeRecenter so the
  // caller can keep the target above the stops panel.
  window.nativeCenterOn = function(lat, lng, bottomOffsetPx){
    map.panTo(centerLatLngFor(lat, lng, map.getZoom(), bottomOffsetPx));
    // Same grace-period reset as nativeRecenter -- without this, during
    // an active navigation the auto-follow tween's next frame slides the
    // map straight back to the rider, so tapping a stop's name in the
    // list looked like it did nothing.
    lastManualRotateMs = Date.now();
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
