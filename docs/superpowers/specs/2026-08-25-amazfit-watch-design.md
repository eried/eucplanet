# Amazfit (Zepp OS) watch companion, design

Third wrist surface for EUC Planet, next to Wear OS and Garmin Connect IQ.
The rider's existing Settings, Watch tab drives it; no new rider-facing
feature is added. Tester hardware: Amazfit T-Rex 3 (Zepp OS 4, 480 x 480
round, API level 4.0).

## Why a third transport, and which one

Zepp OS has no equivalent of the Connect IQ Mobile SDK: a third-party Android
app cannot message a watch mini program directly. What Zepp OS does offer is a
three-part mini program: a Device App on the watch, and a Side Service that
runs inside the official Zepp phone app and can make HTTP requests. The Zepp
Android app (10.7.3, checked against its network_security_config) permits
cleartext HTTP in its base config, so a Side Service `fetch` to `127.0.0.1`
reaches a server running in another app on the same phone.

Options considered:

1. **Side Service polling a loopback HTTP endpoint in EUC Planet.** Watch app
   asks its Side Service, Side Service does `GET http://127.0.0.1:<port>/state`,
   EUC Planet answers with the same snapshot the Garmin bridge builds. Works
   with the stock Zepp app, and the whole chain runs in the Zepp OS Simulator
   (whose Side Service runs on the PC, where `adb forward` maps the same port
   to the Android emulator). Chosen.
2. **Watch as BLE central to a GATT server on the phone.** Zepp OS 3+ has a
   BLE master API, but the phone has no GATT server today (it is only ever a
   BLE central), the simulator cannot exercise BLE, and Android peripheral mode
   needs a new permission and a new subsystem. Rejected for now; noted as the
   fallback if a future Zepp app build blocks loopback HTTP.
3. **Watch talks to the wheel directly.** Would need every wheel protocol on
   the watch. Out of scope.

## Architecture

```
  Amazfit watch                Zepp app (phone)             EUC Planet (phone)
  +-------------+   BLE msg   +----------------+  HTTP GET  +----------------+
  | Device App  | <---------> | Side Service   | ---------> | AmazfitBridge  |
  | page/index  |  request /  | app-side/index | /state     |  loopback HTTP |
  |             |  response   |                | POST       |  127.0.0.1:port|
  +-------------+             +----------------+ /control   +----------------+
```

The watch polls. There is no phone-initiated push, so:

- one-shot phone-to-watch messages (vibrate, quit) ride along in the next
  `/state` response as an `ev` list, drained on read;
- "paired" and "live" are inferred from polls: a watch is listed while it has
  polled in the last 15 s, and Live while the last poll is under 3 s old;
- the phone cannot launch the watch app (Zepp OS offers no remote launch), so
  Auto-start is marked unsupported for Amazfit.

Poll cadence is set by the phone through the `pi` field (milliseconds between
polls, measured from the previous response), derived from the existing
`watchUpdateRate` tier: CONSERVATIVE 1000, NORMAL 500, FAST 250. No new
setting.

## Phone side

Package `com.eried.eucplanet.amazfit`, main source set (no SDK to gate on):

- `AmazfitProtocol.kt`: `AmazfitKeys` (the Garmin/Wear key table verbatim,
  minus `sq`, plus `pi` poll interval and `ev` events), `AmazfitControl`
  (`horn`, `light_on`, `light_off`, `action:`, `info:`), `AMAZFIT_PORT`, and
  the two paths. Documented as a mirror of `GarminKeys`, same as GarminKeys is
  a mirror of the Wear keys.
- `AmazfitLocalServer.kt`: minimal HTTP/1.1 responder on a `ServerSocket`
  bound to `127.0.0.1` only. Accept loop on a daemon thread, two worker
  threads, request line + headers + `Content-Length` body, JSON responses with
  `Connection: close`. Pure JVM, no Android types, unit-tested. Bind failure
  logs and retries every 30 s so a port squatter never crashes the app.
- `AmazfitInbox.kt`: pure state the bridge shares with the server thread:
  pending events queue (drain on read), last-poll timestamp, watch name,
  per-second poll counter. Unit-tested.
- `AmazfitBridge.kt` (`@Singleton`): same constructor as `GarminBridge`. On
  `start()` it starts the server. `GET /state` builds the snapshot on demand
  (same `encodeSnapshot` shape as Garmin, serialised with kotlinx JSON, `gs`
  uses the `-1` sentinel). `POST /control` with `{"cmd": ...}` routes exactly
  like `GarminBridge.handleIncoming`: horn, light, `action:` via
  `FlicManager.dispatchActionByName`, `info:` to `DiagnosticsLogger` as
  `amazfit: ...`. Exposes `pairedDevices`, `deliveryRateHz` (EWMA of polls per
  second, alpha 0.25), `lastSuccessAtMs`, `publishFarewell()` (serves a
  disconnected snapshot until the next successful frame), `sendCloseToWatchBlocking()`
  (queues `quit`, waits up to 1500 ms for the watch to drain it),
  `vibrate(ms)` (queues `vibe`), `pingWatchToWake()` (no-op, kept for parity).

