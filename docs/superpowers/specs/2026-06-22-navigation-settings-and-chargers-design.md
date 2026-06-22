# Navigation settings (route avoidances) + chargers/stations along the route

Date: 2026-06-22
Branch: `feature/navigation-settings`

## Goal

Two additions to the Navigator:

1. **Route avoidance options** — let the rider tell the router to avoid
   highways, tolls, ferries and unpaved roads. All toggles default **OFF**
   (avoid nothing — identical to today's behaviour).
2. **Chargers & stations along the route** — show EV charging stations and
   fuel stations near the planned route as faint, tappable markers. Tapping
   one shows its details and offers **Add as stop**, inserted intelligently
   between the two existing stops whose route segment passes nearest the place.

## Key external-service findings (verified 2026-06-22)

- The app's default router, the **FOSSGIS OSRM** service
  (`routing.openstreetmap.de`), **rejects every `exclude=` flag**
  ("Exclude flag combination is not supported"). The official OSRM demo server
  rejects it too. So avoidances cannot be done by adding a parameter to the
  current router.
- **FOSSGIS Valhalla** (`https://valhalla1.openstreetmap.de/route`) is
  **key-less**, same provider family the app already trusts, supports
  `costing_options` (`use_ferry`, `use_highways`, `use_tolls`,
  `avoid_bad_surfaces` for bicycle) for `bicycle`/`auto`/`pedestrian`, and
  returns an encoded `shape` (polyline6) + `maneuvers`. The app *previously*
  ran on Valhalla (see the "retired Valhalla backend" comment in
  `RoutingService.effectiveRouterUrl`), so reintroducing it as a *secondary*
  backend is low-friction.
- **Overpass API** (`https://overpass-api.de/api/interpreter`) is key-less and
  returns `amenity=charging_station` / `amenity=fuel` nodes with rich tags
  (name, brand, `opening_hours`, `socket:*`, `fuel:electricity`). Matches the
  app's existing key-less-OSM ethos.

## Feature 1 — Route avoidances

### Design

- Add four booleans to `AppSettings`, all default `false`:
  `navAvoidHighways`, `navAvoidTolls`, `navAvoidFerries`, `navAvoidUnpaved`.
  Wire them into `SettingsJson` (put + optBoolean), `SettingsViewModel`
  (`updateNavAvoid*`), and a new "Avoid" group in `NavigatorSettingsContent.kt`
  (four themed `Switch` rows).
- A small immutable `RouteAvoidances(highways, tolls, ferries, unpaved)` value
  carries the flags into the routing layer. `RouteAvoidances.any` is true when
  at least one flag is set.
- `RoutingService.route(...)` gains an `avoid: RouteAvoidances` parameter
  (default = none, so all existing call sites stay valid):
  - **`avoid.none` → unchanged**: route via OSRM exactly as today.
  - **`avoid.any` → Valhalla**: build a Valhalla `/route` request with the
    matching `costing_options`, parse `shape` (reuse `decodePolyline6`) and
    `maneuvers` (new `mapValhallaManeuver`), produce the same `NavRoute`.
    On any Valhalla failure, fall back to OSRM (then straight line), so a
    flaky avoid-router never leaves the rider with nothing.
- Valhalla costing per `TravelMode`: `CYCLING→bicycle`, `WALKING→pedestrian`,
  `DRIVING→auto`. Flags map to the options that costing supports
  (`use_highways`/`use_tolls` only exist for `auto`; `use_ferry` for all;
  `avoid_bad_surfaces` for `bicycle`). Unsupported flags for a costing are
  simply omitted — the toggle is still stored, it just has no effect in that
  mode. `STRAIGHT` never routes.
- Both routing entry points pass the avoidances: the Route Builder preview
  (`RouteBuilderViewModel.scheduleRecompute` / `startNavigation`) and the live
  re-route in `NavigationEngine.reroute`. Each reads the four flags from
  settings (the VM already reads settings in `init`; the engine already reads
  `navRouterUrl` in its settings block).

### Why a secondary backend, not a switch-back

OSRM is fast, proven, and the deliberate current default. At the default
(avoid nothing) there is **zero behaviour change and zero new network
dependency**. Valhalla is only touched the moment a rider opts into an
avoidance, which is exactly when its capability is required.

## Feature 2 — Chargers & stations along the route

### Data source & service

- New `PoiService` (sibling of `RoutingService`, same plain
  `HttpURLConnection` + `Dispatchers.IO` style, key-less). Given the route
  geometry it computes a bounding box (route bbox + ~2 km padding) and runs one
  Overpass query for `amenity=charging_station` and `amenity=fuel` nodes.
- Results are filtered to those within a buffer (default ~1.5 km) of the route
  polyline (`GeoMath.nearestOnPolyline`), so only *on-the-way* places show, and
  capped (e.g. 60) to keep the map light.
- `PointOfInterest(id, lat, lng, kind, name, brand, openingHours, socketInfo,
  distanceFromRouteM)`. `kind` ∈ {CHARGER, FUEL}.

### Map rendering & interaction

- New JS entry point `nativeSetPois(json)` in `MapHtml.kt` draws each POI as a
  **faint** `divIcon` (low opacity, small) on a dedicated layer, below the stop
  markers so taps prefer real stops. A new bridge method
  `AndroidNav.onPoiTapped(id)` reports a tap.
- A map toolbar toggle (default **off**) enables the layer. When on, the
  Builder asks `PoiService` for POIs around the current route (debounced; only
  re-queries when the route bbox changes meaningfully) and pushes them via
  `nativeSetPois`.
- Tapping a faint POI opens a details bottom sheet
  (`PoiDetailsSheet` in `RouteBuilderScreen`): name, kind, brand, opening
  hours, socket/fuel info, distance from route, and an **Add as stop** button.

### Smart stop insertion

- Reuse the existing nearest-segment logic from
  `RouteBuilderViewModel.insertWaypointOnRoute`: build the chain
  `[origin?, stop0, stop1, …]`, find the segment closest to the POI, and insert
  the new stop just before that segment's end — i.e. between the two stops whose
  connecting leg runs nearest the charger. New method
  `addPoiAsStop(poi)` does exactly this (it is `insertWaypointOnRoute` fed the
  POI's coordinates + name, so the existing re-route/preview path is reused).
- Disabled while live guidance is running (same guard as
  `insertWaypointOnRoute`).

## Testing strategy

Pure logic gets unit tests (no network): Valhalla maneuver-type mapping, the
avoidance→`costing_options` JSON builder, the POI route-buffer filter, and the
smart-insert index choice. End-to-end is verified on a fresh emulator:
toggles persist and default off; enabling an avoidance still draws a route;
the POI layer shows faint markers, a tap opens details, Add-as-stop inserts at
the sensible index.

## Implementation refinements (from review during build)

Verified end-to-end on a clean emulator build:

- **Valhalla confirmed live**: with "Avoid ferries" on + cycling mode, an in-app
  request routed via `valhalla1.openstreetmap.de` (bicycle costing,
  `use_ferry=0`) and drew an 89-point polyline. At defaults (all off) routing
  stays on OSRM.
- **Configurable POI source**: added `navOverpassUrl` setting (default
  `overpass-api.de`), shown in "Routing services" next to the geocoder/router
  URLs, so the chargers/stations source is self-hostable like the rest.
- **Graceful degradation**: `PoiService.poisAlongRoute` returns `null` on
  failure vs `[]` on success-but-none. The Builder shows distinct toasts —
  "Loading chargers…" on start, "Couldn't load — check your connection" on
  failure (keeping any already-loaded pins), "none nearby" on empty, and
  "add a stop first" when the layer is enabled with no route. The FAB shows a
  spinner while loading.
- **Richer details, neat card**: the details sheet shows sockets/fuels,
  network, operator, capacity, hours, access, fee, phone and on-route distance
  in a tonal card, with a tiny "Data from OpenStreetMap" attribution in the
  card corner. "Add as stop" and "Open online" sit side by side (half/half).
- **Open online always available**: opens the place's `website` when OSM has
  one, otherwise its OpenStreetMap node page (where any photos / extra tags
  live). Photos are **not** rendered inline — OSM rarely stores them and the app
  bundles no image loader; an `image`/`wikimedia_commons` URL is captured for
  the link only. Inline thumbnails would need an image library (Coil) + a photo
  source and are deliberately deferred.
- **Home/Work markers** changed from round badges to teardrop **pins**, so the
  round chips read unambiguously as the tappable charger/station POIs.

## POI categories, simple mode & smart insertion (second iteration)

Built and verified on the clean emulator build:

- **Electric-only chargers**: `PoiKind.CHARGER` now means real electric
  charging — `amenity=charging_station` OR a fuel station with
  `fuel:electricity=yes`. A plain petrol/diesel station is **not** a charger; it
  classifies as `STORE`. Logic in `PoiService.classify` (unit-tested).
- **Five categories** in two grouped layers: `CHARGER` (⚡) and a "places" group
  = `STORE`🏪 (non-electric fuel + convenience/supermarket), `FOOD`☕
  (cafe/restaurant/fast_food), `REST`🚰 (drinking_water/toilets/shelter),
  `SIGHTS`📸 (attraction/viewpoint/artwork/museum). The Overpass query is the
  union of the enabled categories' node filters; parse keeps only enabled kinds.
- **Two map FABs**: a charger toggle and a places toggle (the whole group).
  Markers are faint **rounded squares** (own shape language vs round route
  stops and teardrop place-pins), one glyph per category.
- **Simple map mode** (`navSimpleMap`, **default ON**): a clean map with no
  overlay FABs, and the advanced "Routing services" source-URL fields disabled.
  Turning it off unlocks both the overlay FABs and the custom endpoints.
  Verified: 2 FABs in simple mode, 4 when off; URL fields greyed/enabled to
  match.
- **Cheapest-insertion for Add-as-stop**: a POI is placed at the position that
  adds the least extra route length — **between** the two nearest stops
  (`1 ── * ── 2`) or **appended** after the last stop when the place lies beyond
  the route's end (`1 ── 2 ── *`). `RouteBuilderViewModel.cheapestInsertIndex`.
- New settings: `navSimpleMap`, `navShowChargers`, `navShowPlaces` (persisted;
  layer selection is remembered across sessions).

## Open Charge Map community data (Path A — chargers)

Built; live data needs a (free) OCM API key.

- **Key-less by default, opt-in key**: new `navOcmApiKey` setting, shown in the
  advanced "Routing services" block (disabled unless Advanced map is on). OCM
  requires a key (verified: 403 without one), so it stays off until the rider
  pastes their free key — consistent with the app's key-less ethos.
- **`OcmService`** (`api.openchargemap.io/v3/poi`, GET + `key`): given a
  charger's lat/lng it fetches the nearest OCM POI with comments, parsed
  (unit-tested) into `OcmCharger` — connectors (`Type 2 · 22 kW, CCS · 50 kW`),
  operator, usage cost, status, charge-point count, average rating + count,
  recent user comments / check-ins, and photo URLs.
- **Flyout "Open Charge Map" card**: shown for chargers when a key is set —
  rating, connectors/cost/status/points, up to 3 recent comments (user · stars ·
  date · text), a "Photos (n)" link, an "Open on OCM" link, and CC-BY
  attribution. Fetched async when the charger flyout opens; a spinner while
  loading; absent (no card) when no key, no match, or on failure.
- Photos are opened externally (links) for now — inline thumbnails still want an
  image library (Coil), deferred.
- Settings copy: a "Charger community (Open Charge Map)" subtitle above the key
  field, and a clickable, underlined `openchargemap.org` link (opens the OCM app
  registration page) instead of a "get a key" button.
- **Verified live** with a real key: tapping "Recharge Tøyen" in Oslo rendered
  the OCM card with connectors (`Type 2 · 12 kW, CHAdeMO · 44 kW, CCS · 44 kW`),
  operator (Charge & Drive / Fortum), status, 12 charge points and the OCM link.
  Rating/comments/photos render the same way for chargers that have them
  (unit-tested).

## Out of scope / follow-ups

- Translations: new user-facing strings land in the default
  `values/strings.xml` (English); the other 18 locales fall back to it and get
  translated in a later parity pass.
- Inline POI photos (needs an image library + photo source: Wikimedia /
  brand logo via `brand:wikidata` / Mapillary / Panoramax).
- Other POI categories (cafes, convenience, water) and a per-category filter.
- Persisting the "show POIs" toggle (kept in-memory for v1).
