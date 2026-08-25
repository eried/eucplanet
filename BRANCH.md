# Amazfit (Zepp OS) watch companion

The EUC Planet wrist dial on Amazfit watches, next to Wear OS and Garmin.
Same Settings, Watch tab, same wire vocabulary, same look. Built and checked
against the Amazfit T-Rex 3; the Balance shares the screen and is in the
targets.

## What to test

You need the phone APK from this release and the watch app. The watch app
cannot be sideloaded from a file: run `npx zeus preview` from a checkout of
this branch (`amazfit-watch-app/`, after `zeus login` with a free Zepp
developer account) and scan the QR code with the Zepp app in Developer mode.
Full steps in `docs/AMAZFIT_SETUP.md`.

Then, with the Zepp app running on the phone:

- Open EUC Planet, connect the wheel, open EUC Planet on the watch. The dial
  should show speed, PWM, batteries, horn and light within a couple of seconds.
- Settings, Watch: an "Amazfit (Zepp OS)" card with a Live badge and a rate
  near 1 Hz (FAST tier: about 1.5 Hz, CONSERVATIVE: about 0.7 Hz).
- Horn and light circles, tap and hold. Select (top right) and Down (bottom
  right) buttons, click and long press. Bindings from Settings, Watch, Buttons.
- Keep display on, the battery toggles, PWM display mode, Prioritize PWM,
  speed unit label: all follow the phone settings on the next poll.
- An alarm rule with Vibrate on Watch buzzes the wrist.
- Stop the ride: the dial zeroes out; with Auto-stop on, the watch app closes.
- Kill the phone app: "Disconnected" after 10 s.
- Navigation with the watch mirror on: arrow and distance over the gauge.

## Report back

Issues go to https://github.com/eried/eucplanet/issues (or the testing
channel). Include the watch model and Zepp OS version, the Zepp app version,
phone and Android version, and the diagnostics log (Settings, Diagnostics,
Share): it carries an `amazfit: model=...` line when the watch app reached the
phone, which is the first thing to check.

## What changed

- Phone: `AmazfitBridge` serves the dial snapshot over a loopback-only HTTP
  socket (`127.0.0.1:28193`) that the Zepp app relays to the watch. Farewell,
  quit and alarm vibrate work like the other bridges. Settings, Watch gains an
  Amazfit device card and AMAZFIT badges on Auto-start and Dial rotation.
- Watch: `amazfit-watch-app/`, a Zepp OS mini program, port of the Garmin dial.
- CI: an `amazfit` job attaches `amazfit-<branch>-<sha>.zab`.
- Docs: `docs/AMAZFIT_SETUP.md`.

## Verified

- `./gradlew :app:testDebugUnitTest :app:assembleDebug`: 845 tests, 0
  failures (21 new for the bridge pieces), BUILD SUCCESSFUL.
- On a Pixel-class AVD (API 36) with a virtual InMotion V8S connected, with
  `adb forward tcp:28193 tcp:28193`: `GET /state` answers the full frame,
  `POST /control` with an `info:` intent lands in logcat and the diagnostics
  log, unknown paths get 404. Polling once a second from the PC makes
  Settings, Watch show "Amazfit T-Rex 3 / Amazfit (Zepp OS) / Live / 1.0 Hz",
  the AMAZFIT badge on Auto-start and the GARMIN badge on Keep display on.
- `zeus build` produces the `.zab` for both targets.
- Still to run: the dial itself in the Zepp OS Simulator (needs the T-Rex 3
  device simulator, which the simulator only downloads after a Zepp developer
  login) and on the real T-Rex 3.
