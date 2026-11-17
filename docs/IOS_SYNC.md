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
| `app/` | Android only | Android-only features (Android Auto, watches, widgets, Flic), services, `Application`, manifest. |
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
| Log, clock, charsets, CRC, format, local time and time zone, the phone's short time and 12/24-hour axis labels, JSON, HTTP, SHA-256, gzip, raw and zlib deflate and inflate, dates, strings, parallel loops | `platform/*.kt` | `platform/*.android.kt` | `platform/*.ios.kt` |
| Settings file | `data/store/SettingsStore.kt` | `app/.../data/store/SettingsStoreAndroid.kt` (DataStore, imperial check) | `iosMain/.../app/IosAppGraph.kt` |
| Dropbox link | `DropboxRepository.beginLink` / `handleAuthCode` | `app/.../data/repository/DropboxLinkAndroid.kt` (Custom Tab, verifier in SharedPreferences) | `iosMain/.../app/IosDropboxLink.kt` (ASWebAuthenticationSession) |
| Ride Live Activity | `service/RideNotificationText.kt` (the text), `service/RideQuickActions.kt` (the buttons), `iosMain/.../app/RideActivity.kt` (when, and the presses) | `WheelService` notification | `iosApp/RideLiveActivity.swift` (ActivityKit), `iosApp/RideActionIntent.swift` (the presses), `iosApp/Widgets/` (the look) |
| Alarm rules for settings.json | `data/sync/DropboxSettingsSync.AlarmRuleStore` | `SyncManager` (AlarmDao) | `IosAppGraph.alarms` (a JSON file until Room moves) |
| Dropbox trips | `data/sync/DropboxTripSync.kt` (Sync all, the background pass, the pull, archiving), `TripFiles.kt` (`TripFileStore`), `TripCsvMeta.kt`, `DropboxTripHost`, `DropboxSyncUi` | `SyncManager` (backup folder hooks, WorkManager retries, the conflict dialog's flows), `DropboxSyncWorker`, `AndroidTripFiles.kt` | `iosMain/.../app/IosDropboxSync.kt` (in-app retries, Documents/themes), `IosTripFiles.kt` |
| Dropbox HTTP | `DropboxRepository(transport)`, `platform.HttpExecutor` | `HttpClient` (OkHttp) | `HttpClient` (NSURLSession); tests run against `commonTest/.../FakeDropbox.kt` |
| Leaderboard rider id and upload trigger | `data/sync/EucStatsSweep.kt`, `EucStatsSettingsPort` | `SyncManager` (rider id in the backup folder), `EucStatsUploadWorker` (WorkManager) | `iosMain/.../app/IosEucStats.kt` (Documents file; trip finish, launch, foreground), `IosBackgroundSync.kt` (while not open) |
| Crew pass camera and links | `crews/CrewsPairScreen.kt` (`CrewsPairHost`) | `app/.../crews/CrewsPairActivity.kt` (CameraX, intent filter) | `iosMain/.../app/IosCrews.kt`, `iosApp/QrScannerBridge.swift`, `AppDelegate` open URL |
| Leaderboard picture | `SettingsHost.rememberAvatarPicker` | `AndroidSettingsHost` (gallery, UserMarkerCropDialog) | `iosMain/.../app/IosAvatarPicker.kt` (UIImagePickerController) |
| Trip meter | `data/repository/TripMeterRepository.kt` (`TripMeterFixes`), `data/store/TripMeterStore.kt` (a DataStore) | `AndroidTripMeterFixes` (TripRepository, `Location.distanceBetween`), `TripMeterStoreAndroid.kt` | `iosMain/.../app/IosTripMeter.kt` (IosLocation, haversine, Documents/datastore) |
| Charging monitor | `ui/charging/` (`ChargingHost`, `ChargingCsvFiles`, `PhoneMotionEffect`), `service/ChargeAlertPolicy.kt` | `ChargingPortsAndroid.kt` (CreateDocument, ContentResolver), `PhoneMotion.android.kt` (SensorManager), `WheelService` charge alert notifications | `iosMain/.../app/IosCharging.kt` (share sheet), `PhoneMotion.ios.kt` (CoreMotion), `app/IosChargeAlerts.kt` (UNUserNotificationCenter) |
| Trip viewer map | `ui/recording/TripMapHtml.kt` (the page), `TripRouteMap.kt`, `TripDetailHost`, `map/MapLayersJs.kt` (the table, from hud-protocol's `MapLayers.kt` via `shared/build.gradle.kts`) | `AndroidTripDetailHost.kt` (WebView, offline tile intercept, fullscreen window) | `iosMain/.../app/IosTripDetail.kt` (WKWebView, the bundled Leaflet from `app/src/main/assets` via `generateIosAppArt`) |
| Legal Mode Lockdown | `data/repository/LegalLockdownController.kt`, `data/store/LegalLockdownStore.kt` (a DataStore), `ui/lockdown/` (`LegalLockdownHost`) | `LegalLockdownDataStore.kt` (the legal_lockdown file), `LegalLockdownHostAndroid.kt`, MainActivity swaps the graph | `iosMain/.../app/IosLockdown.kt` (Application Support, excluded from backup), `MainViewController.kt` swaps the screens |
| Service Mode and crash logs | `diagnostics/WheelDiagnosticsDialog.kt`, `WheelDiagnosticsViewModel.kt` (`DiagnosticsPlatform`), `DiagnosticsLogger.kt`, `CrashReport.kt` (names, header, retention) | `app/.../diagnostics/AndroidDiagnosticsPlatform.kt` (session lines, Wear, packages, the share intent), `util/CrashHandler.kt` (filesDir and Downloads) | `iosMain/.../app/IosDiagnostics.kt` (session lines, share sheet, `IosCrashLog` in Documents/crashes) |
| Trip wheel identity | `data/repository/WheelIdentity.kt` (`WheelIdentity`, `TripWheelLog`) | `TripRepository` | `iosMain/.../app/IosTrips.kt` |
| GPS power tiers | `data/model/GpsPowerPolicy.kt` (`GpsPowerPolicy`, `GpsIdleOff`) | `TripRepository` (fused priority and interval) | `iosMain/.../app/IosGpsPower.kt` (CoreLocation accuracy and distance filter), `iosApp/LocationBridge.swift` |
| Automations | `service/AutomationManager.kt` (`AutomationWheel`, `MediaAutomation`), `ui/settings/AutomationsContent.kt` | `di/ServiceModule.kt` builds it; `service/AndroidMediaAutomation.kt` (AudioManager volume, media keys) | `iosMain/.../app/IosAppGraph.automation` (no media half) |
| Ride session | `service/RideSession.kt` (`RideWheel`, `RideRecorder`, `RideVoice`) | `WheelService` (builds it, adds the notification, widget, engine sound, HUD, accel splits) | `iosMain/.../app/IosRideSession.kt` (adds the engine sound; the Live Activity follows the link itself) |
| HUD link (the phone side) | `service/hud/HudServer.kt` (search, dial, stream, reconnect), `HudLinkPorts.kt` (`HudSockets`, `HudMdns`, `HudBeacons`, `HudNetwork`, `HudTiles`, `HudPhone`; `HudBeacon.parse`, `HudTileHttp`), `HudSubnetProbe.kt`, `HudCommandSink.kt`, `HudDemoSource.kt`, `HudLinkLog.kt` | `app/.../service/hud/AndroidHudLink.kt` (OkHttp, JmDNS, ConnectivityManager), `HudUdpListener.kt` (the broadcast beacon), `HudTileServer.kt`, `di/HudModule.kt`; `WheelService` starts it | `iosMain/.../app/IosHudLink.kt` (URLSessionWebSocketTask, NSNetServiceBrowser, BSD sockets for the probe and the tile listener; no beacon, receiving a broadcast needs the multicast entitlement the app does not have); `IosAppStart` starts it; `Info.plist` carries `NSLocalNetworkUsageDescription` and `NSBonjourServices` |
| Theme editor | `ui/theme/ThemeEditorWidget.kt`, `ThemeTargetTool.kt`, `ThemeEditorSurface` (window size and one pixel) | `app/.../ui/theme/AndroidThemeEditorSurface.kt` (the view, PixelCopy); MainActivity mounts the widget | `iosMain/.../app/IosThemeEditor.kt` (a recorded root layer), `app/StatusBarHost.kt` (status bar style from the theme) |
| Rider actions on the phone | `flic/ActionExecutor.kt` (`MediaControls`, `PhoneControls`, `VoiceListening`) | `app/.../flic/AndroidMediaControls.kt`, `AndroidPhoneControls.kt` (volume, torch), `di/ServiceModule.kt` | `iosMain/.../app/IosDashboardPorts.kt` (`NoMedia`, `IosPhoneControls`: the torch) |
| Back | Compose `BackHandler`s through each `*Host.BackHandler` | `androidx.activity` back (MainActivity, NavGraph) | the left-edge swipe: `iosMain/.../app/IosBack.kt` (`IosBackHandler`, `IosBackSwipeBridge` for dialogs), one handler per screen in `MainViewController.kt` |
| Rear radar | `ble/radar/RadarPorts.kt` (`RadarLink`, `RadarScan`), `data/repository/RadarRepository.kt`, `ui/settings/RadarViewModel.kt` | `app/.../ble/radar/RadarConnectionManager.kt` (GATT, auto-reconnect), `RadarScanner.kt` (scan, Bluetooth state), `di/RadarModule.kt` | not yet (the muted radar row) |
| TPMS | `tpms/TpmsRadio.kt` (`TpmsRadio`, `TpmsAdvert`), `tpms/TpmsScanner.kt`, `TpmsRepository.kt`, `TpmsPairingStore.kt` | `app/.../tpms/AndroidTpmsRadio.kt` (BLE scan, filters, Bluetooth state), `di/TpmsModule.kt` | not yet (the muted TPMS row) |
| External GPS and data sources | `ble/gps/ExternalGpsPorts.kt` (`ExternalGpsLink`, `ExternalGpsScan`), `ble/gps/RaceBoxAdapter.kt`, `DragyAdapter.kt`, `VirtualExternalGps.kt` (the virtual RaceBox and `ExternalGpsLinkRouter`), `data/repository/ExternalGpsRepository.kt`, `ui/settings/ExternalGpsViewModel.kt`, `ExternalGpsSection.kt`, `ui/dashboard/sources/` (`DataSourcesSheet`, `DataSourcesViewModel`, `DataSourcesScript`) | `app/.../ble/gps/ExternalGpsConnectionManager.kt` (GATT, MTU, priority, auto-reconnect), `ExternalGpsScanner.kt` (scan, Bluetooth state), `di/ExternalGpsModule.kt` | `iosMain/.../ble/gps/IosExternalGpsLink.kt`, `IosExternalGpsScan.kt`, `IosAppGraph.externalGps`, `app/IosForeground.kt`, `app/IosLaunchHooks.kt` |
| GPS signal voice | `data/model/GpsSignalWatch.kt`, `RideSession.followGpsSignal` | `TripRepository` (fixes, tracking), `WheelService` | `iosMain/.../app/IosGpsPower.kt` (tracking), `IosLocation` (fixes), `IosRideSession` |
| Engine sound | `audio/EngineSoundEngine.kt` (settings, previews, start / stop), `EngineAudioOutput.kt` (`EngineAudioOutput`, `EngineProducer`), `EngineSynth.kt`, `EngineDynamics.kt`, `GrainBank.kt` | `app/.../audio/EngineAudioTrack.kt` (AudioTrack, producer thread, audio focus, the banks in `res/raw`), `di/ServiceModule.kt`, `WheelService` feeds it | `iosMain/.../app/IosEngineAudio.kt` (AVAudioEngine player node, producer coroutine, the session held with DUCK, the banks from `res/raw` via `generateIosGrainBanks`), `IosAppGraph.engineSound`, `IosRideSession` feeds it |
| Navigation | `nav/NavPorts.kt` (`NavHttp`, `OfflineNav`, `NavStrings`, `NavigationHost`, `NavVoice`), `nav/NavigationEngine.kt`, `nav/RoutingService.kt`, `ui/navigator/NavigationOverlay.kt`, `ui/navigator/MapHtml.kt` (the route builder page), `ui/settings/NavigatorSettingsContent.kt` (`SettingsHost.OfflineAreas`) | `app/.../nav/AndroidNavPorts.kt` (HttpURLConnection, tile store, offline map, connectivity), `AndroidNavigationHost.kt` (TripRepository, WheelService, the engine thread), `VoiceService`, `di/NavModule.kt` | `iosMain/.../app/IosNavigation.kt` (`IosNavigationHost` on IosLocation and the navigating tier in `IosGpsPower`, `IosNavStrings`, the dashboard overlay, the marker file), `IosVoice` (`NavVoice`), `IosAppGraph` (`PlatformNavHttp`, `IosOfflineNav`, the engine), `RideActivity` (the nav line, Stop navigation) |
| Offline router and offline areas | `:brouter` (BRouter in Kotlin Multiplatform, its files through `btools.util.BFiles`; `brouter/README.md`), `nav/offline/OfflineRouter.kt` (`BrouterProfiles`, `Engine`, `EngineStop`), `BrouterMapping.kt`, `TileIndex.kt`, `TileCheck.kt`, `MapCheck.kt`, `OfflineTileStore.kt` (`OfflineFiles`), `TileDownload.kt` (`TileHttp`, `TileJob`), `OfflineTiles.kt` (`TileJobs`), `map/OfflineMapDrawer.kt` (`TileCanvas`), `map/OfflineMapJs.kt`, `ui/settings/offline/` (`OfflineDataViewModel` and `OfflineDataPorts`, `OfflineDataDialog` and `OfflineDataHost`, `OfflineDataState`, `OfflineGridHtml`), `ui/settings/OfflineAreasRow.kt` | `app/.../nav/offline/AndroidBrouterProfiles.kt` (the profiles from the APK), `WorkManagerTileJobs.kt`, `TileDownloadWorker.kt` (`AndroidTileHttp`), `TileUpdateWorker.kt`, `map/OfflineMapRenderer.kt` (Canvas), `map/OfflineMapWeb.kt` (the WebView intercept), `ui/settings/offline/AndroidOfflineData.kt` (WebView, Formatter, ConnectivityManager), `di/NavModule.kt`; `JvmOfflineFiles` in `shared/src/androidMain` | `iosMain/.../app/IosOffline.kt` (`IosOfflineFiles`, Application Support excluded from backup, `IosBrouterProfiles` from the bundle via `generateIosAppArt`, `IosNetwork` on NWPathMonitor, `IosOfflineNav`), `IosTileHttp.kt` (NSURLSession data task), `IosTileJobs.kt` (the queue, started in `MainViewController`), `IosOfflineMap.kt` (Skia, the `eucoffline:` WKURLSchemeHandler, `IosOfflineDataHost`), `IosAppGraph` (the store, the jobs, the router, `offlineDataViewModel`) |
| Route builder and live share | `ui/navigator/RouteBuilderScreen.kt`, `RouteBuilderViewModel.kt`, `ShareDialog.kt`, `NavigatorHud.kt`, `RouteBuilderPorts`, `RouteBuilderHost` (`LocalRouteBuilderHost` for the dialogs), `share/ShareState.kt` (`LiveShare`) | `app/.../ui/navigator/AndroidRouteBuilderPorts.kt` (TripRepository, BatteryManager, tile store, ContentResolver and GpxIO, HttpURLConnection), `AndroidRouteBuilderHost.kt` (WebView and its `AndroidNav` bridge, pickers, marker crop, CameraX scanner, ZXing QR, Custom Tabs, clipboard, share sheet), `share/ShareSession.kt` (OkHttp WebSocket, `ShareCrypto` AES-GCM) | `iosMain/.../app/IosRouteBuilderPorts.kt` (IosLocation, UIDevice battery, no tiles, `util/GpxText.kt`, NSURLSession redirect walk), `IosRouteBuilderHost.kt` (WKWebView and its `AndroidNav` script handler, idle timer, `IosQrScanner`, `QrCodeCanvas`, UIPasteboard, share sheet, Safari), `IosRouteFiles.kt` (Files pickers, the route QR card), `IosAvatarPicker.openMarker`, `IosShareSession.kt` (NSURLSessionWebSocketTask, `share/ShareCipher.kt`); `MainViewController` screen `route` |
| Overlay studio | `ui/studio/` (`OverlayStudioScreen`, `OverlayStudioViewModel`, `StudioHost` and `LocalStudioHost`, `StudioRender` and `LocalStudioRender`, `StudioPorts.kt`, `StudioImages`, `StudioCanvasText`, `camera/StudioCameras.kt`, `recording/` GIF and APNG encoders, `OverlayFrameSpec`), `data/store/OverlayPresetJson.kt`, `OverlayPresets`; the layout types in hud-protocol's commonMain | `app/.../ui/studio/AndroidStudioHost.kt` (window, permissions, pickers, ExoPlayer, MediaCodec sink, MediaStore files, offscreen session, tiles and icon), `camera/StudioCameraHub.kt` (Camera2), `camera/StudioVideoHub.kt` (MediaMetadataRetriever), `recording/` (encoder, capture, offscreen, `StudioFramePixels`), `StudioPortsAndroid.kt`, `data/store/OverlayPresetStore.kt`, `StudioImages.android.kt`, `StudioCanvasText.android.kt` | `iosMain/.../app/IosStudioHost.kt` (window, rotation, permissions, tiles, icon), `IosStudioMedia.kt` (PHPicker, AVAssetImageGenerator, AVPlayer frames), `IosStudioExport.kt` (AVAssetWriter, PhotoKit, the GIF and APNG file sink), `IosStudioCapture.kt` (the microphone), `IosStudioCamera.kt` (AVCaptureSession and AVCaptureMultiCamSession feeds), `IosStudioPorts.kt` (Documents/overlays, `IosTrips`, Core Motion), `StudioImages.ios.kt` and `StudioCanvasText.ios.kt` (Skia); `MainViewController` screen `studio` |
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
- Plurals and string arrays work the same way: `Res.plurals.<key>` and
  `Res.array.<key>` with `platform.pluralStringResource` and
  `stringArrayResource`, read from `R.plurals` and `R.array` on Android.

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
                               # (on the simulator leaderboards go to a
                               # local eucstats at http://127.0.0.1:8000/api/v1,
                               # never the real one, however the app was
                               # started; SIMCTL_CHILD_EUCSTATS_API_BASE_URL
                               # names another)
SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:MASTER xcrun simctl launch booted com.eried.eucplanet.ios
                               # relaunch connected to a virtual wheel
SIMCTL_CHILD_EUC_DIALOG=service xcrun simctl launch booted com.eried.eucplanet.ios
                               # relaunch with a dashboard dialog open
                               # (service, about), for screenshots
SIMCTL_CHILD_EUC_VOICE_EVERY=15 xcrun simctl launch booted com.eried.eucplanet.ios
                               # the periodic report every 15 s, any speed
                               # and output, to hear it in the background
                               # (saved like any settings change)
SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:V14 SIMCTL_CHILD_EUC_ENGINE=V8_MUSCLE xcrun simctl launch booted com.eried.eucplanet.ios
                               # the motor sound on with that engine (an
                               # EngineProfile key), following the virtual
                               # wheel (saved like any settings change); the
                               # log says "scheduled N buffers, last rms"
SIMCTL_CHILD_EUC_SCREEN=route SIMCTL_CHILD_EUC_ROUTE="59.9139,10.7387;59.9170,10.7275" xcrun simctl launch booted com.eried.eucplanet.ios
                               # the route builder with those stops planned
                               # (lat,lon pairs, ";" between stops;
                               # SIMCTL_CHILD_EUC_ROUTE_MODE=CYCLING picks the
                               # travel mode, else the rider's default); add
                               # SIMCTL_CHILD_EUC_NAV=1 to start turn-by-turn
                               # once there is a fix (=dashboard goes back to
                               # the dashboard after, for the turn card), then
                               # move the phone with
                               # xcrun simctl location booted start --speed=6
                               # <lat,lon> <lat,lon> ...; the log shows each
                               # spoken cue as "Voice" / "NavigationEngine"
SIMCTL_CHILD_EUC_SCREEN_AFTER=20 SIMCTL_CHILD_EUC_SCREEN=route SIMCTL_CHILD_EUC_ROUTE="59.9139,10.7387;59.9170,10.7275" xcrun simctl launch booted com.eried.eucplanet.ios
                               # starts on the dashboard and opens EUC_SCREEN
                               # 20 s later, as a rider who plans a while after
                               # the start (the offline router's warm-up has
                               # run by then: "warm-up for wheel done")
SIMCTL_CHILD_EUC_SCREEN=settings SIMCTL_CHILD_EUC_SETTINGS_TAB=8 SIMCTL_CHILD_EUC_OFFLINE=areas xcrun simctl launch booted com.eried.eucplanet.ios
                               # the Offline areas screen; squares copied
                               # into the app's Library/Application Support/
                               # offline/routing (xcrun simctl
                               # get_app_container booted <id> data) count
                               # as downloaded
SIMCTL_CHILD_EUC_NO_NETWORK=1 xcrun simctl launch booted com.eried.eucplanet.ios
                               # the app acts as if it had no connection:
                               # its requests fail, its web views load
                               # nothing from the network (a content rule
                               # list) and the router sees no connection, so
                               # the offline map and offline routing show on
                               # the simulator, which shares the Mac's network
SIMCTL_CHILD_EUC_SCREEN=studio:100 SIMCTL_CHILD_EUC_STUDIO_PRESET=Race xcrun simctl launch booted com.eried.eucplanet.ios
                               # the Overlay Studio replaying trip 100
                               # (studio alone opens it live) with the Race
                               # starter loaded (or a saved preset of that
                               # name); SIMCTL_CHILD_EUC_STUDIO_FORMAT=GIF|APNG|MP4,
                               # EUC_STUDIO_PHOTO=PNG|JPG|WEBP and
                               # EUC_STUDIO_SCALE=50 pick the export, saved
                               # like the rider's pick, and
                               # EUC_STUDIO_CAPTURE=photo|clip|take takes a
                               # photo, exports the replay clip or records a
                               # five second take by itself;
                               # EUC_STUDIO_SHEET=add_element|manage_elements|
                               # layout|load_preset opens that sheet;
                               # EUC_STUDIO_VIEW=1 opens each saved item in
                               # the in-app viewer, as the View action does
SIMCTL_CHILD_EUC_SERVICE=1 SIMCTL_CHILD_EUC_SETTINGS_TAB=10 SIMCTL_CHILD_EUC_SCREEN=settings xcrun simctl launch booted com.eried.eucplanet.ios
                               # Service Mode on and Settings on GPS and
                               # sensors: the pairing card lists the virtual
                               # RaceBox
SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:V6 SIMCTL_CHILD_EUC_EXTGPS=VIRTUAL xcrun simctl launch booted com.eried.eucplanet.ios
                               # pairs and connects the virtual RaceBox, as
                               # its Pair button does (saved like any
                               # pairing; =none forgets it); with a wheel it
                               # rides at the wheel's speed less 3%
SIMCTL_CHILD_EUC_SOURCES=WHEEL:RACEBOX:calibrate=20 xcrun simctl launch booted com.eried.eucplanet.ios
                               # opens the dashboard's Data sources sheet on
                               # a tab (WHEEL, PHONE, RACEBOX) or a comparison
                               # (A:B); calibrate=20 asks "apply calibration?"
                               # 20 s later, apply=20 also presses Apply
SIMCTL_CHILD_EUC_GPS_SPEED=external xcrun simctl launch booted com.eried.eucplanet.ios
                               # Show speed on dashboard and Prioritize
                               # external GPS on (=phone: only the first),
                               # for the dial's external speed dot
SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:V6 SIMCTL_CHILD_EUC_HUD=127.0.0.1:28080 xcrun simctl launch booted com.eried.eucplanet.ios
                               # turns the HUD link on and dials that address
                               # (the manual address, saved like any setting;
                               # the port may be left out); =auto turns it on
                               # and lets it search, =off turns it off,
                               # =browse only logs the HUD Bonjour finds. For
                               # the HUD app on an emulator:
                               # adb forward tcp:28080 tcp:28080 (the link)
                               # and adb reverse tcp:28081 tcp:28081 (the
                               # HUD's map tiles from this phone)
SIMCTL_CHILD_EUC_SCREEN=route SIMCTL_CHILD_EUC_SHARE=scan SIMCTL_CHILD_EUC_SCAN_CODE="https://eucplanet.ried.no/share#<room>.<key>" xcrun simctl launch booted com.eried.eucplanet.ios
                               # opens the route builder on its scan window
                               # (Share > Scan); the simulator has no camera,
                               # so two seconds later the scanner reads
                               # EUC_SCAN_CODE as if shown that code: a share
                               # link opens the join form, a route link opens
                               # the route, a crew pass says so. The crew
                               # pass scanner reads it too
SIMCTL_CHILD_EUC_BG_SYNC=20 xcrun simctl launch booted com.eried.eucplanet.ios
                               # 20 s after launch, runs what a background
                               # task runs (one quiet Dropbox pass and one
                               # leaderboard sweep); the log shows
                               # "Background sync done: ..."
SIMCTL_CHILD_EUC_WEATHER_WIDGETS=59.91,10.75 xcrun simctl launch booted com.eried.eucplanet.ios
                               # fetches the forecast for that place and
                               # writes it for the weather widgets, as their
                               # refresh button does from where the phone
                               # is; the log shows
                               # "EUC_WEATHER_WIDGETS: written"
SIMCTL_CHILD_EUC_HEADLESS=20 SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:V6 xcrun simctl launch booted com.eried.eucplanet.ios
                               # starts as a Bluetooth relaunch does: the
                               # services with no screen for 20 s (the log
                               # says "Starting: BLUETOOTH_RELAUNCH"), a trip
                               # killed moments ago carried on ("Recording
                               # resumed after relaunch"), then the screen
                               # attaches ("Already started"); see Bluetooth
                               # relaunch below
sh iosApp/deploy-device.sh     # build, sign and install on a cabled iPhone
```

A simulator booted from a shell that had `SIMCTL_CHILD_*` variables set
keeps them in its launchd, and they then win over every later launch's own
(`xcrun simctl getenv booted EUC_SCREEN` shows them). Boot it again from a
clean environment (`xcrun simctl shutdown all`, then `env -i HOME=$HOME
PATH=/usr/bin:/bin xcrun simctl boot <udid>`). With the Mac's screen locked
the simulator draws no new frames for `simctl io screenshot`; an XCUITest
runner launching the app (`XCUIApplication(bundleIdentifier:)` with a
`launchEnvironment`) and `XCUIScreen.main.screenshot()` still work.

The device script uses the free Apple ID signing set up in Xcode
(Settings > Accounts). Free installs expire after 7 days; run it again.

### Bluetooth relaunch

iOS may end the app in the background to free memory during a ride. The
wheel, radar and GPS box centrals are restorable (`ble/BluetoothRestore.kt`),
so iOS keeps their links and relaunches the app, with no screen, when one of
them talks; `AppDelegate` then runs `IosAppStart.start` before any window, and
the window is made only when the rider opens the app. Keep everything a ride
needs in `IosAppStart`, never in a screen: a background relaunch has none.

On the simulator (no Bluetooth), `EUC_HEADLESS` checks the start without a
screen: record with a virtual wheel (`EUC_CONNECT=VIRTUAL:V6`, start a trip),
end the app with `xcrun simctl terminate booted com.eried.eucplanet.ios`, then
launch it with `EUC_HEADLESS=20` and the same `EUC_CONNECT` within the trip's
window (the auto-record idle stop, 1 to 10 minutes). The log shows the trip
carried on, one "Connected", and after 20 s the dashboard with the one
recording.

On a real iPhone, cabled to the Mac with Xcode, and the location grant set to
Always (Settings > EUC Planet > Location) so the GPS can restart in the
background:

1. Install a Debug build (Xcode Run, or `sh iosApp/deploy-device.sh`), connect
   the wheel and start a trip (or let auto-record start one).
2. Press the Home button or swipe up to the Home Screen. Do not swipe the app
   away in the app switcher: iOS never relaunches an app the rider closed
   there, by design.
3. End the app the way the system does, not the way the rider does. The
   sure way: in Xcode, Product > Scheme > Edit Scheme > Run > Environment
   Variables, add `EUC_KILL_IN_BACKGROUND` = `20`, run the app from Xcode,
   and 20 s after it goes to the background it ends itself with SIGKILL, as
   iOS ends an app for memory (the relaunch iOS makes later has no such
   variable, so it does not end again). Without Xcode: open a few heavy apps
   (camera, games) until iOS ends it. Xcode's Debug > Simulate Background
   Fetch is something else and does not test this. In Console.app (the
   iPhone picked, filtered by `EucPlanet`) the process ends.
4. Move the wheel (or touch its button) so it sends data. Within a few
   seconds the app is running again with no screen: in Console.app the log
   shows `Starting: BLUETOOTH_RELAUNCH`, `Restored by iOS`, `Picking up the
   restored wheel`, `Connected` and `Recording resumed after relaunch`, and the
   voice reports and alarms are heard again.
5. Open the app: the dashboard shows the wheel connected and the same trip
   still recording (one trip in the list, not two), with no welcome spoken.

A wheel switched off when the app was ended leaves only the waiting connect;
the relaunch then comes when the wheel is switched back on. Past the trip's
window (the idle stop, at most 10 minutes) that trip is finalized and
auto-record starts a new one.

### Sync while the app is not open

`IosBackgroundSync` is Android's periodic Dropbox and leaderboard workers.
It registers two background tasks at launch (their identifiers are in
`Info.plist`, `BGTaskSchedulerPermittedIdentifiers`) and asks for both each
time the app leaves the screen: an app refresh (about 30 s) and a processing
task (minutes, needs a connection, usually while the phone charges). Each
runs one quiet Dropbox pass and one leaderboard sweep and asks for the next.
iOS decides when, never sooner than the pending-upload interval, and not at
all after the rider swipes the app away or turns Background App Refresh off.
With a weather widget on a home screen a run also fetches the forecast, and
the tasks are asked for even with nothing to upload (then hourly at the
soonest).

The simulator schedules no background tasks (the log says "not scheduled"),
so `EUC_BG_SYNC=20` runs the same work there. On a real iPhone, cabled to
the Mac and run from Xcode: record a trip with no connection, send the app
to the Home Screen, turn the connection back on, pause in the debugger and
enter

```
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.eried.eucplanet.ios.sync.refresh"]
```

then continue: the log shows `Background sync (task ...)` and
`Background sync done`, and the trip is on Dropbox and the leaderboard
without the app having been opened.

### Home screen widgets

A widget is drawn by the widget extension, another process, so the app
writes what the widgets say and the extension only lays it out.
`IosWidgets` (Kotlin) builds it from the shared `WidgetContent` and the
rider's slots, writes `widgets.json` into the App Group container
(`group.com.eried.eucplanet.ios`, in both targets' `.entitlements`) and
calls `WidgetReloader` (Swift) to have WidgetKit draw again.
`iosApp/Widgets/WheelWidgets.swift` decodes the file; `IosWidgetsTest` pins
its field names, so rename a field in both.

iOS rations redraws for an app that is not on screen. A change of shape
(connection, light, lock, recording, a slot, a unit) is written at once, the
numbers alone every 5 minutes (`IosWidgets.REFRESH_MS`), and always when the
app leaves the screen. The timeline holds a second entry 20 minutes after
the numbers were read that shows the disconnected look, so a widget does not
keep old numbers when the app is ended mid-ride.

A press is a `RideActionIntent`, the Live Activity's: it runs in the app and
lands in `RideActivity.press`. That needs iOS 17; on 16 the wheel widget
shows its readings only and the button widgets are not offered.

The gallery names come from the file, so they are in the app's language
once the app has run, and in English before that.

The weather widgets have their own file, `weather.json`, written by
`IosWeatherWidgets` and decoded by `iosApp/Widgets/WeatherWidgets.swift`
(`IosWeatherWidgetsTest` pins its field names). Android redraws them from an
hourly worker; iOS grants no such thing, so the file carries every forecast
step ahead, already scored, with its face, temperature and wind as the
shared `WeatherWidgetContent` words them. The timeline has one entry per
step, each from half a step before its time (the panel's "now"), 48 at
most, and is asked for again when they are used up; when the forecast itself
runs out the widgets say there is none. So a widget follows the hours with
the app closed for as long as the forecast reaches, up to 8 days.

A new forecast is written whenever the dashboard has one. With a weather
widget placed (`WidgetCenter.getCurrentConfigurations`), the app also
fetches when it leaves the screen, in a background task, and from the
widget's refresh button, a `RideActionIntent` with the key
`WEATHER_REFRESH` (iOS 17). It asks about where the phone is or was last,
else the place of the last file: a widget does not turn the GPS on. The
small widget shows the forecast's age in place of the temperature from 3
hours on, the same rule as Android. A tap opens `eucplanet://weather`, which
`AppDelegate` hands to `IosWeatherWidgets.handleUrl` and opens the weather
panel.

The App Group is an identifier in the Apple developer account. A signed
build needs it there; the simulator does not. Without it the container is
missing: the app logs "No App Group container" and the widgets stay empty.

The simulator cannot be long-pressed, so the widgets are put on its home
screen by its layout file, with the simulator shut down:
`~/Library/Developer/CoreSimulator/Devices/<UDID>/data/Library/SpringBoard/IconState.plist`.
An entry is `elementType = widget`, `bundleIdentifier =
com.eried.eucplanet.ios.widgets`, `containerBundleIdentifier =
com.eried.eucplanet.ios`, `widgetIdentifier` = the kind (`EucWheel`,
`EucAction1`, `EucAction2`, `EucAction3`, `EucAction12`, `EucAction23`,
`EucWeatherScore`, `EucWeatherForecast`), `gridSize` = `small`, `medium`
or `large`, and two fresh UUIDs, as the stock widgets
in that file have. Put them on the first page, with the app's icon on the
same page: nothing can swipe, a boot opens the first page, and leaving the
app (`xcrun simctl launch booted com.apple.springboard`) lands on the page
its icon is on. A widget is a grey placeholder for some seconds after a
boot. Keep a copy of the file to put back.

The weather widgets get a forecast from `EUC_WEATHER_WIDGETS=<lat>,<lon>`
(or from the dashboard, with a simulated location). To see an old forecast,
lower `fetchedAt` in `weather.json` (in
`.../data/Containers/Shared/AppGroup/<id>/`) and launch the app with the
location permission revoked, which redraws the widgets without fetching;
with the file deleted the same launch shows the empty look.

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

## Duplication sweep (2026-11-10)

Logic written twice, once in `:app` and once in `iosMain/.../app/Ios*.kt`,
and what became of it. Only rules both phones apply were moved; platform
plumbing (notification posting, audio routes, background scheduling) stays
with each platform.

| Duplicate | Outcome |
|---|---|
| Light and lock automations (`AutomationManager`, Android only; iOS answered "switched off") | Moved to `service/AutomationManager.kt` with `HeadlightRules`, `HeadlightSlowPolicy`, `ApplyWhenGate`, `ProximityLockEvaluator`, `SunCalculator`. The media half stays Android's (`AndroidMediaAutomation`). |
| What a connect and a disconnect do (WheelService's connection collector; iOS had a partial copy for the automations) | Moved to `RideSession.followConnection`: suspensions cleared, connection announcements, auto-record on connect, the automation resets, the voice load window cleared. Each platform passes only its own extras. The iPhone gains the connect / disconnect announcements its settings already offered. |
| Per-frame work (WheelService's telemetry collector; iOS spread over IosVoice, IosTrips, IosChargeAlerts and the automation runner) | Moved to `RideSession.onFrame`: the voice load window, the automations, the lights announcement, auto-record on motion, the charge alerts. The iPhone gains the lights announcement. |
| Auto-record start and idle stop (WheelService, IosTrips.followWheel) | Moved to `RideSession`. The iPhone had drifted: no start on connect when not gated on motion, and the idle stop only ran on telemetry frames, so a disconnected wheel never stopped its trip. Both fixed by sharing. |
| Periodic voice reports loop (WheelService.startVoiceLoop, IosVoice.initialize) | Moved to `RideSession.startVoiceLoop`; the platforms supply the report and the external-audio check (`RideVoice`). |
| Charge alert decision and text (WheelService.checkChargeAlerts, IosChargeAlerts) | Moved to `RideSession`; the platforms only post the title and text (`ChargeAlertNotification`, UNUserNotificationCenter). |
| Stop all resets (WheelService.onDestroy, IosDashboardPlatform.stopAllAndExit) | Both call `RideSession.resetAutomations()`. The rest of Stop all is platform teardown. |
| Ride notification text and buttons | Already shared (`RideNotificationText`, `RideQuickActions`); left. |
| Quick-action presses (WheelService intents, RideActivity.press) | Left: iOS goes through the shared `ActionExecutor`, Android's intents call the same repository methods; a few lines each, no rule of their own. |
| Lockdown stops a recording (TripRepository, IosTrips.followWheel) | Left: three lines each over the shared `LegalLockdownController`; moving it would need the whole trip recorder shared. |
| Dropbox pass triggers (SyncManager / WorkManager, IosDropboxSync) | Left: the pass itself is shared (`DropboxTripSync`); only the scheduling differs (WorkManager against in-process retries, and `IosBackgroundSync` on BGTaskScheduler while the app is not open). |
| Leaderboard upload trigger (EucStatsUploadWorker, IosEucStats) | Left: the sweep is shared (`EucStatsSweep`); only the trigger differs (`IosBackgroundSync` while the app is not open). |
| Keep screen on (MainActivity, IosDisplayPrefs) | Left: a settings flow into a platform flag, no rule to share. |
| GPS acquired / lost announcements | Moved to `data/model/GpsSignalWatch.kt` (the state machine TripRepository ran) and `RideSession.followGpsSignal` (WheelService's collector). The iPhone feeds it from `IosLocation` and `IosGpsPower`, so it now speaks them too. |

## How to keep iOS paired

For every change:

1. **Put the rule in commonMain.** A decision (when, whether, what text)
   goes in `shared/src/commonMain`, usually `service/` or `data/`. A
   platform file should only carry out an answer. If you are about to write
   the same `if` in `WheelService` and in an `Ios*.kt`, it belongs in
   `RideSession`, `AutomationManager` or a new shared class instead.
2. **Reach the platform through a small port.** An interface beside the
   shared class (`AutomationWheel`, `MediaAutomation`, `RideVoice`,
   `RideRecorder`, `*Host`, `*Ports.kt`), implemented in `:app` and in
   `iosMain/.../app/Ios*.kt`. Keep it to what differs.
3. **Test it in commonTest.** Tests there run on the JVM (`gradlew test`) and
   on the iOS simulator (`:shared:iosSimulatorArm64Test`), so the rule is
   proven on both. Test names must be legal on Kotlin/Native (no commas,
   dots or colons inside backticks). `RideSessionTest` and
   `AutomationManagerTest` guard the ride session; `CommonMainGuardTest`
   keeps Android APIs out of commonMain; `KoinGraphTest` checks Android's
   wiring; `SettingsSearchHiddenRowsTest` checks what each phone shows.
4. **A feature iOS cannot do** is a `SettingsRow` the iOS host lists in
   `hiddenRows` (or a host flag), and a line in `docs/IOS_PARITY.md`, never a
   dead switch.
5. **New UI strings** go in `app/src/main/res/values*/strings.xml` for every
   locale; shared code names them as `Res.string.x`.
6. **Give every window a way out.** The iPhone has no back button: a new
   dialog needs Cancel or Close, or the shared X (`ui/common/CloseIconButton.kt`);
   a new iOS screen in `MainViewController.kt` registers `IosBackHandler`
   with its back arrow's target.
7. **Run both apps.** The gate plus the Xcode build, then look at the
   changed screens on the Android emulator and the iOS simulator (a virtual
   wheel: `SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:<id>`).
8. **Update `docs/IOS_PARITY.md`** in the same commit, and the seam table
   above when a new port appears.
9. **Merging next-experimental into feature/ios**: after the merge, grep the
   incoming `:app` changes to `WheelService`, `service/`, `data/repository`
   and `voice/` for rules that now live in shared code (`RideSession`,
   `AutomationManager`); port the change there rather than restoring the
   Android copy, then run the gate and both apps.
