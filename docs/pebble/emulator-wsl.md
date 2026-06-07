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

## Testing without hardware

Split the link in two. The **watch side** (rendering + the AppMessage receive
path) is fully testable in the emulator. Only the **app → CoreApp → watch BLE
leg** needs a real Pebble.

```
EUC Planet app (PebbleKitAndroid2)
  → Pebble Android app (coredevices.coreapp / CoreApp)   [Android IPC, same phone]
  → Bluetooth LE                                          ← only this leg needs HW
  → watch  (== the emulator, for everything below the BLE leg)
```

### Feed the emulator with `send-app-message` (WORKS — the no-hardware test)

The Core Devices `pebble` tool (4.9.169) ships a `send-app-message` command that
injects an AppMessage dict straight into the running emulator over the QEMU
protocol — a "fake phone" driving the watchapp's real `inbox_received`. Raw
integer keys map 1:1 to `eucplanet.c` / `PebbleProtocol.kt`:

```bash
pebble build && pebble install --emulator emery
pebble send-app-message --emulator emery \
  --app-uuid 71cc8578-8aad-4179-8d5c-98bb0b13c2e1 \
  --int 0=1 1=234 2=77 3=841 4=52 5=41 6=300 --string 7=kmh 8=C
# dial shows: EUC PLANET, 23 kmh, BATT 77%, PWM 41%, 84.1V 30C
```

`tools/emu-feed.sh [frames]` loops this into an animated simulated ride. This is
the real receive path with the exact wire format `PebbleBridge` sends; it does
NOT exercise PebbleKit Android's intent plumbing (that's the BLE leg). Note: the
`src/pkjs/` JS route does NOT work — this SDK doesn't bundle `src/pkjs/` into the
`.pbw`, so the JS never runs. Use `send-app-message`, not pkjs.

### Driving it from the REAL app — not today, but scaffolded

The CoreApp can't attach to the emulator over TCP **yet**. Source dig of
`coredevices/mobileapp` `libpebble3` (master):

- The transport layer already defines `PebbleSocketIdentifier(address)`,
  `TransportType.Socket`, and a full Kotlin port of the QEMU `0xFEED/0xBEEF`
  framing (`packets/Emulator.kt`) — clear groundwork for an emulator transport.
- BUT only `PebbleBle` and `PebbleBtClassic` `TransportConnector`s are
  implemented; a socket identifier hits `error("Transport not implemented")`
  (`di/LibPebbleModule.kt`), and nothing constructs a `PebbleSocketIdentifier`
  at runtime. So it's a stub.
- The new "developer connection" (`DevConnectionLANServer`, port 9000) is an
  *inbound* WebSocket server that lets pebble-tool/CloudPebble ride the phone's
  existing watch link — NOT an outbound emulator client.

So "PebbleKit Android needs a physical Pebble" is still effectively true for the
full app→CoreApp→watch path — but it's a "not yet," not a "never": if Core
Devices finishes the socket connector, you'd attach a
`PebbleSocketIdentifier("host:12344")` to a qemu-pebble serial socket. Worth
re-checking their repo periodically.

- Community option: `finebyte/AppMsgBridge` bridges an Android app / web UI /
  file → the emulator's pypkjs WebSocket (uses libpebble2). Lets an Android app
  push AppMessages into qemu-pebble — but via that bridge, not via CoreApp, and
  its README warns reliability isn't 100%.
- `emulator-5588` (Android emulator) has no Bluetooth radio either, so the BLE
  leg can't run there regardless.
