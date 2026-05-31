# EUC Planet HUD

The HUD companion turns a small Android display (e-ink HUD module,
spare phone, MotoEye E6 proxy panel) into a wrist-free dashboard that
mirrors the EUC Planet phone app in real time. Five default screens
(Dashboard / Camera / Telemetry / Custom overlay / Map+Nav) plus
opt-in extras (Big Clock, Compass, Trip Stats, Power, Safety) the
rider cycles through with a remote or touch.

## Install

1. **Download the latest HUD APK** from the
   [releases page](https://github.com/eried/eucplanet/releases/latest).
   Look for the asset named `hud-<version>.apk`.
2. **Sideload it** onto the HUD device (`adb install hud-<version>.apk`
   from a host machine, or open the APK from a USB stick on the HUD).
3. **Launch** the EUC Planet HUD app. It will display its own local
   IP address on the splash screen.
4. **On the phone**, open *Settings → HUD*, enable the HUD link, and
   type the IP the HUD is showing (the port defaults to `28080`).
   The HUD's "waiting for phone" splash flips into the live carousel
   within a second.

## Companion devices

The HUD has been tested on:

- **MotoEye E6** dash panel (the original target)
- **Generic Android tablets** behind a windscreen
- **Old phones** mounted on the handlebar / cluster
- **HUD-class Android devices** (any 480×800-ish transflective panel)

The protocol is rate-limited to ~5 frames per second over WebSocket,
so even low-end devices keep up. WiFi-direct or phone-hotspot link is
fine; no internet needed once paired.

## Source + release notes

The HUD lives in the [`hud/`](../../hud) module of the main repo. Each
GitHub Release attaches a fresh `hud-<version>.apk` alongside the
phone APK + Wear OS APK + Garmin Connect IQ artifacts. Release notes
on the [releases page](https://github.com/eried/eucplanet/releases)
describe what changed per version.

For the protocol spec + how the wire frame is laid out, see
[`hud-protocol/`](../../hud-protocol) and the `docs/protocols/`
references.
