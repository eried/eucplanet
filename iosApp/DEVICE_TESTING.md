# Testing EUC Planet on a real iPhone

**Status (2026-06-10):** the shared app is verified to build for a real device —
`./gradlew :shared:linkDebugFrameworkIosArm64` produces a `Mach-O arm64, platform
IOS` framework containing the whole app (BLE, adapters, dashboard, settings,
Service Mode, TTS, persistence). The only thing left is **code signing + install**,
which needs your Apple ID and one USB pairing — neither can be done over SSH.

## What only you can do (one-time)

1. **Add your Apple ID to Xcode** — Xcode → Settings → Accounts → "+" → Apple ID.
   This creates a free **Personal Team** + a development signing certificate. No
   paid ($99) developer account needed for testing.
2. **Pair the iPhone — the first time must be USB.** Plug the iPhone into the Mac
   mini with a cable once. Apple has no pure-wifi first pairing.
3. On the iPhone: **Settings → Privacy & Security → Developer Mode → On**, reboot,
   and tap **Trust This Computer** when prompted.

## Then it's wireless

In Xcode → **Window → Devices and Simulators** → select the iPhone → check
**"Connect via network."** After that first USB pairing, every build / install /
run goes over wifi — no cable again.

## Building + running the app

The shared framework is built by Gradle; the app is a thin UIKit host
(`iosApp/AppDelegate.swift` → `MainViewControllerKt.MainViewController()`).

- Device framework: `./gradlew :shared:linkDebugFrameworkIosArm64`
- Simulator framework: `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`

To run on the device you need a signed `.app`. The cleanest route is a small Xcode
project that embeds the framework and signs with your Personal Team — open it,
pick your iPhone, hit Run (USB once, then wifi). Ping me when your Apple ID is in
Xcode and the phone is pairable and I'll wire that project up with you so we can
iterate on any signing prompts live.

## Notes / gotchas

- **Free provisioning expires after 7 days** — the app stops launching and you
  re-install (just hit Run again). Max 3 sideloaded apps per Apple ID.
- **Bluetooth permission**: `Info.plist` already carries
  `NSBluetoothAlwaysUsageDescription`, so the first scan prompts for permission.
- **The Simulator has no Bluetooth radio**, which is exactly why the live wheel
  telemetry can only be validated on this real device.
- Watching the device screen headlessly once it's paired:
  `xcrun devicectl device screenshot --device <id> out.png`.
