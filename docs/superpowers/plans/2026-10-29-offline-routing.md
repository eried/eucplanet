# Offline Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route, reroute and cue turns from downloaded BRouter tiles, as a fallback (default) or preferred router, with a download screen in Routing services.

**Architecture:** A new pure-Java `:brouter` Gradle module holds BRouter v1.7.10 source. `nav/offline/` in `:app` wraps it (`OfflineRouter`), knows tiles on disk (`RoutingTiles`, `OfflineTileStore`), and downloads them with WorkManager (`TileDownloadWorker`). `RoutingService.route()` asks a pure `RouterPlan` which backend to try per leg. One new full-screen dialog (Leaflet WebView grid) manages tiles.

**Tech Stack:** Kotlin 2.3, Compose, Koin 4.2, WorkManager 2.9, Leaflet in WebView (existing MapHtml pattern), BRouter 1.7.10 (Java 11 source).

**Spec:** `docs/superpowers/specs/2026-10-28-offline-routing-design.md`

## Global Constraints

- No em-dashes anywhere (strings, comments, commits).
- All user text in `strings.xml`, translated to all 23 locales; rider terminology (wheel, rider). Ukrainian apostrophes escaped (`\'`).
- Colors only from `MaterialTheme.appColors`; explicit on-colors.
- Settings read through `SettingsRepository`; new fields in a nested `OfflineRoutingSettings` group; update `AppSettingsArgLimitTest` (252 -> 253).
- Transients via `LocalSnackbar` / `AppNotifier.post()`, never Toast.
- Default download server: `https://brouter.de/brouter` (segments at `/segments4/<name>.rd5`, lookups at `/profiles2/lookups.dat`). The mirror repo is NOT in this plan.
- Default mode: online first, offline only when online fails. Riders with no tiles see no change.
- Offline leg limit: straight-line length under 100 km. Offline timeout: 20 s.
- Cycling -> `wheel.brf`, Walking -> `hiking-mountain.brf`, Driving and Straight -> never offline.
- Verify builds by grepping `BUILD SUCCESSFUL` / `BUILD FAILED`.
- Offline data lives under `getExternalFilesDir(null)/offline/routing/` (fallback `filesDir/offline/routing/`), excluded from backup.

## Review Focus

1. A tile half downloaded (app killed, no space) must never be handed to BRouter: only `<name>.rd5` counts, partials are `<name>.rd5.part`. Test in Task 3.
2. Coordinates on tile edges and negative lat/lng (W/S hemispheres): `E-5_N-5` style names must match brouter.de (`W5_S5`). Test in Task 3.
3. A leg whose endpoints are covered but whose route wanders into a missing tile: BRouter returns an error, the leg falls to the next router, not a crash. Test in Task 5 (fake router failing).
4. Two routes requested at once (reroute while the builder routes): BRouter's static profile cache needs one-at-a-time. Mutex in Task 4, test with two concurrent calls.
5. Server listing unreachable or malformed: the screen shows the downloaded tiles still and a retry, never an empty "nothing downloaded". Test in Task 6 (parser returns empty on garbage).

---

### Task 1: Vendor BRouter as `:brouter`

**Files:**
- Create: `brouter/build.gradle.kts`, `brouter/LICENSE`, `brouter/README.md` (one paragraph: upstream, tag, local changes)
- Create: `brouter/src/main/java/btools/**` copied from tag `v1.7.10` modules `brouter-util`, `brouter-codec`, `brouter-expressions`, `brouter-mapaccess`, `brouter-core` (`src/main/java` only)
- Modify: `settings.gradle.kts` (`include(":brouter")`), `app/build.gradle.kts` (`implementation(project(":brouter"))`)
- Modify: `brouter/src/main/java/btools/router/VoiceHint.java` (public getters)
- Test: `brouter/src/test/java/btools/router/VoiceHintAccessTest.java`

**Interfaces:**
- Produces: `btools.router.VoiceHint` public `getCommand(): int`, `getExitNumber(): int`, `getIndexInTrack(): int`, `getDistanceToNext(): double`, `getAngle(): float`; constants unchanged.

