# Amazfit (Zepp OS) support, setup

EUC Planet shows the same wrist dial on Amazfit watches running Zepp OS that
it does on Wear OS and Garmin Connect IQ. The three surfaces share settings,
share the wire vocabulary, and can all be paired to the phone at the same
time. This file walks through the developer setup, the simulator recipe, and
how to get the app onto a real watch. Tested against the Amazfit T-Rex 3
(Zepp OS 4, 480 x 480 round, API level 4.0); the Amazfit Balance is in the
build targets because it has the same screen.

## Architecture in 30 seconds

```
   Amazfit watch              Zepp app (phone)              EUC Planet (phone)
  +--------------+  BLE msg  +-----------------+  HTTP GET  +-----------------+
  |  Device App  | <-------> |  Side Service   | ---------> |  AmazfitBridge  |
  |  page/index  |           |  app-side/index |  /state    |  127.0.0.1:28193|
  |              |           |                 |  POST      |                 |
  +--------------+           +-----------------+  /control  +-----------------+
```

Zepp OS gives a third-party phone app no way to talk to a watch mini program
directly. What it does give is a three-part mini program: a Device App on the
watch and a Side Service that runs inside the official Zepp phone app and can
make HTTP requests. So the watch polls: its Side Service fetches
`http://127.0.0.1:28193/state` from EUC Planet (the Zepp app permits
cleartext HTTP in its network security config) and hands the JSON frame to the
dial over Bluetooth. Controls go the other way as `POST /control`.

Because the watch polls, the phone cannot push. One-shot messages (vibrate
hints from alarm rules, the QUIT when the ride ends) ride in the `ev` list of
the next `/state` answer. "Paired" means "polled in the last 15 s", "Live"
means "polled in the last 3 s".

Phone-side (`app/src/main/java/com/eried/eucplanet/amazfit/`):

- `AmazfitBridge.kt` builds the snapshot and routes controls, same
  `SettingsRepository` fields and same snapshot as `GarminBridge`.
- `AmazfitLocalServer.kt` is the loopback-only HTTP responder (java.net, no
  library).
- `AmazfitInbox.kt` holds the queued events and the last-poll bookkeeping.
- `AmazfitProtocol.kt` is the key table, mirrored 1:1 from `GarminKeys`.

Watch-side (`amazfit-watch-app/`): a zeus project. `page/index.js` is the
dial (a port of `garmin-watch-app/source/EucPlanetView.mc`),
`app-side/index.js` the Side Service, `utils/protocol.js` the key table.

## Setup steps

### 1. Build the phone APK

Nothing extra: the bridge is in the main source set and needs no SDK.

```bash
./gradlew :app:assembleDebug
```

### 2. Install the Zepp OS toolchain

Node.js 18+ and the zeus CLI:

```bash
npm i @zeppos/zeus-cli -g
zeus -v
```

Then the Zepp OS Simulator from
<https://docs.zepp.com/docs/guides/tools/simulator/download/> (v2.x, no
virtual network adapter needed). Inside it, sign in with a Zepp developer
account (the same login `zeus login` uses; it is free) and use the cloud
download button to fetch the **Amazfit T-Rex 3** device simulator.

### 3. Build the watch app

```bash
cd amazfit-watch-app
npm install
npx zeus build          # dist/*.zab
```

### 4. Run the whole chain in simulators

The simulator runs the Side Service on your PC, so its `127.0.0.1` is the
PC. Forward the port to an Android emulator that runs EUC Planet:

```bash
adb -s emulator-5554 forward tcp:28193 tcp:28193
curl http://127.0.0.1:28193/state      # the JSON frame, once the app is up
```

Open EUC Planet on the emulator (a Virtual wheel from the scan screen works),
then:

```bash
cd amazfit-watch-app
npx zeus dev
```

The dial appears on the T-Rex 3 simulator and updates at the rate the phone
asks for. The simulator Console shows one `request`/`response` pair per poll;
the phone's Settings, Watch tab lists "Amazfit T-Rex 3 / Amazfit (Zepp OS)"
with a Live badge at the configured rate (2 Hz on Normal).

`zeus dev` asks for the target by display name: `zeus dev -t "Amazfit T-Rex 3"`.

Simulator 2.1.2 quirks met on Windows, in case the device never starts:

- With exactly one device simulator downloaded, the shell stores the selected
  device as `{id, name}` while its launcher expects the id string, so it
  looks for `emulator_cache\[object Object]` and later crashes with
  "Cannot read properties of undefined (reading 'device')". Close the
  simulator, open `%APPDATA%\simulator\config.json` and replace the
  `platform` value with the plain id (the `id` of the entry in
  `selectDeviceList`), then start it again. Downloading a second device
  simulator avoids the bug too.
- The first device launch raises a Windows Security prompt for the QEMU
  based device simulator; accept it once.
- The device runs in a QEMU window that cannot be resized; maximise it to
  see the whole 480 px face.

### 5. Put it on a real watch

Zepp OS installs unsigned mini programs only through the Zepp app's
Developer mode:

1. On the phone, open the Zepp app, Profile, tap the Zepp logo seven times
   until "Developer mode" is confirmed.
2. On the PC, `zeus login` (once), then in `amazfit-watch-app/` run
   `npx zeus preview`. A QR code prints in the terminal.
3. In the Zepp app, Developer mode, Scan, and point the phone at the QR code.
   The watch installs the app.

