# Deleted lines accounting (feature/ios into next-experimental)

Compared with `git diff -M origin/next-experimental 5c8c255b0` (5c8c255b0 was the feature/ios tip before next-experimental's last four commits were merged in, 7aba4d34a): 387 files lose lines, 21,612 lines in all. Almost all of it is the move of Android code into the Kotlin Multiplatform module `shared/` so the iPhone app can run it. 110 files moved as is, with only the package, the imports and `R.string` to `Res.string` changing. About 145 moved and had their Android calls swapped for a host, a port or an `expect`/`actual`, while the Android half stays in `app/` (for example `AndroidSettingsHost`, `SettingsPortsAndroid`, `AndroidDashboardHost`, `AndroidStudioHost`, `AndroidHudLink`, `SafBackupFolder`). Large Android classes (SyncManager, TripRepository, WheelService, VoiceService, FlicManager, CrewsPairActivity) shrank because their rules moved to shared classes, which the Android class now calls. Nothing in this diff removes Android Auto: next-experimental already had that removal (b00ef52ad). `app/src/main/res/values*/strings.xml` lose no lines: `shared` generates its compose strings from them at build time (`shared/compose-strings.gradle.kts`). 

## Could not trace

None. Every removed non-comment line has a new home or a stated reason. One merge item and a few small behaviour differences follow; none of them is a lost Android feature.

- Merge item, resolved: the next-experimental crash fix d64623300 (5-minute load window capped at 20,000 samples, `addToWindow`) lives in shared `service/VoiceReports.kt` on feature/ios, with `BoundedWindow.kt` and its test in shared commonMain and commonTest (merge 7aba4d34a). Android and the iPhone both get it.
- Merge items, resolved: next-experimental's crash sweep and crew pass text (6f2a397eb, 0fc53f13f, 71dbf0757, merged as 416219df2) came into feature/ios in the second merge. Where the upstream change landed on code that now lives in `shared/`, it was ported there:
  - `CsvWriter`: `writeRow` returns false on a failed write and closes the file, `close` never throws, one lock for both. Shared, so the upstream `File`/`java.io.Writer` constructor became the existing `TextSink`; Android's `FileTextSink` is unchanged. `CsvWriterFullStorageTest` moved from `app/src/test` to `shared/src/commonTest` with the same two cases, its fake disk now a `TextSink`.
  - DataStore corruption handler: the shared stores no longer create their files, so `ReplaceFileCorruptionHandler { emptyPreferences() }` went on Android's `SettingsStoreAndroid.kt`, `LegalLockdownDataStore.kt` and `TripMeterStoreAndroid.kt`, and on the three iPhone `createWithPath` calls.
  - `addToWindow` in `WheelRepository` and `NavigationEngine` (shared): upstream body kept, plus the import.
  - `ScanViewModel.onCleared` (shared): the Nearby devices check uses `ScanPlatform.mayConnect()`, which on Android is the same `BLUETOOTH_CONNECT` check as the new `canConnectBluetooth()`.
  - Crew pass screen: the request title, server line, `Access:` scope line and `scopeLabel` went into shared `crews/CrewsPairScreen.kt`, the screen Android's `CrewsPairActivity` already hosts.
  - `TripRepository` (Android) takes `AppHealthRepository` and stops on a refused row, as upstream. The storage warning uses `Res.string`. The iPhone recorder (`IosTrips`) does the same, with `IosAppHealth.noteStorageFull`, and its file sink now uses the `NSError` form of `writeData`, since the plain one raises an Objective-C exception on a full disk that Kotlin cannot catch.
- `TileDownloadWorker` now reports progress with `setProgressAsync` instead of the suspending `setProgress`, so the call no longer waits.
- Dropbox retry waits use `delay` instead of `Thread.sleep`, and the 403 log line builds its path from the URL string. Only the log text changes.
- The `HudTileServerTest` comment named a `HudTileHttpTest` that does not exist; fixed in 7aba4d34a to point at `HudLinkRulesTest`, where the cases moved.
- `NavigatorSettingsContent` can now hide the offline routing row when the host lists it as hidden. Android hides no rows (`AndroidSettingsHost.hiddenRows = emptySet()`).

## Not PR deletions: next-experimental commits feature/ios lacks

These lines show as deletions only because the two-dot diff runs backwards over four next-experimental commits (d64623300, 0a1ad30f9 and their two merges). The three-dot diff, which is what the PR shows, does not list them. Merge 7aba4d34a brought them into feature/ios, so the PR no longer deletes them.

- `app/src/main/java/service/BoundedWindow.kt` (-24) and `app/src/test/java/service/BoundedWindowTest.kt` (-35): d64623300. Merge 7aba4d34a moved them to `shared/src/commonMain/.../service/BoundedWindow.kt` and `shared/src/commonTest/.../service/BoundedWindowTest.kt`.
- `app/src/main/java/service/WheelService.kt` (22 of its 375): d64623300 (`inForeground`, `lastStartId`, `stopSelfResult`, promote in `onStartCommand`). Present after 7aba4d34a.
- `app/src/main/java/service/VoiceService.kt` (4 of its 303): d64623300 (`LOAD_HISTORY_MAX_SAMPLES`, `addToWindow`). See the merge item above.
- `app/src/main/AndroidManifest.xml` (-6) and `app/build.gradle.kts` (`versionCode = 286`): 0a1ad30f9 (Wi-Fi feature not required).
- `BRANCH.md` (-16): next-experimental's tester notes, replaced by feature/ios's own. Merge 7aba4d34a keeps both: next-experimental's text plus the iPhone lines.

## Removed with a reason (build and docs)

- `app/build.gradle.kts` (-18): `ksp`, the Room compiler and `room.schemaLocation` moved to `shared/build.gradle.kts` (Room database moved to shared, 6561d4993, same `app/schemas` path). `project(":brouter")` is now an `api` dependency of `:shared`. The settings search index task and its source dir moved with `settings-search-index.gradle.kts` to `:shared`.
- `app/settings-search-index.gradle.kts` -> `shared/settings-search-index.gradle.kts` (-24): the same generator, now scanning shared sources and emitting `Res.string` keys.
- `gradle/libs.versions.toml` (-12): version bumps (AGP 8.10.1 to 8.13.2, Kotlin 2.3.21 to 2.4.21, KSP 2.3.7 to 2.3.12, Compose BOM 2025.04.01 to 2026.06.00, Room 2.7.2 to 2.8.4; Koin unchanged). Comments explaining the old pins went with them (90bcff1ac).
- `gradle.properties` (-4): the `android.experimental.lint.version=8.13.2` override is no longer needed, because AGP is now 8.13.
- `gradle/wrapper/gradle-wrapper.properties` (-1): Gradle 8.11.1 to 8.14.4.
- `wear/build.gradle.kts` (-1), `hud/build.gradle.kts` (-1): a blank line each.
- `docs/WEAR_OS_MAP.md` (-2): paths updated for hud-protocol's move to `androidMain`.
- brouter/build.gradle.kts (-8): `java-library` (Java 11) replaced by Kotlin Multiplatform (jvm 17, iOS), reference tests wired; `test` still runs jvmTest.
- brouter/README.md (-10): rewritten for the Kotlin port; the old "local changes" bullets are kept in the new list.
- hud-protocol/build.gradle.kts (-16): Android library became Kotlin Multiplatform (androidTarget plus iOS); same deps and JVM 17.

## Android classes that shrank or split (logic moved to shared, Android keeps the platform part)

### Ride service and automations

- `app/src/main/java/service/AutomationManager.kt` (-585, deleted): logic moved to `shared/.../service/AutomationManager.kt` (lights, beep volume, proximity lock, suspensions) and `shared/.../service/VolumeCurve.kt`; the AudioManager volume and media-key parts stay Android in `app/.../service/AndroidMediaAutomation.kt` (`evaluateVolume`, `evaluateMediaControl`, `restoreBaselineVolume`); wired in `di/ServiceModule.kt`
- `app/src/main/java/service/WheelService.kt` (-375; 22 of these are upstream, see above): the ride rules moved to `shared/.../service/RideSession.kt` (`onFrame`, `followConnection`, `followGpsSignal`, `startVoiceLoop`, `startAutoRecordIdleLoop`, `checkLightTransition`, `handleAccelSplits`, `checkChargeAlerts`, `resetAutomations`); notification button list to `RideQuickActions.resolve`, notification text to `RideNotificationText`, widget metric rows to `widget/WidgetContent.metrics`; the service keeps the foreground, notification and widget posting

### Sync, trips, stores, crash log, locale, eucstats

- `data/sync/SyncManager.kt` (-1666): the folder passes (runSync, runSyncPass, uploadCsv, downloadCsv, backups, rider id file, restore, listSettingsBackups, writtenAt, snapshotBeforeRestore, freeArchiveName, removeRenamedTripCopies, listFolderTripSizes, themes/overlays subdir sync via planSubdir) went to cM `data/sync/BackupFolderSync.kt`. The Dropbox trip passes (runDropboxSync, downloadMissingTrips, followDropboxArchive, archiveTripFile(s), markDropboxSynced, backoff constants, REFUSED_RETRY_SECONDS, WATCHDOG_ATTEMPT) went to cM `data/sync/DropboxTripSync.kt`. The settings.json 3-way push and apply (pushDropboxSettingsIfOnlyPhoneChanged, readRemoteSettings, portableSettingsBytes, sha256Hex) went to cM `data/sync/DropboxSettingsSync.kt`. parseCsvMeta went to cM `data/sync/TripCsvMeta.kt` (`TripCsvMeta.read`). The types went to cM `data/sync/BackupTypes.kt` and `RiderFileResult.kt`. DocumentFile and DocumentsContract calls (findFile, createFile, moveDocument, deleteDocument, the child-listing cursor query) went to the new `data/sync/SafBackupFolder.kt` (BackupFolder over SAF, with the same `canWrite()` check, and the try/catch is kept in `BackupFolderSync.folder`). File I/O went to `data/sync/AndroidTripFiles.kt`. SyncManager keeps the persistable-URI grant and release, `applyRestoredLanguage` (passed in as `applyLanguage`), every WorkManager enqueue, cancel and periodic call (those lines are unchanged), and thin delegates.
- `data/sync/DropboxSyncWorker.kt` (-245): the body of `pass()` moved, step for step, into cM `DropboxTripSync.backgroundPass` (not-linked reset, 403 refusal backoff, needsUpload with the 3-way hash check, the pending counters, the settings push, the themes/overlays mirror through `host.mirrorFoldersInBackground` -> `BackupFolderSync.mirrorBackupSubdirsMissingOnDropbox`, the download budget DOWNLOAD_BUDGET_MS, retry scheduling). The worker maps the `BackgroundOutcome` to `Result.retry/success`. Retry scheduling still goes through `SyncManager.scheduleDropboxSyncAttempt` (the host's `scheduleRetry`).
- `data/sync/TripUploadWorker.kt` (-77): the folder upload loop (pending plus reconcile, removeRenamedTripCopies, conflict recount, markUploaded or setUploadStatus(3)) moved to cM `BackupFolderSync.uploadPass` (which runs inside withUploadPass). The worker keeps its retry scheduling.
- `data/sync/EucStatsUploadWorker.kt` (-56): the sweep (gates, refreshHeldTrips, the per-trip outcomes and their logs, endsPass) and `workerResultRetry` moved to cM `data/sync/EucStatsSweep.kt` (`sweepEucStatsUploads`). The worker keeps the WorkManager retry.
- `data/repository/TripRepository.kt` (-564): GPS acquired/lost tracking went to cM `data/model/GpsSignalWatch.kt`. The idle-off timer went to `GpsIdleOff` in cM `data/model/GpsPowerPolicy.kt`. Killed-recording recovery (finalizedTripOrNull, parseTripQuads) went to cM `data/repository/TripRecovery.kt` (`recoverKilledRecordings`). Rename, split, combine, save section, change wheel, knownWheels and the wheel meta backfill went to cM `data/repository/TripTools.kt` (with `resync` passed in, so edited trips still re-upload). The wheel identity and the Extra-column queue (pendingExtras, `wheel.disconnected=1`) went to `TripWheelLog` in cM `data/repository/WheelIdentity.kt`. The external GPS speed choice went to `externalSpeedForTrip` in cM `ExternalGpsRepository.kt`. The willEucstats check became `eucStatsUploadsOn` in cM `EucStatsSweep.kt`. The File-based TripDerive writers went to `data/repository/TripDeriveFiles.kt` (app). Location sampling and the GPS tiers stay in the app.
- `data/repository/DropboxRepository.kt` -> `shared/.../data/repository/DropboxRepository.kt` (-185): OkHttp calls were swapped for the `HttpClient`/`HttpRequest` facade (the 403 interceptor became the `onResponse` hook, and `Thread.sleep` became `delay`). SharedPreferences "dropbox_link" moved behind `DropboxLinkScratch` -> `SharedPrefsDropboxLinkScratch`. The Custom Tab launch and the `Uri` code parse went to `data/repository/DropboxLinkAndroid.kt` (beginLink/handleAuthCode). OffsetDateTime parsing became `isoEpochSeconds`. These are wired in `di/DataModule.kt`.
- `data/store/ThemeStore.kt` (-84): the DocumentFile list, save and load moved to `FolderThemes` in cM `data/sync/BackupFolderStores.kt` (same `put("name")`, same isLight floor, same sanitize). ThemeStore implements `ThemeFiles`.
- `data/store/OverlayPresetStore.kt` (-73): list, save, load and delete moved to `FolderPresets` in the same cM `BackupFolderStores.kt`. The draft and bundled presets stay in the app, which implements `OverlayPresets`.
- `util/CrashHandler.kt` (-33): the file naming, the header layout, MAX_FILES=20 retention and the "EUCPlanet-crashlog-" Downloads name moved to cM `diagnostics/CrashReport.kt`. The Android header values and `writeToDownloads` stay.
- `data/repository/PhoneSensorRepository.kt` (-30): `PhoneImuSample` moved to cM `data/repository/PhoneImuSample.kt`. The class now implements `StudioImu` (cM `ui/studio/StudioPorts.kt`), so only `override` modifiers changed.
- `data/eucstats/EucStatsApi.kt` -> `shared/.../data/eucstats/EucStatsApi.kt` (-113): RiderCard, RiderProfile, TripStatus, UploadResult and RegisterResult moved to cM `data/eucstats/EucStatsTypes.kt`. OkHttp and Multipart were swapped for HttpRequest. `di/EucStatsModule.kt` now passes `HttpClient()` instead of the Koin `OkHttpClient` single (no timeouts were set before or after).
- `data/eucstats/EucStatsRepository.kt` -> `shared/.../data/eucstats/EucStatsRepository.kt` (-25): `SyncResult` moved to `EucStatsTypes.kt`. `UUID` became `kotlin.uuid.Uuid`. `TimeZone` became `localTimeZoneId`/`localTimeZoneOffsetMs`. The hardcoded "google_play" is now a `platform` constructor parameter (same default). gzip became expect/actual `platform/Gzip.kt` (Android actual in `Gzip.android.kt`).
- `data/sync/FileSyncPolicy.kt` (-48, deleted): FileSyncPlan, FileSyncPolicy and DropboxContentHash now live in cM `data/sync/FileSyncPolicy.kt` (git shows an add, under 50% similar, because the SHA-256 became common code).
- `data/eucstats/Attestation.kt` -> `PlayIntegrityAttestation.kt` (-14): AttestationToken, the Attestation interface and StubAttestation moved to cM `data/eucstats/Attestation.kt`. The Play Integrity implementation stays in the app.
- `data/repository/AppHealthRepository.kt` (-49): WarningTextRes and AppWarning moved to cM `data/repository/AppWarning.kt` (now StringResource). The other lines are `R.string` -> `Res.string` swaps. The warning checks (mic, notifications, Bluetooth, location services and permission, PiP, HUD overlay, battery optimisation, no backup) are all still in the file.
- `util/LocaleHelper.kt` (-86): the SUPPORTED list (all 31 languages), BRITISH_REGIONS and normalizeToSupportedTag moved verbatim to cM `util/AppLanguages.kt`. LocaleHelper delegates. Applying the per-app locale stays in LocaleHelper.

### Offline maps, navigation, live share, HUD, ENGO, BRouter

- app/.../nav/offline/TileDownloadWorker.kt (-161): the resume, Range/If-Range, 416, short-file, check, rename and lookups-lock logic went to shared `nav/offline/TileDownload.kt` (`TileDownload.run`, `fetch`, `finish`, `validator`, `resumeDate`, `MAX_ATTEMPTS`, `LOOKUPS_MAX_AGE_MS`); the worker keeps WorkManager and an `AndroidTileHttp` (HttpURLConnection, same User-Agent). Only change: progress is posted with `setProgressAsync` instead of the suspending `setProgress`.
- app/.../nav/offline/OfflineTiles.kt (-171, deleted): server list and queueing to shared `nav/offline/OfflineTiles.kt` (`remote`, `read` with the same 15 s/30 s timeouts and User-Agent, `download`, `cancel`, `outdated`, `autoUpdate`); the WorkManager requests (CONNECTED/UNMETERED, storage not low, exponential 30 s backoff, unique KEEP, weekly UNMETERED periodic) moved verbatim to app `nav/offline/WorkManagerTileJobs.kt`.
- app/.../nav/offline/TileUpdateWorker.kt (-11): the wifiAutoUpdate, empty-store and outdated checks went to shared `OfflineTiles.autoUpdate()`; the worker calls it.
- app/.../nav/offline/OfflineTileStore.kt (-53, deleted): to shared `nav/offline/OfflineTileStore.kt` over `OfflineFiles` (`JvmOfflineFiles` on Android, files only as before); `default(ctx)` (external files dir or internal, `offline/routing`) became `offlineTileStore()` in app `di/NavModule.kt`.
- app/.../nav/offline/MapCheck.kt (-21, deleted): to shared `nav/offline/MapCheck.kt` (`problem`), wired as `TileDownload`'s map check.
- app/.../nav/offline/map/OfflineMapRenderer.kt (-254): tile drawing (48-tile decode LRU, open squares, land, water, buildings, z16 edges, road casings, label collision) moved to shared `nav/offline/map/OfflineMapDrawer.kt`; Android keeps `AndroidTileCanvas` (same Paint, Path, PathMeasure, DashPathEffect calls). Spot-checked the label placement and paint values: same.
- app/.../nav/offline/map/OfflineMapWeb.kt (-108): the Leaflet layer script moved to shared `OfflineMapJs.js(tileUrl, prefer)`; diffed old and new script: identical except the tile URL is a parameter (Android passes the same `https://$HOST/...`). `AndroidNav.onOfflineMap` bridge call kept.
- app/.../nav/NavFormat.kt (-70, deleted): to shared `nav/NavFormat.kt` with a string lookup; Android callers use app `nav/NavFormatAndroid.kt` extensions. `etaLine`'s clock takes a `clock` lambda; Android passes `DateFormat.getTimeFormat` through `AndroidHudLink.shortTime`, its only caller (HudServer).
- shared nav/NavigationEngine.kt (-119, moved): Intent to WheelService `ACTION_START_NAVIGATION` moved to app `nav/AndroidNavigationHost.kt`; `context.getString` texts go through `NavStrings`.
- shared nav/RoutingService.kt (-49), nav/PoiService.kt (-36), nav/OcmService.kt (-28), moved: HttpURLConnection GET/POST went to app `nav/AndroidNavPorts.kt` (`AndroidNavHttp`, same method, timeouts and headers, constants unchanged in shared); offline routing calls go through `OfflineNav` (`AndroidOfflineNav`). Commit d4c8b7d8a.
- shared nav/offline/OfflineRouter.kt (-61, moved): `runInterruptible(Dispatchers.IO)` became `runBlockingIo` (androidMain actual is the same `runInterruptible`); asset copy of profiles went to app `nav/offline/AndroidBrouterProfiles.kt`. Commit e39661c61.
- shared nav/offline/map/PmTiles.kt (-27, moved): File/RandomAccessFile and GZIPInputStream replaced by `RandomReadFile` and the shared `gunzip`; LinkedHashMap LRU became `LruMap(64)`. Commit d4c8b7d8a.
- shared nav/offline/TileIndex.kt (-19, moved): SimpleDateFormat Europe/Berlin became `LocalDateParserUs(..., timeZoneId = "Europe/Berlin")`.
- shared ui/navigator/RouteBuilderScreen.kt (-534, moved): WebView, `NavJsBridge` ("AndroidNav"), GPX CreateDocument launcher, marker photo crop, ACTION_VIEW and custom tabs went to app `ui/navigator/AndroidRouteBuilderHost.kt`; landscape check to shared androidMain `ScreenGeometry.android.kt`; bearing to shared `platform/GeoDistance.kt`. Spot-checked.
- shared ui/navigator/RouteBuilderViewModel.kt (-143, moved): `resolveShortlink` (manual redirect walk, 4 s timeouts) moved to app `ui/navigator/AndroidRouteBuilderPorts.kt`; tile has/served checks to the same ports (`hasTile`, `servedTiles`). Spot-checked.
- shared ui/navigator/ShareDialog.kt (-396, moved): clipboard (with the pre-Tiramisu notice), share intents, chooser, `RouteQrCard.share`, custom-tab open went to `AndroidRouteBuilderHost`; QR scanner stays app `ShareQrScanner.kt` (`QrScannerArea`) via the host. Spot-checked.
- shared ui/settings/offline/OfflineDataDialog.kt (-97, moved): WebView grid, `AndroidOffline` JS bridge, offline tile intercept and `evaluateJavascript` went to app `ui/settings/offline/AndroidOfflineData.kt`; the update-check notices stay a Compose snackbar in shared, now with compose resources.
- shared ui/settings/NavigatorSettingsContent.kt (-216, moved): the offline areas row (size via `Formatter`, opens the dialog) went to shared `OfflineAreasRow` through `SettingsHost.OfflineAreas()` (Android formats with `Formatter` in `AndroidOfflineData`); the whole weather block went to shared `WeatherSettingsContent`, still drawn by this section (commit 0be19ae8c). New: the offline row hides when `SettingsRow.OFFLINE_ROUTING` is in the host's hidden rows (iPhone only).
- shared service/hud/HudServer.kt (-349, moved): network callbacks (onLost, onLosing), mDNS resolve, peer TCP probe, OkHttp websocket, `isUiForeground` (process importance), HudDebug reads, map-linked flag and app sender reset went to app `service/hud/AndroidHudLink.kt` behind `HudLinkPorts` (`uiForeground`, `debug`, `setMapLinked`, `onLinkDown`, `shortTime`). Commit 14ca8e7bb. Spot-checked the residual lines.
- shared service/hud/HudSubnetProbe.kt (-75, moved): `candidateIpv4Cidrs` (same interface filters) and `tryTcp` moved to `AndroidHudLink.kt` (`AndroidHudNetwork`); sweep stays shared.
- app/.../service/hud/HudUdpListener.kt (-33): beacon parsing to shared `HudLinkPorts.kt` `HudBeacon.parse` (same prefix, ip, port rules); listener implements `HudBeacons`.
- app/.../service/hud/HudTileServer.kt (-27): request-line regex and response heads to shared `HudTileHttp.parse` and `HudTileHttp.head` (same 404, no-data and 200 heads); sockets stay.
- app/.../service/hud/engo/EngoAdapter.kt (-19): `State` enum to shared `engo/EngoGlasses.kt`; the adapter implements `EngoGlasses` (bound in `di/HudModule.kt`).
- app/.../ui/settings/EngoSection.kt (-187, deleted, rename not detected): to shared `ui/settings/EngoSection.kt`; `koinInject<EngoAdapter>()` became `LocalSettingsHost.current.engoGlasses()` (Android: `AndroidSettingsHost.engoGlasses`). Arrived through the merge aed9a2861.
- app/.../share/ShareSession.kt (-53): `Identity`, `PeerState`, `ShareState`, `activePeers` moved to shared `share/ShareState.kt`; ShareSession implements `LiveShare` (bound in `di/NavModule.kt`).
- app/.../share/ShareLink.kt (-29, deleted): to shared `share/ShareLink.kt` (`ShareLinks`, same BASE and parse rules), Base64 and random bytes from shared helpers; parity covered by app `ShareCipherParityTest`.
- app/.../util/GpxIO.kt (-34): `GpxData` and `contents()` moved to shared `util/GpxData.kt`; the parser stays in app.
- app/src/test/.../nav/offline/TileDownloadRulesTest.kt (-46, deleted): to shared commonTest `nav/offline/TileDownloadRulesTest.kt` (same 4 tests, against `TileDownload`), plus new `TileDownloadTest.kt`.
- app/src/test/.../nav/offline/OfflineTileStoreTest.kt (-6): constructor takes a path and `JvmOfflineFiles`; File helpers added.
- brouter/src/test/java/btools/router/VoiceHintAccessTest.java (-27, deleted): ported to brouter commonTest `VoiceHintAccessTest.kt`, same assertions; the 101 upstream Java files are pure renames to `brouter/reference/upstream`.
- shared tests nav/PoiServiceTest.kt (-7), RoutingServiceAvoidTest.kt (-5), OcmServiceTest.kt (-5): moved to commonTest: JUnit asserts become kotlin.test, `org.json` becomes the shared `platform.JSONObject` facade, multi-line asserts joined; same cases.

### Voice, Bluetooth, Flic, crews, audio, studio, widgets

- `service/VoiceService.kt` (-303): the report builder (`buildReportParts`, `catalogSentence`, `statSentence`, `metricSentence`, `formatCatalogValue`, `smoothed`, the load-sample window) moved to shared `service/VoiceReports.kt`. Strings and the clock go through `SpokenStrings`, and Android's side is `service/AndroidSpokenStrings.kt`, which keeps `DateFormat.getTimeFormat`. `VoiceOption`/`VoiceChoice` moved to shared `service/VoiceTypes.kt`, where `VoiceOption.locale` became `localeId: String`. The class now implements `NavVoice` and `CommandVoice`, so the old `fun` lines came back as `override fun`. TTS engine, locale, and audio focus code is not touched. **Merge note:** 4 of the lines (`LOAD_HISTORY_MAX_SAMPLES`, `addToWindow`) are upstream d64623300, not PR deletions. Merge 7aba4d34a put the cap in shared `VoiceReports.recordTelemetry`, so Android keeps the OOM fix and the iPhone gets it.
- `service/TonePlayer.kt` (-202): waveform, FX, and the note/beep/series rendering moved to shared `service/ToneSynth.kt` (`renderNotes`, `renderBeep`, `Stream`, `fadeOut`; constants unchanged, and Crush's `Math.round` became the same `floor(x+0.5)`). Prompt figures moved to shared `voice/VoiceCueFigures` (PROMPT, END, ERROR). The AudioTrack playback stays in TonePlayer, which now implements `RouteKeepAlive` and `CommandCues`.
- `voice/VoiceCommandController.kt` (-209, moved to shared): Android parts are now behind `VoiceCommandPorts`, implemented by `voice/AndroidVoiceCommandPorts.kt`: RECORD_AUDIO check, `AndroidVoiceListener`, `ListeningFocus` hold/release, localized contexts (`androidLanguageStrings`), `appHealth` mic notes, Flic `runAction`, trip location and last trip. Wired in `di/ServiceModule.kt` (`singleOf(::VoiceCommandController)`).
- `flic/FlicManager.kt` (-469): `executeAction`/`dispatchCustomBle` moved to shared `flic/ActionExecutor.kt` (same key table, lockdown gate, custom BLE notice). Slot logic moved to shared `flic/FlicSlots.kt` (`reconcile`, `isKnown`, `assign`, `actionFor`), and `LockdownGate` to shared `flic/LockdownGate.kt`. Music start, MediaBrowser, and media keys moved to `flic/AndroidMediaControls.kt`, with the same MUSIC_* constants. Volume and torch moved to `flic/AndroidPhoneControls.kt` (`stepVolume` maps +1/-1 to ADJUST_RAISE/LOWER, FLAG_SHOW_UI kept). Flic SDK pairing stays in `flic/AndroidFlicPairing.kt`. All of these are bound in `di/ServiceModule.kt` lines 84-94.
- `crews/CrewsPairActivity.kt` (-438): `CrewsPairDeps`, the `Step` machine and the pass UI moved to shared `crews/CrewsPairScreen.kt`. The activity now provides a `CrewsPairHost`: `QrScannerArea` (Android camera scanner), `isShareLink`, `openLeaderboardSettings`, which starts MainActivity with `EXTRA_OPEN_LEADERBOARD` and is now resolved with `Class.forName`.
- `ble/BleConnectionManager.kt` (-86): `ConnectionState` moved to shared `ble/WheelLink.kt`, and the class implements `WheelLink`. Reconnect timing and conditions moved to shared `ble/WheelReconnect.kt`. Checked value by value: 60 s window, pauses 5/15/30/60 s, low power after 10 min, settle 1.5 s, drop 2 s, poll 300 ms and 1 s, and the same boolean conditions. The write-type log text moved to `BleProfile.writeTypeNote` (shared `ble/WheelAdapter.kt`). UUIDs are converted with `toJavaUuid`/`toKotlinUuid`.
- `ble/radar/RadarConnectionManager.kt` (-19): `RawNotification` became shared `RadarNotification` (`ble/radar/RadarPorts.kt`), and the class implements `RadarLink`. UUID conversion as above.
- `ble/radar/RadarScanner.kt` (-10): `RadarScanResult` moved to shared `ble/radar/RadarPorts.kt`. The class implements `RadarScan` and gains `watchBluetooth`, an Android BroadcastReceiver.
- `ble/gps/ExternalGpsScanner.kt` (-10): `ExternalGpsScanResult` moved to shared `ble/gps/ExternalGpsPorts.kt`. The class implements `ExternalGpsScan` and gains `watchBluetooth`.
- `ble/gps/ExternalGpsConnectionManager.kt` (-8): implements `ExternalGpsLink`. The profile is held as a private `JavaGattProfile` converted from the shared Kotlin-UUID `ExternalGpsGattProfile`.
- `tpms/TpmsScanner.kt` (-110, moved to shared): Android scan code moved to `tpms/AndroidTpmsRadio.kt` (`TpmsRadio`). It keeps the same ScanSettings (BALANCED when monitoring, LOW_LATENCY when searching), the address ScanFilters, the BT state receiver (`watchBluetooth`), and builds `TpmsAdvert` from ScanResult. The shared scanner keeps the monitor/search state, the 1 s restart after BT on, and the 15 s staleness tick.
- `audio/EngineSoundEngine.kt` (-112, moved to shared): AudioTrack, focus, producer thread and raw grain-bank lookup moved to `audio/EngineAudioTrack.kt` (`EngineAudioOutput`). Same USAGE_MEDIA/CONTENT_TYPE_MUSIC, PCM float, GAIN_TRANSIENT_MAY_DUCK, and the headphones check through `AudioOutput.isExternalActive`. The preview `Thread` became a coroutine (`previewScope`, `delay`). Wired in `di/ServiceModule.kt` lines 97-98.
- `audio/GrainBank.kt` (-33, moved to shared): the ByteBuffer/IOException parsing became a byte reader that throws `FormatException`. `load` now takes `() -> ByteArray?` (Android feeds it from `EngineAudioTrack.rawBank`).
- `audio/HornPlayer.kt` (-13): `ImportResult` moved to shared `audio/HornImportResult.kt` as `HornImportResult`. The class implements `PhoneHorn`, so the old lines came back as `override`.
- `ui/studio/OverlayStudioScreen.kt` (-328, moved to shared): Android parts moved to `ui/studio/AndroidStudioHost.kt` (`StudioHost`, per commit 408f6a481): camera/mic permission launcher, PickVisualMedia/OpenDocument pickers, `StudioCapture.saveJpeg`/`newPendingImage`, thumbnails and frame pixels, offscreen renderer and emulator check, `openInGallery`, orientation listener. Wired through `rememberAndroidStudioHost()` in `ui/navigation/NavGraph.kt:160`.
- `ui/studio/StudioConfigSheets.kt` (-262, moved to shared): the video picker launcher moved to the host (`AndroidStudioHost`, `launch(arrayOf("video/*"))`). The rest is `R.string` to `Res.string`.
- `ui/studio/StudioOverlayElements.kt` (-107, moved to shared): map tiles and the launcher icon go through `StudioRender`, implemented by `AndroidStudioRender` (MapTileCache.get/load). Base64 data URLs and chroma key moved to `StudioImages.decodeDataUrl`/`applyChromaKey` (androidMain actual). The map credit and radar labels use Paint in `StudioCanvasText.android.kt` (`drawMapCredit`, `drawRadarDistanceLabel`). The clock reads `localTimeParts` instead of `Calendar`.
- `ui/studio/recording/StudioApngEncoder.kt` (-52, moved to shared): takes ARGB `IntArray` and a byte sink. CRC32/Deflater moved to platform `crc32`/`deflateZlibFast` (Deflater BEST_SPEED on Android). The bitmap side moved to `ui/studio/recording/StudioFramePixels.kt`.
- `ui/studio/recording/StudioGifEncoder.kt` (-27, moved to shared): same as APNG. The per-band threads became `runInParallel` (a thread per band on Android, per commit 3a652d44f).
- `ui/studio/StudioImages.kt` -> `shared/src/androidMain/.../ui/studio/StudioImages.android.kt` (-2): `object` became `actual object`, and `decode` now returns `ImageBitmap`.
- `ui/studio/camera/StudioCameraHub.kt` (-58): `StudioCameraInfo` and the `StudioCameraHub` state class moved to shared `ui/studio/camera/StudioCameras.kt`. The Camera2 binding stays in this file, now `AutoCloseable`.
- `ui/studio/camera/StudioVideoHub.kt` (-5): renamed `AndroidStudioVideoHub`, implementing the shared `StudioVideoHub` interface (`StudioCameras.kt`).
- `ui/studio/recording/StudioOffscreenSession.kt` (-29): `OverlayFrameSpec` moved to shared `ui/studio/recording/OverlayFrameSpec.kt`. The session now takes a `StudioRender` and provides `LocalStudioRender`.
- `ui/settings/eucstats/RemoteAvatar.kt` -> `shared/src/androidMain/.../eucstats/AvatarCache.android.kt` (-55): the `RemoteAvatar` composable moved to shared `ui/settings/eucstats/RemoteAvatar.kt`. `AvatarCache` became `actual object` returning `ImageBitmap`, with the same HttpURLConnection loader.
- `crews/PairApi.kt` (-20, moved to shared): OkHttp became the platform `HttpClient`/`HttpRequest`, and `org.json` became the platform `JSONObject`. The content type "application/json" loses its explicit "; charset=utf-8", but the Android client (`Http.android.kt`, `RequestBody.create`) uses OkHttp, which appends charset=utf-8, so the request is unchanged.
- `voice/VoicePhrases.kt` (-82, deleted): identical object in shared `voice/VoicePhrases.kt`, with `R.string`/`Int` changed to `Res.string`/`StringResource` (diffed). Not detected as a rename.
- `ble/BleScanner.kt` (-86): `BleDevice`, `isLikelyWheelName` and the RX_* regexes moved to shared `ble/WheelNames.kt` (also used by iOS `IosScanPorts.kt`). The scanner itself stays.
- `ble/VeteranHeadlightState.kt` (-51, deleted): same logic in shared `ble/VeteranHeadlightState.kt`. `@Synchronized` became `synchronized(lock)` from atomicfu (diffed).
- `app/src/test/java/.../audio/TestGrainBanks.kt` (-13, deleted): moved to `shared/src/commonTest/.../audio/TestGrainBanks.kt`, used by the shared engine tests.
- `widget/WidgetMetricFormat.kt` (-46): `unit`/`value` tables moved to shared `widget/WidgetContent.kt` (`unit`, `value`), checked entry by entry.
- `widget/EucWidget.kt` (-33): `isEnabled`/`ALWAYS_AVAILABLE` and the button labels moved to `WidgetContent.enabled` and `WidgetContent.buttonLabel` (the same string ids).
- `widget/WeatherWidgetPublisher.kt` (-7): the temperature and wind labels moved to shared `WeatherWidgetContent.tempLabel`/`windLabel`.
- `widget/WeatherWidgets.kt` (-5): `signedScore` and `STALE_MS` (3 h) moved to `WeatherWidgetContent`.
- `widget/WeatherWidgetWorker.kt` (-1): `windowHours <= 12` moved to `WeatherWidgetContent.fine`.

## Moved to shared behind an interface

Each file moved to `shared/`; the removed lines that are not identical reappear rewritten. The note lists the kinds of swap found in the removed lines (a pattern scan, residual lines read by hand for the larger files). "Host" and "port" mean an interface in shared with an Android implementation in `app/`.

- `app/src/main/java/ui/settings/SettingsScreen.kt` (-3543) -> `shared/src/commonMain/kotlin/ui/settings/SettingsScreen.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime, Koin lookups passed in, Android calls behind a host or port, JVM-only APIs to common code, window size via a common helper, Android graphics behind a host or Compose, Math to kotlin.math; ports: HintText,LocalSettingsHost,SearchableText,SettingsHost
- `app/src/main/java/ui/dashboard/DashboardScreen.kt` (-942) -> `shared/src/commonMain/kotlin/ui/dashboard/DashboardScreen.kt`: R ids to Res, format calls to a common helper, org.json via the shared JSON facade, Koin lookups passed in, Android calls behind a host or port, file I/O behind a port, android.util.Log to the shared Log, window size via a common helper, Math to kotlin.math; ports: DashboardHost
- `app/src/main/java/ui/recording/TripDetailScreen.kt` (-937) -> `shared/src/commonMain/kotlin/ui/recording/TripDetailScreen.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime, org.json via the shared JSON facade, Koin lookups passed in, Android calls behind a host or port, window size via a common helper, Android graphics behind a host or Compose, Math to kotlin.math; ports: TripDetailHost,HintText
- `app/src/main/java/ui/settings/SettingsViewModel.kt` (-381) -> `shared/src/commonMain/kotlin/ui/settings/SettingsViewModel.kt`: org.json via the shared JSON facade, Koin lookups passed in, Android calls behind a host or port, JVM-only APIs to common code; ports: HudSettingsLink,SettingsPlatform
- `app/src/main/java/ui/recording/RecordingViewModel.kt` (-213) -> `shared/src/commonMain/kotlin/ui/recording/RecordingViewModel.kt`: R ids to Res, Android calls behind a host or port, file I/O behind a port, android.util.Log to the shared Log, JVM-only APIs to common code; ports: RecordingPlatform
- `app/src/main/java/ui/dashboard/DashboardViewModel.kt` (-152) -> `shared/src/commonMain/kotlin/ui/dashboard/DashboardViewModel.kt`: R ids to Res, format calls to a common helper, Android calls behind a host or port, JVM concurrency to common primitives; ports: ExternalGpsFeed,TripMeterFeed,DashboardPlatform
- `app/src/main/java/diagnostics/WheelDiagnosticsViewModel.kt` (-147) -> `shared/src/commonMain/kotlin/diagnostics/WheelDiagnosticsViewModel.kt`: format calls to a common helper, dates via kotlinx-datetime, Android calls behind a host or port, file I/O behind a port, android.util.Log to the shared Log; ports: WheelLink,DiagnosticsPlatform,AndroidDiagnosticsPlatform
- `app/src/main/java/data/repository/TripDerive.kt` (-137) -> `shared/src/commonMain/kotlin/data/repository/TripDerive.kt`: file I/O behind a port; ports: LineSource,LineSink,TripToolFiles
- `app/src/main/java/ui/settings/AlarmSettingsContent.kt` (-133) -> `shared/src/commonMain/kotlin/ui/settings/AlarmSettingsContent.kt`: R ids to Res, format calls to a common helper, Koin lookups passed in, Android calls behind a host or port, window size via a common helper, Android graphics behind a host or Compose; ports: HintText
- `app/src/main/java/ui/charging/ChargingMonitorScreen.kt` (-117) -> `shared/src/commonMain/kotlin/ui/charging/ChargingMonitorScreen.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime, Koin lookups passed in, Android calls behind a host or port, window size via a common helper; ports: ChargingHost
- `app/src/main/java/data/model/AdvancedSpec.kt` (-112) -> `shared/src/commonMain/kotlin/data/model/AdvancedSpec.kt`: R ids to Res, format calls to a common helper, Math to kotlin.math
- `app/src/main/java/ui/settings/ExternalGpsSection.kt` (-110) -> `shared/src/commonMain/kotlin/ui/settings/ExternalGpsSection.kt`: R ids to Res, Koin lookups passed in; ports: HintText,SearchableText
- `app/src/main/java/ui/settings/eucstats/OnlineUploadOnboarding.kt` (-95) -> `shared/src/commonMain/kotlin/ui/settings/eucstats/OnlineUploadOnboarding.kt`: R ids to Res, Android calls behind a host or port, JVM-only APIs to common code, Android graphics behind a host or Compose; ports: LocalSettingsHost
- `app/src/main/java/ui/settings/eucstats/OnlineProfileScreen.kt` (-92) -> `shared/src/commonMain/kotlin/ui/settings/eucstats/OnlineProfileScreen.kt`: R ids to Res, dates via kotlinx-datetime, Android calls behind a host or port, Android graphics behind a host or Compose; ports: LocalSettingsHost
- `app/src/main/java/ui/recording/RecordingScreen.kt` (-87) -> `shared/src/commonMain/kotlin/ui/recording/RecordingScreen.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime, org.json via the shared JSON facade, Koin lookups passed in, Android calls behind a host or port; ports: RecordingHost
- `app/src/main/java/ui/dashboard/WeatherFlyout.kt` (-69) -> `shared/src/commonMain/kotlin/ui/dashboard/WeatherFlyout.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime
- `app/src/main/java/ui/scan/ScanViewModel.kt` (-69) -> `shared/src/commonMain/kotlin/ui/scan/ScanViewModel.kt`: R ids to Res, Android calls behind a host or port; ports: WheelLink,ScanPlatform
- `app/src/main/java/ui/charging/ChargingMonitorViewModel.kt` (-68) -> `shared/src/commonMain/kotlin/ui/charging/ChargingMonitorViewModel.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime, Android calls behind a host or port, android.util.Log to the shared Log; ports: ChargingCsvFiles,ChargingHost
- `app/src/main/java/data/repository/WheelRepository.kt` (-63) -> `shared/src/commonMain/kotlin/data/repository/WheelRepository.kt`: R ids to Res, format calls to a common helper, Android calls behind a host or port, Math to kotlin.math; ports: WheelLink,ExternalGpsFeed,LegalLockdownGate,TyrePressureFeed
- `app/src/main/java/ui/settings/AutomationsContent.kt` (-59) -> `shared/src/commonMain/kotlin/ui/settings/AutomationsContent.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime, Koin lookups passed in, Android calls behind a host or port, Math to kotlin.math; ports: HintText,LocalSettingsHost,SettingsHost
- `app/src/main/java/ui/recording/TripToolsDialog.kt` (-57) -> `shared/src/commonMain/kotlin/ui/recording/TripToolsDialog.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime
- `app/src/main/java/ui/studio/StudioReplayDialog.kt` (-57) -> `shared/src/commonMain/kotlin/ui/studio/StudioReplayDialog.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime, window size via a common helper
- `app/src/main/java/ui/scan/ScanScreen.kt` (-47) -> `shared/src/commonMain/kotlin/ui/scan/ScanScreen.kt`: R ids to Res, Koin lookups passed in, Android calls behind a host or port; ports: ScanHost
- `app/src/main/java/ui/dashboard/MetricDetailScreen.kt` (-46) -> `shared/src/commonMain/kotlin/ui/dashboard/MetricDetailScreen.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime, Koin lookups passed in, Android calls behind a host or port
- `app/src/main/java/ui/dashboard/sources/DataSourcesSheet.kt` (-45) -> `shared/src/commonMain/kotlin/ui/dashboard/sources/DataSourcesSheet.kt`: R ids to Res, Koin lookups passed in, Android calls behind a host or port, Math to kotlin.math; ports: DataSource
- `app/src/main/java/ui/settings/VoiceVocabularyDialog.kt` (-44) -> `shared/src/commonMain/kotlin/ui/settings/VoiceVocabularyDialog.kt`: R ids to Res, Android calls behind a host or port; ports: VoiceCommandPorts,LanguageStrings
- `app/src/main/java/ui/recording/TripCompareDialogs.kt` (-41) -> `shared/src/commonMain/kotlin/ui/recording/TripCompareDialogs.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime
- `app/src/main/java/ui/theme/ThemeTargetTool.kt` (-39) -> `shared/src/commonMain/kotlin/ui/theme/ThemeTargetTool.kt`: R ids to Res, Android calls behind a host or port, Android graphics behind a host or Compose
- `app/src/main/java/service/AlarmEngine.kt` (-36) -> `shared/src/commonMain/kotlin/service/AlarmEngine.kt`: R ids to Res, Android calls behind a host or port, JVM concurrency to common primitives; ports: WheelLink
- `app/src/main/java/ui/settings/RadarViewModel.kt` (-36) -> `shared/src/commonMain/kotlin/ui/settings/RadarViewModel.kt`: Android calls behind a host or port
- `app/src/main/java/ui/settings/ExternalGpsViewModel.kt` (-36) -> `shared/src/commonMain/kotlin/ui/settings/ExternalGpsViewModel.kt`: Android calls behind a host or port
- `app/src/test/java/ui/studio/StudioVideoSyncTest.kt` (-35) -> `shared/src/commonTest/kotlin/ui/studio/StudioVideoSyncTest.kt`: small rewrites
- `app/src/main/java/nav/RouteLink.kt` (-33) -> `shared/src/commonMain/kotlin/nav/RouteLink.kt`: org.json via the shared JSON facade, JVM-only APIs to common code
- `app/src/main/java/ble/gps/RaceBoxAdapter.kt` (-32) -> `shared/src/commonMain/kotlin/ble/gps/RaceBoxAdapter.kt`: dates via kotlinx-datetime
- `app/src/main/java/ui/theme/ThemeEditorWidget.kt` (-31) -> `shared/src/commonMain/kotlin/ui/theme/ThemeEditorWidget.kt`: R ids to Res, Koin lookups passed in
- `app/src/test/java/nav/TrackRouteTest.kt` (-29) -> `shared/src/commonTest/kotlin/nav/TrackRouteTest.kt`: Math to kotlin.math
- `app/src/main/java/ui/studio/StudioReplay.kt` (-28) -> `shared/src/commonMain/kotlin/ui/studio/StudioReplay.kt`: format calls to a common helper, dates via kotlinx-datetime
- `app/src/main/java/ui/settings/RadarSection.kt` (-27) -> `shared/src/commonMain/kotlin/ui/settings/RadarSection.kt`: R ids to Res, Koin lookups passed in; ports: SearchableText,HintText
- `app/src/test/java/tpms/TpmsReachesTheAppTest.kt` (-27) -> `shared/src/commonTest/kotlin/tpms/TpmsReachesTheAppTest.kt`: small rewrites
- `app/src/main/java/data/model/WidgetSlotType.kt` (-26) -> `shared/src/commonMain/kotlin/data/model/WidgetSlotType.kt`: R ids to Res
- `app/src/main/java/ui/settings/FlicScreen.kt` (-25) -> `shared/src/commonMain/kotlin/ui/settings/FlicScreen.kt`: R ids to Res, Koin lookups passed in; ports: SearchableText,HintText
- `app/src/main/java/ui/settings/SettingsSearch.kt` (-25) -> `shared/src/commonMain/kotlin/ui/settings/SettingsSearch.kt`: R ids to Res, Android calls behind a host or port, window size via a common helper; ports: SettingsHost
- `app/src/main/java/ui/lockdown/LegalLockdownDialogs.kt` (-25) -> `shared/src/commonMain/kotlin/ui/lockdown/LegalLockdownDialogs.kt`: R ids to Res, format calls to a common helper, Android calls behind a host or port
- `app/src/main/java/ui/lockdown/LegalLockdownScreen.kt` (-24) -> `shared/src/commonMain/kotlin/ui/lockdown/LegalLockdownScreen.kt`: R ids to Res, format calls to a common helper, Koin lookups passed in, Android calls behind a host or port; ports: LegalLockdownHost
- `app/src/main/java/weather/WeatherRepository.kt` (-24) -> `shared/src/commonMain/kotlin/weather/WeatherRepository.kt`: R ids to Res, dates via kotlinx-datetime, HTTP via Ktor or a facade
- `app/src/main/java/ui/settings/AlarmViewModel.kt` (-23) -> `shared/src/commonMain/kotlin/ui/settings/AlarmViewModel.kt`: R ids to Res, Android calls behind a host or port, JVM concurrency to common primitives; ports: WheelLink
- `app/src/test/java/audio/GrainBankTest.kt` (-22) -> `shared/src/commonTest/kotlin/audio/GrainBankTest.kt`: file I/O behind a port
- `app/src/main/java/diagnostics/WheelDiagnosticsDialog.kt` (-22) -> `shared/src/commonMain/kotlin/diagnostics/WheelDiagnosticsDialog.kt`: format calls to a common helper, dates via kotlinx-datetime, Koin lookups passed in, Android calls behind a host or port
- `app/src/main/java/util/TripCsv.kt` (-20) -> `shared/src/commonMain/kotlin/util/TripCsv.kt`: dates via kotlinx-datetime, Math to kotlin.math
- `app/src/main/java/ui/navigator/NavigatorHud.kt` (-20) -> `shared/src/commonMain/kotlin/ui/navigator/NavigatorHud.kt`: R ids to Res, format calls to a common helper, dates via kotlinx-datetime, Android calls behind a host or port
- `app/src/main/java/data/store/SettingsStore.kt` (-19) -> `shared/src/commonMain/kotlin/data/store/SettingsStore.kt`: Koin lookups passed in, Android calls behind a host or port
- `app/src/test/java/share/ShareModelTest.kt` (-19) -> `shared/src/commonTest/kotlin/share/ShareModelTest.kt`: small rewrites
- `app/src/main/java/data/eucstats/MetaBuilder.kt` (-18) -> `shared/src/commonMain/kotlin/data/eucstats/MetaBuilder.kt`: format calls to a common helper, dates via kotlinx-datetime, JVM-only APIs to common code
- `app/src/test/java/nav/RouteSolverTest.kt` (-18) -> `shared/src/commonTest/kotlin/nav/RouteSolverTest.kt`: small rewrites
- `app/src/main/java/util/CsvWriter.kt` (-17) -> `shared/src/commonMain/kotlin/util/CsvWriter.kt`: format calls to a common helper, dates via kotlinx-datetime; ports: TextSink
- `app/src/main/java/ui/settings/offline/OfflineDataViewModel.kt` (-17) -> `shared/src/commonMain/kotlin/ui/settings/offline/OfflineDataViewModel.kt`: Android calls behind a host or port; ports: OfflineDataPorts,AndroidOfflineDataPorts
- `app/src/test/java/nav/RouteLinksTest.kt` (-16) -> `shared/src/commonTest/kotlin/nav/RouteLinksTest.kt`: Math to kotlin.math
- `app/src/test/java/nav/NavEtaTest.kt` (-16) -> `shared/src/commonTest/kotlin/nav/NavEtaTest.kt`: small rewrites
- `app/src/test/java/ui/recording/TripCompareTest.kt` (-16) -> `shared/src/commonTest/kotlin/ui/recording/TripCompareTest.kt`: small rewrites
- `app/src/main/java/ui/navigator/MapHtml.kt` (-16) -> `shared/src/commonMain/kotlin/ui/navigator/MapHtml.kt`: small rewrites
- `app/src/main/java/service/AccelSplitVoice.kt` (-15) -> `shared/src/commonMain/kotlin/service/AccelSplitVoice.kt`: R ids to Res, Android calls behind a host or port
- `app/src/test/java/tpms/LyTpmsDecoderTest.kt` (-15) -> `shared/src/commonTest/kotlin/tpms/LyTpmsDecoderTest.kt`: small rewrites
- `app/src/main/java/data/repository/ExternalGpsRepository.kt` (-14) -> `shared/src/commonMain/kotlin/data/repository/ExternalGpsRepository.kt`: small rewrites; ports: ExternalGpsLink,IosExternalGpsLink,ExternalGpsFeed
- `app/src/test/java/nav/RouteFileNameTest.kt` (-14) -> `shared/src/commonTest/kotlin/nav/RouteFileNameTest.kt`: small rewrites
- `app/src/main/java/util/SunCalculator.kt` (-14) -> `shared/src/commonMain/kotlin/util/SunCalculator.kt`: dates via kotlinx-datetime
- `app/src/main/java/data/store/LegalLockdownStore.kt` (-14) -> `shared/src/commonMain/kotlin/data/store/LegalLockdownStore.kt`: Koin lookups passed in, Android calls behind a host or port
- `app/src/main/java/util/Units.kt` (-14) -> `shared/src/commonMain/kotlin/util/Units.kt`: R ids to Res, Android calls behind a host or port
- `app/src/test/java/service/HeadlightSlowPolicyTest.kt` (-14) -> `shared/src/commonTest/kotlin/service/HeadlightSlowPolicyTest.kt`: small rewrites
- `app/src/test/java/service/HeadlightRulesTest.kt` (-14) -> `shared/src/commonTest/kotlin/service/HeadlightRulesTest.kt`: small rewrites
- `app/src/test/java/nav/NavEdgeCasesTest.kt` (-14) -> `shared/src/commonTest/kotlin/nav/NavEdgeCasesTest.kt`: Math to kotlin.math
- `app/src/main/java/data/store/NavMarkerStore.kt` (-13) -> `shared/src/commonMain/kotlin/data/store/NavMarkerStore.kt`: Koin lookups passed in, Android calls behind a host or port, file I/O behind a port
- `app/src/test/java/ble/gps/DragyAdapterTest.kt` (-12) -> `shared/src/commonTest/kotlin/ble/gps/DragyAdapterTest.kt`: small rewrites
- `app/src/main/java/ui/studio/OverlayStudioViewModel.kt` (-12) -> `shared/src/commonMain/kotlin/ui/studio/OverlayStudioViewModel.kt`: file I/O behind a port; ports: ExternalGpsFeed
- `app/src/main/java/data/model/NotificationActionType.kt` (-11) -> `shared/src/commonMain/kotlin/data/model/NotificationActionType.kt`: R ids to Res
- `app/src/main/java/data/store/TripMeterStore.kt` (-11) -> `shared/src/commonMain/kotlin/data/store/TripMeterStore.kt`: Koin lookups passed in, Android calls behind a host or port
- `app/src/main/java/data/repository/LegalLockdownController.kt` (-11) -> `shared/src/commonMain/kotlin/data/repository/LegalLockdownController.kt`: format calls to a common helper, JVM-only APIs to common code; ports: LegalLockdownGate
- `app/src/main/java/ui/dashboard/sources/DataSourcesViewModel.kt` (-11) -> `shared/src/commonMain/kotlin/ui/dashboard/sources/DataSourcesViewModel.kt`: small rewrites; ports: ExternalGpsFeed
- `app/src/test/java/nav/offline/RoutingTilesTest.kt` (-11) -> `shared/src/commonTest/kotlin/nav/offline/RoutingTilesTest.kt`: small rewrites
- `app/src/test/java/nav/offline/OfflineRouterTest.kt` (-11) -> `shared/src/androidUnitTest/kotlin/nav/offline/OfflineRouterTest.kt`: file I/O behind a port, JVM concurrency to common primitives; ports: JvmOfflineFiles
- `app/src/main/java/diagnostics/DiagnosticsLogger.kt` (-10) -> `shared/src/commonMain/kotlin/diagnostics/DiagnosticsLogger.kt`: format calls to a common helper, dates via kotlinx-datetime, JVM concurrency to common primitives
- `app/src/main/java/data/repository/SettingsRepository.kt` (-10) -> `shared/src/commonMain/kotlin/data/repository/SettingsRepository.kt`: org.json via the shared JSON facade, android.util.Log to the shared Log
- `app/src/test/java/ui/recording/ChartWindowTest.kt` (-10) -> `shared/src/commonTest/kotlin/ui/recording/ChartWindowTest.kt`: small rewrites
- `app/src/test/java/data/model/TripMeterAccumulatorTest.kt` (-9) -> `shared/src/commonTest/kotlin/data/model/TripMeterAccumulatorTest.kt`: small rewrites
- `app/src/main/java/data/repository/TripMeterRepository.kt` (-9) -> `shared/src/commonMain/kotlin/data/repository/TripMeterRepository.kt`: small rewrites
- `app/src/main/java/tpms/TpmsRepository.kt` (-9) -> `shared/src/commonMain/kotlin/tpms/TpmsRepository.kt`: JVM concurrency to common primitives; ports: TyrePressureFeed
- `app/src/test/java/util/TripCsvTest.kt` (-8) -> `shared/src/commonTest/kotlin/util/TripCsvTest.kt`: small rewrites
- `app/src/test/java/ui/recording/SustainedTopSpeedTest.kt` (-8) -> `shared/src/commonTest/kotlin/ui/recording/SustainedTopSpeedTest.kt`: small rewrites
- `app/src/test/java/ui/dashboard/sources/SeriesDecimationTest.kt` (-8) -> `shared/src/commonTest/kotlin/ui/dashboard/sources/SeriesDecimationTest.kt`: small rewrites
- `app/src/test/java/ble/gps/ExternalGpsBatteryTest.kt` (-8) -> `shared/src/commonTest/kotlin/ble/gps/ExternalGpsBatteryTest.kt`: small rewrites
- `app/src/main/java/nav/RerouteGate.kt` (-8) -> `shared/src/commonMain/kotlin/nav/RerouteGate.kt`: JVM concurrency to common primitives
- `app/src/main/java/ui/settings/FlicViewModel.kt` (-8) -> `shared/src/commonMain/kotlin/ui/settings/FlicViewModel.kt`: small rewrites
- `app/src/main/java/ble/VeteranCommands.kt` (-8) -> `shared/src/commonMain/kotlin/ble/VeteranCommands.kt`: dates via kotlinx-datetime, JVM-only APIs to common code
- `app/src/main/java/util/TripShareLink.kt` (-7) -> `shared/src/commonMain/kotlin/util/TripShareLink.kt`: JVM-only APIs to common code
- `app/src/main/java/ui/settings/offline/OfflineGridHtml.kt` (-7) -> `shared/src/commonMain/kotlin/ui/settings/offline/OfflineGridHtml.kt`: small rewrites
- `app/src/test/java/ui/recording/CompareMetricRegistryTest.kt` (-7) -> `shared/src/commonTest/kotlin/ui/recording/CompareMetricRegistryTest.kt`: small rewrites
- `app/src/test/java/tpms/ZeepinTpmsDecoderTest.kt` (-7) -> `shared/src/commonTest/kotlin/tpms/ZeepinTpmsDecoderTest.kt`: small rewrites
- `app/src/main/java/ui/settings/DashboardSlotRestore.kt` (-7) -> `shared/src/commonMain/kotlin/ui/settings/DashboardSlotRestore.kt`: org.json via the shared JSON facade
- `app/src/main/java/data/model/AccelSplitMode.kt` (-7) -> `shared/src/commonMain/kotlin/data/model/AccelSplitMode.kt`: R ids to Res
- `app/src/test/java/ble/VeteranLockTest.kt` (-7) -> `shared/src/androidUnitTest/kotlin/ble/VeteranLockTest.kt`: dates via kotlinx-datetime
- `app/src/main/java/data/eucstats/CanonicalJson.kt` (-7) -> `shared/src/commonMain/kotlin/data/eucstats/CanonicalJson.kt`: format calls to a common helper, org.json via the shared JSON facade, JVM-only APIs to common code
- `app/src/main/java/data/repository/RadarRepository.kt` (-6) -> `shared/src/commonMain/kotlin/data/repository/RadarRepository.kt`: small rewrites; ports: RadarLink
- `app/src/test/java/tpms/TpmsLifecycleTest.kt` (-6) -> `shared/src/commonTest/kotlin/tpms/TpmsLifecycleTest.kt`: small rewrites
- `app/src/main/java/data/store/SettingsJson.kt` (-5) -> `shared/src/commonMain/kotlin/data/store/SettingsJson.kt`: org.json via the shared JSON facade
- `app/src/main/java/ble/gps/DragyAdapter.kt` (-5) -> `shared/src/commonMain/kotlin/ble/gps/DragyAdapter.kt`: JVM-only APIs to common code
- `app/src/test/java/audio/EngineSynthSpectrumTest.kt` (-5) -> `shared/src/commonTest/kotlin/audio/EngineSynthSpectrumTest.kt`: small rewrites
- `app/src/test/java/data/repository/IncomingShareParseTest.kt` (-5) -> `shared/src/commonTest/kotlin/data/repository/IncomingShareParseTest.kt`: small rewrites
- `app/src/test/java/nav/offline/BrouterRealTileTest.kt` (-5) -> `shared/src/androidUnitTest/kotlin/nav/offline/BrouterRealTileTest.kt`: file I/O behind a port
- `hud-protocol/src/main/java/hud/protocol/HudTiles.kt` (-5) -> `hud-protocol/src/commonMain/kotlin/hud/protocol/HudTiles.kt`: format calls to a common helper, JVM-only APIs to common code
- `app/src/main/java/service/hud/engo/EngoHudRenderer.kt` (-4) -> `shared/src/commonMain/kotlin/service/hud/engo/EngoHudRenderer.kt`: small rewrites
- `app/src/main/java/data/repository/IncomingShareRepository.kt` (-4) -> `shared/src/commonMain/kotlin/data/repository/IncomingShareRepository.kt`: JVM-only APIs to common code
- `app/src/main/java/service/VoicePill.kt` (-4) -> `shared/src/commonMain/kotlin/service/VoicePill.kt`: JVM-only APIs to common code
- `app/src/main/java/util/AppNotifier.kt` (-3) -> `shared/src/commonMain/kotlin/util/AppNotifier.kt`: small rewrites
- `hud-protocol/src/main/java/hud/protocol/WebMercator.kt` (-3) -> `hud-protocol/src/commonMain/kotlin/hud/protocol/WebMercator.kt`: Math to kotlin.math
- `app/src/main/java/nav/TrackRoute.kt` (-3) -> `shared/src/commonMain/kotlin/nav/TrackRoute.kt`: JVM-only APIs to common code
- `app/src/test/java/data/ArchivePolicyTest.kt` (-3) -> `shared/src/androidUnitTest/kotlin/data/ArchivePolicyTest.kt`: file I/O behind a port, source-reading test retargeted to shared
- `app/src/main/java/crews/PairLink.kt` (-3) -> `shared/src/commonMain/kotlin/crews/PairLink.kt`: Android calls behind a host or port, JVM-only APIs to common code
- `app/src/main/java/nav/offline/map/VectorTile.kt` (-3) -> `shared/src/commonMain/kotlin/nav/offline/map/VectorTile.kt`: JVM-only APIs to common code
- `app/src/main/java/nav/RouteFileName.kt` (-3) -> `shared/src/commonMain/kotlin/nav/RouteFileName.kt`: small rewrites
- `app/src/main/java/cheats/CheatState.kt` (-2) -> `shared/src/commonMain/kotlin/cheats/CheatState.kt`: small rewrites
- `app/src/main/java/ble/radar/RadarAdapter.kt` (-2) -> `shared/src/commonMain/kotlin/ble/radar/RadarAdapter.kt`: JVM-only APIs to common code
- `app/src/main/java/audio/EngineDynamics.kt` (-2) -> `shared/src/commonMain/kotlin/audio/EngineDynamics.kt`: small rewrites
- `app/src/main/java/ui/common/DialogSizing.kt` (-2) -> `shared/src/commonMain/kotlin/ui/common/DialogSizing.kt`: window size via a common helper
- `app/src/main/java/ble/virtual/KingsongVirtualWheel.kt` (-1) -> `shared/src/commonMain/kotlin/ble/virtual/KingsongVirtualWheel.kt`: small rewrites
- `app/src/main/java/ui/common/ToolRow.kt` (-1) -> `shared/src/commonMain/kotlin/ui/common/ToolRow.kt`: small rewrites

## Moved as is

Every removed code line has an exact or near-exact match in the moved file. The only differences are package, imports, `R.string` to `Res.string`, and `@StringRes Int` to `StringResource`.

- `app/src/main/java/data/model/MetricCatalog.kt` (-75) -> `shared/src/commonMain/kotlin/data/model/MetricCatalog.kt`
- `app/src/main/java/ui/dashboard/WelcomeTutorial.kt` (-68) -> `shared/src/commonMain/kotlin/ui/dashboard/WelcomeTutorial.kt`
- `app/src/main/java/ui/settings/LegalLockdownSetting.kt` (-42) -> `shared/src/commonMain/kotlin/ui/settings/LegalLockdownSetting.kt`
- `app/src/main/java/data/model/AlarmRule.kt` (-36) -> `shared/src/commonMain/kotlin/data/model/AlarmRule.kt`
- `app/src/main/java/data/model/ActionCatalog.kt` (-35) -> `shared/src/commonMain/kotlin/data/model/ActionCatalog.kt`
- `app/src/main/java/ui/studio/StudioMetric.kt` (-33) -> `shared/src/commonMain/kotlin/ui/studio/StudioMetric.kt`
- `app/src/main/java/ui/settings/VoicePillEditor.kt` (-31) -> `shared/src/commonMain/kotlin/ui/settings/VoicePillEditor.kt`
- `app/src/main/java/ui/dashboard/TripMeterDetailScreen.kt` (-27) -> `shared/src/commonMain/kotlin/ui/dashboard/TripMeterDetailScreen.kt`
- `app/src/main/java/ui/navigator/SolveRouteDialog.kt` (-26) -> `shared/src/commonMain/kotlin/ui/navigator/SolveRouteDialog.kt`
- `app/src/main/java/ble/WheelAdapter.kt` (-23) -> `shared/src/commonMain/kotlin/ble/WheelAdapter.kt`
- `app/src/main/java/ui/navigator/NavigationOverlay.kt` (-18) -> `shared/src/commonMain/kotlin/ui/navigator/NavigationOverlay.kt`
- `app/src/main/java/ui/settings/TpmsSection.kt` (-17) -> `shared/src/commonMain/kotlin/ui/settings/TpmsSection.kt`
- `app/src/main/java/ui/navigator/RouteQrContent.kt` (-16) -> `shared/src/commonMain/kotlin/ui/navigator/RouteQrContent.kt`
- `app/src/test/java/nav/offline/TileIndexTest.kt` (-15) -> `shared/src/androidUnitTest/kotlin/nav/offline/TileIndexTest.kt`
- `app/src/main/java/ui/studio/StudioViewports.kt` (-15) -> `shared/src/commonMain/kotlin/ui/studio/StudioViewports.kt`
- `app/src/main/java/ui/recording/TripActionDialog.kt` (-14) -> `shared/src/commonMain/kotlin/ui/recording/TripActionDialog.kt`
- `app/src/test/java/service/ApplyWhenGateTest.kt` (-13) -> `shared/src/commonTest/kotlin/service/ApplyWhenGateTest.kt`
- `app/src/main/java/weather/WeatherFace.kt` (-12) -> `shared/src/commonMain/kotlin/weather/WeatherFace.kt`
- `app/src/main/java/ble/gps/ExternalGpsAdapter.kt` (-11) -> `shared/src/commonMain/kotlin/ble/gps/ExternalGpsAdapter.kt`
- `app/src/main/java/ui/theme/ThemeController.kt` (-10) -> `shared/src/commonMain/kotlin/ui/theme/ThemeController.kt`
- `app/src/main/java/ui/dashboard/HeadlightButtonState.kt` (-10) -> `shared/src/commonMain/kotlin/ui/dashboard/HeadlightButtonState.kt`
- `app/src/main/java/ui/navigator/UnlockTrackDialog.kt` (-10) -> `shared/src/commonMain/kotlin/ui/navigator/UnlockTrackDialog.kt`
- `app/src/test/java/ui/studio/StudioBatteryEnvelopeTest.kt` (-9) -> `shared/src/commonTest/kotlin/ui/studio/StudioBatteryEnvelopeTest.kt`
- `app/src/test/java/nav/RouteLegsTest.kt` (-9) -> `shared/src/commonTest/kotlin/nav/RouteLegsTest.kt`
- `app/src/main/java/ui/common/TrimTimeDialog.kt` (-9) -> `shared/src/commonMain/kotlin/ui/common/TrimTimeDialog.kt`
- `app/src/test/java/ui/recording/TripTrimTest.kt` (-9) -> `shared/src/commonTest/kotlin/ui/recording/TripTrimTest.kt`
- `app/src/test/java/tpms/TpmsPolicyTest.kt` (-8) -> `shared/src/commonTest/kotlin/tpms/TpmsPolicyTest.kt`
- `app/src/test/java/nav/NavMotionTest.kt` (-8) -> `shared/src/commonTest/kotlin/nav/NavMotionTest.kt`
- `app/src/test/java/service/hud/HudLinkTraceTest.kt` (-8) -> `shared/src/commonTest/kotlin/service/hud/HudLinkTraceTest.kt`
- `app/src/main/java/data/repository/AccelSplitRepository.kt` (-8) -> `shared/src/commonMain/kotlin/data/repository/AccelSplitRepository.kt`
- `app/src/test/java/ui/studio/StudioMetricAltitudeTest.kt` (-7) -> `shared/src/commonTest/kotlin/ui/studio/StudioMetricAltitudeTest.kt`
- `app/src/main/java/service/hud/HudCommandSink.kt` (-7) -> `shared/src/commonMain/kotlin/service/hud/HudCommandSink.kt`
- `app/src/test/java/nav/NavStartGuardTest.kt` (-7) -> `shared/src/commonTest/kotlin/nav/NavStartGuardTest.kt`
- `app/src/test/java/ble/radar/VariaAdapterTest.kt` (-7) -> `shared/src/commonTest/kotlin/ble/radar/VariaAdapterTest.kt`
- `app/src/main/java/service/AlarmValueFormat.kt` (-7) -> `shared/src/commonMain/kotlin/service/AlarmValueFormat.kt`
- `app/src/main/java/ui/dashboard/sources/DataSource.kt` (-6) -> `shared/src/commonMain/kotlin/ui/dashboard/sources/DataSource.kt`
- `app/src/test/java/ui/navigator/FinishedRouteRulesTest.kt` (-6) -> `shared/src/commonTest/kotlin/ui/navigator/FinishedRouteRulesTest.kt`
- `app/src/test/java/tpms/TpmsActiveSensorTest.kt` (-6) -> `shared/src/commonTest/kotlin/tpms/TpmsActiveSensorTest.kt`
- `app/src/test/java/nav/RoutingBackendTest.kt` (-6) -> `shared/src/commonTest/kotlin/nav/RoutingBackendTest.kt`
- `app/src/main/java/ui/charging/ChargeLevelDialog.kt` (-6) -> `shared/src/commonMain/kotlin/ui/charging/ChargeLevelDialog.kt`
- `app/src/main/java/nav/offline/TileCheck.kt` (-6) -> `shared/src/commonMain/kotlin/nav/offline/TileCheck.kt`
- `app/src/main/java/ui/settings/eucstats/LeaderboardProfileCard.kt` (-5) -> `shared/src/commonMain/kotlin/ui/settings/eucstats/LeaderboardProfileCard.kt`
- `app/src/test/java/service/hud/HudSearchPacingTest.kt` (-5) -> `shared/src/commonTest/kotlin/service/hud/HudSearchPacingTest.kt`
- `app/src/test/java/ui/navigator/ScannedCodeTest.kt` (-5) -> `shared/src/commonTest/kotlin/ui/navigator/ScannedCodeTest.kt`
- `app/src/test/java/nav/ValhallaChunkTest.kt` (-5) -> `shared/src/commonTest/kotlin/nav/ValhallaChunkTest.kt`
- `app/src/test/java/nav/RerouteGateTest.kt` (-5) -> `shared/src/commonTest/kotlin/nav/RerouteGateTest.kt`
- `app/src/test/java/nav/offline/map/PmTilesTest.kt` (-5) -> `shared/src/androidUnitTest/kotlin/nav/offline/map/PmTilesTest.kt`
- `app/src/main/java/nav/offline/BrouterMapping.kt` (-5) -> `shared/src/commonMain/kotlin/nav/offline/BrouterMapping.kt`
- `app/src/test/java/ui/studio/GForceTrailBufferTest.kt` (-4) -> `shared/src/commonTest/kotlin/ui/studio/GForceTrailBufferTest.kt`
- `app/src/test/java/ui/navigator/MarkPassedUpToTest.kt` (-4) -> `shared/src/commonTest/kotlin/ui/navigator/MarkPassedUpToTest.kt`
- `app/src/test/java/nav/offline/RouterPlanTest.kt` (-4) -> `shared/src/commonTest/kotlin/nav/offline/RouterPlanTest.kt`
- `app/src/test/java/nav/offline/TileCheckTest.kt` (-4) -> `shared/src/androidUnitTest/kotlin/nav/offline/TileCheckTest.kt`
- `app/src/main/java/ui/recording/TripTrimBar.kt` (-4) -> `shared/src/commonMain/kotlin/ui/recording/TripTrimBar.kt`
- `app/src/main/java/ui/navigator/NavigationOverlayViewModel.kt` (-4) -> `shared/src/commonMain/kotlin/ui/navigator/NavigationOverlayViewModel.kt`
- `app/src/main/java/service/AlarmEvaluator.kt` (-4) -> `shared/src/commonMain/kotlin/service/AlarmEvaluator.kt`
- `app/src/main/java/ble/radar/VariaAdapter.kt` (-4) -> `shared/src/commonMain/kotlin/ble/radar/VariaAdapter.kt`
- `app/src/test/java/share/ShareLinkTest.kt` (-4) -> `shared/src/commonTest/kotlin/share/ShareLinkTest.kt`
- `app/src/main/java/service/hud/engo/EngoFonts.kt` (-3) -> `shared/src/commonMain/kotlin/service/hud/engo/EngoFonts.kt`
- `hud-protocol/src/main/java/hud/protocol/RouteWindow.kt` (-3) -> `hud-protocol/src/commonMain/kotlin/hud/protocol/RouteWindow.kt`
- `app/src/main/java/service/hud/HudLinkLog.kt` (-3) -> `shared/src/commonMain/kotlin/service/hud/HudLinkLog.kt`
- `app/src/main/java/share/ShareModel.kt` (-3) -> `shared/src/commonMain/kotlin/share/ShareModel.kt`
- `app/src/main/java/ble/CompositeWheelAdapter.kt` (-3) -> `shared/src/commonMain/kotlin/ble/CompositeWheelAdapter.kt`
- `app/src/test/java/util/TripShareLinkTest.kt` (-3) -> `shared/src/commonTest/kotlin/util/TripShareLinkTest.kt`
- `app/src/test/java/ui/navigator/FinishedRouteEdgeCasesTest.kt` (-3) -> `shared/src/commonTest/kotlin/ui/navigator/FinishedRouteEdgeCasesTest.kt`
- `app/src/test/java/ui/dashboard/sources/TimedSeriesResetTest.kt` (-3) -> `shared/src/commonTest/kotlin/ui/dashboard/sources/TimedSeriesResetTest.kt`
- `app/src/test/java/tpms/TpmsMonitorFilterTest.kt` (-3) -> `shared/src/commonTest/kotlin/tpms/TpmsMonitorFilterTest.kt`
- `app/src/test/java/service/ProximityLockEvaluatorTest.kt` (-3) -> `shared/src/commonTest/kotlin/service/ProximityLockEvaluatorTest.kt`
- `app/src/test/java/nav/offline/map/OfflineAddressTest.kt` (-3) -> `shared/src/commonTest/kotlin/nav/offline/map/OfflineAddressTest.kt`
- `app/src/test/java/data/TripSplitDetectorTest.kt` (-3) -> `shared/src/commonTest/kotlin/data/TripSplitDetectorTest.kt`
- `app/src/test/java/data/OverlayPresetJsonReplayTest.kt` (-3) -> `shared/src/commonTest/kotlin/data/OverlayPresetJsonReplayTest.kt`
- `app/src/main/java/ui/common/CloseIconButton.kt` (-3) -> `shared/src/commonMain/kotlin/ui/common/CloseIconButton.kt`
- `app/src/main/java/ble/InMotionV2Adapter.kt` (-2) -> `shared/src/commonMain/kotlin/ble/InMotionV2Adapter.kt`
- `app/src/main/java/ui/common/FixButton.kt` (-2) -> `shared/src/commonMain/kotlin/ui/common/FixButton.kt`
- `app/src/main/java/data/model/CustomBleCommand.kt` (-2) -> `shared/src/commonMain/kotlin/data/model/CustomBleCommand.kt`
- `app/src/main/java/ble/VeteranParser.kt` (-2) -> `shared/src/commonMain/kotlin/ble/VeteranParser.kt`
- `hud-protocol/src/main/java/hud/protocol/OverlayLayout.kt` (-2) -> `hud-protocol/src/commonMain/kotlin/hud/protocol/OverlayLayout.kt`
- `app/src/test/java/nav/offline/OfflineRideCheckTest.kt` (-2) -> `shared/src/commonTest/kotlin/nav/offline/OfflineRideCheckTest.kt`
- `app/src/test/java/data/model/GpsPowerPolicyTest.kt` (-2) -> `shared/src/commonTest/kotlin/data/model/GpsPowerPolicyTest.kt`
- `app/src/test/java/audio/FiringScheduleTest.kt` (-2) -> `shared/src/commonTest/kotlin/audio/FiringScheduleTest.kt`
- `app/src/test/java/ui/navigator/RouteBuilderInitOrderTest.kt` (-2) -> `shared/src/androidUnitTest/kotlin/ui/navigator/RouteBuilderInitOrderTest.kt`
- `app/src/test/java/ble/BleProfileTest.kt` (-2) -> `shared/src/androidUnitTest/kotlin/ble/BleProfileTest.kt`
- `app/src/main/java/service/VoiceReportPlan.kt` (-2) -> `shared/src/commonMain/kotlin/service/VoiceReportPlan.kt`
- `app/src/main/java/data/store/OverlayPresetJson.kt` (-2) -> `shared/src/commonMain/kotlin/data/store/OverlayPresetJson.kt`
- `app/src/main/java/data/model/NavModels.kt` (-2) -> `shared/src/commonMain/kotlin/data/model/NavModels.kt`
- `app/src/main/java/audio/EngineSynth.kt` (-2) -> `shared/src/commonMain/kotlin/audio/EngineSynth.kt`
- `app/src/main/java/ui/dashboard/DashboardRadarMini.kt` (-2) -> `shared/src/commonMain/kotlin/ui/dashboard/DashboardRadarMini.kt`
- `app/src/main/java/data/model/AppSettings.kt` (-2) -> `shared/src/commonMain/kotlin/data/model/AppSettings.kt`
- `app/src/main/java/nav/offline/RouterPlan.kt` (-1) -> `shared/src/commonMain/kotlin/nav/offline/RouterPlan.kt`
- `app/src/main/java/data/model/GpsPowerPolicy.kt` (-1) -> `shared/src/commonMain/kotlin/data/model/GpsPowerPolicy.kt`
- `app/src/main/java/ble/InMotionV1Adapter.kt` (-1) -> `shared/src/commonMain/kotlin/ble/InMotionV1Adapter.kt`
- `app/src/main/java/ble/BegodeParser.kt` (-1) -> `shared/src/commonMain/kotlin/ble/BegodeParser.kt`
- `app/src/main/java/service/hud/HudDemoSource.kt` (-1) -> `shared/src/commonMain/kotlin/service/hud/HudDemoSource.kt`
- `app/src/main/java/ble/virtual/V14VirtualWheel.kt` (-1) -> `shared/src/commonMain/kotlin/ble/virtual/V14VirtualWheel.kt`
- `app/src/main/java/ble/NinebotParser.kt` (-1) -> `shared/src/commonMain/kotlin/ble/NinebotParser.kt`
- `app/src/main/java/ble/NinebotAdapter.kt` (-1) -> `shared/src/commonMain/kotlin/ble/NinebotAdapter.kt`
- `app/src/main/java/share/ShareLocationText.kt` (-1) -> `shared/src/commonMain/kotlin/share/ShareLocationText.kt`
- `app/src/main/java/ble/InMotionV1Protocol.kt` (-1) -> `shared/src/commonMain/kotlin/ble/InMotionV1Protocol.kt`
- `app/src/main/java/ui/common/BluetoothEnabler.kt` (-1) -> `shared/src/androidMain/kotlin/ui/common/BluetoothEnabler.android.kt`
- `app/src/test/java/tpms/TpmsScanKindTest.kt` (-1) -> `shared/src/androidUnitTest/kotlin/tpms/TpmsScanKindTest.kt`
- `app/src/test/java/audio/EngineRideRenderTest.kt` (-1) -> `shared/src/androidUnitTest/kotlin/audio/EngineRideRenderTest.kt`
- `app/src/main/java/ui/theme/AppThemeColors.kt` (-1) -> `shared/src/commonMain/kotlin/ui/theme/AppThemeColors.kt`
- `app/src/main/java/service/hud/engo/EngoLayout.kt` (-1) -> `shared/src/commonMain/kotlin/service/hud/engo/EngoLayout.kt`
- `app/src/main/java/service/AlarmWheelPass.kt` (-1) -> `shared/src/commonMain/kotlin/service/AlarmWheelPass.kt`
- `app/src/main/java/nav/offline/OfflineWarmUp.kt` (-1) -> `shared/src/commonMain/kotlin/nav/offline/OfflineWarmUp.kt`
- `app/src/main/java/data/sync/FolderConflicts.kt` (-1) -> `shared/src/commonMain/kotlin/data/sync/FolderConflicts.kt`
- `app/src/main/java/data/repository/WheelChoice.kt` (-1) -> `shared/src/commonMain/kotlin/data/repository/WheelChoice.kt`
- `app/src/main/java/ui/theme/PlatformUnsupportedBadge.kt` (-1) -> `shared/src/commonMain/kotlin/ui/theme/PlatformUnsupportedBadge.kt`

## App wiring and tests changed in place

- `app/src/main/java/MainActivity.kt` (-4): replaced lines only, the same calls gain `viewModel =` and `host =` arguments (trailing comma)
- `app/src/main/java/ui/navigation/NavGraph.kt` (-25): replaced lines gain `viewModel =`/`host =` (Android hosts per screen); the metric route parsing moved to `shared/.../ui/dashboard/MetricDetailRoute.parse`
- `app/src/main/java/ui/common/ExperimentalBannerState.kt` (-2): now implements the shared `ExperimentalBannerFlag`
- `app/src/main/java/ui/common/ExperimentalBanner.kt` (-1): parameter type is the shared `ExperimentalBannerFlag`
- `app/src/main/java/di/AppModule.kt` (-5): `SettingsStore` and `LegalLockdownStore` now take a DataStore (`provideSettingsStore(context, dataStore)`, `single<LockdownStore>`)
- `app/src/main/java/di/BleModule.kt` (-19): the six wheel adapters and `CompositeWheelAdapter` are registered in `shared/.../di/SharedKoin.kt`; `BleConnectionManager` is now bound as `WheelLink`
- `app/src/main/java/di/CrewsModule.kt` (-2): `PairApi(HttpClient())`, the shared HTTP facade (OkHttp underneath, `shared/src/androidMain/.../platform/Http.android.kt`)
- `app/src/main/java/di/DataModule.kt` (-10): same singletons, now with interface binds (`LegalLockdownGate`, `OverlayPresets`, `ThemeFiles`, `AccelSplitReset`) and explicit constructors (`TripMeterStore`, `DropboxRepository`, `PhoneSensorRepository`)
- `app/src/main/java/di/EucStatsModule.kt` (-4): the Koin `OkHttpClient` single is gone; `EucStatsApi(HttpClient())` uses the shared HTTP facade; nothing else injected that single (grep)
- `app/src/main/java/di/ExternalGpsModule.kt` (-6): same singletons bound as `ExternalGpsLink`/`ExternalGpsScan`; repository constructor rewritten for the shared class
- `app/src/main/java/di/HudModule.kt` (-3): `HudUdpListener` bound as `HudBeacons`, `EngoAdapter` as `EngoGlasses`
- `app/src/main/java/di/NavModule.kt` (-4): `offlineTileStore(ctx)` helper, `OfflineRouter.brouterEngine(store.dir, AndroidBrouterProfiles(...))`, `OfflineTiles(..., WorkManagerTileJobs(...))`, `ShareSession` bound as `LiveShare`
- `app/src/main/java/di/RadarModule.kt` (-2): same singletons bound as `RadarLink`/`RadarScan`
- `app/src/main/java/di/ServiceModule.kt` (-14): `AutomationManager(...)` with the Android media part, `TonePlayer` bound as `RouteKeepAlive`/`CommandCues`, `singleOf(::VoiceCommandController)`, `AppNotifier`/`CheatState`/`HornPlayer`/`ExperimentalBannerState` bound to shared interfaces, `EngineSoundEngine(get(), EngineAudioTrack.rawBank(...))`, `ThemeController(get(), get()) {...}`
- `app/src/main/java/di/TpmsModule.kt` (-1): same line plus `bind TyrePressureFeed::class`
- `app/src/main/java/di/ViewModelModule.kt` (-6): `DataSourcesViewModel(...)`, `OfflineDataViewModel(...)` with explicit ports, `viewModelOf(::SettingsViewModel)` over the ports in `ui/settings/SettingsPortsAndroid.kt`
- `app/src/test/java/LifecycleWiringTest.kt` (-4): the `calledElsewhere` check is rewritten to also look in `:shared` and through supertypes, not dropped
- `app/src/test/java/ble/ReconnectLowPowerTest.kt` (-3): same asserts on `WheelReconnect.lowPowerDue`
- `app/src/test/java/service/hud/HudTileServerTest.kt` (-10): `onlyTileRequestsParse` moved to `shared/src/commonTest/.../service/hud/HudLinkRulesTest.kt` (`HudTileHttp.parse`, same five cases); note the new comment names a `HudTileHttpTest` that does not exist
- `app/src/test/java/data/eucstats/EucStatsRepositoryTest.kt` (-6): the five `isEditableOn` asserts now pass the date as a string
- `app/src/test/java/data/eucstats/EucStatsApiTest.kt` (-8), `crews/PairApiTest.kt` (-7): clients built through the shared `HttpClient(...)` facade; same tests and asserts
- `app/src/test/java/data/eucstats/MetaBuilderTest.kt` (-1): time zone passed the shared way
- Source-reading tests retargeted to the moved files (same number of tests, asserts equal or more): `data/BackupStatusIconTest.kt` (-6), `data/DropboxEndpointTest.kt` (-1), `data/DropboxRateLimitTest.kt` (-16), `data/DropboxRefusalTest.kt` (-7), `data/FolderMirrorTest.kt` (-6), `data/RemoteEditWinsTest.kt` (-2), `data/TripNameTravelsTest.kt` (-4), `ui/SettingsShortcutsTest.kt` (-22), `ui/MapProvidersTest.kt` (-8), `ui/ChargingShortcutTest.kt` (-2), `ui/DropdownSingleLineTest.kt` (-1), `ui/TripTileRegistryTest.kt` (-1), `ui/navigator/NavMapTypeFallbackTest.kt` (-2), `ui/navigator/PeerMarkerContractTest.kt` (-2), `util/BatteryEnvelopeFollowsOverrideTest.kt` (-2), `util/BatteryEnvelopeTest.kt` (-1), `voice/VoicePhraseMatchTest.kt` (-3), `metrics/TorquePhaseMetricsTest.kt` (-1), `WheelLogCreditTest.kt` (-1), `crews/CrewPassLayoutTest.kt` (-1), `di/KoinGraphTest.kt` (-4), `weather/WeatherModuleTest.kt` (-5, `ExposedDropdownMenuBox` check now on `WeatherSettingsContent.kt`), `hud/src/test/.../StudioMetricKeysTest.kt` (-1)
- `R.string` ids to `Res.string` keys in tests (same asserts): `ui/settings/SettingsSearchCoverageTest.kt` (-17), `ui/settings/SettingsSearchIndexTest.kt` (-29), `voice/VoiceMusicLocalePhrasesTest.kt` (-13), `ui/dashboard/HeadlightButtonStateTest.kt` (-5), `ui/dashboard/WeatherNoFixTest.kt` (-5), `data/model/ActionCatalogKeysTest.kt` (-1), `data/model/SpeedSplitsActionTest.kt` (-1), `weather/WeatherFaceTest.kt` (-1)

## Method and depth

- Every removed non-comment line was matched against all added lines of the diff and the tip tree: exact matches first, then fuzzy ones (75% similarity after `R.` to `Res.`). The lines left over (about 1,700) were read by hand, and their destination was looked up with `git grep` at 5c8c255b0.
- Read line by line: WheelService, AutomationManager, the DI modules, MainActivity, NavGraph, every changed app test (test and assert counts compared before and after), the build files, DropboxSyncWorker, TripUploadWorker, EucStatsUploadWorker, ThemeStore, OverlayPresetStore, CrashHandler, LocaleHelper, AppHealthRepository, DropboxRepository, EucStatsApi, VoiceService, TonePlayer, FlicManager, BleConnectionManager, BleScanner, the radar and GPS managers, TpmsScanner, HornPlayer, the widgets, PairApi, the studio camera files, OfflineMapWeb (old and new script diffed), OfflineTiles and the WorkManager jobs.
- Spot-checked only (left-over lines and key symbols, not every hunk): SyncManager, TripRepository, SettingsScreen, SettingsViewModel, DashboardScreen, TripDetailScreen, RouteBuilderScreen, RouteBuilderViewModel, ShareDialog, HudServer, OverlayStudioScreen and its sheets and elements, the APNG and GIF encoders, VoiceCommandController, CrewsPairActivity, EngineSoundEngine, GrainBank and OfflineMapRenderer. In the "behind an interface" list, the swap kinds come from a pattern scan, and only the larger files had their leftover lines read.
- Live runs (rule 16): see the PR description for what was run on the Android emulator and the iOS simulators.
