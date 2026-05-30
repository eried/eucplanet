# Motoeye HUD companion protocol

Reference for the wire format between the EUC Planet phone app
(`com.eried.eucplanet`) and the Motoeye HUD companion app
(`com.eried.eucplanet.hud`). Covers transport, framing, both directions
of the link, and the embedded Overlay Studio preset format the phone
streams to the HUD's Custom screen.

Aimed at someone porting this surface to a different phone-side
codebase (the `next-version` branch). The link is fully specified by
two files in the shared `:hud-protocol` Gradle module:

- `HudWire.kt` — `HudState` (phone → HUD), `HudCommand` (HUD → phone),
  `HudDiscovery` (service-discovery constants).
- `OverlayLayout.kt` — `OverlayPreset` / `OverlayElement` (the embedded
  custom-overlay format inside `HudState.customOverlayJson`).

Both modules consume `:hud-protocol`, so the on-wire JSON cannot drift
between sides without a compile error in both. Reusing the same module
in `next-version` keeps that property; if the new branch is in a
different language, the field tables below are the source of truth.

Status: stable for the `feat/motoeye-hud` (preview-2) build. Protocol
version 1.


## 1. Architecture

```
   ┌────────────────────────────┐                ┌──────────────────────────┐
   │ Phone app (com.eried.      │                │ HUD app (com.eried.      │
   │   eucplanet)               │                │   eucplanet.hud)         │
   │                            │                │                          │
   │  WheelService              │                │  HudActivity             │
   │   ├─ BLE → wheel           │                │   ├─ Compose UI          │
   │   ├─ GPS / location        │                │   ├─ CameraX preview     │
   │   ├─ NavigationEngine      │                │   └─ HudTileCache        │
   │   └─ HudServer (dialer)    │                │                          │
   │       ├── snapshot @ 5 Hz  │                │  HudServer (listener)    │
   │       └── WebSocket OUT ───┼──── WS ───────►│       /state             │
   │                            │   port 28080   │  └── _state: HudState    │
   │                            │◄───── WS ──────┤                          │
   │                            │   HudCommand   │                          │
   └────────────────────────────┘                └──────────────────────────┘
```

The HUD is a **passive renderer**. It owns a Ktor WebSocket server on
port 28080 and waits for the phone to dial in. The phone owns the
WebSocket client. Once connected, the phone pushes `HudState` frames
at 5 Hz; the HUD pushes `HudCommand` messages on the same socket on
button presses.

This direction is the opposite of the original `0.1.3` build, where
the phone advertised and the HUD discovered. The flip happened because
many phone hotspots (carrier-grade tethering, Android client isolation,
public-wifi style softAPs) drop multicast and block inbound peer
traffic. **Phone-dials-HUD survives any hotspot policy short of a full
block on outbound TCP from the phone**, which is rare.


## 2. Transport

| Property              | Value                                    |
| --------------------- | ---------------------------------------- |
| Layer                 | WebSocket over TCP/IP                    |
| Default port          | `28080`                                  |
| Path                  | `/state` (only path used in v1)          |
| Frame encoding        | UTF-8 JSON, one frame per WebSocket TEXT |
| Direction (phone→HUD) | `HudState`, ~5 frames/s (every 200 ms)   |
| Direction (HUD→phone) | `HudCommand`, fired on button press      |
| Ping interval         | 15 s (Ktor server side, OkHttp client)   |
| Read timeout          | 30 s (Ktor); 0 / unlimited (OkHttp)      |
| Reconnect window      | 1–5 s exponential backoff (phone side)   |

The phone never closes the socket on its own except when the rider
toggles the data link off or the `WheelService` is stopped. The HUD
never closes the socket — it just keeps accepting whatever frames the
phone sends.

The Ktor server uses `pingPeriodMillis = 15_000`. If the OS can no
longer deliver TCP traffic (rider walks out of WiFi range, switches
hotspots), the ping eventually trips `onFailure` on the OkHttp side
and the dial loop restarts.


### 2.1 Network model

Both apps assume they share a single LAN. Anything that works for
that — phone hotspot, home WiFi, captive AP — works for the link. The
phone-as-hotspot setup is the typical deployment because it keeps the
HUD on a network the rider controls.

