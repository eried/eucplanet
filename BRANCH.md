# Amazfit watches

EUC Planet now talks to Amazfit watches. The same dial that runs on Wear OS and
Garmin: speed gauge, PWM, three batteries, horn and light, navigation arrow,
plus the two side buttons. Built and tested against the T-Rex 3; the Balance
has the same screen and is in the build as well.

![The dial on a T-Rex 3](https://raw.githubusercontent.com/eried/eucplanet/feature/amazfit-watch/docs/screenshots/amazfit-dash.png)

## How it works, in one paragraph

Zepp OS does not let a phone app talk to a watch app directly, so the watch
asks instead. Its little helper inside the Zepp phone app fetches the dial data
from EUC Planet about once a second over the phone's own loopback address and
sends it up to the wrist. Nothing leaves the phone. It means the Zepp app has
to be running (it normally is, it handles your notifications), and it means
the watch cannot be launched from the phone: you open it from the app list.

## Installing

You need two things: the phone APK from this release, and the watch app.

**Phone.** Download `phone-amazfit-watch-<sha>.apk` below and install it. If
you have the Play Store version, uninstall that first, a debug build cannot
update over it.

**Watch.** Zepp OS only installs unsigned apps through the Zepp app's
developer mode, and only from a QR code:

1. In the Zepp app on your phone, go to Profile and tap the Zepp logo at the
   top seven times. A toast confirms "Developer mode".
2. Profile, Developer mode, Scan, and point the phone at this code. The watch
   picks the app up in a few seconds.

![Install QR](https://raw.githubusercontent.com/eried/eucplanet/feature/amazfit-watch/docs/screenshots/amazfit-preview-qr.png)

The code is valid until the 1st of September 2026. If it has expired, ask in
the testing channel for a fresh one, or build it yourself: install Node.js,
`npm i -g @zeppos/zeus-cli`, `zeus login` with a free Zepp account, then
`npx zeus preview` inside `amazfit-watch-app/` prints a new code. Details in
`docs/AMAZFIT_SETUP.md`.

## Using it

Open EUC Planet on the phone and connect the wheel, then open "EUC Planet" on
the watch. The dial says "Open EUC Planet on your phone" until the first data
arrives, and "Disconnected" if the phone goes quiet for ten seconds.

- Tap the horn circle for the horn, the light circle for the light. Hold
  either for the hold action.
- The side buttons follow the same layout as on Garmin: Select is button 1,
  Up is button 2 (both click and hold), Down is button 3 (click only). Assign
  what they do in Settings, Watch, Buttons.
- Settings, Watch shows the watch as "Amazfit (Zepp OS)" with a Live badge and
  the real update rate. Keep display on, the battery toggles, PWM display,
  Prioritize PWM and the speed unit label all apply on the next update.
- Auto-stop closes the watch app when you stop the ride; alarm rules set to
  vibrate on the watch buzz the wrist.

What it does not do: auto-start (Zepp OS has no way to launch an app from the
phone) and dial rotation. Both rows show an AMAZFIT badge in Settings so it is
clear where they stop.

## What to look for

- Does the dial follow the wheel without noticeable lag? The card in
  Settings, Watch should read around 1 Hz on Normal, 1.5 Hz on Fast.
- Do the taps and the three buttons reach the wheel? While Service Mode is
  recording, every key and tap the watch sees is written to the diagnostics
  log, so a log is enough to tell which side lost a press.
- Leave the phone in your pocket for a ride: does the watch keep updating, or
  does Android put the Zepp app to sleep? If it stops, check the battery
  settings for the Zepp app and tell us which phone you have.
- Stop the ride from the phone: does the watch app close?
- Anything odd in the layout on your watch model.

## Reporting

https://github.com/eried/eucplanet/issues or the testing channel. Include the
watch model and its Zepp OS version, the Zepp app version, the phone and its
Android version, and the diagnostics log from Settings, Diagnostics, Share. The
log has a line starting with `amazfit: model=` when the watch reached the
phone; if that line is missing, the problem is on the phone side.

## For developers

New code lives in `app/src/main/java/com/eried/eucplanet/amazfit/` (phone
side: a tiny loopback HTTP responder serving the same snapshot the Garmin
bridge builds) and `amazfit-watch-app/` (the Zepp OS mini program, a port of
the Garmin dial). CI attaches `amazfit-<branch>-<sha>.zab` to this release; that
is the store submission bundle, not something you can sideload. 845 unit
tests pass, 21 of them new. The simulator recipe, wire contract and the
Zepp simulator quirks are in `docs/AMAZFIT_SETUP.md`.
