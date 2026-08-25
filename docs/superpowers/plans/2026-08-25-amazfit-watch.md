# Amazfit (Zepp OS) Watch Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put the existing EUC Planet wrist dial on Amazfit (Zepp OS) watches, driven by the same Settings, Watch tab as Wear OS and Garmin.

**Architecture:** The phone runs a loopback-only HTTP responder (`AmazfitBridge` + `AmazfitLocalServer`) that answers `GET /state` with the same snapshot the Garmin bridge builds and accepts `POST /control`. A Zepp OS mini program (`amazfit-watch-app/`) polls it through its Side Service (which runs inside the Zepp phone app) and renders a port of the Garmin dial.

**Tech Stack:** Kotlin + Hilt + kotlinx.serialization (phone), Zepp OS mini program (JavaScript, `@zeppos/zml` 0.0.43, zeus CLI 1.9.3), Zepp OS Simulator 2.1.2.

**Spec:** `docs/superpowers/specs/2026-08-25-amazfit-watch-design.md`

## Global Constraints

- No em-dashes anywhere (CLAUDE.md rule 2). Copy stays short.
- No new rider-facing settings; reuse `watchUpdateRate` and the `watch*` fields.
- Every user-facing string lives in `strings.xml` and is translated to all 22 locales.
- Verify gradle builds by grepping `BUILD SUCCESSFUL` / `BUILD FAILED`.
- Wire keys mirror `GarminKeys` verbatim (except `sq`), plus `pi` and `ev`.
- Loopback port is the constant `AMAZFIT_PORT = 28193`; the socket binds `127.0.0.1` only.
- New features land on `next-experimental`; this branch is `feature/amazfit-watch`.

---

### Task 1: Protocol constants, JSON helper, inbox

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/amazfit/AmazfitProtocol.kt`
- Create: `app/src/main/java/com/eried/eucplanet/amazfit/AmazfitInbox.kt`
- Test: `app/src/test/java/com/eried/eucplanet/amazfit/AmazfitProtocolTest.kt`
- Test: `app/src/test/java/com/eried/eucplanet/amazfit/AmazfitInboxTest.kt`

**Interfaces:**
- Produces: `object AmazfitKeys` (string constants), `object AmazfitControl`, `const AMAZFIT_PORT: Int`, `AMAZFIT_PATH_STATE`, `AMAZFIT_PATH_CONTROL`, `fun amazfitPollIntervalMsFor(rate: String): Int`, `object AmazfitJson { fun encode(map: Map<String, Any?>): String; fun cmdOf(body: String): String? }`, `@Singleton class AmazfitInbox` with `enqueue(Map<String, Any>)`, `drainEvents(): List<Map<String, Any>>`, `notePoll(nowMs)`, `takePollCount(): Int`, `hasPolledWithin(windowMs, nowMs): Boolean`, `awaitDrained(timeoutMs): Boolean`, `@Volatile var watchName: String`, `val lastPollAtMs: Long`.

- [ ] **Step 1: Write the failing tests** (`AmazfitProtocolTest`: poll interval tiers, JSON encodes booleans/numbers/strings/nested event lists, `cmdOf` parses `{"cmd":"horn"}` and returns null on garbage; `AmazfitInboxTest`: drain empties the queue, `hasPolledWithin` respects the window, `takePollCount` resets, `awaitDrained` returns true once another thread drains).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.amazfit.*"` and confirm compile failure.
- [ ] **Step 3: Implement** the two files per the spec (kotlinx `buildJsonObject`, `ConcurrentLinkedQueue`, `AtomicInteger`; `awaitDrained` polls every 50 ms).
- [ ] **Step 4: Run the tests**, expect PASS.
- [ ] **Step 5: Commit** `feat(amazfit): wire vocabulary, JSON helper and inbox`.

