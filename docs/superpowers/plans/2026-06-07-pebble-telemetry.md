# Pebble Time 2 Telemetry Watchapp - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the new Pebble Time 2 (and PebbleOS watches) as a telemetry companion - a glanceable wrist dial fed live by the phone - mirroring the Garmin/WearOS companions, telemetry-only.

**Architecture:** A phone-side `PebbleBridge` (gated by `-PpebbleEnabled` + source-set swap, exactly like Garmin) pushes a small key->value telemetry dict to a Pebble C-SDK watchapp via PebbleKitAndroid2 (`io.rebble.pebblekit2:client`). The watchapp renders a dial. One-way (phone -> watch) this branch; button actions are a deferred follow-up.

**Tech Stack:** Kotlin + Hilt (phone), PebbleKitAndroid2 1.1.0 (JitPack), Pebble C SDK + `pebble` tool / QEMU emulator (watch, `emery` platform).

**Reference pattern - read these before each mirrored task:**
- `app/src/garminEnabled/kotlin/com/eried/eucplanet/garmin/GarminBridge.kt` (+ `GarminProtocol.kt`)
- `app/src/garminStub/kotlin/com/eried/eucplanet/garmin/GarminBridge.kt` (the no-op)
- `app/build.gradle.kts` lines ~147-172 (the `garminEnabled` gating)
- `garmin-watch-app/` (the watch-side app + `package.json` shape)
- The Garmin Settings section in `SettingsScreen.kt` + the `section_hud_companion`/Garmin strings

---

## Task 1: Pebble SDK + emery emulator environment

**Files:** none (environment). Goal: be able to `pebble build` + `pebble install --emulator emery`.

- [ ] **Step 1: Pick the toolchain.** The Pebble SDK is Linux/macOS-first; this is a Windows machine. Two options:
  - **(a) WSL2 + pebble-tool** (local emulator, scriptable): in an Ubuntu WSL2 distro, follow github.com/andyburris/pebble-setup; `pip install pebble-tool`, install the SDK + QEMU. Needs admin to enable WSL (one-time, user action).
  - **(b) CloudPebble** (browser IDE + in-browser emulator, zero local setup; needs a Rebble/GitHub login).
- [ ] **Step 2: Verify the emulator runs.** Build any sample and `pebble install --emulator emery` (WSL) or run it in CloudPebble. Expected: the emery (Pebble Time 2) emulator window/screen appears.
- [ ] **Step 3: Record the chosen toolchain** in `pebble-watch-app/README.md` (created in Task 9) so the build is reproducible.

> NOTE: If neither can be set up headlessly, STOP and ask the user to run the WSL/CloudPebble setup. The phone-side tasks (2-8) do NOT depend on this and can proceed first.

---

## Task 2: Build gating (`-PpebbleEnabled` + source-set swap + dependency)

**Files:**
- Modify: `app/build.gradle.kts` (mirror the `garminEnabled` block ~147-172)
- Modify: `gradle/libs.versions.toml` (add the pebblekit2 lib + JitPack repo if absent)
- Modify: `settings.gradle` / root `build.gradle` repositories (add `maven { url = uri("https://jitpack.io") }`)

- [ ] **Step 1:** Add JitPack to the dependency repositories (root `settings.gradle` `dependencyResolutionManagement.repositories`): `maven { url = uri("https://jitpack.io") }`.
- [ ] **Step 2:** In `libs.versions.toml` add `pebblekit2 = "1.1.0"` under `[versions]` and `pebblekit2-client = { module = "io.rebble.pebblekit2:client", version.ref = "pebblekit2" }` under `[libraries]`.
- [ ] **Step 3:** In `app/build.gradle.kts`, mirror the garmin block:
```kotlin
val pebbleEnabled = (project.findProperty("pebbleEnabled") as? String)?.lowercase() != "false"
// inside android { sourceSets { getByName("main") { ... } } }:
kotlin.srcDir(if (pebbleEnabled) "src/pebbleEnabled/kotlin" else "src/pebbleStub/kotlin")
// inside dependencies { }:
if (pebbleEnabled) { implementation(libs.pebblekit2.client) }
```
- [ ] **Step 4: Verify both flavors compile (stub first, before the bridge exists is fine to defer to Task 4).** Run: `./gradlew :app:assembleDebug -PpebbleEnabled=false` -> BUILD SUCCESSFUL. Then default (`-PpebbleEnabled` absent) once Task 4 lands.
- [ ] **Step 5: Commit** `git add app/build.gradle.kts gradle/libs.versions.toml settings.gradle && git commit -m "build(pebble): -PpebbleEnabled gating + pebblekit2 dependency (mirrors garmin)"`

