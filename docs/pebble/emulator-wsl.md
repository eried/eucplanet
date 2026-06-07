# Pebble emulator in WSL + connecting the phone app

Hard-won setup notes for developing the EUC Planet Pebble watchapp
(`pebble-watch-app/`) on Windows without Pebble hardware.

## Status

- ✅ The Pebble Time 2 (`emery`) emulator builds, runs, and renders the watchapp
  dial in WSL2 + WSLg (visible window) — see the command below.
- ✅ The watchapp decodes + renders the exact wire format the phone sends
  (verified by seeding `EUC_DEMO=1` and screenshotting: `23 kmh`, `BATT 77%`,
  `PWM 41%`, `84.1V 30C`).
- ❌ **OPEN:** driving the emulator from the real EUC Planet phone app
  (PebbleKitAndroid2) — the live phone → emulator telemetry link. See
  "Connecting the phone app" below.

## Toolchain (one-time, in WSL Ubuntu)

```bash
uv tool install pebble-tool --python 3.13
pebble sdk install latest
sudo apt install -y libsdl2-2.0-0 libsndio7.0 libfdt1    # qemu-pebble runtime deps
```

## Run the emulator with a VISIBLE window (WSL2 + WSLg)

A plain `pebble install --emulator emery` opens a window that instantly dies:
WSLg's GPU GL path (d3d12) crashes qemu-pebble, and XWayland's RandR reports a
bogus `131072x1` screen. Force SDL's native Wayland backend + software GL — this
is the known-good incantation:

```bash
SDL_VIDEODRIVER=wayland SDL_VIDEO_X11_XRANDR=0 \
LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
pebble install --emulator emery
```

Run it **foreground in an interactive terminal** — each `wsl … -- bash -lc`
one-shot is a throwaway session, so a detached/background launch lets qemu die.
Export the four vars in `~/.bashrc` to make it the default.

### Fallbacks

- **VNC** (no SDL window at all): `… --vnc` needs the keymaps qemu-pebble looks
  for in `/usr/local/share/qemu/keymaps`, which the relocated SDK lacks:
  ```bash
  sudo apt install -y qemu-system-data
  sudo mkdir -p /usr/local/share/qemu
  sudo ln -sfn /usr/share/qemu/keymaps /usr/local/share/qemu/keymaps
  SDL_VIDEODRIVER=dummy pebble install --emulator emery --vnc
  ```
  then point a VNC viewer at `localhost:5900`.
- **Headless screenshot** (CI / quick check):
  ```bash
  SDL_VIDEODRIVER=dummy pebble install --emulator emery
  pebble screenshot out.png
  ```
- **CloudPebble** (browser, zero local setup).

### Gotchas

- `pkill -f qemu-pebble` matches its own bash command line and kills the shell —
  use `pkill qemu-pebble` (match by name).
- Leftover `<defunct>`/zombie qemu processes confuse `ps`; `wsl --shutdown` (from
  Windows) clears them.
- The seed flag `EUC_DEMO` in `src/c/eucplanet.c` must be `0` in committed builds.

## Connecting the phone app — needs real hardware (researched, settled)

Goal was: drive the emulator from the real EUC Planet app so the link could be
tested without hardware. **Researched conclusion: not possible. PebbleKit Android
companion apps require a physical Pebble.** Don't re-investigate this.

The stack:

```
EUC Planet app (PebbleKitAndroid2)
  → Pebble Android app (coredevices.coreapp / CoreApp)   [Android IPC, same phone]
  → Bluetooth LE
  → watch
```

Why there is no emulator bridge:

- The emulator covers the **watch side only**. Rebble + `pebble-android-sdk`
  docs state plainly: *"a physical Pebble is needed to test PebbleKit Android
  apps, since the emulator cannot communicate with a phone."*
- The emulator's "phone" is **pypkjs**, which only runs PebbleKit **JS** — not
  native PebbleKit Android / PebbleKitAndroid2.
- The Core Devices **CoreApp** (github.com/coredevices/mobileapp) is
  **Bluetooth-LE only** — no emulator mode, no dev-connection or TCP transport.
  Their own team "manually tested with real hardware." So there is no
  phone→emulator path, not even TCP.
- `emulator-5588` (Android emulator) also has no Bluetooth radio.

### How Pebble devs actually split this

- **Watch-side** (the C watchapp): develop + verify in the emulator (this doc).
- **Phone-side** (the PebbleKitAndroid2 companion): a real **Pebble Time 2** +
  CoreApp over BLE. This is how everyone, including Core Devices, does it.

### Closest local test without hardware

Inject the telemetry the phone *would* send straight into the emulator via a
`pkjs` (pypkjs executes it), so the watchapp renders live values over the real
AppMessage receive path. This validates the watch end; only the BLE companion
hop is left for real hardware. Do **not** ship the pkjs — the phone app is the
production companion; it's a test harness only.
