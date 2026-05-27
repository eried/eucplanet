# EUC Planet

[![Latest release](https://img.shields.io/github/v/release/eried/eucplanet)](https://github.com/eried/eucplanet/releases)
[![License: MIT](https://img.shields.io/github/license/eried/eucplanet)](LICENSE)
[![Google Play](https://img.shields.io/badge/Google_Play-EUC_Planet-3DDC84?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=com.eried.eucplanet)
[![Telegram](https://img.shields.io/badge/Telegram-EUCPlanetApp-26A5E4?logo=telegram&logoColor=white)](https://t.me/EUCPlanetApp)
[![Downloads](https://img.shields.io/github/downloads/eried/eucplanet/total)](https://github.com/eried/eucplanet/releases)

An open-source Android companion app for **electric unicycles**. Live telemetry, turn-by-turn navigator, on-screen telemetry overlay for video recording, and a Wear OS dial.

Built because every other EUC app either asks for a monthly subscription, ships a crummy UI, loses connection, or locks useful features behind paywalls. This one is free, open source, no ads, no tracking, no upselling, no subscriptions, no telemetry phoned home, no nonsense.

> **Wheel support tiers:**
>
> | Tier | Wheels | What it means |
> |---|---|---|
> | **Verified** | InMotion V14 50GB / 50S, P6 | Author's daily wheel + telemetry/controls confirmed against labelled real-hardware captures |
> | **Preliminary** | InMotion V12 HS / HT / Pro | Parser exists, not yet author-tested |
> | **Preview** | KingSong S22 / S20 / S19 / S18 / S16 / KS-14/16/18 / F18P / F22P | Telemetry + commands implemented from the public protocol; needs a real-hardware tester |
> | **Preview** | Begode/Gotway Master / Master Pro / T3 / T4 / RS / RS-HT / EX / EX.N / EX2 / MSP / MSX / Hero / XWay / Mten4 / Mten5 / MCM5 | same; high-voltage tiltback (>100 km/h) handled since v0.6.2 |
> | **Preview** | Veteran Sherman / Sherman S / Sherman Max / Patton / Lynx / Abrams | same |
> | **Preview** | InMotion V1 family: V5 / V8 / V8F / V8S / V10 / V10F / V10S / V10T / V10FT / L6 / Lively / Glide 3 | same |
> | **Preview** | Ninebot Z6 / Z10, plus legacy One E / E+ / S2 / Mini (read-only) | same; Ninebot Z uses the documented XOR keystream encryption |
> | **Experimental** | InMotion V11, V13, V9 | In the model registry; please file a wheel report if you try them |
> | Not yet | Onewheel and other non-EUC vehicles | Different protocol family, out of scope |
>
> Preview wheels are implemented from the spec docs in [`docs/protocols/`](docs/protocols/) (KingSong, Begode, Veteran, InMotion V1, InMotion V2 / V14 / V12 / P6, Ninebot) but have not yet been tested against the actual hardware. If your wheel is in this tier and you can ride it, please connect through the app and file a wheel report via the orange in-app banner, telemetry verification is the fastest path to upgrading the tier.
>
> Want to help add a wheel that's not on the list? See the [BLE capture guide](docs/BLE_CAPTURE_GUIDE.md), record one labelled riding session and we can usually map it in a single pass.

---

## Gallery

<img src="docs/screenshots/dashboard.png" width="22%" alt="Dashboard" /> <img src="docs/screenshots/navigator.png" width="22%" alt="Turn-by-turn navigator" /> <img src="docs/screenshots/studio.png" width="22%" alt="Overlay Studio" /> <img src="docs/screenshots/compare.png" width="22%" alt="Compare" />

<img src="docs/screenshots/trips.png" width="22%" alt="Trip list" /> <img src="docs/screenshots/studio-export.png" width="22%" alt="Studio transparent export" /> <img src="docs/screenshots/navigator-sat.png" width="22%" alt="Navigator over satellite" /> <img src="docs/screenshots/settings.png" width="22%" alt="Settings" />

<img src="docs/screenshots/wear-dash.png" width="16%" alt="Wear OS dial" /> <img src="docs/screenshots/wear-details.png" width="16%" alt="Wear OS telemetry detail" /> <img src="docs/screenshots/wear-navigation.png" width="16%" alt="Wear OS turn arrow" /> <img src="docs/screenshots/wear-goal.png" width="16%" alt="Wear OS arrival flag" />

---

## Features

### Dashboard
- Live speed, battery %, voltage, amps, temperature, PWM load, trip distance.
- Tap any tile to jump to a historical graph.
- Imperial or metric units.

### Turn-by-turn navigator
- Multi-stop route builder on a map (walk / bike / car / straight-line modes).
- Live guidance with voice cues, off-route reroute, sticky GPS, and a Treasure Hunt proximity mode for unmarked destinations.
- Optional Wear OS popup mirror so the next-turn arrow shows up on your wrist.

### Overlay Studio
- Records video and stills with a customisable on-screen telemetry overlay: dials, gauges, rolling graphs, `{speed}`-style text, mini-map, layered cameras.
- Save layouts as JSON presets. Export plain MP4 for the final clip, or transparent overlay for compositing on top of footage from another camera.

### Wheel Control
- Horn, light toggle, wheel lock, legal-mode speed cap, voice announcements, all one tap away.
- **Legal Mode**: configurable speed cap that reprograms the wheel's tiltback + alarm speeds temporarily, then restores your normals when you turn it off.

### Custom Alarms
- Define your own threshold-based alarms on speed, battery, temperature, PWM, voltage, current.
- Each alarm can independently fire a beep (custom tone + pitch), a TTS voice message (template-based, e.g. `"Battery at {value}%"`), and/or vibration.
- Cooldown and repeat-while-active settings so alarms don't spam.

### Voice Announcements
- Periodic reports at a configurable interval.
- Configurable TTS speech rate and locale (multilingual TTS supported).
- Special event announcements: lock/unlock, lights on/off, GPS fix, connection, legal mode, recording start/stop.

### Trip Recording
- GPS + telemetry logged to **DarknessBot-compatible CSV**.
- Auto-record.
- Live map preview of the recorded track.
- Trip list with quick export and share.

### Automations
- **Auto Lights**: turn lights on before sunset and off after sunrise, based on live GPS location. Handles midnight sun and polar night (I live in the arctic circle 🧐).
- **Auto Volume**: phone volume changes based on speed.

### Integrations
- **Flic 2 buttons**: pair up to two buttons.
- **Volume keys**: use the phone's physical volume up/down for extra shortcuts.
- **External GPS**: pair a RaceBox (or any compatible BLE GPS) for centimetre-class speed and altitude without burning the phone radio. Falls back to phone GPS automatically.
- **Wear OS companion**: full-bleed speed dial, three batteries (wheel/phone/watch), accent and unit settings synced from the phone, horn + light remote controls, navigation cue mirror. Tested on Galaxy Watch Ultra; works on any Wear OS 5+ watch.
- **Garmin companion** (preview): same dial on Garmin Connect IQ watches and Edge bike computers. Shares the rider's Watch settings with the Wear OS companion (both surfaces read the same fields), so flipping a toggle in Settings → Watch applies to both wrists. See [`docs/GARMIN_SETUP.md`](docs/GARMIN_SETUP.md) to build and sideload the watch app today; Connect IQ Store release pending real-device testing.

---

## Requirements

- Android 10 (API 29) or newer.
- A supported wheel (see the support-tier table at the top of this README). The V14 and P6 are the most thoroughly tested today; KingSong / Begode / Veteran / InMotion V1 / Ninebot wheels work in preview and need community testing to graduate to "verified".
- Bluetooth + location permissions (location is required by Android for BLE scanning).
- Camera + microphone permission (optional): only requested the first time you open Overlay Studio.
- Wear OS companion (optional): Wear OS 5 or newer, paired through the Wear OS by Google app.

## Install

Grab the latest APK from the [releases](../../releases) page and sideload it, or build from source:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
---

## Why does this exist?

I got tired of:
- paying a monthly sub to talk to a wheel I already own,
- apps that look like they were built in 2014,
- waiting forever for fixes or functionalities,
- paying for basic stuff,
- apps with annoying alarms, not flexible enough or just badly designed,
- apps that silently lose BLE and you only notice when the wheel hits its internal tiltback at an unexpected speed,
- apps that treat your GPS trace as the vendor's property.

## Contributing

The BLE protocol layer is separate from the UI: each brand family has its own `WheelAdapter` in [`app/src/main/java/com/eried/eucplanet/ble/`](app/src/main/java/com/eried/eucplanet/ble/), and `CompositeWheelAdapter` routes by the BLE-advertised name at connect time. Spec docs live under [`docs/protocols/`](docs/protocols/).

To add a new wheel: write a parser + commands + adapter that implement `WheelAdapter`, register it in `CompositeWheelAdapter`, add the BLE-name pattern to `BleScanner`. The fastest path is the [BLE capture guide](docs/BLE_CAPTURE_GUIDE.md); one labelled riding session is usually enough.

PRs welcome. Bug reports → [GitHub Issues](../../issues). Feature ideas and votes → [ideas.ried.no/euc-planet](https://ideas.ried.no/euc-planet).

## License

MIT. The Flic 2 SDK and any third-party dependencies retain their own licenses.
