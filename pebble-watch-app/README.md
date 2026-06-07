# EUC Planet - Pebble watchapp

Telemetry companion for the new Pebble (Pebble Time 2 / PebbleOS), mirroring the
WearOS and Garmin companions. The phone app (`com.eried.eucplanet`, the
`-PpebbleEnabled` build) pushes live telemetry over Bluetooth via
PebbleKitAndroid2; this watchapp renders the glanceable dial (speed, battery,
PWM, volts and temp). Telemetry-only for now; button actions are a later branch,
same staging as Wear/Garmin.

## Protocol

AppMessage integer keys `0..9`, defined in `src/c/eucplanet.c` and mirroring
`app/src/main/java/com/eried/eucplanet/pebble/PebbleProtocol.kt` (`PebbleKeys`):
canonical metric ints (speed/volts/amps/temp scaled x10) plus unit-code strings,
converted on the watch. The app UUID `71cc8578-8aad-4179-8d5c-98bb0b13c2e1` must
match `PebbleBridge.PEBBLE_APP_UUID` on the phone.

## Build and test (no Pebble hardware needed)

Toolchain is the modern Core Devices SDK via `uv`:

    uv tool install pebble-tool --python 3.13
    pebble sdk install latest

Build, then run in the Pebble Time 2 emulator:

    pebble build
    pebble install --emulator emery

The QEMU emulator needs SDL + a few libs: `libsdl2-2.0-0 libsndio7.0 libfdt1`.

### Visible window on Windows (WSL2 + WSLg)

A plain `pebble install --emulator emery` under WSLg opens a window that instantly
dies: WSLg's GPU GL path (d3d12) crashes qemu-pebble, and XWayland's RandR also
reports a bogus `131072x1` screen. Force SDL's native Wayland backend + software
GL (this is the known-good incantation):

    SDL_VIDEODRIVER=wayland SDL_VIDEO_X11_XRANDR=0 \
    LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
    pebble install --emulator emery

Export those four in `~/.bashrc` to make it the default. Fallbacks if the window
still misbehaves: qemu's built-in VNC -- `... --vnc` (needs
`sudo apt install qemu-system-data` plus a
`/usr/local/share/qemu/keymaps -> /usr/share/qemu/keymaps` symlink), then a VNC
viewer on `localhost:5900`; or CloudPebble (browser, zero setup).

For a headless screenshot (CI / quick check):

    SDL_VIDEODRIVER=dummy pebble install --emulator emery
    pebble screenshot out.png

Flip `EUC_DEMO` to `1` in `eucplanet.c` to seed sample telemetry in the emulator
without a phone. It MUST be `0` in committed builds.
