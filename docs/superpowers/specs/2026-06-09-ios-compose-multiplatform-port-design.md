# EUC Planet — iOS port via Compose Multiplatform (v1: core ride slice)

**Date:** 2026-06-09
**Branch:** `feat/ios-multiplatform` (off `next-version`)
**Status:** Design — pending owner approval

## 1. Goal

Port EUC Planet to iPhone as a **single shared codebase** so that one Kotlin/Compose
change updates both Android and iOS. The Android app must keep building and shipping,
with **zero behavioral regressions**, at every step of the migration.

The chosen mechanism is **Compose Multiplatform (CMP) + Kotlin Multiplatform (KMP)**:
JetBrains Compose UI runs natively on iOS, and the existing Kotlin logic is shared.
This is the only strategy where Android edits auto-reflect on iOS (a native SwiftUI
rewrite would force the UI to be maintained twice; a Flutter/RN rewrite discards the
existing Compose/Kotlin investment).

## 2. Locked decisions

| Decision | Choice |
|---|---|
| Strategy | Compose Multiplatform + KMP (shared codebase) |
| First milestone | Core ride slice only (see §3) |
| Distribution | Free personal provisioning → sideload to iPhone 11; paid account / TestFlight later |
| iOS background | **When-in-use only** for v1 (recording pauses when backgrounded; true background recording is v2 + paid account) |
| DI | **Koin** (not kotlin-inject) — no codegen, tolerates incremental Hilt coexistence |
| Navigation (shared) | **No nav library for v1** — simple state-based screen switching; adopt Voyager/Decompose later if shared screens grow |
| Room | Stays **2.6.1 on the Android side behind a DAO interface**; iOS starts with a fresh SQLite DB (no Android-backup import in v1). No Room version bump bundled with this work. |
| Motor sound | Silent stub on iOS v1 (no AVAudioEngine PCM engine-sound port yet) |
| Mac build host | Driven over **SSH from the Windows session** |
| AGP | **Stays 8.7.3** — do not move to AGP 9.x (it removes the `wearApp` DSL the release build depends on) |

## 3. Scope

**In v1 (the "core ride slice"):**
1. BLE connect to the wheel (scan, connect, reconnect).
2. Live dashboard telemetry.
3. Trip recording to DarknessBot-compatible CSV.
4. Custom alarms + voice announcements (beep + TTS).

**Out of v1 — stays Android-only in `:app`, carved cleanly so it never blocks the iOS build:**
maps/navigator (MapLibre), Overlay Studio (CameraX/Media3 video), motor-sound synthesis,
HUD server (Ktor/JmDNS), Wear OS, Garmin Connect IQ, Flic 2, external BLE GPS (RaceBox),
Varia radar, EucStats upload + Play Integrity attestation, true background recording.

## 4. Module architecture

Introduce **one** new KMP module `:shared` plus a thin **`iosApp/`** Xcode project. `:app`,
`:wear`, `:hud`, `:hud-protocol` are otherwise untouched.

```
EucPlanet/
├── :shared            (NEW — kotlin.multiplatform + org.jetbrains.compose)
│   targets: androidTarget(), iosArm64() [iPhone 11], iosSimulatorArm64() [M1 sim]
│   ├── commonMain/com/eried/eucplanet/
│   │   ├── ble/            ALL parsers/commands/protocol + WheelAdapter iface + CompositeWheelAdapter dispatch + ByteUtils
│   │   ├── ble/transport/  expect BleTransport / BleScanner / BleDevice (the I/O seam)
│   │   ├── data/model/     WheelData, WheelSettings, AppSettings, TripRecord, AlarmRule, WheelProfile (Android R.string IDs decoupled — see §6)
│   │   ├── data/repo/      Settings/Trip/Wheel/Alarm repositories (plain constructor injection)
│   │   ├── service/        AlarmEngine, AutomationManager, ChargingEstimator, beep/sine math
│   │   ├── location/       expect LocationProvider + LocationData
│   │   ├── platform/       expect: Speaker, TonePlayer, Haptics, AudioFocus, FileStore, DirectoryProvider, SettingsStore, PermissionController, Logger, Crc32, Clock
│   │   ├── di/             Koin commonModule + expect platformModule
│   │   └── ui/             CMP screens: DashboardScreen, tiles, MetricDetail, theme/* (pure Kotlin)
│   │   └── composeResources/  strings + drawables migrated from app res/ (compose.resources)
│   ├── androidMain/   actual seams = thin wrappers over EXISTING code (BluetoothGatt, Fused, TTS, AudioTrack, Room DAOs, DataStore)
│   └── iosMain/       actual seams = CoreBluetooth, CLLocationManager, AVSpeechSynthesizer, AVAudioEngine, FileManager, NSUserDefaults, SQLite
├── :app   Android — unchanged build, now depends on :shared. Keeps Hilt, WheelService,
│          WorkManager, CameraX/Studio, MapLibre, Garmin, Flic, NavGraph, Attestation.
├── :wear / :hud / :hud-protocol   untouched (zero dependency on :shared)
└── iosApp/   minimal SwiftUI/UIKit host → ComposeUIViewController { MainViewController() }
```