### Task 2: Loopback HTTP responder

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/amazfit/AmazfitLocalServer.kt`
- Test: `app/src/test/java/com/eried/eucplanet/amazfit/AmazfitLocalServerTest.kt`

**Interfaces:**
- Produces: `class AmazfitLocalServer(port: Int, handler: (Request) -> Response)` with `data class Request(method, path, body)`, `data class Response(status: Int, body: String)`, `fun start(): Boolean`, `fun stop()`, `val boundPort: Int`.

- [ ] **Step 1: Write the failing tests** using `HttpURLConnection` against port 0: GET returns the handler body with status 200 and `application/json`; POST delivers the body to the handler; unknown status codes pass through (404); a raw socket sending garbage does not stop the next request from succeeding; 8 concurrent GETs all succeed.
- [ ] **Step 2: Run**, confirm compile failure.
- [ ] **Step 3: Implement**: `ServerSocket(port, 8, InetAddress.getLoopbackAddress())`, daemon accept thread, `Executors.newFixedThreadPool(2)` with daemon threads, 3 s socket timeout, request line + headers + `Content-Length` body, response with `Connection: close` and `Cache-Control: no-store`.
- [ ] **Step 4: Run tests**, expect PASS.
- [ ] **Step 5: Commit** `feat(amazfit): loopback HTTP responder`.

### Task 3: AmazfitBridge

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/amazfit/AmazfitBridge.kt`
- Test: `app/src/test/java/com/eried/eucplanet/amazfit/AmazfitSnapshotTest.kt`

**Interfaces:**
- Consumes: Task 1 and 2 types, `WheelRepository`, `SettingsRepository`, `CheatState`, `ExternalGpsRepository`, `TripRepository`, `NavigationEngine`, `FlicManager`, `ThemeController` (same constructor list as `GarminBridge`) plus `AmazfitInbox`.
- Produces: `@Singleton class AmazfitBridge` with `pairedDevices: StateFlow<List<String>>`, `deliveryRateHz: StateFlow<Double>`, `lastSuccessAtMs: StateFlow<Long>`, `start()`, `pingWatchToWake()` (no-op), `publishFarewell()`, `sendCloseToWatchBlocking()`, `vibrate(ms)`. Snapshot building lives in `internal object AmazfitSnapshot { fun encode(...): Map<String, Any> }` so it is testable without Android.

- [ ] **Step 1: Write the failing test**: `AmazfitSnapshot.encode` output contains every key in the list the watch reads (`c n s b b2 v i p t tr tq l ms ch cl us ud ut im ac wko wsb wpb wwb wpd wsu wpp wrot wgb wgo wgr wce s1c s1h s2c s2h b1c b1h b2c b2h hap gs gsr na ng np nd nar ts pi ev k`), `gs` is `-1f` when GPS is absent, `pi` follows the tier.
- [ ] **Step 2: Run**, confirm failure.
- [ ] **Step 3: Implement** the bridge: server on `start()` with 30 s retry, presence/rate poller every 1 s (EWMA alpha 0.25, paired while polled within 15 s, name from `info:` or "Amazfit watch"), request routing (`GET /state`, `POST /control`, else 404), farewell flag cleared once the wheel reconnects, `sendCloseToWatchBlocking` enqueues `{"k":"quit"}` and `awaitDrained(1500)` only when a watch polled within 15 s.
- [ ] **Step 4: Run tests**, `./gradlew :app:assembleDebug`, grep `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit** `feat(amazfit): phone bridge serving the dial snapshot over loopback HTTP`.

### Task 4: Phone integration, settings UI, strings

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/EucPlanetApp.kt` (inject + `amazfitBridge.start()` after Garmin)
- Modify: `app/src/main/java/com/eried/eucplanet/service/WheelService.kt` (farewell + close next to Garmin)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/dashboard/DashboardViewModel.kt` (`stopEverything` closes Amazfit after Garmin)
- Modify: `app/src/main/java/com/eried/eucplanet/wear/WatchVibrator.kt` (inject `AmazfitInbox`, enqueue `{"k":"vibe","ms":ms}`)
- Modify: `app/src/main/java/com/eried/eucplanet/MainActivity.kt` (diagnostics row "Watch (Amazfit)")
- Modify: `app/src/main/java/com/eried/eucplanet/data/model/PairedSurface.kt` (`Kind.AMAZFIT`)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsViewModel.kt` (`pairedSurfaces`, `hasAmazfitPaired`, `hasHardwareButtonCapableWatch`)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt` (`WatchTab` gates and badges, `DeviceCard` icon, `surfaceKindLabel`)
- Modify: `app/src/main/res/values/strings.xml` (+ `watch_paired_kind_amazfit`, updated `watch_paired_none_desc`, `watch_hardware_button_1_subtitle`, `watch_hardware_button_2_subtitle`)
- Create: `tools/inject-amazfit-translations.py` (writes the 4 keys into all 22 locale files, idempotent)

- [ ] **Step 1: Apply the edits** listed above. Watch tab rules: Auto-start row shows when any watch is paired and carries `PlatformUnsupportedTextBadge("AMAZFIT")` when an Amazfit is paired; Keep display on and Update rate show for `hasWearOs || hasAmazfitPaired`; Dial rotation stays `hasWearOs`-gated and shows both the Garmin and Amazfit badges.
- [ ] **Step 2: Run** `python tools/inject-amazfit-translations.py`, then `./gradlew :app:testDebugUnitTest :app:assembleDebug`, grep `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit** `feat(amazfit): wire the bridge into the app, Settings and alarms`.

