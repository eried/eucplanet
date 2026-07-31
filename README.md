# EUC Planet

[![Latest release](https://img.shields.io/github/v/release/eried/eucplanet)](https://github.com/eried/eucplanet/releases)
[![License: MIT](https://img.shields.io/github/license/eried/eucplanet)](LICENSE)
[![Google Play](https://img.shields.io/badge/Google_Play-EUC_Planet-3DDC84?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=com.eried.eucplanet)
[![Garmin Connect IQ](https://img.shields.io/badge/Connect_IQ-EUC_Planet-007CC3?logo=garmin&logoColor=white)](https://apps.garmin.com/apps/14c2d086-fcb5-4042-bd5b-034519d18a71)
[![Telegram](https://img.shields.io/badge/Telegram-EUCPlanetApp-26A5E4?logo=telegram&logoColor=white)](https://t.me/EUCPlanetApp)
[![Leaderboard](https://img.shields.io/badge/Leaderboard-eucstats.ried.no-FF8F00)](https://eucstats.ried.no/)
[![Trip Viewer](https://img.shields.io/badge/Trip_Viewer-eucviewer.ried.no-2b6fd6)](https://eucviewer.ried.no/)
[![Donate](https://img.shields.io/badge/Donate-PayPal-00457C?logo=paypal&logoColor=white)](https://www.paypal.com/donate/?hosted_button_id=AEB2RPZHNRTKG)
[![Downloads](https://img.shields.io/github/downloads/eried/eucplanet/total)](https://github.com/eried/eucplanet/releases)

Open-source Android app for electric unicycles: live telemetry, turn-by-turn
navigation, an on-screen overlay for video recording, and many other integrations.
I don't want to pay to just enjoy a wheel I already own. No ads, no in-app
tracking, no subscriptions, no upselling, no features behind a paywall, no
telemetry phoned home.

## What wheels work?

I only own and ride a V14. Everything else gets done through collaboration with
riders who have the wheel.

| Status | Wheels |
|---|---|
| **Verified** | InMotion V14 (50GB / 50S) |
| **Verified** | InMotion P6 |
| **Rider-tested** | LeaperKim Lynx S, Oryx · Begode/Gotway Mten3, EX30, E20 · KingSong KS-16X |
| **In test** | Begode/Gotway Master, Master Pro, T3, T4, RS, RS-HT, EX, EX.N, EX2, MSP, MSX, Hero, XWay, Mten4, Mten5, MCM5 |
| **In test** | LeaperKim Sherman, Sherman S, Sherman Max, Patton, Lynx, Abrams |
| **In test** | KingSong S22, S20, S19, S18, S16, KS-14/16/18, F18P, F22P |
| **Waiting to be tested** | InMotion V12 HS / HT / Pro |
| **Waiting to be tested** | InMotion V1 family: V5, V8, V8F, V8S, V10, V10F, V10S, V10T, V10FT, L6, Lively, Glide 3 |
| **Waiting to be tested** | Ninebot Z6, Z10, plus legacy One E / E+ / S2 / Mini (read-only) |
| **Experimental** | InMotion V9, V11, V13 |

Help with your wheel, check the [BLE capture guide](docs/BLE_CAPTURE_GUIDE.md).
Already connects but a reading looks wrong? See the [in-app diagnostics guide](docs/DIAGNOSTICS_GUIDE.md).

---

## Gallery

<img src="docs/screenshots/dashboard.png" width="22%" alt="Dashboard" /> <img src="docs/screenshots/navigator.png" width="22%" alt="Turn-by-turn navigator" /> <img src="docs/screenshots/studio.png" width="22%" alt="Overlay Studio" /> <img src="docs/screenshots/compare.png" width="22%" alt="Compare" />

<img src="docs/screenshots/trips.png" width="22%" alt="Trip list" /> <img src="docs/screenshots/studio-export.png" width="22%" alt="Studio transparent export" /> <img src="docs/screenshots/navigator-sat.png" width="22%" alt="Navigator over satellite" /> <img src="docs/screenshots/settings.png" width="22%" alt="Settings" />

<table>
<tr>
<td align="center"><img src="docs/screenshots/wear-dash.png" width="120" alt="Wear OS dial" /><br><sub>Speed dial</sub></td>
<td align="center"><img src="docs/screenshots/wear-details.png" width="120" alt="Wear OS telemetry detail" /><br><sub>Telemetry</sub></td>
<td align="center"><img src="docs/screenshots/wear-navigation.png" width="120" alt="Wear OS turn arrow" /><br><sub>Navigation</sub></td>
<td align="center"><img src="docs/screenshots/wear-goal.png" width="120" alt="Wear OS arrival flag" /><br><sub>Arrival flag</sub></td>
</tr>
</table>

---

## Features

**Dashboard.** Live speed, battery, voltage, amps, temperature, PWM load and
distance. Rearrange the tiles, build composite tiles and action groups, add a live
runtime clock. Tap any tile for its history graph. Metric or imperial.

**Screen geometry.** Every screen adapts to the device it runs on: a portrait
glance-view, a landscape three-column layout for bar mounts, roomier spacing on
tablets, and a tiny cover-screen mode for foldables (the Galaxy Z Flip outer
display and friends) with a compact speedo and sideways metric and button pages.
Rotation is per-screen, so the dashboard can stay portrait while the map goes
full landscape.

**Turn-by-turn navigator.** Multi-stop routes on a map (walk, bike, car,
straight-line), voice cues, off-route reroute, sticky GPS, and a Treasure Hunt
proximity mode for unmarked spots. Charging stations (connectors, power, cost,
ratings and photos) and other points of interest along the way (food, shops,
rest stops) sit right on the map and refresh as you pan and zoom. Full Path
mode lets you tap any point along the route to add a detour (skip sand, gravel,
the wrong side of a one-way). The next-turn arrow can mirror to your watch,
HUD, or Garmin Edge.

**Overlay Studio.** Record video and stills with a customisable telemetry overlay:
dials, gauges, rolling graphs, `{speed}`-style text, mini-map, layered cameras. Save
layouts as JSON. Export a finished MP4, or a transparent overlay to composite over
footage from another camera. It's not only for recording and replay: the same
editor designs your **MotoEye / Android HUD** layout, so the visor shows exactly
the tiles and gauges you arranged.

**Charging monitor.** A live charge curve with a scrubbable prediction line,
energy split into used and charged, and a per-cell BMS view (Cells tab) for
smart-BMS wheels (Veteran, KingSong and others) showing individual cell voltages,
pack imbalance and temperatures so you can spot a weak cell early.

**Wheel control.** Horn, lights, lock, voice announcements, all one tap away. Legal
Mode temporarily reprograms the wheel's tiltback and alarm speeds to a cap you set,
then restores your normal settings when you switch it off.

**Custom alarms.** Your own thresholds on speed, battery, temperature, PWM, voltage
or current. Each can beep (custom tone and pitch), speak (`"Battery at {value}%"`),
and/or vibrate, with cooldowns so they don't nag. Predictive triggers warn you up
to 3 seconds ahead, and a most-severe-per-metric engine keeps a louder alarm from
eating a quieter one.

**Voice announcements.** Periodic reports at your interval, configurable rate and
a per-language voice picker, plus event callouts: lock/unlock, lights, GPS fix,
connection, legal mode, recording.

**Trip recording.** GPS and telemetry to DarknessBot-compatible CSV, auto-record,
live track preview, and a trip list with quick export and share. Trips are
recovered even if the app is closed mid-ride. View them later in
the [web Trip Viewer](https://github.com/eried/eucviewer), or opt in to share them on
the [EUC Stats leaderboard](https://eucstats.ried.no/) and rank your distance against
riders worldwide, by country.

**Backup & sharing, on your terms.** Every ride is a plain CSV file on your own
device, nothing is uploaded by default. Optional Dropbox sync mirrors your trips,
settings, themes and overlays into your own private folder so you can restore them
on another phone, and you can hand a single ride out as a link when you want to.
You decide what leaves the phone, where it goes, and who sees it.

**Automations.** Auto Lights on before sunset, off after sunrise, from live GPS.
Handles midnight sun and polar night (I live in the arctic circle 🧐). Auto Volume
scales phone volume with speed.

**Helmet HUD.** Sideload the small HUD companion on a MotoEye E6 or any
Android-based head-up display and the dashboard mirrors live to your visor
over local WiFi. New Map + Nav combined screen in the HUD shows the route
arrow on top of a moving map (Garmin Edge style). See
[eucplanet.ried.no/hud](https://eucplanet.ried.no/hud/).

**Garmin Edge / watches.** A Connect IQ data field shows live EUC speed,
battery, PWM, current and motor temperature on your bar-mounted Edge or
on your wrist. Supports Edge 530/540/830/840/1030/1040/1050 and most modern
Garmin watches (135+ devices). Get it on the
[Connect IQ Store](https://apps.garmin.com/apps/14c2d086-fcb5-4042-bd5b-034519d18a71),
or build it yourself from [docs/GARMIN_SETUP.md](docs/GARMIN_SETUP.md).

**Varia rear-view radar.** Pair a Garmin Varia RTL515 or RCT715 and see
approaching vehicles on the dashboard. Wire custom alarms (beep, voice, or
vibrate) to rear-vehicle distance and closing speed. Same sensor cyclists
already trust, now on your EUC.

**Integrations.** Flic 2 buttons (up to two), physical volume-key shortcuts,
external BLE GPS (RaceBox or compatible, for centimetre-class speed and altitude
without draining the phone radio; auto-falls back to phone GPS), and a Wear OS
companion (speed dial, three batteries, horn/light remotes, navigation mirror,
Touch / Physical buttons split; tested on Galaxy Watch Ultra, works on any
Wear OS 5+ watch).

**First-run tour & health.** A short Welcome tour walks new riders through the
key features. A top-bar warning chip surfaces any permissions you've denied
(BLE, location, notifications) with one-tap Fix actions, so a denial doesn't
silently break a feature later in the ride.

**Advanced settings.** A dedicated Advanced panel exposes the knobs behind every
feature: poll and refresh rates, chart history windows, HUD discovery timings,
screen geometry, alarm and charging tuning, and more. Each has a sensible default,
a valid range, and a one-tap restore, so you can tune deeply without breaking
anything.

**Multi-language support.** Full UI localisation, at parity across all of them.

---

## Recent changes

Latest work since **0.12.2** (unreleased, on the `next-version` branch, see the
[full history](../../commits/next-version)):

- **P6 power** now decodes: battery and motor power show on the dashboard instead
  of reading 0 W. ([`de099cc`](https://github.com/eried/eucplanet/commit/de099cc9219a62409134078ee8ab306fa2598ba7))
- **Odometer** history/detail now shows your distance unit (mi or km), matching the
  dashboard tile. ([`744c322`](https://github.com/eried/eucplanet/commit/744c322dfe48178a4b7f18dd81abc72f7b19e28a))
- **Navigator** keeps the full "Start navigation" label unless the layout is very
  narrow. ([`2bd37eb`](https://github.com/eried/eucplanet/commit/2bd37eb96b80a78214162d51b169abe0245d1f92))
- **Alarm beeps**: short tones under the audio buffer minimum now play, a 30 ms
  duration floor, and the constant-tone dialog smooths the tone (max transition,
  30 ms floor). ([`dfa91c4`](https://github.com/eried/eucplanet/commit/dfa91c4d54b091d043529dd0582a31a9c2b5239d), [`68d66ee`](https://github.com/eried/eucplanet/commit/68d66ee4dee5f1715e2d1d457054e0c4f1b9073e))
- **Trip view** remembers the map style you picked for the current app session.
  ([`2a06c67`](https://github.com/eried/eucplanet/commit/2a06c67ec7946b912feb2e3ea5123f07f6e3f8d1))

---

## Where do I get it?

[Google Play](https://play.google.com/store/apps/details?id=com.eried.eucplanet) has
a small symbolic price, treat it as a tip if you'd like to support the project and
get automatic updates. Or grab the latest APK from [releases](../../releases) for
free and sideload it. Same app either way.

On a Garmin watch or Edge, install the EUC Planet data field straight from the
[Connect IQ Store](https://apps.garmin.com/apps/14c2d086-fcb5-4042-bd5b-034519d18a71).

Build from source:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/phone-debug.apk
```

Needs Android 10 (API 29) or newer, a supported wheel, and Bluetooth + location
permissions (Android requires location for BLE scanning). Camera and mic are
optional and only asked for the first time you open Overlay Studio.

---

## Why does this exist?

I got tired of:

- apps that look like they were built in 2014,
- waiting forever for fixes or improvements,
- inflexible developers over just bad designs,
- apps that call home and spy on you.

## Contributing

The BLE layer is separate from the UI. Each brand family has its own `WheelAdapter`
in [`app/src/main/java/com/eried/eucplanet/ble/`](app/src/main/java/com/eried/eucplanet/ble/),
and `CompositeWheelAdapter` routes by the advertised BLE name at connect time. Specs
live in [`docs/protocols/`](docs/protocols/).

To add a wheel: implement `WheelAdapter` (parser plus commands), register it in
`CompositeWheelAdapter`, add the BLE-name pattern to `BleScanner`. The
[BLE capture guide](docs/BLE_CAPTURE_GUIDE.md) is the fast path; one labelled ride is
usually enough. If a supported wheel misbehaves instead, the
[in-app diagnostics guide](docs/DIAGNOSTICS_GUIDE.md) walks owners through sending a
Service Mode recording.

PRs welcome. Bugs go to [Issues](../../issues). Live discussion is on
[Telegram](https://t.me/EUCPlanetApp), and more serious ideas and votes go to
[ideas.ried.no/euc-planet](https://ideas.ried.no/euc-planet).

## Support

EUC Planet is free and stays free. If it saved you a subscription, or for any other
reason you want to chip in: [donate via PayPal](https://www.paypal.com/donate/?hosted_button_id=AEB2RPZHNRTKG).
Entirely optional, very appreciated.

## Acknowledgements

Thanks to the people and projects that helped, kept in sync with the app (tap version number → **Thanks**):

| Who | For |
| --- | --- |
| Gio (Wheel In Motion) | Promotion, suggestions and P6 testing |
| FlyboyEUC (Adam) | Mten3, E20 and EX30 testing |
| Soolek | KS-16X testing |
| Jonathan Wiesner | LeaperKim Lynx S testing |
| Felix K | LeaperKim Oryx testing |
| Bearkat713 | Motoeye E6 testing |
| Ilya Shkolnik | Advice and help. Maintains DarknessBot. |
| InMotion | For making a great V14 |

## License

Released under the [MIT License](LICENSE), use it, fork it, build on it, just keep
the copyright notice. The Flic 2 SDK and other third-party dependencies keep their
own licenses.