There is **no proxy of phone-side internet through the HUD link**: the
HUD pulls its own map tiles directly from CartoCDN (`light_all`
basemap) over whatever WiFi it joined. If the rider's phone hotspot
doesn't have a working uplink, the HUD's Map screen falls back to a
checkerboard placeholder; everything else still works.


## 3. Service discovery (mDNS) — optional

The phone tries mDNS only when no manual IP is set in
`AppSettings.hudIp`. If the rider has typed an IP, that is used
directly and mDNS is skipped.

The HUD advertises:

| Field         | Value                                              |
| ------------- | -------------------------------------------------- |
| Service type  | `_eucplanet._tcp.local.`                           |
| Service name  | `motoeye-hud`                                      |
| Port          | `28080` (= `HudDiscovery.DEFAULT_PORT`)            |
| TXT `v`       | `HudState.PROTOCOL_VERSION` as decimal string      |
| TXT `state`   | `/state` (= `HudDiscovery.PATH_STATE`)             |

The phone-side resolver:

1. Acquires a `WifiManager.MulticastLock` (Android filters multicast
   by default).
2. Opens a fresh `JmDNS` instance per attempt (binds to current
   interface — JmDNS does not always rebind cleanly on network change).
3. Waits up to 5 s for the first service-resolved event.
4. Picks the first IPv4 address. Accepts the HUD only when TXT `v` is
   ≤ the phone's known `PROTOCOL_VERSION` (a newer HUD is rejected;
   an older HUD is OK because the phone can omit newer fields it
   doesn't know are ignored).

On a hotspot that drops multicast (very common), mDNS will time out
silently and the rider will need to type the IP. The HUD displays its
own IP on the disconnected splash so the rider can read it off the
helmet.


### 3.1 Manual pairing

The rider types the HUD's IP into the phone's *Settings → Integration
→ Motoeye HUD → Device IP* field. The phone then dials
`ws://<ip>:<port>/state` directly. Port is configurable in the same
screen (`hudServerPort`) but should not need to change unless the LAN
has a conflict.

A debug system property exists for emulator testing:

```
adb shell setprop debug.eucplanet.hud.peer 10.0.2.2:18080
```

When set, the phone-side dialer uses this peer in preference to
settings *and* mDNS. Property is wiped on reboot.


## 4. Connection lifecycle

### 4.1 Phone side (`HudServer.kt` in `:app`)

```
        ┌────────────────────────────────────────┐
        │ rider toggles Enable data link ON      │
        └────────────────────────────────────────┘
                          │
                          ▼
        ┌────────────────────────────────────────┐
        │ doStart()                              │
        │  • capture initial snapshot()          │
        │  • launch publishJob (loops @ 5 Hz)    │
        │  • launch loopJob → dialLoop()         │
        └────────────────────────────────────────┘
                          │
                          ▼
        ┌────────────────────────────────────────┐
        │ dialLoop:                              │
        │  • read settings.hudIp / hudServerPort │
        │  • if blank: resolveViaMdns (5 s)      │
        │  • streamUntilClosed(peer)             │
        │  • on transport error: backoff,        │
        │    retry; attempt clears on clean exit │
        └────────────────────────────────────────┘
                          │
                          ▼
        ┌────────────────────────────────────────┐
        │ streamUntilClosed:                     │
        │  • open ws://peer/state                │
        │  • onOpen: launch sendJob              │
        │     while (true) {                     │
        │       send( json(latest) )             │
        │       delay 200 ms                     │
        │     }                                  │
        │  • onMessage: decode HudCommand, route │
        │  • onClosed / onFailure: complete done │
        └────────────────────────────────────────┘
```

Reconnect backoff is `1 s → 2 s → 4 s → 5 s → 5 s ...` (`backoff(attempt)`
in `HudServer.kt`). The first attempt has no delay. Any clean close
(rider toggling off, or a 1000 close frame from the HUD) resets the
attempt counter.

A snapshot is taken **synchronously** inside `doStart()` before the
loops launch, so the very first frame on the wire carries live wheel
data instead of `HudState()` defaults. This is the "first-frame fix"
that resolved the "have to cycle the phone app twice before any data
shows up" bug.

