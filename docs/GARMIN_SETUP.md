# Garmin support — setup

EUC Planet exposes the same wrist dial on Garmin Connect IQ devices that it
does on Wear OS. The two surfaces share settings, share the wire vocabulary,
and can both be paired to the phone at the same time. This file walks through
the developer setup; if you only want to install and use the app, watch this
space for a Connect IQ Store release once the watch app graduates from
preview.

## Architecture in 30 seconds

```
       ┌─────────────────────┐      Wearable Data Layer        ┌───────────┐
       │                     │ ──────────────────────────────▶ │  Wear OS  │
       │     EUC Planet      │                                 │  watch    │
       │  (Android phone)    │                                 └───────────┘
       │                     │      Connect IQ Mobile SDK      ┌───────────┐
       │  SettingsRepository │ ──────────────────────────────▶ │  Garmin   │
       │   ↓ both bridges    │                                 │  watch    │
       │                     │ ◀────────── controls ────────── │  / Edge   │
       └─────────────────────┘                                 └───────────┘
```

Phone-side:
- `app/src/main/java/com/eried/eucplanet/wear/WearBridge.kt` publishes to the
  Wear OS Data Layer.
- `app/src/garminEnabled/kotlin/com/eried/eucplanet/garmin/GarminBridge.kt`
  publishes to the Connect IQ Mobile SDK. Same `SettingsRepository`, same
  field names (`watchKeepScreenOn`, `watchShowWheelBattery`, `watchStem1Click`,
  …), same publish cadence (5 Hz), same farewell-on-stop semantics.

Watch-side:
- `wear/` — Kotlin + Jetpack Compose for Wear OS.
- `garmin-watch-app/` — Monkey C for Garmin Connect IQ. Same wire keys, same
  visual language, same FlicAction binding vocabulary.

Because the rider's "Watch" settings drive both bridges, the phone has a
single Settings → Watch screen, not one per surface. Flipping
`watchHapticOnAction` toggles haptics on both wrists; remapping `stem1` to
`LIGHT_TOGGLE` works on both surfaces.

## Setup steps

### 1. Build the phone APK (Garmin support compiled in)