---

## Task 3: `PebbleKeys` protocol + dict-assembly unit test (TDD)

**Files:**
- Create: `app/src/pebbleEnabled/kotlin/com/eried/eucplanet/pebble/PebbleProtocol.kt`
- Create: `app/src/pebbleStub/kotlin/com/eried/eucplanet/pebble/PebbleProtocol.kt` (keys only, no SDK refs - so the test compiles in both flavors; OR put the pure key map + builder in `main` and only the sender in `pebbleEnabled`. PREFER: a pure `PebbleTelemetry.build(wheel, settings): Map<Int, Any>` in `app/src/main` so it's testable without the SDK.)
- Test: `app/src/test/java/com/eried/eucplanet/pebble/PebbleTelemetryTest.kt`

Decision: put `PebbleKeys` (int key constants) + `fun buildTelemetry(data: WheelData, settings: AppSettings): Map<Int, Any>` in **`app/src/main`** (`com.eried.eucplanet.pebble.PebbleTelemetry`), so it's flavor-independent and unit-testable. The `pebbleEnabled` source set only adds the SDK sender/bridge.

- [ ] **Step 1: Write the failing test.** Mirror what a Garmin/Wear protocol test would assert.
```kotlin
class PebbleTelemetryTest {
    @Test fun buildsMetricTelemetryDict() {
        val data = WheelData(speedKmh = 23.4f, batteryPercent = 77, voltage = 84.1f,
            current = 5.2f, pwm = 41f, temperatureC = 30f, connected = true)
        val s = AppSettings() // metric defaults
        val m = PebbleTelemetry.build(data, s)
        assertEquals(234, m[PebbleKeys.SPEED])     // speed * 10, int (km/h)
        assertEquals(77, m[PebbleKeys.BATTERY])
        assertEquals(41, m[PebbleKeys.PWM])
        assertEquals(1, m[PebbleKeys.CONNECTED])
        assertEquals("kmh", m[PebbleKeys.UNIT_SPEED])
    }
    @Test fun disconnectedZeroesLive() {
        val m = PebbleTelemetry.build(WheelData(connected = false), AppSettings())
        assertEquals(0, m[PebbleKeys.CONNECTED])
    }
}
```
- [ ] **Step 2: Run, expect FAIL** (`PebbleTelemetry` unresolved). Run: `./gradlew :app:testDebugUnitTest --tests "*PebbleTelemetryTest*"`.
- [ ] **Step 3: Implement** `PebbleKeys` (int consts SPEED/BATTERY/VOLTAGE/CURRENT/PWM/TEMP/CONNECTED/UNIT_SPEED/UNIT_TEMP/ACCENT, mirroring `GarminKeys` semantics) and `PebbleTelemetry.build(...)`. Send speed/temp in canonical metric as ints scaled (speed*10), let the watch convert units (mirror how Garmin/HUD pass canonical metric + unit codes). Keep keys short/stable.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `build(pebble): PebbleTelemetry dict + keys with unit tests`.

---

## Task 4: `PebbleBridge` (enabled) + no-op stub

**Files:**
- Create: `app/src/pebbleEnabled/kotlin/com/eried/eucplanet/pebble/PebbleBridge.kt`
- Create: `app/src/pebbleStub/kotlin/com/eried/eucplanet/pebble/PebbleBridge.kt`

Read `GarminBridge.kt` (both flavors) first - mirror its lifecycle, DI constructor, the `wheelRepository`/`settings` collection, the 5Hz-ish snapshot publish, and the stub's no-op signatures (the two must expose the SAME public API: `fun start()`, `fun stop()`, and whatever the publish site calls).

- [ ] **Step 1: Stub** - `@Singleton class PebbleBridge @Inject constructor()` with no-op `start()`/`stop()` (exactly like `garminStub/GarminBridge`).
- [ ] **Step 2: Enabled bridge:**
```kotlin
@Singleton
class PebbleBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val sender = DefaultPebbleSender(context)        // verify exact ctor from pebblekit2
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pumpJob: Job? = null
    @Volatile private var appOpen = false                    // set by PebbleListenerService

    fun start() { /* observe settings.pebbleEnabled; when enabled, ready the pump */ }
    fun onWatchAppOpened() { appOpen = true; startPump() }
    fun onWatchAppClosed() { appOpen = false; pumpJob?.cancel() }

    private fun startPump() {
        pumpJob?.cancel()
        pumpJob = scope.launch {
            wheelRepository.wheelData
                .sample(280)                                 // ~3.5 Hz; tune on hardware
                .collect { data ->
                    if (!appOpen) return@collect
                    val s = settingsRepository.get()
                    if (!s.pebbleEnabled) return@collect
                    val dict = PebbleTelemetry.build(data, s)
                    runCatching { sender.sendDataToPebble(PEBBLE_APP_UUID, dict) }
                }
        }
    }
    companion object { val PEBBLE_APP_UUID = "<uuid matches watchapp package.json>" }
}
```
- [ ] **Step 3: Verify** the exact `DefaultPebbleSender` constructor + `sendDataToPebble` signature against the pebblekit2 1.1.0 sources/README (https://github.com/pebble-dev/PebbleKitAndroid2). Adjust ctor/args. Run: `./gradlew :app:assembleDebug` (default) and `-PpebbleEnabled=false` -> both SUCCESSFUL.
- [ ] **Step 4: Commit** `feat(pebble): PebbleBridge telemetry pump (enabled) + no-op stub`.

---

## Task 5: `PebbleListenerService` + manifest (app open/close lifecycle only)

**Files:**
- Create: `app/src/pebbleEnabled/kotlin/com/eried/eucplanet/pebble/PebbleListenerService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1:** `class PebbleListenerService : BasePebbleListenerService()` overriding `onAppOpened`/`onAppClosed` -> route to the singleton `PebbleBridge` (inject via Hilt `@AndroidEntryPoint`, or an EntryPoint accessor; mirror how any existing Service reaches a singleton). Leave `onMessageReceived` empty (actions are deferred).
- [ ] **Step 2: Manifest** (guard so the stub build doesn't reference it - put the `<service>` only when pebble is enabled; simplest: a manifest in `app/src/pebbleEnabled/AndroidManifest.xml` that merges in):
```xml
<service android:name="com.eried.eucplanet.pebble.PebbleListenerService" android:exported="true">
    <intent-filter><action android:name="io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH"/></intent-filter>
</service>
```
- [ ] **Step 3: Verify** both flavors build. `./gradlew :app:assembleDebug` and `-PpebbleEnabled=false`.
- [ ] **Step 4: Commit** `feat(pebble): listener service for watchapp open/close lifecycle`.

---

## Task 6: DI wiring + start the bridge

**Files:**
- Modify: the Hilt module + the telemetry publish site that already constructs/starts `GarminBridge` (grep `GarminBridge` to find both).

- [ ] **Step 1:** Provide `PebbleBridge` the same way `GarminBridge` is provided (it's `@Singleton @Inject`, so Hilt likely provides it directly - confirm). 
- [ ] **Step 2:** At the same site that calls `garminBridge.start()` (likely a service/app init), call `pebbleBridge.start()`. Inject `PebbleBridge` there.
- [ ] **Step 3: Verify** `./gradlew :app:assembleDebug` -> SUCCESSFUL; app launches on the 5588/physical phone without crash (`adb install` + launch).
- [ ] **Step 4: Commit** `feat(pebble): wire PebbleBridge into app startup (mirrors GarminBridge)`.

---

## Task 7: Settings "Pebble" section + 14-locale strings

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt` (add `val pebbleEnabled: Boolean = false`)
- Modify: `app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt` (put/get `pebbleEnabled` - parity rule)
- Modify: `SettingsScreen.kt` + `SettingsViewModel.kt` (a Pebble section mirroring the Garmin/HUD section)
- Modify: `app/src/main/res/values/strings.xml` + all 13 `values-*/strings.xml`

- [ ] **Step 1:** Add `pebbleEnabled` to `AppSettings` and to `SettingsJson.toJson`/`fromJson` (the rule: every new field goes in both or it silently reverts).
- [ ] **Step 2:** Add `updatePebbleEnabled` to `SettingsViewModel` (mirror `updateHudServerEnabled`).
- [ ] **Step 3:** Add a Pebble section to `SettingsScreen.kt` mirroring the Garmin section: title, enable switch, a connection-status line (from `DefaultPebbleInfoRetriever` if wired, else a simple "open the watchapp" hint). Strings: `section_pebble`, `pebble_enabled`, `pebble_status_*`, `pebble_get_app_hint`.
- [ ] **Step 4:** Add the new strings to `values/strings.xml`, then translate into all 13 locales (de, fr, es, it, pt-rBR, nl, da, sv, no, pl, ru, uk, ja, ko, tr, zh, zh-rTW, b+es+419 - match the set already present). Terse, no trailing period, no em-dashes.
- [ ] **Step 5: Verify** `./gradlew :app:assembleDebug :app:testDebugUnitTest` -> SUCCESSFUL; toggle shows in Settings.
- [ ] **Step 6: Commit** `feat(pebble): settings section + pebbleEnabled persisted + 14-locale strings`.

---

## Task 8: Docs chip

**Files:** Modify: `docs/index.html` (the `.chip-row`)

- [ ] **Step 1:** Add a Pebble `<a class="chip" href="...">` (icon + "Pebble") in the chip-row near Garmin/HUD. If it overflows the row, apply the existing `.chip-pair` wrap treatment.
- [ ] **Step 2: Verify** visually (serve `docs/` locally, screenshot at ~470px - same as the Garmin+HUD pair fix).
- [ ] **Step 3: Commit** `docs: add Pebble companion chip`.

---

## Task 9: Watchapp scaffold (`pebble-watch-app/`)

**Files:** Create `pebble-watch-app/`: `package.json`, `wscript` (or pebble's default), `src/c/keys.h`, `README.md`. Mirror `garmin-watch-app/` structure (manifest -> Pebble's `package.json`).

- [ ] **Step 1:** `package.json` with a generated `uuid` (matches `PebbleBridge.PEBBLE_APP_UUID`), `targetPlatforms: ["emery"]` (add others later), `companionApp: { android: ["com.eried.eucplanet"] }`, `messageKeys` matching `keys.h`.
- [ ] **Step 2:** `keys.h` int message-key ids matching `PebbleKeys` names/values.
- [ ] **Step 3: Verify** `pebble build` succeeds (empty app). Commit `feat(pebble-watch): watchapp scaffold + keys`.

---

## Task 10: Watchapp telemetry receive + dial render

**Files:** `pebble-watch-app/src/c/main.c`, `src/c/comm.c`, `src/c/dial.c`. Mirror Garmin's `EucPlanetView.mc`/`SpeedGauge.mc` layout in C.

- [ ] **Step 1:** `comm.c` - register `app_message_register_inbox_received`, decode the dict by `keys.h` ids into a `Telemetry` struct, mark stale after ~2 s.
- [ ] **Step 2:** `dial.c` - render big speed (convert from canonical metric using the unit keys), battery, PWM bar (orange/red bands like the HUD/Garmin gauge), temp. Use the accent key for color.
- [ ] **Step 3:** `main.c` - window setup, `app_message_open` with a sane inbox size, prevent screen sleep while active.
- [ ] **Step 4: Verify in emulator:** `pebble build && pebble install --emulator emery`; inject a test dict (PebbleKitJS shim or `pebble emu-*`); the dial shows the values. Iterate the layout.
- [ ] **Step 5: Commit** `feat(pebble-watch): AppMessage receive + telemetry dial`.

---

## Task 11: End-to-end smoke + rate tuning

- [ ] **Step 1:** With the phone app (pebble enabled) + the emulator (via phone Dev Connect) OR a physical Pebble Time 2: open the watchapp, ride the V14 sim / a virtual wheel, confirm the dial tracks live. If the emulator can't join the PebbleKitAndroid2 path, note it and use hardware.
- [ ] **Step 2:** Tune the pump rate (start 3.5 Hz) - raise/lower for smoothness vs BLE/battery; update the `sample(...)` value + a code comment.
- [ ] **Step 3: Commit** any tuning. Update the spec's "open questions" with the resolved rate.

---

## Self-review notes (done while writing)

- Spec coverage: watch app (T9-10), phone bridge (T3-4), listener (T5), gating (T2), settings (T7), docs (T8), testing (T3 unit, T10 emulator, T11 e2e), env (T1) - all covered.
- The pure `PebbleTelemetry.build` lives in `main` (flavor-independent) so the unit test compiles without the SDK - resolves the "test needs the SDK" ambiguity.
- Stub + enabled `PebbleBridge` MUST expose the identical public API (`start`/`stop`/`onWatchAppOpened`/`onWatchAppClosed`); the publish site only calls `start()`.
- Open risk carried from spec: exact `DefaultPebbleSender` ctor/args (verify in T4); emulator-in-the-PebbleKit-path (verify in T11); Windows SDK setup (T1, may need user).