**Dual-Compose caveat (the single most fragile build concern):** `:app` uses the Android
`androidx.compose-bom`; `:shared` uses the *different* `org.jetbrains.compose` artifact
group with its own Material3. They must never share a source set. Shared CMP screens are
introduced behind a feature route so the two stacks never collide; if coexistence
misbehaves, shared screens can render iOS-only while Android keeps its existing screens.
**This is proven with a one-screen spike (build step 0) before any real migration.**

## 5. Platform seams (`expect`/`actual`) needed for v1

Each is a small interface in `commonMain` with an Android actual (wraps existing code)
and an iOS actual (Kotlin/Native):

| Seam | Android actual | iOS actual |
|---|---|---|
| `BleTransport` / `BleScanner` / `BleDevice` | BluetoothGatt / BluetoothLeScanner | CoreBluetooth (CBCentralManager/CBPeripheral) |
| `LocationProvider` / `LocationData` | FusedLocationProviderClient | CLLocationManager |
| `Speaker` | TextToSpeech | AVSpeechSynthesizer |
| `TonePlayer` (sine math stays in common) | AudioTrack | AVAudioEngine |
| `Haptics` | Vibrator/VibratorManager | UIImpactFeedbackGenerator / CoreHaptics |
| `AudioFocus` | AudioManager focus | AVAudioSession |
| `FileStore` / `DirectoryProvider` | java.io.File, filesDir/externalFilesDir | FileManager, documentDirectory |
| `SettingsStore` | DataStore JSON blob | NSUserDefaults |
| DAO interface | Room 2.6.1 DAOs | fresh SQLite |
| `PermissionController` | ContextCompat + ActivityResult | CB/CL authorization |
| `Logger` / `Crc32` / `Clock` | android.util.Log / java.util.zip.CRC32 / System time | os_log / pure-Kotlin / kotlinx-datetime |

The BLE **parsers, command builders, state machines, and `CompositeWheelAdapter` dispatch
stay in `commonMain` as the single source of truth**; only I/O crosses the seam. Adapter
reset semantics (InMotionV2 `reassemblyBuffer`, Kingsong `lastTemp`, Veteran `lastLightOn`)
and HM-10 first-frame magic-byte disambiguation (DC/5A/5C) must be preserved in the
`expect` contract and covered by frame-fixture tests that run on both targets.

## 6. One-time refactors ("taxes") — verified against the code

These are ~40% of total effort, paid once; afterward shared features are ~free on both
platforms.

- **Hilt → Koin** across the v1 surface. Hilt spans **71 files** (1 `@HiltAndroidApp`,
  5 `@AndroidEntryPoint`, 18 `@HiltViewModel`, ~91 `@Inject`, 2 `@HiltWorker`, 5 `@Module`,
  `hiltViewModel()` in 22 screens). KSP/Hilt codegen cannot run on Kotlin/Native.
  **Strangler migration:** de-annotate classes moving to `:shared` (plain constructor
  injection), register them in Koin, and have `:app`'s Hilt `@Provides` *delegate to Koin*
  so a single instance is shared and Android keeps resolving everything. Migrate
  package-by-package; lint for orphaned `@Inject`. Out-of-scope graphs (Radar, ExternalGps
  multibindings) stay Hilt-only in `:app`.
