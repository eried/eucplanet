# wear-os-watch-ultra

## What this branch adds

Companion app for Wear OS 5+ watches (built and tuned on the Galaxy Watch
Ultra). The phone holds the BLE link to the wheel and pushes a compact
telemetry snapshot to the watch over the Wearable Data Layer; the watch is a
thin client and never talks to the wheel directly.

Concretely shipped here:

- **Full-bleed speed dial** that wraps the entire watch face. Same arc
  geometry as the phone dashboard (260° sweep, accent-tinted safe band,
  orange/red danger wedges, ticks). Speed number, units, batteries and
  buttons live inside the dial.
- **Three batteries at a glance** above the speed number: wheel, phone, and
  watch, each colour-graded by the same red/amber/green thresholds used on
  the phone dashboard.
- **Accent colour follows the phone.** The accent the user picked in app
  settings travels through the wire format and tints the dial safe band,
  the wheel-name header on page 2, and the horn / light buttons.
- **Imperial units follow the phone.** When `imperialUnits` is on, the
  watch shows mph, miles, and °F; flipping the setting takes effect within
  one publish cycle (≤200 ms).
- **Page 2 — at-a-glance details.** Wheel name in accent, live speed, then
  a tabular column of voltage / current / power (V × A) / PWM / temp /
  torque / trip. Values align vertically across rows so you can scan down a
  column.
- **Buttons follow the phone iconography.** Horn = `Icons.Filled.Campaign`,
  Light = `Icons.Filled.FlashOn` — same glyphs as the phone dashboard.
- **Disconnected state** shows a phone glyph and a two-line "Open EUC
  Planet on your phone" message. No more red dot that read as an error.
- **Resolution-clean.** All sizes derive from `BoxWithConstraints.maxWidth`
  so the layout looks right on small round watches (~390 dp) and on Watch
  Ultra (~454 dp) without separate code paths.

## Architecture

- `WearBridge` (phone, `app/`) subscribes to `WheelRepository` flows and
  `SettingsRepository.settings`, samples to 5 Hz, packs a `DataMap` and
  publishes at `/euc/state`. Reads phone battery via `BatteryManager`.
- `WatchBridgeService` (watch, `wear/`) decodes the DataMap into a
  `WatchState` and updates a singleton `WatchStateRepository`.
- `WatchApp` (Compose) collects from the repo and renders.
- Control flow (horn / light) is the existing reverse channel: watch sends
  short Messages on `/euc/control`, phone routes them through
  `WheelRepository`.

## Who should test this

- **Watch Ultra owners** with a paired phone running the matching debug or
  pre-release APK from the same branch: confirm the dial reads correctly,
  battery percentages match Settings/Battery on each device, accent and
  imperial follow the phone, and horn/light controls work.
- **Other Wear OS 5+ watches** (round and rectangular): the layout should
  scale; please report clipping or overlap. Square watches use the same
  dial with the corners falling outside the arc — intentional.
- **Anyone curious about the UI without hardware**: debug builds expose an
  ADB demo broadcast. With the watch app open:
  ```
  adb shell am broadcast -p com.eried.eucplanet \
    -a com.eried.eucplanet.wear.DEMO \
    --ef speed 32 --ei battery 78 --ei phone 64 \
    --es accent teal --ef maxSpeed 70 \
    --ez imperial false --es name "InMotion V14"
  ```
  Speed/battery/accent/imperial extras are all optional.

## Known limits

- **Pairing must be done via the Wear OS by Google companion app** the
  first time. Without pairing, the watch shows the disconnected
  placeholder forever; this branch does not change that.
- **Tile and complication** (carousel and watch-face quick-glance) are not
  here yet. The companion launches as an app you open from the launcher.
- **No standalone (watch-only) BLE.** The watch never connects to the
  wheel directly; if the phone's app process is killed, telemetry stops.
- **No on-watch settings.** Imperial / accent / max-speed cap are read
  from the phone — change them there.

## Feedback

File issues at https://github.com/eried/eucplanet/issues. Tag with the
watch model and Wear OS version if you can.
