# iOS migration plan

One codebase: the Android app's own code moves into `:shared` (Kotlin
Multiplatform + Compose Multiplatform) and both the Android app and the iOS app
consume it. Screens are the same Compose code on both platforms. Files move with
`git mv` so rename detection carries later Android edits over.

The archive tag `archive/ios-multiplatform-2026-06` is a parts bin only (iOS
CoreBluetooth handling, Info.plist keys, Dropbox PKCE). It is never merged.

## What the survey found

- 413 Kotlin files, about 160k lines, under `app/src/main/java/com/eried/eucplanet`.
- About 3,760 JVM unit tests in `app/src/test` (254 files), only 4 touch Android APIs.
- Maps are not MapLibre (that dependency is commented out). They are Leaflet HTML
  in an Android `WebView` (`MapHtml.kt`, trip detail, EUC viewer, route builder),
  plus a raster tile cache (`map/MapTileCache.kt`) drawn in Compose. On iOS the
  same HTML runs in a `WKWebView`. No map library migration is needed.
- 3,115 `R.string` references, 2,226 `stringResource` calls, 229 non-Compose
  `getString` calls in 32 files. 2,663 strings, 23 locales.
- Hilt: 21 `@HiltViewModel`, 74 `@Singleton`, 90 `@Inject` constructors,
  10 `@EntryPoint`s, Hilt WorkManager workers.
- JVM APIs used from logic code: `android.util.Log` (551 calls), `org.json`
  (30 files), `String.format` (430), `System.currentTimeMillis` (237),
  `SimpleDateFormat`/`Date`/`Locale`/`TimeZone`, `UUID`, `java.io.File`,
  `ByteArrayOutputStream`, `CRC32`, `MessageDigest`, `HttpURLConnection`,
  `synchronized`/`@Volatile`/`java.util.concurrent`.
- 214 `internal` declarations, so tests move with their code (see Tests).
- Toolchain is old for KMP: Kotlin 2.0.21, Compose BOM 2025.04.01, Room 2.6.1,
  AGP 8.10.1. AGP must stay on 8.x for now: AGP 9 drops the `wearApp` DSL
  (see the note in `app/build.gradle.kts`).
- CI builds on `ubuntu-latest`, so iOS targets must be skipped off macOS.

## Library choices (versions checked on Maven Central, 2026-10-27)

| Today | Shared | Note |
| --- | --- | --- |
| Kotlin 2.0.21 | Kotlin 2.4.21 | needed by CMP 1.12 |
| Compose BOM 2025.04 | Compose Multiplatform 1.12.2 | Android resolves to the matching Jetpack Compose; bump is its own step |
| Hilt | Koin 4.2.2 (`koin-compose-viewmodel`) | Hilt bridge during the move, see DI |
| OkHttp, HttpURLConnection | Ktor client 3.6.0 (OkHttp engine on Android, Darwin on iOS) | OkHttp engine keeps Android's network stack |
| Room 2.6.1 | Room 2.8.x KMP | Android keeps its SupportSQLite driver and migrations; iOS uses the bundled driver and starts at the current schema |
| DataStore 1.1.1 | DataStore 1.2.x KMP (`createWithPath`) | Android keeps the same file path, so no data moves |
| strings.xml | Compose resources | see Strings |
| Coil 2.7 | Coil 3.6.3 | |
| navigation / lifecycle / viewmodel | `org.jetbrains.androidx.navigation` 2.10.0, `lifecycle` 2.11.0 | |
| reorderable 2.4.3 | reorderable 3.1.0 (already KMP) | |
| zxing core (QR encode) | expect/actual: zxing on Android, CoreImage on iOS, same Compose renderer | |
| org.json | expect class, `actual typealias` to org.json on Android | Android behavior identical |
| java.util date/format | expect/actual, `typealias` or direct call on Android, Foundation on iOS | |
| kotlinx-datetime, okio | added for new common code | |
| BLE | own `BleTransport` interface: Android actual is the existing GATT code, iOS actual is CoreBluetooth | Kable not used: swapping Android's stack would change behavior on the product riders use |

## Core techniques

1. **Facades with an Android actual that is today's call.** `Log`, `nowMs()`,
   `String.format`, JSON, date formatting, UUID, CRC32, files. On Android the
   actual is a `typealias` or a one-line delegate to exactly what the code calls
   today, so Android behavior cannot change. A moved file usually changes only
   its imports.
