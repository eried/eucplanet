# iOS parity

What the iPhone app does compared with the Android phone app, screen by
screen and feature by feature. Update the row in the same commit that changes
it. How code reaches iOS is in `docs/IOS_SYNC.md`.

Status values:

- **shared**: the code is in `:shared` and runs on both apps unchanged.
- **iOS actual done**: shared, plus the iOS side of its platform seam is written and tested.
- **Android-only**: deliberately not on iOS (for now). Hidden on iOS.
- **not yet**: still in `:app`, so iOS doesn't have it.

## Screens

| Screen | Status | Notes |
|---|---|---|
| Dashboard | not yet | iOS shows a temporary probe screen with live speed and battery. |
| Scan / connect | not yet | The probe lists wheels and virtual wheels and connects. |
| Settings (all sections) | not yet | The Dropbox block of Backup and the sync conflict dialog are shared and shown on a temporary iOS tab, with a read-out of the synced settings. |
| Metric detail | not yet | |
| Trip meter detail | not yet | |
| Recording (trip list) | not yet | |
| Trip detail (map, charts) | not yet | Leaflet map in a WebView: needs a WKWebView `expect`. |
| EUC viewer | not yet | Leaflet map in a WebView. |
| Route builder | not yet | Leaflet map in a WebView. |
| Charging monitor | not yet | |
| Overlay studio | not yet | Camera recording needs an AVFoundation side. |
| Legal lockdown | not yet | |
| Navigator | not yet | |
| Radar | not yet | |
| Onboarding | not yet | |
| Flic buttons | Android-only | iOS later via the Flic iOS SDK. |

## Features

| Feature | Status | Notes |
|---|---|---|
| Format, log, clock, charsets, CRC, local time facades | iOS actual done | `platform/`; JVM-printed format cases pass on the iOS simulator. |
| Wheel protocols (Begode, Veteran, Kingsong, InMotion V1/V2, Ninebot) | shared | Adapters, parsers, commands, write queue. Simulator: Begode, InMotion V1 and V2, KingSong virtual wheels decode on iOS. |
| Virtual wheels | shared | |
| Units | shared | `speedUnit(context)` stays in `:app` for Android-only callers. |
| BLE link | iOS actual done (partial) | `WheelLink`: CoreBluetooth scan, connect, service re-routing, notify, paced writes. Not yet on iOS: auto-reconnect, RSSI. Not yet tested on a real wheel. |
| Settings storage | iOS actual done | Same JSON blob in DataStore (`SettingsStore`, `SettingsRepository`, `SettingsJson`). First-run units on iOS follow the phone's region alone; Android also checks the cell network. |
| Trip database | not yet | Room KMP. |
| Strings (23 locales) | iOS actual done | Compose resources generated from `app/src/main/res` (docs/IOS_SYNC.md). Latin American Spanish still reads Spain's Spanish on iOS: the es-419 lookup is not wired yet. In-app language picker not on iOS; it follows the iPhone's language. |
| Voice alerts (TTS) | not yet | AVSpeechSynthesizer. |
| Alarms | not yet | Rules sync with settings.json and are kept on iOS in a JSON file until the database moves; nothing fires them on iOS yet. |
| Trip recording, GPS | not yet | CoreLocation; `location` background mode is in Info.plist. |
| Background connection | not yet | `bluetooth-central` background mode is in Info.plist. |
| Motor sound | not yet | AVAudioEngine. |
| Notifications | not yet | UNUserNotificationCenter. |
| Backups (Dropbox), share, import | iOS actual done (partial) | Dropbox link and settings.json both ways (settings and alarm rules, same three-way check and conflict question). Not yet on iOS: trips, themes and overlays folders, background sync, share, import, backup folder. Linking not yet tried with a real account on iOS. |
| eucstats leaderboards | not yet | Server checks Play Integrity; iOS needs App Attest support on the server. |
| Crews | not yet | |
| Weather | not yet | |
| Navigation | not yet | |
| TPMS, radar (Varia), RaceBox | not yet | Archive tag has iOS parsers to lift. |
| QR scan | not yet | |
| Android Auto | Android-only | Not planned for iOS, no CarPlay: it exists for the MotoEye E6, which connects through Android Auto. |
| Wear OS watch | Android-only | iOS counterpart later: a watchOS app over WatchConnectivity (archive tag has a start). |
| HUD | Android-only | The HUD itself runs Android. The phone side talks Wi-Fi, so it can come to iOS later (Local Network permission, Bonjour discovery; archive tag has a start). First in line. |
| Garmin, Amazfit watches | Android-only | Garmin later via the Connect IQ iOS SDK. Amazfit unsure: the bridge is a local server on the phone. |
| Home-screen widgets | not yet | iOS as a WidgetKit extension (Swift, not shared code). |
| Flic buttons | not yet | iOS via the Flic iOS SDK (free, Swift Package). Needs the wheel actions in shared code first. |
| Persistent ride notification | iOS actual done (partial) | A Live Activity (lock screen and Dynamic Island) while a wheel is connected, with the wheel's name over the same line as the Android notification (`RideNotificationText`), at most once a second. Swift side: `iosApp/RideLiveActivity.swift` and the `EucPlanetWidgets` extension. Not yet: the quick-action buttons and the navigation line. Checked on the simulator with a virtual wheel. |

## iOS-specific gaps

- Info.plist permission texts are English only; they need `InfoPlist.strings`
  for the 23 locales.