Integration, one line each, next to the Garmin call:
`EucPlanetApp.onCreate` (start), `WheelService.onDestroy` (farewell, close),
`DashboardViewModel.stopEverything` (close), `WatchVibrator.vibrate` (also
queues on the Amazfit bridge so alarm rules with `vibrateTarget` WATCH/BOTH
reach the wrist), `MainActivity` connection-info dump (a "Watch (Amazfit)"
row), `SettingsViewModel` (`pairedSurfaces` gains AMAZFIT entries,
`hasAmazfitPaired`, `hasHardwareButtonCapableWatch` includes Amazfit),
`PairedSurface.Kind.AMAZFIT`.

Settings, Watch tab (no new rows):

| Row | Amazfit |
|---|---|
| Auto-start | shown when any watch is paired, `AMAZFIT` unsupported badge when an Amazfit is paired |
| Auto-stop (close on exit) | supported (`quit` event, plus 20 s self-close fallback like Garmin `wce`) |
| Keep display on | supported (`setPageBrightTime` + `pauseDropWristScreenOff`); row now shows for Wear or Amazfit |
| Show navigation | supported |
| Update rate | supported (poll interval); row now shows for Wear or Amazfit |
| Battery toggles, PWM display, prioritize PWM, speed unit | supported |
| Dial rotation | unsupported, stays Wear-gated, `AMAZFIT` badge alongside the Garmin one |
| Touch buttons | supported, click and hold |
| Hardware buttons | supported: button 1 = Select (click, hold), button 2 = Up (click, hold), button 3 = Down (click), the Garmin three-button model |
| Haptic on action | supported |

Strings: `watch_paired_kind_amazfit` ("Amazfit (Zepp OS)", universal),
`watch_paired_none_desc` and the two hardware-button subtitles gain an Amazfit
mention, translated to every locale by `tools/inject-amazfit-translations.py`
(same idempotent pattern as the Garmin injector).

Manifest: nothing new; `INTERNET` is already declared and the socket is
loopback-only.

## Watch side, `amazfit-watch-app/`

A zeus project (Zepp OS mini program) with `@zeppos/zml` for the request/response
plumbing:

- `app.json`: targets `t-rex-3` (deviceSource 8716544, 8716545, 8716547) and
  `balance` (8519936, 8519937, 8519939), both 480 px round, sharing one page.
  English only, like the Garmin app.
- `app-side/index.js`: `onRequest` with method `state` does the loopback GET
  and returns the parsed body; `control` does the POST. Any failure returns
  `{ok:false}` so the watch shows its "Open EUC Planet on your phone"
  placeholder rather than a frozen frame.
- `page/index.js`: the dial, a port of `garmin-watch-app/source/EucPlanetView.mc`
  and `SpeedGauge.mc` onto Zepp widgets: 260 degree arc opening at the bottom
  (track, optional three-colour safety band, speed fill, GPS dot), speed
  numeral with unit suffix, PWM bar/number honouring `wpd` and `wpp`, battery
  row (wheel/phone/watch, red <=15, amber <=30, green), horn and light circles
  at 36 % / 64 % width and 86 % height, nav overlay (black disc, rotated arrow
  image, distance, "Arrived"). Placeholders: waiting for phone; Disconnected
  after 10 s without a frame. Zeroed dial with greyed buttons while the phone
  is up but no wheel is connected.
- Input: taps and holds on the two circles map to `b1c/b1h/b2c/b2h`;
  `onKey` SELECT and DOWN, click and long press, map to `s1c/s1h/s2c/s2h`.
  Dispatch mirrors `Actions.mc`: HORN and LIGHT_TOGGLE use the dedicated
  intents, everything else goes out as `action:<NAME>`, optional short vibrate
  when `hap`.
- Lifecycle: `setWakeUpRelaunch(true)`; keep-on per `wko`; `info:` sent once
  on start (`model=<deviceName>|fw=<osVersion>|api=<minAPI>`); `quit` event
  calls `exit()`; `vibe` runs the vibrator for the requested duration.

Accent colour is ignored, as on Garmin (fixed green/amber/red palette).

## Build, CI, docs

- `zeus build` produces `dist/*.zab`. A new `amazfit` job in
  `branch-apk.yml` and `release-apk.yml` runs `npm ci` + `npx zeus build` and
  attaches `amazfit-<suffix>.zab`. No secrets needed.
- Installing on a watch: `zeus preview` (developer logged in with a Zepp
  account, tester scans the QR in the Zepp app's Developer mode). Documented
  in `docs/AMAZFIT_SETUP.md` together with the simulator recipe
  (`zeus dev` + `adb forward tcp:<port> tcp:<port>`), the wire contract, and
  the limitations list. README gets one line next to Garmin.
- `BRANCH.md` describes this branch for the rolling pre-release.

## Testing

- JVM unit tests: `AmazfitLocalServerTest` (GET/POST routing, 404, malformed
  request survives, concurrent requests), `AmazfitInboxTest` (event drain,
  presence timeout, rate counter), `AmazfitProtocolTest` (snapshot contains the
  full key set the watch reads, poll interval per tier).
- Simulator: EUC Planet debug build on the Android emulator with a virtual
  wheel, `adb forward`, `zeus dev` on the T-Rex 3 simulator; screenshots of
  waiting, riding, disconnected and nav states.
- Real watch: the tester's T-Rex 3 via `zeus preview`; the diagnostics log
  line `amazfit: model=...` confirms the chain end to end.

## Out of scope

Theme colours on the watch, a details page, the Zepp app store listing, BLE
direct transport, Settings App (phone-side Zepp UI).