2. **Platform interfaces for capabilities**, injected through Koin or a
   CompositionLocal: `BleTransport`, `LocationSource`, `Speaker` (TTS),
   `TonePlayer`, `EngineAudio`, `Notifier`, `FilePicker`/`Sharer`,
   `UrlOpener`, `Haptics`, `BackgroundSession`, `QrScanner`, `QrEncoder`,
   `MapWebView`, `SpeechInput`, `PlatformFeatures` (what this platform has).
3. **Android-only features stay in `:app`** and plug into shared code through the
   same interfaces. On iOS their UI entry points hide via `PlatformFeatures`.
4. **commonMain guard**: a script plus a JVM test fail when commonMain imports
   `android.*`, `androidx.*` (non-KMP), `java.*`, `javax.*`, `dagger.*`,
   `okhttp3.*`, `org.json.*` or uses `R.`. It exists from the first move.

## Strings

`strings.xml` stays the single source in `app/src/main/res/values*/`, so lint,
`LocaleCoverageTest`, widgets, the manifest and `locales_config.xml` keep
working. A Gradle task generates `composeResources/values*/strings.xml` from it
into `shared/build` (generated, never committed, so it cannot drift). Shared code
uses `Res.string.x`.

Compose resources only substitutes `%1$s` style arguments, while the app uses
`%s`, `%d`, `%.1f`, `%+.1f`, `%+d`, `%%`. So shared code calls a project
`stringResource(res, vararg args)` that reads the raw string and formats it with
the `String.format` facade (Java's formatter on Android), keeping output
identical. Risks to verify in step 0.5: the `values-b+es+419` qualifier, in-app
language switching (AppCompat per-app locale) on Android, `\'`/`\n` escapes,
plurals and the one string-array. A drift test compares the generated set with
the source (every key, every locale).

## DI

Moved classes are registered in common Koin modules (`singleOf(::X)`). While
Hilt still builds the Android graph, a Hilt bridge module provides each moved
type with `koin.get<X>()`, so unmoved Android classes and `@HiltViewModel`s keep
working unchanged. ViewModels move to `koinViewModel()` when their screen moves.
Hilt is removed when the last consumer is gone. `AppStartupTest` and
`LifecycleWiringTest` are extended to read Koin modules, and a Koin `verify()`
test guards the graph.

## Tests

Tests move with their code (`git mv app/src/test/... shared/src/androidUnitTest/...`)
so `internal` stays visible and JUnit4 keeps working unchanged. The total count
must not drop. Later, pure-logic tests can be promoted to `commonTest` so they
also run on the iOS simulator.

## Order

Each step is behavior-neutral for Android, ends with `./gradlew test` and lint
green and the app run on the emulator, and is small enough to merge into
next-experimental soon after.

**Step 0, groundwork**
- 0.1 Toolchain: Kotlin 2.4.21, KSP 2.x, Compose BOM to what CMP 1.12.2 maps to,
  AGP 8.13, Room 2.8 (still Android only). Pure version bump.
- 0.2 Empty `:shared` KMP module (android, iosArm64, iosSimulatorArm64; iOS
  targets only on macOS), `:app` depends on it.
- 0.3 Facades: Log, clock, format, JSON, date, UUID, CRC32, files.
- 0.4 commonMain guard script and test.
- 0.5 Strings generation, `stringResource` wrapper, drift test.

**Step 1, Milestone 1 (prove the pipeline)**
- Move pure model and decoders: `data/model` (WheelData, WheelSettings,
  HeadlightReadback, BatteryPercentSettings), `util` (ByteUtils,
  BatteryPercentEstimator, MetricSanity, Units, Smoothing), `diagnostics`
  (DiagnosticCommand, DiagnosticsLogger), `ble/*` parsers, protocols, commands,
  adapters, `ble/virtual`. Tests move with them.
- Split `BleConnectionManager`: the policy (connect state machine, write queue,
  polling, reconnect, auth handshakes) stays as one class and moves to shared;
  the GATT calls move behind `BleTransport` with the existing code as the Android
  actual. Checked with the virtual wheels and then on a real wheel by you.
- iOS actual for `BleTransport` on CoreBluetooth (lifted from the archive).
- `iosApp` Xcode project hosting a Compose `MainViewController`. M1 screen: the
  real scan list plus a temporary probe showing live speed and battery, deleted
  when the dashboard moves.

**Step 2, data layer**: settings (`AppSettings`, `AdvancedSpec`, `SettingsJson`,
`SettingsStore` to DataStore KMP, `SettingsRepository`), Room entities, DAOs and
database (migrations in androidMain), repositories, theme store, Koin bridge.

**Step 3, ride logic and services**: alarms, automations, headlight rules, trip
recording, charging estimator, voice reports. `WheelService` splits into a shared
`RideSession` plus an Android foreground-service shell and an iOS
background shell. iOS actuals: CoreLocation, AVSpeechSynthesizer, AVAudioEngine
(tones and engine sound), UNUserNotificationCenter, CoreMotion.

**Step 4, UI** in this order: theme and `ui/common`, navigation host, scan,
dashboard (+ sources), metrics, settings (largest, 23k lines), recording and trip
detail (map via `MapWebView`), navigator and route builder, charging, lockdown,
radar. Each screen is compared side by side with Android.

**Step 5, network features**: weather, nav routing, crews, share (Ktor
websockets), Dropbox (PKCE via ASWebAuthenticationSession, from the archive),
eucstats.

**Step 6, Studio** (camera, recording, replay): AVFoundation actuals; floating
overlay windows stay Android-only.

**Step 7**: remove Hilt, write `docs/IOS_SYNC.md` final form.

## Android-only APIs and their iOS strategy

| Android | Where | iOS |
| --- | --- | --- |
| BluetoothGatt, LE scanner | ble, ble/gps, ble/radar, tpms | CoreBluetooth `BleTransport`; Info.plist `bluetooth-central` background mode |
| FusedLocation | repositories, nav | CLLocationManager; `location` background mode for trip recording |
| TextToSpeech | service/VoiceService | AVSpeechSynthesizer |
| AudioTrack tones, ExoPlayer gapless loops | service/TonePlayer, audio | AVAudioEngine buffers and looping player nodes |
| SpeechRecognizer | voice | SFSpeechRecognizer (later) |
| Foreground service, WorkManager | service, data/sync | background BLE and location modes; uploads when foregrounded or BGTaskScheduler |
| NotificationManager | service, charge alerts | UNUserNotificationCenter |
| Vibrator | util/VibratorHelper | UIImpactFeedbackGenerator |
| SensorManager | PhoneSensorRepository | CoreMotion |
| WebView + evaluateJavascript | maps | WKWebView in `UIKitView`, same HTML and JS calls |
| CameraX + zxing scan | ui/navigator QR scanner | AVCaptureMetadataOutput |
| zxing encode | ui/dashboard QR | CoreImage CIQRCodeGenerator into the same Compose renderer |
| Activity result, SAF, share intents | studio, recording, settings | UIDocumentPicker, UIActivityViewController |
| Custom Tabs | Dropbox link | ASWebAuthenticationSession |
| AppCompat per-app locale | util/LocaleHelper | `AppleLanguages` override, applied on restart |
| Play Integrity | data/eucstats | App Attest; needs a server change on eucstats, open question |
| camera2, MediaCodec, MediaStore | ui/studio | AVFoundation, AVAssetWriter, Photos (step 6) |
| BatteryManager, ConnectivityManager | diagnostics, amazfit, hud | UIDevice battery, NWPathMonitor |

## Android-only for now

Android Auto (`car`), Wear OS (`wear`), the HUD (`service/hud`, `:hud`), Garmin
(`garminEnabled`), Amazfit (`amazfit`), home-screen widgets (`widget`), Flic
(`flic`), floating overlay and PiP (`service/overlay`, `ui/pip`). They stay in
`:app` and are listed in `docs/IOS_PARITY.md`.

## Open questions for Erwin

- Which wheel do you have at hand for the BLE tests (brand and model)?
- eucstats uses Play Integrity; iOS needs App Attest plus a server path. Fine to
  leave eucstats upload off on iOS until then?
- iOS hides Android-only sections (Flic, Garmin, widgets, ...) instead of
  greying them out. Agreed?
