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

## Connecting the phone app (OPEN PROBLEM)

Goal: the real EUC Planet app drives the emulator with live telemetry, exactly as
it would a real Pebble Time 2 — so the link can be tested without hardware.

The stack:

```
EUC Planet app (PebbleKitAndroid2)
  → Pebble Android app (coredevices.coreapp)   [Android IPC, same phone]
  → transport
  → watch
```

Why it isn't plug-and-play:

- A real watch's transport is **BLE**. The `emulator-5588` Android emulator has
  **no Bluetooth radio**, and the QEMU emulator is not a BLE device.
- The QEMU emulator's "phone" is **pypkjs** (the pebble tool's built-in phone
  simulator), not the Core Devices Pebble app. The real app talks to the real
  Pebble app, so there is no out-of-the-box bridge between the two.

Avenues to investigate (rough order):

1. **Does the Core Devices Pebble app support an emulator / TCP transport?** It's
   open source (github.com/coredevices, `libpebblecommon`). Look for a
   developer/QEMU/socket transport that could point at the emulator instead of
   BLE.
2. **The emulator's serial/TCP sockets.** qemu_micro_pebble exposes local TCP
   sockets that pypkjs attaches to for the Bluetooth-link emulation; list them
   with `ss -tlnp` while the emulator runs. If the Pebble app can speak that
   protocol it could attach directly.
3. **Networking 5588 ↔ WSL.** The Android emulator reaches the Windows host via
   `10.0.2.2`; the WSL distro has its own IP. Any TCP bridge needs port
   forwarding between them (`netsh interface portproxy` on Windows), or run the
   app on a physical phone on the same LAN as WSL.

### Practical local test without solving the link

Inject the telemetry the phone *would* send straight into the emulator via a
`pkjs` (pypkjs executes it), so the watchapp renders live values over the real
AppMessage receive path. This validates the watch end; only the BLE/transport
hop is left for real hardware. Do **not** ship the pkjs — the phone app is the
production companion; it's a test harness only.
