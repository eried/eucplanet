# EUC Planet

[![Latest release](https://img.shields.io/github/v/release/eried/eucplanet)](https://github.com/eried/eucplanet/releases)
[![License: MIT](https://img.shields.io/github/license/eried/eucplanet)](LICENSE)
[![Google Play](https://img.shields.io/badge/Google_Play-EUC_Planet-3DDC84?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=com.eried.eucplanet)
[![Telegram](https://img.shields.io/badge/Telegram-EUCPlanetApp-26A5E4?logo=telegram&logoColor=white)](https://t.me/EUCPlanetApp)
[![Downloads](https://img.shields.io/github/downloads/eried/eucplanet/total)](https://github.com/eried/eucplanet/releases)

An open-source Android companion app for **electric unicycles**, with a Wear OS companion for Galaxy Watch Ultra and other Wear OS 5+ watches.

Built because every other EUC app either asks for a monthly subscription, ships a crummy UI, loses connection, or locks useful features behind paywalls. This one is free, does the things I actually want while riding, and doesn't phone home.

> **Wheel support tiers:**
>
> | Tier | Wheels | What it means |
> |---|---|---|
> | **Verified** | InMotion V14 50GB / 50S, P6 | Author's daily wheel + telemetry/controls confirmed against labelled real-hardware captures |
> | **Preliminary** | InMotion V12 HS / HT / Pro | Parser exists, not yet author-tested |
> | **Preview** | KingSong S22 / S20 / S19 / S18 / S16 / KS-14/16/18 / F18P / F22P | Telemetry + commands implemented from the public protocol; needs a real-hardware tester |
> | **Preview** | Begode/Gotway Master / Master Pro / T3 / T4 / RS / RS-HT / EX / EX.N / EX2 / MSP / MSX / Hero / Mten4 / Mten5 / MCM5 | same |
> | **Preview** | Veteran Sherman / Sherman S / Sherman Max / Patton / Lynx / Abrams | same |
> | **Preview** | InMotion V1 family: V5 / V8 / V8F / V8S / V10 / V10F / V10S / V10T / V10FT / L6 / Lively / Glide 3 | same |
> | **Preview** | Ninebot Z6 / Z10, plus legacy One E / E+ / S2 / Mini (read-only) | same; Ninebot Z uses the documented XOR keystream encryption |
> | **Experimental** | InMotion V11, V13, V9 | In the model registry; please file a wheel report if you try them |
> | Not yet | Onewheel and other non-EUC vehicles | Different protocol family, out of scope |
>
> Preview wheels are implemented from the spec docs in [`docs/protocols/`](docs/protocols/) (KingSong, Begode, Veteran, InMotion V1, Ninebot) but have not yet been tested against the actual hardware. If your wheel is in this tier and you can ride it, please connect through the app and file a wheel report via the orange in-app banner — telemetry verification is the fastest path to upgrading the tier.
>
> Want to help add a wheel that's not on the list? See the [BLE capture guide](docs/BLE_CAPTURE_GUIDE.md) — record one labelled riding session and we can usually map it in a single pass.

---

## Gallery

<img src="docs/screenshots/main.png" width="22%" alt="Dashboard" /> <img src="docs/screenshots/customalarms.png" width="22%" alt="Custom alarms" /> <img src="docs/screenshots/trips.png" width="22%" alt="Historical graph" /> <img src="docs/screenshots/tripdetails.png" width="22%" alt="Trip detail" />

<img src="docs/screenshots/historicalcurrent.png" width="14%" alt="Trip list" /> <img src="docs/screenshots/voicesettings.png" width="14%" alt="Voice settings" /> <img src="docs/screenshots/autolights.png" width="14%" alt="Auto lights" /> <img src="docs/screenshots/autovolume.png" width="14%" alt="Auto volume" /> <img src="docs/screenshots/speedsettings.png" width="14%" alt="Speed settings" /> <img src="docs/screenshots/settings.png" width="14%" alt="Settings" />

---

## Features

### Dashboard
- Live speed, battery %, voltage, amps, temperature, PWM load, trip distance.
- Tap any tile to jump to a historical graph.
- Imperial or metric units.

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
- **Wear OS companion**: full-bleed speed dial, three batteries (wheel/phone/watch), accent and unit settings synced from the phone, horn + light remote controls. Tested on Galaxy Watch Ultra; works on any Wear OS 5+ watch.

---

## Requirements

- Android 10 (API 29) or newer.
- A supported wheel (see the support-tier table at the top of this README). The V14 and P6 are the most thoroughly tested today; KingSong / Begode / Veteran / InMotion V1 / Ninebot wheels work in preview and need community testing to graduate to "verified".
- Bluetooth + location permissions (location is required by Android for BLE scanning).
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

The BLE protocol layer is separate from the UI: each brand family has its own `WheelAdapter` implementation in [`app/src/main/java/com/eried/eucplanet/ble/`](app/src/main/java/com/eried/eucplanet/ble/), and `CompositeWheelAdapter` routes connect-time by the BLE-advertised name. Spec docs for each family live under [`docs/protocols/`](docs/protocols/). To add a new wheel: write a parser + commands + adapter that implement `WheelAdapter`, register it in `CompositeWheelAdapter`, add the BLE-name pattern to `BleScanner`. The fastest path is the [BLE capture guide](docs/BLE_CAPTURE_GUIDE.md) — record a single labelled riding session and we can usually map a new wheel in one pass. PRs welcome. Bug reports go on [GitHub Issues](../../issues); feature ideas and votes live on the community board at [ideas.ried.no/euc-planet](https://ideas.ried.no/euc-planet).

## License

TBD (likely MIT). The Flic 2 SDK and any third-party dependencies retain their own licenses.
