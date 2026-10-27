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
| Dashboard | not yet | iOS shows a temporary probe screen (English only) with live speed and battery. |
| Scan / connect | not yet | The probe lists wheels and virtual wheels and connects. |
| Settings (all sections) | not yet | |
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
| Flic buttons | Android-only | |

## Features

| Feature | Status | Notes |
|---|---|---|
| Format, log, clock, charsets, CRC, local time facades | iOS actual done | `platform/`; JVM-printed format cases pass on the iOS simulator. |
| Wheel protocols (Begode, Veteran, Kingsong, InMotion V1/V2, Ninebot) | shared | Adapters, parsers, commands, write queue. Simulator: Begode, InMotion V1 and V2, KingSong virtual wheels decode on iOS. |
| Virtual wheels | shared | |
| Units | shared | `speedUnit(context)` stays in `:app` until strings move. |
| BLE link | iOS actual done (partial) | `WheelLink`: CoreBluetooth scan, connect, service re-routing, notify, paced writes. Not yet on iOS: auto-reconnect, RSSI. Not yet tested on a real wheel. |
| Settings storage | not yet | DataStore KMP. |
| Trip database | not yet | Room KMP. |
| Strings (23 locales) | not yet | Compose resources generated from `app/src/main/res`. |
| Voice alerts (TTS) | not yet | AVSpeechSynthesizer. |
| Alarms | not yet | |
| Trip recording, GPS | not yet | CoreLocation; `location` background mode is in Info.plist. |
| Background connection | not yet | `bluetooth-central` background mode is in Info.plist. |
| Motor sound | not yet | AVAudioEngine. |
| Notifications | not yet | UNUserNotificationCenter. |
| Backups (Dropbox), share, import | not yet | |
| eucstats leaderboards | not yet | Server checks Play Integrity; iOS needs App Attest support on the server. |
| Crews | not yet | |
| Weather | not yet | |
| Navigation | not yet | |
| TPMS, radar (Varia), RaceBox | not yet | Archive tag has iOS parsers to lift. |
| QR scan | not yet | |
| Android Auto | Android-only | Not planned for iOS, no CarPlay: it exists for the MotoEye E6, which connects through Android Auto. |
| Wear OS watch | Android-only | |
| HUD | Android-only | |
| Garmin, Amazfit watches | Android-only | |
| Home-screen widgets | Android-only | |
| Flic buttons | Android-only | |

## iOS-specific gaps

- Info.plist permission texts are English only; they need `InfoPlist.strings`
  for the 23 locales.