- **`AlarmRule` R.string decoupling (verified blocker).** `AlarmRule.kt` imports
  `com.eried.eucplanet.R`; `AlarmMetric`/`AlarmComparator` enums store `labelRes: Int`
  resource IDs. Replace the `Int` IDs with stable string keys resolved at the UI layer.
  Do this as an isolated Android-green refactor first, then relocate. Touches every
  `.labelRes` call site. `Units.kt` (`Context.getString(R.string.unit_*)`) gets the same
  treatment.
- **`SettingsJson` org.json → kotlinx.serialization.** `AppSettings` has 200+ fields;
  `org.json` is JVM-only. Prototype on a small model (`AlarmRule`) to confirm
  byte-compatible output, then roll `AppSettings` forward keeping the **same JSON shape**
  so existing DataStore blobs still parse. kotlinx.serialization is already a project dep.
- **~139 `stringResource(R.string)` → CMP `Res.string`** via `compose.resources`
  (first-class CMP resources; not moko-resources). Drawables → `composeResources/drawable/`.
  Native iOS chrome strings (`Info.plist` usage descriptions) authored separately in `iosApp/`.

## 7. Keeping Android green (no-regression principles)

1. **Strangler, not big-bang.** `:shared` is additive; create it empty, wire into `:app`,
   ship a no-op, then move ONE leaf subsystem per commit (start with `ByteUtils` + pure
   parsers — zero Android deps).
2. **Move code, not behavior.** Relocated files keep identical logic; existing JUnit parser
   tests must pass unchanged after each move.
3. **Room stays put for v1** (2.6.1, schema 47, `exportSchema=false`). Do not combine the
   KMP introduction with a Room bump or migration changes.
4. **Android-only edges stay in `:app`** (WheelService, WorkManager, Attestation,
   CameraX/Studio, MapLibre, Garmin, Flic, NavGraph, WelcomeTutorial) with their Hilt
   annotations intact.
5. **CI guardrail:** existing `assembleDebug`/`assembleRelease` + parser unit tests stay a
   required check on `feat/ios-multiplatform`; add `:shared` Android-target compilation
   before adding the iOS link step.

## 8. Environment & toolchain

- **Roles:** Windows = edit shared Kotlin/Compose + Android builds + git; **Mac mini M1 =
  iOS build host** (Kotlin/Native + Xcode require macOS); iPhone 11 = test device.
- **Repo sync:** via **git** — the Mac clones the repo and works on `feat/ios-multiplatform`;
  push/pull between machines.
- **Mac access:** Remote Login (SSH) enabled; the Windows session drives toolchain install,
  Gradle/Xcode builds, and device deploys over SSH (key-based auth).
- **Mac toolchain:** Xcode + Command Line Tools, **JDK 17** (Temurin), Homebrew.
  **No CocoaPods** — direct **XCFramework** via
  `./gradlew :shared:embedAndSignAppleFrameworkForXcode` in an Xcode Run Script phase.
- **iOS app:** minimal SwiftUI host → `ComposeUIViewController { MainViewController() }`;
  `AppDelegate` calls `KoinInitializer.start()` and primes permissions early. Deployment
  target **iOS 14.5+**. `Info.plist`:
  `NSBluetoothAlwaysAndWhenInUseUsageDescription`, `NSLocationWhenInUseUsageDescription`,
  `UIBackgroundModes=[bluetooth-central, location]`.
- **Signing:** free provisioning via personal Apple ID (Automatically manage signing,
  personal team) → installs to iPhone 11, **re-sign every 7 days**. iPhone needs Developer
  Mode enabled + the developer cert trusted.
- **Versions:** Kotlin 2.0.21, KSP 2.0.21-1.0.28 (androidTarget only), AGP **8.7.3** (frozen),
  add Compose Multiplatform ~1.7.x. Targets `iosArm64` (device) + `iosSimulatorArm64` (M1 sim);
  skip `iosX64`.

## 9. Build sequence (de-risked)

0. **Dual-Compose spike** (1–3 days): throwaway `:shared` renders ONE Material3 screen on
   both Android and an iOS simulator from the Mac. Validates the riskiest assumption first.
