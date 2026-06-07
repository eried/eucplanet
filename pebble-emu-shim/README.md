# EUC Pebble Shim (dev-only, never shipped)

A fake Pebble phone app. It implements the **server** side of PebbleKitAndroid2
so the real EUC app talks to it exactly as it would the Core Devices CoreApp —
then it forwards the telemetry into the QEMU emulator. This lets the whole phone
side be tested with **no Pebble hardware**:

```
EUC app (real PebbleKitAndroid2 send path)
  → this shim  (BasePebbleSenderReceiver, Android IPC)
  → emu-bridge.py (WSL)
  → pebble send-app-message
  → emery emulator  → the dial renders
```

Everything our code does is exercised — the Paired-devices card, the bridge
pump, the exact key 0–9 wire format, the watchapp decode — except the literal
BLE radio + the real CoreApp internals (Pebble's own validated leg).

## How it works

- `ShimSenderReceiverService` declares the `io.rebble.pebblekit2.SEND_DATA_TO_WATCH`
  intent-filter. PebbleKitAndroid2's app-picker discovers it by that filter and
  auto-selects it when it's the only Pebble app installed (so **don't** install
  the real CoreApp alongside it). Each telemetry frame lands in
  `sendDataToPebble()` → forwarded to the bridge.
- `ShimActivity` "Connect" calls `sendOnAppOpened()` on the EUC app's listener,
  which flips its Paired-devices card to active and starts the telemetry pump.

## Run it

In WSL, with the emery emulator up and the watchapp installed
(`EUC_DEMO 0`, see `../pebble-watch-app/docs`):

```bash
# 1. emulator visible + watchapp installed (see pebble-watch-app/README.md)
SDL_VIDEODRIVER=wayland SDL_VIDEO_X11_XRANDR=0 LIBGL_ALWAYS_SOFTWARE=1 \
  GALLIUM_DRIVER=llvmpipe pebble install --emulator emery

# 2. start the bridge
python3 ../pebble-watch-app/tools/emu-bridge.py
```

On Windows:

```powershell
# 3. build + install both apps on the Android emulator
./gradlew :app:assembleDebug :pebble-emu-shim:assembleDebug
adb -s emulator-5588 install -r app/build/outputs/apk/debug/phone-debug.apk
adb -s emulator-5588 install -r pebble-emu-shim/build/outputs/apk/debug/pebble-emu-shim-debug.apk

# 4. route the shim's socket to the WSL bridge
adb -s emulator-5588 reverse tcp:5599 tcp:5599
```

Then: open the EUC app → enable Pebble in Settings → open **EUC Pebble Shim** →
tap **Connect**. The EUC app shows the Pebble device (active) and the emulator
dial starts moving with live telemetry.

If the shim can't reach the bridge, WSL localhost forwarding may be off; add a
Windows port proxy: `netsh interface portproxy add v4tov4 listenport=5599
connectport=5599 connectaddress=<wsl-ip>`.