You need:
- JDK 17 (the project's Kotlin and AGP both target 17).
- Android SDK with `compileSdk = 35` installed.

The Connect IQ Mobile SDK is on Maven Central
(`com.garmin.connectiq:ciq-companion-app-sdk:2.4.0`), so nothing to download
manually — Gradle pulls it in on first build.

```bash
./gradlew :app:assembleDebug
# slim build without Garmin support (saves ~150 KB):
./gradlew :app:assembleDebug -PgarminEnabled=false
```

The two variants share the public `GarminBridge` API; `EucPlanetApp.kt`
calls into whichever one ended up on the classpath, so flipping the flag
needs no code edits.

### 2. Install the Connect IQ SDK + simulator

The SDK lets you build the watch project and run it in a simulator without a
real Garmin device.

1. Download the **Connect IQ SDK Manager** (Windows / macOS / Linux) from
   <https://developer.garmin.com/connect-iq/sdk/>.
2. Run the Manager and install the latest SDK (9.1+) plus the device
   profiles you care about. Each `<iq:product id="…"/>` line in
   `garmin-watch-app/manifest.xml` must have a matching device installed in
   the SDK Manager or `monkeyc` skips it at build time.

### 3. Generate a developer key

```bash
openssl genrsa -out developer_key.pem 4096
openssl pkcs8 -topk8 -inform PEM -outform DER \
    -in developer_key.pem -out developer_key.der -nocrypt
```

Point the Garmin tools at `developer_key.der`. The same key signs every
build until you publish; treat it like any other signing key. The file is
in `.gitignore` and never commits.

### 4. Build the watch app

From `garmin-watch-app/`:

```bash
# Single-device debug build:
monkeyc -f monkey.jungle \
        -o build/EucPlanet-fenix843.iq \
        -y developer_key.der \
        -d fenix843mm -r

# Multi-device export (all manifest devices in one .iq):
monkeyc -f monkey.jungle \
        -o build/EucPlanet.iq \
        -y developer_key.der \
        -e -r
```

`-e` is the export flag; the resulting `EucPlanet.iq` is the package you
sideload or upload to the Connect IQ Store. The repo ships with a manifest
that builds for ~30 device targets out of the box.

### 5. Run in the simulator

```bash
connectiq                # boots the GUI simulator
monkeydo build/EucPlanet.iq fenix843mm
```

The simulator pairs to a Garmin Connect Mobile **emulator endpoint** on
localhost. Pointing the phone app at the simulator requires running the
phone app on an Android emulator with the Garmin Connect Mobile app
installed and signed into the same developer account that signed your watch
build.

### 6. Sideload to a real device

The fastest path for one-off testing on a real watch:

1. Plug the watch in over USB.
2. The watch mounts as a normal USB drive.
3. Copy `build/EucPlanet.iq` into `GARMIN/APPS/` on the watch.
4. Eject. Reboot the watch if it doesn't show up under Activities →
   Connect IQ.

The watch app talks to the phone through Garmin Connect Mobile (on the
phone), which routes messages over Bluetooth to the watch. There's no direct
phone→watch BT connection — Connect Mobile is the broker.

## Shared settings between Wear OS and Garmin

The "Watch" section of the phone Settings UI applies to both surfaces. The
single source of truth is `SettingsRepository`. Bridges read these fields:

| Setting key                  | Effect on both surfaces                         |
|------------------------------|-------------------------------------------------|
| `watchKeepScreenOn`          | Disables the watch's inactivity timeout         |
| `watchShowWheelBattery`      | Wheel battery dot at the top of the dial        |
| `watchShowPhoneBattery`      | Phone battery dot                               |
| `watchShowWatchBattery`      | Watch's own battery dot                         |
| `watchPwmDisplay`            | `"BAR"` / `"NUMBERS"` / `"BOTH"`                |
| `watchShowSpeedUnit`         | Unit label under the speed number               |
| `watchPrioritizePwm`         | Bigger PWM, smaller speed font                  |
| `watchDialRotationDeg`       | Tilt the dial -90..+90°                         |
| `watchStem1Click` / `Hold`   | Programmable button bindings (FlicAction names) |
| `watchStem2Click` / `Hold`   | Same for the second button                      |
| `watchScreen1Click` / `Hold` | On-screen horn button bindings                  |
| `watchScreen2Click` / `Hold` | On-screen light button bindings                 |
| `watchHapticOnAction`        | Vibrate the watch on action fire                |
| `watchShowNavigation`        | Mirror the phone's nav popup on the watch       |
| `showGaugeColorBand`         | Safe/warn/danger band on the gauge              |
| `gaugeOrangeThresholdPct`    | Orange-zone start (% of gauge max)              |
| `gaugeRedThresholdPct`       | Red-zone start                                  |

When the rider has both a Wear OS watch and a Garmin watch paired, both
receive every telemetry frame at 5 Hz. The phone is the source of truth;
each surface renders independently. There's no cross-watch coordination —
both watches show the same speed at the same moment because both subscribe
to the same `WheelRepository` flow.

## Limitations vs. Wear OS

These don't carry over from Wear OS to Garmin yet, and are tracked as
follow-up work:

- **Auto-launch on phone open**: Connect IQ apps can't be programmatically
  brought to the foreground on the watch (Wear OS has no such restriction).
  The rider has to open the EUC Planet app on the watch face once per
  session; the phone-side `pingWatchToWake` exists as a no-op for parity but
  doesn't do anything Garmin-side until the user opens the app.
- **Per-locale strings**: Wear OS ships in 16 languages; the Garmin app
  starts with English only. Porting the strings is mechanical — copy from
  `wear/src/main/res/values-XX/strings.xml` into
  `garmin-watch-app/resources-XX/strings.xml`.
- **Accent colour**: the gauge defaults to safe-green when the color band is
  off, ignoring the rider's `accentColor` setting. The Wear OS dial applies
  the accent. Add a Monkey C colour table that maps the accent keys
  (`teal`, `coral`, `purple`, …) to RGB to close the gap.
- **Edge UX**: the bike computer family auto-records a Garmin activity in
  parallel with the EUC Planet trip recording. We haven't decided whether to
  surface that on the dashboard, suppress it, or expose a setting. Treated
  as a follow-up once the watch flow ships.

## File map

```
app/src/garminStub/kotlin/com/eried/eucplanet/garmin/
    GarminBridge.kt                    # no-op stub (-PgarminEnabled=false)
app/src/garminEnabled/kotlin/com/eried/eucplanet/garmin/
    GarminProtocol.kt                  # wire keys + UUID
    GarminBridge.kt                    # CIQ-backed bridge (default)
garmin-watch-app/
    manifest.xml                       # Connect IQ app manifest
    monkey.jungle                      # build jungle
    resources/strings/strings.xml      # localised strings
    resources/drawables/drawables.xml  # launcher icon ref + bitmap
    resources/drawables/launcher_icon.png
    source/
        EucPlanetApp.mc                # AppBase entry
        EucPlanetView.mc               # dial view
        EucPlanetDelegate.mc           # button input
        SpeedGauge.mc                  # gauge drawing
        WatchState.mc                  # state holder + key vocabulary
        Bridge.mc                      # Communications transport
        Actions.mc                     # FlicAction dispatch
        Units.mc                       # speed/distance/temp conversion
```