### Task 5: Zepp OS watch app

**Files:**
- Create: `amazfit-watch-app/package.json`, `app.json`, `app.js`, `app-side/index.js`, `page/index.js`, `page/dial.js`, `utils/protocol.js`, `assets/t-rex-3/*.png`, `assets/balance/*.png`, `.gitignore`, `README.md`

- [ ] **Step 1: Scaffold** the project (see spec for `app.json` targets). Icons: scale the Garmin 24 px PNGs to 32 px with Pillow, app icon 240 px from `garmin-watch-app/resources/drawables/launcher_icon.png`, a 40 px white arrow for the nav overlay.
- [ ] **Step 2: Side Service**: `onRequest` methods `state` (GET `http://127.0.0.1:28193/state`, 2500 ms timeout, returns `{ok:true, state}` or `{ok:false}`) and `control` (POST `{"cmd":...}`).
- [ ] **Step 3: Dial page**: widgets created once in `build()`, updated with `setProperty` from `applyState(s)`; poll loop `request({method:'state'})` then `setTimeout(poll, s.pi)`; stale check every 1 s; placeholders; buttons (`BUTTON` with `click_func`/`longpress_func`), `onKey` SELECT/DOWN; `setWakeUpRelaunch`, keep-on; events `quit`/`vibe`; `info:` on init.
- [ ] **Step 4: Build** with `npx zeus build` and confirm a `.zab` lands in `dist/`.
- [ ] **Step 5: Commit** `feat(amazfit): Zepp OS watch app`.

### Task 6: Simulator verification

- [ ] **Step 1:** Install the phone debug APK on the Android emulator, connect a virtual wheel.
- [ ] **Step 2:** `adb forward tcp:28193 tcp:28193`; `curl http://127.0.0.1:28193/state` returns the snapshot.
- [ ] **Step 3:** `npx zeus dev` on the T-Rex 3 simulator; screenshot waiting, riding, disconnected, nav states; POST a `vibe` event and confirm the console log; check the phone Settings Device card shows "Amazfit (Zepp OS)" Live with a rate.
- [ ] **Step 4:** Fix whatever the run surfaces; commit `fix(amazfit): ...` as needed.

### Task 7: CI, docs, branch notes

**Files:**
- Modify: `.github/workflows/branch-apk.yml`, `.github/workflows/release-apk.yml` (new `amazfit` job: setup-node 22, `npm ci`, `npx zeus build`, rename to `amazfit-<suffix>.zab`, stage for publish; publish job `needs` it with `always()`)
- Create: `docs/AMAZFIT_SETUP.md`
- Modify: `README.md` (one paragraph next to Garmin), `BRANCH.md` (replace with this branch's notes)

- [ ] **Step 1:** Edit workflows; validate YAML with `python -c "import yaml,sys; yaml.safe_load(open(...))"`.
- [ ] **Step 2:** Write docs; run `grep -rn "—" docs/AMAZFIT_SETUP.md BRANCH.md` to confirm no em-dashes.
- [ ] **Step 3:** Commit `docs(amazfit): setup guide, CI job and branch notes`.