If the WebSocket's `send` throws or returns false (OkHttp's outbound
queue is full), the send loop explicitly closes the WebSocket so the
dial loop sees the failure inside 1 s instead of waiting 15 s for the
OkHttp ping to time out.

### 4.2 HUD side (`HudServer.kt` in `:hud`)

```
        HudActivity.onCreate
                │
                ▼
        HudServer.start() in lifecycleScope
                │
                ▼
        embeddedServer(CIO, port=28080) {
            install(WebSockets) { pingPeriodMillis = 15_000 }
            routing {
                webSocket("/state") {
                    _peer.value = call.request.local.remoteHost
                    _status.value = CONNECTED
                    launch outboundCommandSender
                    for (frame in incoming) {
                        decode HudState
                        if (protocolVersion ≤ ours) _state.value = it
                    }
                    // socket dies
                    _peer.value = null
                    _status.value = LISTENING
                }
            }
        }
                │
                ▼
        mDNS advertise on _eucplanet._tcp.local.
```

The HUD only accepts ONE phone at a time (single WebSocket route, no
session tracking beyond `_peer`). A second phone connecting while
another is active does not formally interfere, but only the most
recent socket's frames feed `_state`.

Outbound `HudCommand` messages are buffered in an unbounded `Channel`
so a button press during a momentary disconnect is not lost — it ships
on the next reconnect.


## 5. `HudState` — phone → HUD, every 200 ms

JSON object, all fields nullable with defaults; decoder runs with
`ignoreUnknownKeys = true` and `allowSpecialFloatingPointValues = true`
(the wire carries `NaN` literals for "no value").

| Field                       | Type     | Default       | Notes |
| --------------------------- | -------- | ------------- | ----- |
| `protocolVersion`           | int      | `1`           | See §8. |
| `connected`                 | boolean  | `false`       | Phone↔wheel BLE link is live. |
| `wheelName`                 | string   | `""`          | BLE-advertised wheel name. |
| `speedKmh`                  | float    | `0`           | Wheel-reported speed, canonical km/h. |
| `batteryPercent`            | int      | `0`           | 0..100. |
| `voltage`                   | float    | `0`           | Wheel pack voltage (V). |
| `current`                   | float    | `0`           | Wheel pack current (A); negative = regen. |
| `pwm`                       | float    | `0`           | Wheel PWM in percent, 0..100. |
| `temperatureC`              | float    | `0`           | Hottest reported temperature in °C. |
| `tripKm`                    | float    | `0`           | Trip distance, canonical km. |
| `torque`                    | float    | `0`           | Wheel torque, raw units (used by some renderers). |
| `lightOn`                   | boolean  | `false`       | Headlight current state. |
| `gaugeMaxKmh`               | float    | `30`          | Top of the speed dial. Tracks tilt-back. |
| `gaugeOrangeThresholdPct`   | int      | `80`          | PWM percent → orange band. |
| `gaugeRedThresholdPct`      | int      | `90`          | PWM percent → red band. |
| `showGaugeColorBand`        | boolean  | `true`        | Master switch for the gauge colour band. |
| `unitSpeed`                 | string   | `"kmh"`       | `"kmh"` / `"mph"`. HUD does the conversion. |
| `unitDistance`              | string   | `"km"`        | `"km"` / `"mi"`. |
| `unitTemp`                  | string   | `"C"`         | `"C"` / `"F"`. |
| `accentArgb`                | string   | `"#FF00C853"` | Phone settings accent, `#AARRGGBB` hex. |
| `latitude`                  | double   | `0.0`         | WGS84 degrees, 0.0 = no fix. |
| `longitude`                 | double   | `0.0`         | WGS84 degrees, 0.0 = no fix. |
| `gpsSpeedKmh`               | float    | `NaN`         | km/h when available, NaN otherwise. |
| `gpsSource`                 | string   | `""`          | `"PHONE"` / `"EXTERNAL"` / `""`. |
| `gpsHasFix`                 | boolean  | `false`       | Any source. |
| `gpsHeadingDeg`             | float    | `NaN`         | 0 = north, +clockwise. NaN when no bearing. |
| `gpsAltitudeM`              | float    | `NaN`         | Meters. NaN when no altitude. |
| `wheelRollDeg`              | float    | `0`           | Lean angle, +right. 0 = not reported. |
| `wheelPitchDeg`             | float    | `0`           | Pitch, +forward. 0 = not reported. |
| `customOverlayJson`         | string   | `""`          | Embedded `OverlayPreset` JSON. See §7. |
| `navActive`                 | boolean  | `false`       | Render the navigation overlay. |
| `navArrowAngleDeg`          | float    | `0`           | 0 = straight up, +clockwise. |
| `navPrimary`                | string   | `""`          | E.g. "Turn left onto Storgata". |
| `navDistance`               | string   | `""`          | E.g. "120 m". |
| `navArrived`                | boolean  | `false`       | Rider is at the destination. |
| `timestampMs`               | long     | `0`           | Server epoch millis at capture. |

