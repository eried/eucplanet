# Keeping iOS in sync with Android

The iPhone app is not a port. It runs the same Kotlin code as the phone app:
everything that can be shared lives in the `:shared` module, and both apps
consume it. A change to shared code reaches iOS the next time the iOS app is
built, with no extra work.

See `docs/IOS_MIGRATION_PLAN.md` for how the code got here and
`docs/IOS_PARITY.md` for what each screen and feature does on iOS today.

## Where code lives

| Source set | Compiled for | What goes there |
|---|---|---|
| `shared/src/commonMain` | Android and iOS | Everything that does not touch a platform API: protocols, decoders, models, settings, view models, Compose screens. |
| `shared/src/androidMain` | Android only | The Android side of an `expect`, or an interface implementation that uses the Android SDK. |
| `shared/src/iosMain` | iOS only | The iOS side of an `expect`, or an interface implementation that uses an Apple framework (CoreBluetooth, CoreLocation, AVFoundation...). |
| `shared/src/androidUnitTest` | JVM | The JUnit tests that moved with the code. They run in `./gradlew test` like before. |
| `shared/src/commonTest` | JVM and iOS simulator | Tests that should also run on iOS (`:shared:iosSimulatorArm64Test`, Mac only). |
| `app/` | Android only | Android-only features (Android Auto, watches, HUD, widgets, Flic), services, `Application`, manifest. |
| `iosApp/` | iOS only | The Xcode project: a small UIKit host that shows the shared Compose UI. |

## The everyday case: changing a shared feature

Edit the file in `shared/src/commonMain`, build and test Android as usual.
Nothing else is needed. The iOS app picks the change up on its next build.

## When iOS needs its own side

Only when the change touches a platform API. Then:

1. Put the platform call behind a small interface (preferred, injected through
   Koin) or an `expect` declaration in commonMain.
2. Write the Android side in `shared/src/androidMain` (or in `app/` when it is
   wired from there).
3. Write the iOS side in `shared/src/iosMain`. If you can't write it now, add a
   stub that does nothing and mark the feature "not yet" in
   `docs/IOS_PARITY.md`. Never leave the iOS build broken.

Seams that need both sides on every change:

