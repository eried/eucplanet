# HUD companion: testing without a Motoeye

The `:hud` module ships an APK for aftermarket motorcycle HUDs (the Motoeye E6
is the reference target). These devices are stock-ish Android, so most of the
app can be developed and verified inside the standard Android Studio emulator.
This doc captures what the emulator covers, what it doesn't, and how to wire
the two halves together (`:app` on a phone, `:hud` on the emulator) so you
can ride-test code paths end to end.

---

## What the emulator can verify

| Surface | Verifiable in emulator | Notes |
|---|---|---|
| All four screens render | Yes | Compose, no hardware deps. |
| Remote-button navigation | Yes | Send keycodes via `adb shell input keyevent`. |
| mDNS discovery (`_eucplanet._tcp`) | Yes | Two emulators on the same host, or one emulator + a real phone on the same WiFi. |
| SSE state stream + reconnect | Yes | `:app` on the other end pushes frames as soon as `hudServerEnabled` is on. |
| Tile proxy + map rendering | Yes | The emulator renders Carto tiles fetched through the phone-side proxy. |
| Turn-by-turn arrow + distance | Yes | Trigger by starting a route on the phone-side app. |
| Rear camera preview | Partial | Emulator exposes a virtual back-camera; image content is the rotating box, not actual footage. |
| Exact display safe area / DPI | No | Set the AVD to 800×480 landscape as a reasonable proxy. |
| Real IR remote keycodes | No | Most aftermarket HUDs send standard `DPAD_*` codes; if a unit differs, extend `HudActivity.onKeyDown`. |

If a behaviour depends on real hardware (camera HAL quirks, exact DPI, the
remote's launcher integration) call it out in the PR and gate it behind a
`TODO(hud-device)` comment so a future tester can confirm.

---

## AVD recipe (Android Studio → Device Manager → Create device)

| Field | Value |
|---|---|
| Category | **Hardware profile → New Hardware Profile** |
| Name | `Motoeye E6 (proxy)` |
| Screen size | 5.0" |
| Resolution | **800 × 480** (landscape) |
| Density | `hdpi` (~213 dpi) |
| RAM | 2 GB |
| Storage | 4 GB |
| Hardware buttons | **Yes** (DPad and OK) |
| Has battery | No |
| Default orientation | Landscape |
| Cameras | Back: `Emulated`, Front: `None` |

Then create an AVD from this profile with system image **Android 11.0 (R) / API
30, Google APIs, x86_64**. API 30 matches what Motoeye E6 units ship with; the
"Google APIs" image gives us a working CameraX and mDNS stack, while the bare
AOSP image misses both.

Save the AVD as `motoeye_proxy`.

---

## Running the two ends together

You need the phone-side `:app` and the HUD-side `:hud` on the same LAN so the
HUD can discover the phone via mDNS.

### Option A — two emulators on the host

```bash
# Terminal 1: phone-shaped AVD (any modern Pixel works fine)
emulator -avd Pixel_7_API_34 -netdelay none -netspeed full

# Terminal 2: HUD-shaped AVD
emulator -avd motoeye_proxy -netdelay none -netspeed full
```

Both emulators sit on the host's `10.0.2.x` virtual LAN. The phone advertises
on the host's primary interface; the HUD's JmDNS sees the advertisement
because the Android emulator forwards multicast across that virtual switch.

```bash
# Install the apps (run once per emulator)
./gradlew :app:installDebug   # → on the phone emulator
./gradlew :hud:installDebug   # → on the HUD emulator
```

### Option B — one emulator + a real phone

If you want to verify the actual hotspot path:

1. Turn the phone's WiFi hotspot on.
2. Connect a laptop to that hotspot.
3. Run the HUD emulator on the laptop (`emulator -netfast`).
4. Confirm the laptop can `curl http://<phone-ip>:28080/health` first — if
   that 404s, the hotspot is firewalling client-to-client traffic and you
   need Option A.

### Option C — both on real devices

Best signal, only available once a Motoeye unit is in hand. Sideload the HUD
APK exactly as the forum post describes (USB-mount E6, drop APK into root,
install via App Installer).

---

## Enabling the HUD server on the phone

The phone server is **off by default** (`AppSettings.hudServerEnabled`).
Enable it for testing through the phone-app Settings → HUD section, or set
the flag manually:

```bash
adb shell "am force-stop com.eried.eucplanet"
adb shell "am start -n com.eried.eucplanet/.MainActivity"
# (toggle in Settings; the WheelService starts the HudServer when the toggle flips.)
```

A live phone exposes:

```
GET  http://<phone-ip>:28080/health        → "eucplanet-hud ok"
GET  http://<phone-ip>:28080/state         → text/event-stream JSON frames @ 5 Hz
GET  http://<phone-ip>:28080/tiles/{z}/{x}/{y}
POST http://<phone-ip>:28080/command       → {"type":"ToggleLight"} etc.
```

`curl -N http://<phone-ip>:28080/state` is a quick way to confirm the phone
is publishing before you bring the HUD APK up.

---

## Simulating the remote in the emulator

The HUD remote sends standard Android keycodes; `adb shell input keyevent`
mimics it perfectly:

```bash
# Switch screens
adb shell input keyevent KEYCODE_DPAD_LEFT       # 21
adb shell input keyevent KEYCODE_DPAD_RIGHT      # 22

# Dashboard EUC/GPS toggle, Map zoom
adb shell input keyevent KEYCODE_DPAD_UP         # 19
adb shell input keyevent KEYCODE_DPAD_DOWN       # 20

# Pair / OK
adb shell input keyevent KEYCODE_DPAD_CENTER     # 23

# Exit
adb shell input keyevent --longpress KEYCODE_ESCAPE
```

Or click the emulator's extended-controls D-pad. If you have an actual
Bluetooth keypad lying around, AVDs forward its keypress events to the
emulated device directly.

---

## What still needs a physical device

Track these in the GitHub issue for the v0.1 HUD release; do not claim "works
on Motoeye" until each is signed off by a tester with hardware:

1. **Remote keycode mapping.** Standard `DPAD_*` is the most likely case,
   but some aftermarket units remap LEFT/RIGHT to `MEDIA_PREVIOUS/NEXT` or
   custom 0x10x codes. Extend the `when` in
   `HudActivity.onKeyDown` if a tester reports otherwise.
2. **Rear camera HAL.** CameraX picks `DEFAULT_BACK_CAMERA`; on some
   non-Google-certified Android builds the camera2 service is missing or
   gated. Code already falls back to a "not available" placeholder.
3. **App launcher integration.** Some HUDs use a custom car-launcher
   instead of `Launcher3`. Our manifest's `intent-filter` is the standard
   `MAIN` + `LAUNCHER`, which every Android launcher honours, but verify
   the EUC Planet icon actually appears.
4. **Mass storage install.** Motoeye is installed by USB-mounting and
   dropping the APK into the device storage, then opening "App Installer".
   Test that the release APK is small enough that the on-device installer
   accepts it (usually fine up to ~50 MB).
5. **Hotspot client-to-client traffic.** Some phones (notably MIUI, some
   Samsung One UI versions) block client-to-client traffic on their
   hotspot — phone and HUD see each other in DHCP but can't open a TCP
   connection. There's no client-side workaround for this besides asking
   the rider to use a router or check phone settings.

When any of these is signed off on real hardware, mark the row as Verified
in the README support table.