### 5.1 Unit and sentinel conventions

- **All telemetry is canonical metric**: km/h, °C, km. The HUD does
  the per-rider conversion using `unitSpeed`/`unitDistance`/`unitTemp`.
  This keeps the wire encoder independent of rider settings.
- **`NaN` means "no value"** for the GPS-derived floats (`gpsSpeedKmh`,
  `gpsHeadingDeg`, `gpsAltitudeM`). Both ends use the
  `allowSpecialFloatingPointValues = true` JSON flag. **Without that
  flag every frame would fail to serialize** — this was the original
  silent-link bug in `0.1.4`.
- **`0` is ambiguous on `wheelRollDeg`/`wheelPitchDeg`**: it means
  either "wheel reports 0°" or "wheel doesn't report it". The HUD
  treats `0.0 && 0.0` as "no data" and skips the horizon. If the
  next-version protocol needs to distinguish, switch these to NaN-
  sentinel like the GPS fields.
- **`0.0` lat/lon is also ambiguous**: real (0, 0) is in the Atlantic,
  so the HUD treats it as "no fix" too.
- **`accentArgb` is `#AARRGGBB`** with the leading `#`, 8 hex digits.
  The phone's `Color.value` is a `ULong`; the high 32 bits are the
  ARGB.

### 5.2 Frame rate

The publish loop runs at 5 Hz (200 ms period). That matches the rate
the wear bridge uses for the watch face, and is the slowest rate that
still feels responsive on the speed dial. The HUD does NOT request a
specific rate — the phone is the timer. The HUD just treats whichever
frame is currently in `_state` as the latest.

Frame timestamps bump every tick even when no other field has changed,
so the HUD can use `timestampMs` as a "freshness" signal independent
of its own wall clock.


## 6. `HudCommand` — HUD → phone, on button press

JSON object, one of four variants. `kotlinx.serialization` polymorphic
encoding adds a `"type"` discriminator:

```jsonc
// Sent immediately after the HUD's WebSocket route accepts the phone:
{ "type": "Pair",
  "hudId":      "motoeye-e6-7f3a",
  "hudVersion": "0.1.5" }

// Rider pressed OK on the Dashboard:
{ "type": "ToggleLight" }

// Rider held OK on the Dashboard:
{ "type": "Horn" }

// Rider pressed OK on the Nav screen (active route):
{ "type": "StopNavigation" }
```

| Variant             | Phone-side effect                                  |
| ------------------- | -------------------------------------------------- |
| `Pair`              | Recorded in diagnostics. **Not** a security gate. |
| `ToggleLight`       | `WheelRepository.toggleLight()` if wheel supports. |
| `Horn`              | `WheelRepository.horn()` if wheel supports.        |
| `StopNavigation`    | `NavigationEngine.stop()`.                         |

The `Pair` message is informational only. The HUD has no way to
*authorize* itself to the phone; the phone treats any TCP connection
that survives the WebSocket handshake as a valid HUD. This is fine on
a private hotspot but should be revisited if the link ever runs on a
shared LAN.

Commands queue inside an unbounded `Channel` on the HUD side; if the
WebSocket drops while the queue has pending items, they ship on the
next reconnect. There is no ACK frame — the HUD assumes successful
delivery once `send()` returns.


## 7. Embedded overlay preset (`customOverlayJson`)

When the rider picks an Overlay Studio preset in the phone's *Settings
→ Integration → HUD overlay → Select preset* picker, the phone serializes
the chosen preset to JSON and ships it inside `HudState.customOverlayJson`
on every frame. **Empty string** means "no preset selected" and the
HUD's Custom screen shows the empty-state placeholder.