| Seam | Common | Android | iOS |
|---|---|---|---|
| Wheel link (BLE) | `ble/WheelLink.kt` | `app/.../ble/BleConnectionManager.kt` | `iosMain/.../ble/IosWheelLink.kt` |
| Log, clock, charsets, CRC, format, local time and time zone, the phone's short time and 12/24-hour axis labels, JSON, HTTP, SHA-256, gzip and raw inflate, dates, strings | `platform/*.kt` | `platform/*.android.kt` | `platform/*.ios.kt` |
| Settings file | `data/store/SettingsStore.kt` | `app/.../data/store/SettingsStoreAndroid.kt` (DataStore, imperial check) | `iosMain/.../app/IosAppGraph.kt` |
| Dropbox link | `DropboxRepository.beginLink` / `handleAuthCode` | `app/.../data/repository/DropboxLinkAndroid.kt` (Custom Tab, verifier in SharedPreferences) | `iosMain/.../app/IosDropboxLink.kt` (ASWebAuthenticationSession) |
| Ride Live Activity | `service/RideNotificationText.kt` (the text), `service/RideQuickActions.kt` (the buttons), `iosMain/.../app/RideActivity.kt` (when, and the presses) | `WheelService` notification | `iosApp/RideLiveActivity.swift` (ActivityKit), `iosApp/RideActionIntent.swift` (the presses), `iosApp/Widgets/` (the look) |
| Alarm rules for settings.json | `data/sync/DropboxSettingsSync.AlarmRuleStore` | `SyncManager` (AlarmDao) | `IosAppGraph.alarms` (a JSON file until Room moves) |
| Dropbox trips | `data/sync/DropboxTripSync.kt` (Sync all, the background pass, the pull, archiving), `TripFiles.kt` (`TripFileStore`), `TripCsvMeta.kt`, `DropboxTripHost`, `DropboxSyncUi` | `SyncManager` (backup folder hooks, WorkManager retries, the conflict dialog's flows), `DropboxSyncWorker`, `AndroidTripFiles.kt` | `iosMain/.../app/IosDropboxSync.kt` (in-app retries, Documents/themes), `IosTripFiles.kt` |
| Dropbox HTTP | `DropboxRepository(transport)`, `platform.HttpExecutor` | `HttpClient` (OkHttp) | `HttpClient` (NSURLSession); tests run against `commonTest/.../FakeDropbox.kt` |
| Leaderboard rider id and upload trigger | `data/sync/EucStatsSweep.kt`, `EucStatsSettingsPort` | `SyncManager` (rider id in the backup folder), `EucStatsUploadWorker` (WorkManager) | `iosMain/.../app/IosEucStats.kt` (Documents file; trip finish, launch, foreground) |
| Crew pass camera and links | `crews/CrewsPairScreen.kt` (`CrewsPairHost`) | `app/.../crews/CrewsPairActivity.kt` (CameraX, intent filter) | `iosMain/.../app/IosCrews.kt`, `iosApp/QrScannerBridge.swift`, `AppDelegate` open URL |
| Leaderboard picture | `SettingsHost.rememberAvatarPicker` | `AndroidSettingsHost` (gallery, UserMarkerCropDialog) | `iosMain/.../app/IosAvatarPicker.kt` (UIImagePickerController) |
| Trip meter | `data/repository/TripMeterRepository.kt` (`TripMeterFixes`), `data/store/TripMeterStore.kt` (a DataStore) | `AndroidTripMeterFixes` (TripRepository, `Location.distanceBetween`), `TripMeterStoreAndroid.kt` | `iosMain/.../app/IosTripMeter.kt` (IosLocation, haversine, Documents/datastore) |
| Charging monitor | `ui/charging/` (`ChargingHost`, `ChargingCsvFiles`, `PhoneMotionEffect`), `service/ChargeAlertPolicy.kt` | `ChargingPortsAndroid.kt` (CreateDocument, ContentResolver), `PhoneMotion.android.kt` (SensorManager), `WheelService` charge alert notifications | `iosMain/.../app/IosCharging.kt` (share sheet), `PhoneMotion.ios.kt` (CoreMotion), `app/IosChargeAlerts.kt` (UNUserNotificationCenter) |
| Trip viewer map | `ui/recording/TripMapHtml.kt` (the page), `TripRouteMap.kt`, `TripDetailHost`, `map/MapLayersJs.kt` (the table, from hud-protocol's `MapLayers.kt` via `shared/build.gradle.kts`) | `AndroidTripDetailHost.kt` (WebView, offline tile intercept, fullscreen window) | `iosMain/.../app/IosTripDetail.kt` (WKWebView, the bundled Leaflet from `app/src/main/assets` via `generateIosLeaflet`) |
| Legal Mode Lockdown | `data/repository/LegalLockdownController.kt`, `data/store/LegalLockdownStore.kt` (a DataStore), `ui/lockdown/` (`LegalLockdownHost`) | `LegalLockdownDataStore.kt` (the legal_lockdown file), `LegalLockdownHostAndroid.kt`, MainActivity swaps the graph | `iosMain/.../app/IosLockdown.kt` (Application Support, excluded from backup), `MainViewController.kt` swaps the screens |
| Object graph | `di/SharedKoin.kt` (Koin) | `app/.../di/SharedModule.kt` hands the Koin singletons to Hilt; `AppModule` builds the moved repositories | `MainViewController.kt` reads Koin; `app/IosAppGraph.kt` builds the rest |

The table grows as packages move; keep it current.

## Strings

The 23 locales stay where they are, in `app/src/main/res/values*/strings.xml`.
Translate and add strings there exactly as before.

- Shared code names a string `Res.string.<key>` and shows it with
  `com.eried.eucplanet.platform.stringResource` (or `Context.getString(res)`
  in Android-only code). On Android that resolves to the phone app's own
  `R.string.<key>` through a table `app/string-ids.gradle.kts` generates from
  the keys shared code names, so Android text and formatting are exactly what
  they were.
- iOS reads Compose resources that `shared/compose-strings.gradle.kts`
  generates from the same files at build time, processed the way aapt2
  processes them. `tools/check-ios-strings.py` compares every string in every
  locale with an APK's aapt2 dump (61,158 identical when it was written).
- `values-b+es+419` becomes `values-es-rXL`, since Compose takes two-letter
  regions only.
- The settings search index reads `:shared`'s ui code too, and both
  `R.string` and `Res.string`.

Labels some Android-only code still reads from `R.string` stay in `:app` as
extensions on the shared type: `Units.speedUnit(context, unit)`
(`util/UnitsAndroid.kt`) and the widget registries' `pickerLabel`
(`data/model/WidgetSlotLabels.kt`).

## Using JVM-style APIs in common code

Shared code keeps the same call sites the Android code used. The facades in
`com.eried.eucplanet.platform` shadow the JVM names, so moving a file usually
means only changing imports:

| Instead of | Import |
|---|---|
| `android.util.Log` | `com.eried.eucplanet.platform.Log` |
| `System.currentTimeMillis()`, `System.nanoTime()`, `System.arraycopy` | `com.eried.eucplanet.platform.System` |
| `Charsets.US_ASCII`, `String(bytes, cs)`, `toByteArray(cs)` | `com.eried.eucplanet.platform.Charsets` (and `String`, `toByteArray`) |
| `"%.1f".format(x)`, `String.format(...)`, `Locale.US` | `com.eried.eucplanet.platform.format`, `com.eried.eucplanet.platform.Locale` |
| `java.util.zip.CRC32` | `com.eried.eucplanet.platform.crc32` |
| `java.util.Calendar` | `com.eried.eucplanet.platform.localTimeParts` |
| `SimpleDateFormat(p, Locale.getDefault()).format(Date(ms))` | `com.eried.eucplanet.platform.formatLocalDate` |
| `OffsetDateTime.parse(s).toEpochSecond()` | `com.eried.eucplanet.platform.isoEpochSeconds` |
| `MessageDigest` SHA-256, `SecureRandom.nextInt` | `com.eried.eucplanet.platform.sha256`, `secureRandomInt` |
| `Uri.Builder().appendQueryParameter` | `com.eried.eucplanet.platform.UrlQuery` |
| OkHttp calls | `com.eried.eucplanet.platform.HttpClient` (OkHttp on Android, NSURLSession on iOS) |
| `java.util.Random` | `kotlin.random.Random` |
| `BluetoothGattCharacteristic.WRITE_TYPE_*`, `PROPERTY_WRITE*` | `com.eried.eucplanet.ble.GattCharacteristic` |
| `java.io.ByteArrayOutputStream` | `com.eried.eucplanet.platform.ByteArrayOutputStream` |
| `@Volatile` (kotlin.jvm) | `kotlin.concurrent.Volatile` |
| `synchronized(lock)`, `@Synchronized` | `kotlinx.atomicfu.locks.synchronized` with a `SynchronizedObject` |
| `java.util.UUID` | `kotlin.uuid.Uuid` (convert with `toJavaUuid()` at the Android BLE boundary) |
| `org.json.JSONObject`, `JSONArray`, `JSONObject.NULL`, `JSONObject.quote` | `com.eried.eucplanet.platform.JSONObject`, `JSONArray`, `JSONNull`, `jsonQuote` |
| `R.string.x`, `stringResource(R.string.x)` | `Res.string.x` with `com.eried.eucplanet.platform.stringResource` (see Strings) |
| `javax.inject` / Hilt | a Koin module |

`org.json` on Android is the framework's own class; on iOS it is a port of
the AOSP one, so key order, number text and escapes match (`JsonAospTest`).

`String.format` on iOS is a clone of the JVM formatter (`platform/JavaFormat.kt`).
`JavaFormatTest` compares it with `java.lang.String.format` on the JVM, and
`JavaFormatCommonTest` runs 1,950 JVM-printed cases on the iOS simulator.

## The check: Android-only APIs in commonMain

`CommonMainGuardTest` (in `shared/src/androidUnitTest`) runs in
`./gradlew test` on every host, including Windows and the Linux CI where iOS
can't compile. It fails when a file in `shared/src/commonMain`:

- imports `android.*`, `java.*`, `javax.*`, `dagger.*`, `okhttp3.*`,
  `org.json.*`, Google Play services, `androidx.activity/core/hilt/work`,
  `LocalContext`, `AndroidView`, Android resources (`R`,
  `androidx.compose.ui.res`) or `kotlin.jvm.Volatile`;
- calls `System.` (or `System::`), `Charsets.`, `Log.x(`, `.format(`, `@Volatile` or
  `synchronized(` without importing the facade (these compile on the JVM
  without an import, so they would slip through);
- uses a fully qualified `java.`/`javax.`/`android.` name, `R.string...`,
  `Thread` or `@Synchronized`.

The failure message names the file, the line and the fix. On a Mac, the iOS
compile is the final word:

```sh
sh gradlew :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test
```

## Building iOS

Mac only. `iosApp/iosApp.xcodeproj` runs
`:shared:embedAndSignAppleFrameworkForXcode` as its first build phase, so
opening it in Xcode and pressing Run is enough. From a terminal:

```sh
sh iosApp/run-simulator.sh     # build, install and launch on a simulator
SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:MASTER xcrun simctl launch booted com.eried.eucplanet.ios
                               # relaunch connected to a virtual wheel
sh iosApp/deploy-device.sh     # build, sign and install on a cabled iPhone
```

The device script uses the free Apple ID signing set up in Xcode
(Settings > Accounts). Free installs expire after 7 days; run it again.

### TestFlight

```sh
TEAM=<team id> ASC_KEY_ID=<key id> ASC_ISSUER_ID=<issuer id> sh iosApp/testflight.sh
```

archives a Release build and uploads it to App Store Connect. The version is
Android's `versionName` and the build number the commit count, so every
upload from a newer commit is accepted. It needs an App Store Connect API key
with Admin access, its `.p8` saved as
`~/.appstoreconnect/private_keys/AuthKey_<key id>.p8`; with it Xcode creates
the distribution certificate and profile itself, so nobody signs in to Xcode.
The app (bundle id `com.eried.eucplanet.ios`) has to exist in App Store
Connect first; the API cannot create it.