The `.zab` from CI is the store-submission format; it cannot be sideloaded
directly, so testers either run `zeus preview` from a checkout or wait for
the Zepp app store listing.

### 6. Use it

Open EUC Planet on the phone, then open "EUC Planet" in the watch's app list.
The dial reads "Open EUC Planet on your phone" until the first frame lands,
"Disconnected" after 10 s without one. Tap the horn and light circles, or
press Select (button 1, click and hold), Up (button 2, click and hold) and
Down (button 3, click only), the same three-button model as the Garmin app.
Bindings come from Settings, Watch, Buttons on the phone. While Service Mode
is recording, the watch reports every key and tap it sees into the
diagnostics log (`amazfit input: ...`).

Settings, Diagnostics logs `amazfit: model=...|fw=...|api=...` once when the
watch app starts; that line is the end-to-end proof the chain works on a
given phone.

## Wire contract

`GET /state` answers a JSON object with the `GarminKeys` vocabulary (`c`,
`s`, `b`, `b2`, `v`, `i`, `p`, `t`, `tr`, `tq`, `l`, `ms`, `n`, `us`, `ud`,
`ut`, `wko` ... `hap`, `gs`, `gsr`, `na` ... `nar`, `ts`) plus:

- `pi`: milliseconds between frames (1000 / 500 / 250 for the CONSERVATIVE /
  NORMAL / FAST update-rate tier, so 1, 2 or 4 frames a second).
- `ev`: list of one-shot events queued since the previous poll, each
  `{"k": "vibe", "ms": 300}` or `{"k": "quit"}`.
- `s3c`: binding of the third hardware button, and `dg`: true while the
  phone's Service Mode records (the watch then sends `debug:` reports).

`POST /control` takes `{"cmd": "<intent>"}` with the `GarminControl`
vocabulary: `horn`, `light_on`, `light_off`, `action:<FlicAction>`,
`info:model=...|fw=...|api=...`, and `debug:<event>` while `dg` is set.

The server binds `127.0.0.1` only. Nothing off the phone can reach it.

## If the watch stays on "Open EUC Planet on your phone"

That screen means the watch app is fine but no data has arrived. In order:

1. **Use the branch phone APK.** A normal Play Store EUC Planet has no Amazfit
   support and nothing listens on the phone. Install `phone-amazfit-watch-*.apk`.
2. **The Zepp app must be running and un-optimised.** The side service that
   feeds the watch lives inside the Zepp app, so if Android sleeps or kills
   Zepp, the watch goes quiet. Set Zepp to unrestricted battery, open it once.
3. **Check the phone.** EUC Planet, Settings, Watch: an "Amazfit (Zepp OS)"
   card (even Idle) means the link is reaching the phone. Settings,
   Diagnostics: an `amazfit:` line is the same proof.

The watch itself now distinguishes the two failure modes: it stays on "Open
EUC Planet on your phone" when it cannot reach its own side service (Zepp not
relaying), and switches to "Can't reach EUC Planet, open it on your phone"
when the side service is alive but its loopback fetch to the phone app fails.

The loopback server binds the IPv4 address `127.0.0.1` explicitly, not
`getLoopbackAddress()`, because that can hand back the IPv6 loopback `::1` on a
dual-stack phone while the watch connects to the IPv4 literal, which the
emulator never reproduces.

## Limitations vs Wear OS

- **No auto-start.** Zepp OS offers no remote launch for third-party mini
  programs; open the watch app by hand. The Auto-start row carries an AMAZFIT
  badge while an Amazfit is paired.
- **Dial rotation** is not implemented (Zepp OS widgets do not rotate a whole
  page). Wear-only row, badged.
- **Up to 4 Hz.** Normal is 2 frames a second, Fast 4, Conservative 1. The
  simulator sustains about 7 a second, so Fast keeps a margin; faster costs
  watch battery for little visible gain.
- **Accent colour is ignored**, same as Garmin: fixed green / amber / red.
- **English only** on the watch, same as Garmin.
- **The Zepp app must be running** on the phone; it hosts the Side Service.
  Battery optimisation that kills the Zepp app also stops the dial.
- **Updating a BUTTON needs its geometry.** Zepp OS ignores a BUTTON
  `setProperty` that does not carry `x, y, w, h`; the dial repeats the button
  frame on every state change for that reason.

## File map

| Path | What |
|---|---|
| `app/src/main/java/com/eried/eucplanet/amazfit/AmazfitBridge.kt` | Snapshot, control routing, presence, farewell, quit |
| `app/src/main/java/com/eried/eucplanet/amazfit/AmazfitLocalServer.kt` | Loopback HTTP responder |
| `app/src/main/java/com/eried/eucplanet/amazfit/AmazfitInbox.kt` | Event queue + last-poll bookkeeping |
| `app/src/main/java/com/eried/eucplanet/amazfit/AmazfitProtocol.kt` | Keys, port, JSON helper |
| `app/src/main/java/com/eried/eucplanet/amazfit/AmazfitSnapshot.kt` | Frame builder (pure Kotlin, unit-tested) |
| `app/src/test/java/com/eried/eucplanet/amazfit/` | Unit tests for all of the above |
| `amazfit-watch-app/` | The Zepp OS mini program |
| `tools/inject-amazfit-translations.py` | Locale strings injector |
