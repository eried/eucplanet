# Offline routing (and the download screen offline maps will share)

Branch: organicrouting. Step 1 of 2. Step 2 (offline map display) is a later
design; this one only keeps the door open for it.

## Goal

A rider can download routing data for the areas they ride and get routes,
reroutes and turn cues with no signal, using a profile made for wheels.
Riders who download nothing see no change.

## Decisions

- **Engine: BRouter**, vendored as source (5 small Java modules: core,
  mapaccess, expressions, util, codec) pinned to tag v1.7.10, in a new
  `:brouter` Gradle module. Not JitPack: v1.7.10 fails to build there and
  the hint fields we need are package-private (we add getters). MIT code,
  keep its LICENSE file.
- **Data: brouter.de rd5 tiles**, 5x5 degrees, named by the south-west
  corner (`E10_N55.rd5`), rebuilt weekly. Real sizes: Oslo tile 65 MB,
  central Europe 200 to 250 MB, sparse tiles a few KB. Plus `lookups.dat`
  from `profiles2/`. Map data is ODbL: credit "(c) OpenStreetMap
  contributors" on the download screen and in About.
- **Our own mirror: GitHub Releases in a new `eucplanet-maps` repo.** Not
  GitHub Pages: Pages caps a site at 1 GB and a file at 100 MB, and the
  tiles are 10 GB with some over 200 MB. Release assets allow 2 GB per file
  and no total cap. A scheduled GitHub Action copies the tiles from
  brouter.de once a month into a rolling release (`routing-latest`), plus an
  index.json (name, bytes, date, sha256). Later the same repo can hold the
  offline map regions of step 2.
- **Download server is a setting** beside the OSRM and Valhalla URLs
  (default: the mirror), so a rider can point at brouter.de or their own.

## Router order

For each leg of a route, with "Prefer online" (the default, so a rider who
changes nothing gets exactly today's routing):

1. Valhalla / OSRM as today.
2. If they fail (no signal, server down), **offline**, when every tile the leg crosses is downloaded and the leg's
   straight-line length is under 100 km (BRouter time grows with distance
   squared; past that it is slow on a phone).
3. Then a straight line, as today.

With "Prefer offline": offline first when the tiles are there and the leg
is short enough, otherwise online. This is the hybrid: a route that leaves
the downloaded tiles, or a leg over the limit, routes online, and the rest
offline. Offline also fails over to online (timeout 20 s, error).

Both cost little battery: an online route is a few seconds of radio and
about 20 to 50 KB, an offline one a few seconds of CPU. Screen, GPS and
Bluetooth dominate a ride either way. Offline wins on reroute speed and
dead zones; online wins on street names and very long legs.

Travel modes map to profiles: Cycling -> our wheel profile, Walking ->
`hiking`, Driving -> online only (BRouter car profiles are weak and the
wheel is not a car). Straight stays straight.

## The wheel profile

`wheel.brf`, our edit of BRouter's `trekking.brf`, shipped in the APK:
no steps, a heavy penalty on unpaved and on bad smoothness, a penalty for
steep climbs, cycleways and quiet roads preferred. The existing avoid
toggles map to BRouter globals passed per route (`avoid_unsafe`,
ferries, unpaved, motorways), so Avoid when routing works offline too.
Tuned by riding; testers compare it against the online route.

## Turn cues

BRouter voice hints become our `Maneuver`s: turn types map onto `TurnType`
(roundabouts keep their exit number). BRouter has **no street names**, so
offline cues say "Turn left" without "onto Storgata". (Organic Maps does
say names: its own map files carry them. We do not have those files.) Distances, arrows,
voice timing and the HUD, watch and car screens work as today. If street
names are missed, a later step can look them up from the online route when
signal returns.

Heading on reroute: BRouter's start direction, like the Valhalla and OSRM
paths already do.

## Download screen

Settings > Navigation > Routing services > **Offline data** (a new screen,
built so offline map regions can join it in step 2):

- A map with the 5x5 degree grid. Tiles show downloaded, updating, or
  available with their size. Tap a tile to download or delete it.
  A "Tiles along this route" button picks every tile the current route
  crosses.
- Top line: space used, free space on the phone.
- Updates: tiles older than the mirror's index show "update" and an
  Update all button (whole tiles, monthly). No automatic
  downloads on mobile data; a switch allows Wi-Fi-only auto updates.
- Downloads run in a WorkManager job: resumable (HTTP Range), survive the
  app closing, a progress notification, cancel from the screen. Check the
  file is complete before use; a broken file is deleted and redownloaded.
- Storage: app-specific external storage (`getExternalFilesDir`), not
  backed up (excluded in the backup rules), removed with the app. Falls back
  to internal storage when there is no external.

Nothing else in the app's UI changes: the Navigator, turn card, HUD,
watches and car screen look the same whichever router made the route.

All text in strings.xml, all 23 locales. Colors from appColors.

## Code layout

- `:brouter` module: vendored BRouter source, no Android code.
- `nav/offline/OfflineRouter.kt`: wraps RoutingEngine, one route at a time
  (BRouter's profile cache is static), on a single background thread, maps
  OsmTrack to NavRoute.
- `nav/offline/RoutingTiles.kt`: tile naming, which tiles a leg crosses,
  what is on disk. Pure, unit tested.
- `nav/offline/TileDownloader.kt` + a WorkManager worker.
- `ui/settings/offline/OfflineDataScreen.kt` + ViewModel.
- `RoutingService.route()` gets the offline step in front of the chain.
- Settings: `navOfflineRouting` (prefer / fallback / off), `navBrouterUrl`,
  `navOfflineWifiUpdates`. AppSettings is at 252 of 255 arguments, so these
  go in a nested `OfflineRoutingSettings` group.

## Testing

- Unit: tile math (edges, negative coordinates, the antimeridian), leg
  splitting at 100 km, OsmTrack to NavRoute mapping, hint to TurnType,
  router order with fakes (offline ok, offline fails, no tiles, no network).
- Instrumented on the emulator: download a small tile, route offline in
  airplane mode, reroute, delete.
- Pixel: a 20 km and a 60 km route in airplane mode, time and memory.
- Testers: ride a known route offline and online, compare.

## Not in this step

Offline map display (step 2), offline address search, car routing offline,
street names in offline cues.

## Open

- Create the `eucplanet-maps` repo and its sync Action (needs the user's
  go-ahead; it is public and uploads about 10 GB a month).
- Size limit: warn before a download over 200 MB on mobile data.

Estimate: about 2 weeks.