1. **Create `:shared`**, wire into `:app`, ship a no-op. `assembleDebug` stays green.
2. **Move pure parsers + ByteUtils** to `commonMain`; add `expect Crc32`/`Logger`; existing
   JVM parser tests pass unchanged.
3. **Stand up Koin + de-annotate** v1 repos/engines; Hilt `@Provides` delegate to Koin.
4. **`BleTransport`/`BleScanner` expect**; Android actual wraps existing
   `BleConnectionManager`/`BleScanner`/`BleAutoReconnector`. Verify Android BLE connect +
   live telemetry against a real wheel — no behavior change. Preserve onDisconnect reset.
5. **Migrate settings + CSV + models off Android APIs**: `SettingsStore`,
   `CsvWriter`→`FileStore` (DarknessBot columns byte-identical), `AlarmRule` R.string
   decoupling, `Clock`. Models move to `commonMain`.
6. **`LocationProvider` + `Speaker`/`TonePlayer`/`Haptics`/`AudioFocus` expect**; Android
   actuals wrap existing impls. Android app fully green on the shared core.
7. **Dashboard + alarm UI → CMP** in `commonMain`; ~139 `stringResource` renames; theme
   system ports as-is (pure Kotlin); state-based navigation. Render on Android via CMP
   behind a feature route.
8. **iOS actuals + `iosApp` Xcode project**: CoreBluetooth/CLLocationManager/
   AVSpeechSynthesizer/AVAudioEngine/CoreHaptics/FileManager/NSUserDefaults/SQLite. Build
   XCFramework; host `ComposeUIViewController`; `Info.plist` + `UIBackgroundModes`.
9. **Sideload to iPhone 11** (free provisioning); ride-test the core slice end-to-end:
   BLE connect → live dashboard → trip CSV → alarms + voice. Validate disconnect/reconnect
   and Bluetooth flicker; when-in-use GPS only.

## 10. Effort & cost drivers

**~10–14 engineer-weeks for v1** (one experienced Android dev ramping on KMP/iOS).
Biggest drivers: one-time KMP scaffolding + dual-Compose proof (~1 wk, high uncertainty,
the gate) · DI migration (~2–3 wk) · BLE expect/actual + CoreBluetooth (~2–3 wk) ·
data layer incl. `AlarmRule`/`AppSettings` (~2 wk) · audio/location actuals (~2 wk) ·
dashboard → CMP (~2 wk) · iOS host + sideload + ride-testing (~1 wk). One-time refactor ≈
40% of total, paid once.

## 11. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Dual-Compose artifact collision | Spike step 0; keep androidx-compose in `:app` and CMP in `:shared` strictly separate; feature-route shared screens; iOS-only fallback. |
| `AlarmRule` carries R.string Int IDs | Decouple to string keys as an isolated Android-green refactor before relocating. |
| Room lock-in | Keep Room 2.6.1 androidMain behind a DAO interface; iOS fresh DB; no version bump bundled. |
| iOS background-execution mismatch | v1 = when-in-use only; recording pauses when backgrounded; defer background recording to v2 + paid account. |
| Stateful BLE adapters / HM-10 disambiguation diverging on CoreBluetooth | Keep all parser/dispatch in commonMain; iOS transport does I/O only; frame-fixture tests on both targets; explicit disconnect/reconnect/flicker tests. |
| DI blast radius (71 files) | Strangler with Hilt→Koin delegation; migrate package-by-package; lint for orphaned `@Inject`. |
| org.json JVM-only | Migrate `SettingsJson` to kotlinx.serialization; prototype small, keep JSON shape stable. |
| Motor-sound (AudioTrack PCM, ~23ms latency) no clean iOS analog | Out of v1 — silent stub on iOS; only simple beep TonePlayer in scope. |

## 12. Deferred to v2+

Maps/navigator (MapLibre has an iOS SDK — feasible later), Overlay Studio (full
AVFoundation/VideoToolbox rewrite), motor-sound synthesis, HUD, Wear OS, Garmin, Flic,
external BLE GPS, Varia radar, EucStats upload + Play Integrity, true background recording,
Android-DB import on iOS, and the full 71-file Hilt→Koin sweep for out-of-scope graphs.