The preset string is a `JSONObject` matching the shape of
`OverlayLayout.kt`'s `OverlayPreset`:

```jsonc
{
  "name": "Cruise",
  "layout": "SINGLE",              // ignored on HUD (no viewport panes)
  "dividers": [],
  "viewports": [ { /* ignored */ } ],
  "elements": [
    {
      "id":            "name",
      "type":          "WHEEL_NAME",
      "x":             0.01,        // top-left, fraction of canvas width
      "y":             0.27,        // top-left, fraction of canvas height
      "width":         0.32,        // fraction of canvas width
      "height":        0,           // 0 = auto, else fraction of canvas height
      "rotationDeg":   90,          // see §7.2
      "opacity":       0.95,
      "foreground":    4294967295,  // ARGB as a long
      "background":    1711276032,
      "showLabel":     true,
      "metric":        "SPEED",     // for DATA_* element types
      "graphWindowSec": 10,
      "gaugeMax":      100,
      // ... other type-specific fields, all optional with defaults
    },
    { /* battery */ }, { /* speed pill */ }, …
  ],
  "dividerColor":     3439329279,
  "dividerThickness": 3
}
```

The full set of fields per element is in `OverlayLayout.kt`. Unused
fields for a given `type` are silently ignored.

### 7.1 Element types

The HUD supports a subset of the studio's element library. Unknown
types are dropped silently.

| Type              | Supported on HUD | Notes                                  |
| ----------------- | ---------------- | -------------------------------------- |
| `WHEEL_NAME`      | ✓                | Shows `wheelName` field or fallback.   |
| `APP_BADGE`       | ✓                | Hard-coded "EUC Planet" + bundled logo. |
| `TEXT`            | ✓                | `{speed}` etc. templating works.       |
| `DATA_VALUE`      | ✓                | Pill with label + value + unit.        |
| `DATA_GRAPH`      | ✓                | HUD keeps its own rolling buffer.      |
| `DATA_DIAL`       | ✓                | `FULL` and `SEMICIRCLE` styles.        |
| `DATA_BAR`        | ✓                | Horizontal value bar.                  |
| `CLOCK`           | ✓ (digital only) | `ANALOG` / `STOPWATCH` fall back.      |
| `G_FORCE`         | ✓                | Lateral derived from `wheelRollDeg`.   |
| `MAP`             | ✓                | Uses HUD's CartoCDN tile cache.        |
| `FLOATING_CAMERA` | ✗                | Not rendered.                          |
| `IMAGE`           | ✗                | Not rendered (skip the base64 cost).   |

### 7.2 Coordinate system

Elements use **portrait-canvas 0..1 fractions** in the rider's phone's
screen frame, exactly as the studio stores them. The HUD reproduces the
rider's *landscape* view of that canvas:

- The studio's canvas is **portrait-fixed** (`OverlayStudioScreen.kt`:
  *"the layout itself never rotates; that would scramble the viewport
  panes"*).
- `OverlayElement.rotationDeg` is stamped by the studio at element
  placement time based on the phone's current orientation:
  `rotationDeg = (360 - deviceRotation) % 360` where `deviceRotation`
  is the `OrientationEventListener` reading (0 = portrait, increases
  CW from natural).
- Net mapping per orientation:

  | `rotationDeg` | Phone orientation at authoring | Portrait → rider landscape view |
  | ------------- | ------------------------------ | ------------------------------- |
  | 0             | Portrait (`deviceRotation=0`)  | identity                        |
  | 90            | Left-landscape (`devRot=270`)  | `(X, Y) = (y_p, 1 − x_p)`       |
  | 180           | Upside-down portrait           | (rare; HUD treats as portrait)  |
  | 270           | Right-landscape (`devRot=90`)  | `(X, Y) = (1 − y_p, x_p)`       |

The HUD picks a `dominantRotation` across the preset's elements and
renders inside a portrait sub-canvas rotated by `-dominantRotation`,
which produces those mappings. Per-element `rotationDeg` is also
applied (via `graphicsLayer`) so off-axis elements stay at their
intended angle relative to the canvas.

