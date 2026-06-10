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

There's now a ready, **verified** Xcode project: **`iosApp/iosApp.xcodeproj`**
(target `EucPlanet`, bundle id `com.eried.eucplanet.ios`). A Gradle build phase
builds the shared framework for the active SDK (device or simulator), the thin
UIKit host (`AppDelegate.swift` → `MainViewControllerKt.MainViewController()`)
compiles against it, and `Shared.framework` is embedded + signed automatically.
It was verified with `xcodebuild` (simulator) and the produced app runs.

To run on your iPhone (on the Mac, in the synced `~/eucplanet` checkout):

1. `open iosApp/iosApp.xcodeproj`
2. Target **EucPlanet** → **Signing & Capabilities** → set **Team** to your
   Personal Team (the Apple ID you added). Automatic signing handles the cert +
   provisioning profile + device registration.
3. Pick your iPhone in the device menu (USB once; wifi after) → **Run** (⌘R).

Notes: the build phase sets `JAVA_HOME` from `~/.jdks/jdk-17*` and `ANDROID_HOME`
from `~/Library/Android/sdk`; `ENABLE_USER_SCRIPT_SANDBOXING` is off so it can run
Gradle. Manual framework builds if needed:

- Device framework: `./gradlew :shared:linkDebugFrameworkIosArm64`
- Simulator framework: `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`

## One-command deploy (after the one-time setup)

Once your Apple ID is in Xcode and the iPhone is paired, you don't need the Xcode
GUI each time — there's a script:

```sh
TEAM=<YOURTEAMID> bash iosApp/deploy-device.sh
```

It finds the paired device, builds + signs (`xcodebuild -allowProvisioningUpdates`,
which auto-creates the provisioning profile), and installs via `devicectl` (USB or
wifi). Your Team ID is in Xcode → Settings → Accounts, or the `(TEAMID)` suffix from
`security find-identity -v -p codesigning`. I can run this for you over SSH once the
prerequisites exist.

## Notes / gotchas

- **Free provisioning expires after 7 days** — the app stops launching and you
  re-install (just hit Run again). Max 3 sideloaded apps per Apple ID.
- **Bluetooth permission**: `Info.plist` already carries
  `NSBluetoothAlwaysUsageDescription`, so the first scan prompts for permission.
- **The Simulator has no Bluetooth radio**, which is exactly why the live wheel
  telemetry can only be validated on this real device.
- Watching the device screen headlessly once it's paired:
  `xcrun devicectl device screenshot --device <id> out.png`.
