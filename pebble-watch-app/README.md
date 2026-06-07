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

Build and run in the Pebble Time 2 emulator:

    pebble build
    pebble install --emulator emery

On Linux the QEMU emulator needs `libsdl2-2.0-0 libsndio7.0 libfdt1`. On Windows
use WSL2 (with WSLg for the window) or CloudPebble (browser, zero setup). For a
headless screenshot: `SDL_VIDEODRIVER=dummy pebble install --emulator emery`
then `pebble screenshot out.png`.

Flip `EUC_DEMO` to `1` in `eucplanet.c` to seed sample telemetry in the emulator
without a phone. It MUST be `0` in committed builds.