If `next-version` ever stops storing `rotationDeg` on elements (e.g.
because the studio becomes orientation-aware and stores coords
directly in the rider's chosen view frame), the HUD can drop the
sub-canvas rotation and render the coords raw.


## 8. Versioning

`HudState.PROTOCOL_VERSION` is currently `1`. The contract is:

- **Additions are backwards-compatible.** Both decoders use
  `ignoreUnknownKeys = true`, so a newer phone with extra fields can
  feed an older HUD without errors. The HUD just won't render the new
  data.
- **Removals or semantic changes bump the major version.** Examples
  that would require bumping:
  - Switching `gpsAltitudeM` from "meters" to "feet".
  - Changing `wheelRollDeg/wheelPitchDeg` to use NaN as the "no data"
    sentinel.
  - Renaming a JSON field.
- **HUD refuses an mDNS pairing where TXT `v` > its own version**, so a
  too-new HUD will fail to be auto-discovered by an older phone. There
  is currently no check the OTHER way (a too-new phone connecting to
  an older HUD silently sends extra fields it ignores).

The HUD also gates incoming frames by version on the wire:
```kotlin
if (s.protocolVersion in 1..HudState.PROTOCOL_VERSION) {
    _state.value = s
}
```
Frames with `protocolVersion = 0` (encoder dropped the field) or a
version newer than the HUD knows are dropped silently.


## 9. Settings the phone exposes to the rider

Mirrored to `AppSettings` (DataStore) and surfaced in *Settings →
Integration → Motoeye HUD*:

| Setting                  | Key in `AppSettings`     | Default | Notes |
| ------------------------ | ------------------------ | ------- | ----- |
| Enable data link         | `hudServerEnabled`       | `false` | Storage key predates the role flip; means "HUD link active". |
| Device IP                | `hudIp`                  | `""`    | Blank → fall back to mDNS. |
| Port                     | `hudServerPort`          | `28080` | Match the HUD's listening port. |
| HUD overlay preset name  | `hudCustomOverlayName`   | `""`    | Picked name, for display in the picker. |
| HUD overlay preset JSON  | `hudCustomOverlayJson`   | `""`    | Serialized preset, shipped in every frame. |

The HUD never reads `AppSettings` directly. Everything it needs to
render comes from the wire.


## 10. Notes for a `next-version` port

Things to keep:

- **Same module split.** `:hud-protocol` as a pure-Kotlin (or
  language-equivalent) module that both sides depend on. Drift between
  sides is the most common failure mode.
- **`allowSpecialFloatingPointValues = true` on both ends.** Easy to
  miss; every frame fails silently without it.
- **First-frame snapshot in `doStart()`.** The "open the app twice
  before data shows up" bug came from sending an uninitialised
  `HudState()` as the first frame.
- **Explicit WS close on send failure.** Otherwise the dial loop waits
  ~15 s for OkHttp's ping to time out before reconnecting.

Things to revisit:

- **Auth.** The `Pair` message carries an ID but the phone treats any
  incoming WS as trusted. For a shared-LAN deployment, add a shared
  secret (rider types it once on both sides) and reject unknown HUDs.
- **NaN for "no data" on wheel roll/pitch.** See §5.1.
- **Map tiles direct from CartoCDN.** Works for testing; CartoCDN is
  free but their AUP requires attribution and rate-limits commercial
  use. Long-term should proxy through the phone (which has the rider's
  Mapbox/MapTiler API key) or bundle a small offline tile pack.
- **Drop `viewports` / `dividers` / `layout` from
  `customOverlayJson`.** The HUD ignores them entirely; they exist
  only because the wire format reuses the studio's full preset shape.
  Saves a few hundred bytes per frame.
- **Single phone-per-HUD enforcement.** The HUD currently accepts
  successive WebSocket connections without rejecting the previous one.
  A second phone joining mid-ride could quietly take over the link.

If the next-version branch wants to ship a phone client without
pulling in this whole Kotlin codebase, the absolute minimum to be on
the wire is:

1. Open `ws://<hud-ip>:28080/state`.
2. Every 200 ms, send a JSON-encoded `HudState` (only the fields you
   actually have — defaults cover the rest).
3. Read incoming text frames and decode as `HudCommand` (4 variants
   covering all rider input).

A 50-line client in any language with a WebSocket library is enough to
put a working dashboard on the HUD.