- [ ] Step 1: `git clone --depth 1 --branch v1.7.10 https://github.com/abrensch/brouter` into the scratchpad; copy the five `src/main/java` trees into `brouter/src/main/java`; copy upstream `LICENSE`.
- [ ] Step 2: `brouter/build.gradle.kts`: `java-library` plugin, Java 11 source/target, no dependencies (add only if compile proves a need). Check `rg "import (java\.awt|javax\.)" brouter/src` is empty or only in classes we delete (server-only classes go).
- [ ] Step 3: Write `VoiceHintAccessTest` constructing a VoiceHint (or reading the fields' getters reflectively) asserting getters exist and return set values. Run `./gradlew :brouter:test`, expect FAIL (no getters).
- [ ] Step 4: Add the getters. Run `./gradlew :brouter:test :app:assembleDebug`, expect `BUILD SUCCESSFUL`.
- [ ] Step 5: Commit `feat(nav): vendor BRouter 1.7.10 as :brouter`.

### Task 2: Settings group

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt` (new group + field `offlineRouting`)
- Modify: `app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt`
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsViewModel.kt`
- Test: `app/src/test/java/com/eried/eucplanet/data/AppSettingsArgLimitTest.kt` (expectedSlots 253), `app/src/test/java/com/eried/eucplanet/data/OfflineRoutingSettingsJsonTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class OfflineRoutingMode { FALLBACK, PREFER }
  data class OfflineRoutingSettings(
      val mode: OfflineRoutingMode = OfflineRoutingMode.FALLBACK,
      val serverUrl: String = "https://brouter.de/brouter",
      val wifiAutoUpdate: Boolean = false,
  )
  // AppSettings.offlineRouting: OfflineRoutingSettings
  // SettingsViewModel: updateOfflineRoutingMode(m), updateOfflineServerUrl(v), updateOfflineWifiAutoUpdate(b)
  ```

- [ ] Step 1: Test: round trip through SettingsJson keeps all three fields; missing `offlineRouting` key gives the defaults; unknown mode string gives FALLBACK.
- [ ] Step 2: Run, expect FAIL. Implement (JSON key `offlineRouting`, mode by name). Bump tripwire to 253 with comment "253 offlineRouting group".
- [ ] Step 3: `./gradlew :app:testDebugUnitTest --tests "*OfflineRouting*" --tests "*ArgLimit*"` PASS. Commit.

### Task 3: Tiles and the store

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/nav/offline/RoutingTiles.kt`
- Create: `app/src/main/java/com/eried/eucplanet/nav/offline/OfflineTileStore.kt`
- Test: `app/src/test/java/com/eried/eucplanet/nav/offline/RoutingTilesTest.kt`, `OfflineTileStoreTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object RoutingTiles {
      const val SIZE_DEG = 5
      const val MAX_LEG_KM = 100.0
      fun nameFor(lat: Double, lng: Double): String          // "E10_N55", "W5_S5"
      fun tilesForLeg(a: GeoPoint, b: GeoPoint, padDeg: Double = 0.05): Set<String>
      fun bounds(name: String): DoubleArray                   // [south, west, north, east]
      fun legKm(a: GeoPoint, b: GeoPoint): Double
  }
  class OfflineTileStore(val dir: File) {
      fun has(name: String): Boolean        // <name>.rd5 exists, length > 0
      fun downloaded(): Map<String, Long>   // name -> bytes, complete files only
      fun covers(tiles: Set<String>): Boolean
      fun partFile(name: String): File; fun finalFile(name: String): File
      fun delete(name: String)
      fun lookupsFile(): File               // dir/lookups.dat
      fun usedBytes(): Long
      companion object { fun default(ctx: Context): OfflineTileStore }
  }
  ```

- [ ] Step 1: Tests: `nameFor(59.91, 10.75) == "E10_N55"`, `nameFor(-0.1, -0.1) == "W5_S5"`, `nameFor(60.0, 15.0) == "E15_N60"` (edge goes to the tile it starts), `tilesForLeg` across a tile boundary returns both, `legKm` Oslo to Drammen about 36 km (+-2), a `.rd5.part` is not `has`, deleting removes both files, `covers` false when one missing.
- [ ] Step 2: FAIL, implement (floor to multiple of 5; letter E/W N/S with absolute value; tilesForLeg walks the padded bbox), PASS. Commit.

### Task 4: OfflineRouter

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/nav/offline/OfflineRouter.kt`
- Create: `app/src/main/java/com/eried/eucplanet/nav/offline/BrouterMapping.kt`
- Create: `app/src/main/assets/brouter/wheel.brf` (from upstream `misc/profiles2/trekking.brf`: `allow_steps=false`, `unpavedPenalty` raised, smoothness penalty added, higher `uphillcost`; header comment says what changed), `app/src/main/assets/brouter/hiking-mountain.brf`, `app/src/main/assets/brouter/lookups.dat` (bundled fallback)
- Modify: `app/src/main/java/com/eried/eucplanet/di/NavModule.kt`
- Test: `app/src/test/java/com/eried/eucplanet/nav/offline/BrouterMappingTest.kt`, `OfflineRouterTest.kt`

**Interfaces:**
- Consumes: Task 1 getters, Task 3 store.
- Produces:
  ```kotlin
  object BrouterMapping {
      fun turnType(cmd: Int): TurnType            // VoiceHint.C->CONTINUE, TL->LEFT, TSLL->SLIGHT_LEFT, TSHL->SHARP_LEFT, TR/TSLR/TSHR mirrored, KL->SLIGHT_LEFT, KR->SLIGHT_RIGHT, TU/TLU/TRU->UTURN, RNDB/RNLB->ROUNDABOUT, else CONTINUE
      fun profileFor(mode: TravelMode): String?   // CYCLING "wheel", WALKING "hiking-mountain", else null
      fun avoidParams(avoid: RouteAvoidances): Map<String, String>  // profile globals, "1"/"0"
      fun toNavRoute(name: String, waypoints: List<Waypoint>, mode: TravelMode, track: OsmTrack): NavRoute
  }
  class OfflineRouter(context: Context, store: OfflineTileStore) {
      suspend fun route(name: String, waypoints: List<Waypoint>, mode: TravelMode,
                        avoid: RouteAvoidances, heading: Double?): NavRoute?   // null on any failure or 20 s timeout
  }
  ```
  `instruction` strings come from the same resources the OSRM/Valhalla parsers use for each TurnType; `streetName` is "".

- [ ] Step 1: Mapping tests: every listed command maps as above; a synthetic OsmTrack (3 nodes, one TL hint) gives geometry of 3 points, one LEFT maneuver at the hint node with its cumulative distance, ARRIVE last, `totalDistanceM` equal to track distance.
- [ ] Step 2: Router test with a fake engine seam (`internal var engine: (RoutingContext, List<OsmNodeNamed>) -> OsmTrack?`): two concurrent `route` calls never overlap (counter of in-flight calls max 1); an engine that sleeps past the timeout returns null; an engine that throws returns null.
- [ ] Step 3: FAIL, implement: copy assets to the store dir on first use (lookups.dat only when the store has none), `RoutingContext` with `localFunction` = profile path, `keyValues` = avoidParams, `startDirection` + `forceUseStartDirection` from heading, `turnInstructionMode = 9` (OsmAnd-style hints with turn angles), `memoryclass = 64`; run `RoutingEngine(...).doRun(20_000)` on `Dispatchers.IO` inside a `Mutex`; coordinates `(deg + 180) * 1e6` / `(deg + 90) * 1e6`. PASS. Commit.

### Task 5: Router order in RoutingService

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/nav/offline/RouterPlan.kt`
- Modify: `app/src/main/java/com/eried/eucplanet/nav/RoutingService.kt` (`route()` gains `offline: OfflineRoutingMode = FALLBACK`; constructor gains `OfflineRouter`, `OfflineTileStore`)
- Modify: callers that already pass `valhalla =` (NavigationEngine, RouteBuilderViewModel, EucCarAppService x2, NavigationOverlayViewModel) to pass `offline = s.offlineRouting.mode`
- Test: `app/src/test/java/com/eried/eucplanet/nav/offline/RouterPlanTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class Backend { OFFLINE, ONLINE }
  object RouterPlan {
      fun offlineEligible(mode: TravelMode, covered: Boolean, legKm: Double): Boolean
      fun order(mode: OfflineRoutingMode, eligible: Boolean): List<Backend>  // FALLBACK [ONLINE, OFFLINE?], PREFER [OFFLINE?, ONLINE]
  }
  ```
  `route()`: PREFER and every leg eligible -> one offline call for the whole route; PREFER mixed -> per leg (eligible legs offline, the rest one online call each), joined with existing `joinRoutes`; FALLBACK -> today's online chain, and if it returns null, per leg offline where eligible, straight line for the rest. Offline failure in PREFER drops that leg to online.

- [ ] Step 1: Tests: DRIVING never eligible; 120 km leg not eligible; uncovered not eligible; FALLBACK eligible -> [ONLINE, OFFLINE]; PREFER eligible -> [OFFLINE, ONLINE]; not eligible -> [ONLINE] in both.
- [ ] Step 2: FAIL, implement, PASS; full `:app:testDebugUnitTest` PASS (RoutingBackendTest unchanged). Commit.

### Task 6: Downloads

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/nav/offline/TileIndex.kt`
- Create: `app/src/main/java/com/eried/eucplanet/nav/offline/TileDownloadWorker.kt`
- Create: `app/src/main/java/com/eried/eucplanet/nav/offline/OfflineTiles.kt` (facade: index, enqueue, cancel, delete, progress Flow)
- Modify: `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` (exclude `file` path `offline/`)
- Test: `app/src/test/java/com/eried/eucplanet/nav/offline/TileIndexTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class RemoteTile(val name: String, val bytes: Long, val dateMs: Long)
  object TileIndex { fun parseListing(html: String): List<RemoteTile> }   // Apache/nginx listing of segments4; [] on garbage
  class OfflineTiles(context: Context, store: OfflineTileStore, settings: SettingsRepository) {
      val progress: StateFlow<Map<String, Float>>      // name -> 0..1 while downloading
      suspend fun remote(): List<RemoteTile>?          // null when unreachable
      fun download(names: Collection<String>)          // unique work per tile, KEEP
      fun cancel(name: String); fun delete(name: String)
      fun needsUpdate(local: File, remote: RemoteTile): Boolean   // remote.dateMs > local.lastModified + 1 day
  }
  ```
  Worker: Range resume into `.part`, `User-Agent: EUCPlanet/<version>`, on finish size must equal Content-Length then rename to `.rd5` and set lastModified to the server date; also fetches `/profiles2/lookups.dat` when missing; foreground notification with progress (existing channel helper); network constraint CONNECTED, or UNMETERED for auto updates.

- [ ] Step 1: Parser tests against a saved sample of the real brouter.de listing (fetch once into `app/src/test/resources/segments4_listing.html`, trim to ~10 rows): names, byte sizes, dates parsed; garbage -> empty; `needsUpdate` true/false cases.
- [ ] Step 2: FAIL, implement, PASS. Commit.

### Task 7: Offline data screen and settings rows

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/ui/settings/offline/OfflineDataDialog.kt` (full-screen Dialog, `dismissOnClickOutside = false`)
- Create: `app/src/main/java/com/eried/eucplanet/ui/settings/offline/OfflineDataViewModel.kt`
- Create: `app/src/main/java/com/eried/eucplanet/ui/settings/offline/OfflineGridHtml.kt` (Leaflet page: map layer from `mapLayersJson()`, grid rectangles colored from values passed in, tap -> bridge `onTile(name)`)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/NavigatorSettingsContent.kt` (under Routing services: half/half segmented "Online first" / "Offline first", a row "Offline routing data" with used size opening the dialog, `EndpointField` for the download server)
- Modify: `app/src/main/res/values*/strings.xml` (23 locales)
- Modify: About / credits: "(c) OpenStreetMap contributors" line for offline routing data, and BRouter MIT credit

Dialog content: top line used / free bytes; map grid (downloaded, downloading with percent, update available, available with size); tap toggles a confirm sheet (download N MB / delete); buttons "Tiles along this route" (current route from `CurrentRouteStore`), "Update all" (shown when any needs update); switch "Update on Wi-Fi"; credit line. Warn before a download over 200 MB when not on Wi-Fi.

- [ ] Step 1: ViewModel unit test: state from a fake store + fake remote list gives the right per-tile status (downloaded / update / available / downloading); remote null keeps downloaded tiles listed and sets `offlineListError`.
- [ ] Step 2: FAIL, implement, PASS. Strings in all 23 locales; `:app:assembleDebug` SUCCESSFUL. Commit.

### Task 8: Live verification and BRANCH.md

- [ ] Step 1: Emulator 5580: open the dialog, download `E10_N55` (65 MB) and watch progress, kill the app mid-download and confirm it resumes.
- [ ] Step 2: Mode Offline first, airplane mode, GPS in Oslo, route two stops 10 km apart: route drawn, turn cues spoken, log says routed offline. Reroute by moving GPS off route.
- [ ] Step 3: Mode Online first with network: route identical to before (log says Valhalla). Airplane mode: falls to offline.
- [ ] Step 4: Delete the tile, confirm space freed. Screenshots of settings rows and dialog in dark and light themes.
- [ ] Step 5: Pixel: build installed via adb; 20 km and 60 km routes timed in airplane mode.
- [ ] Step 6: BRANCH.md Worked on / Please test lines; push organicrouting via PowerShell (user verifies before any merge).
