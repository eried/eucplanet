# next-experimental

New features land here first. They work on the wheels they were written for
and may not work on yours yet, and settings can move between builds. Ride it
only when you can cut the ride short.

## Worked on

- Everything that was here shipped in 0.22.0 and is on next-version now. New work starts from here.
- Release builds are obfuscated (Play asks for it by Feb 2027). Branch APKs are debug builds and do not change.
- HUD map: keeps working with the phone app in the background and no wheel connected. It used to say "Map tiles not reachable", now it says when it is waiting for the phone GPS.

## Please test

- Once a release build of this lands: install it over your current one. Settings, trips, the wheel connection and Android Auto still work?

**HUD (MotoEye)**
- Install the phone and the HUD APK from this build. Open the HUD Map screen, leave the phone app, no wheel connected: does the map stay?

## Reporting back

Open an issue with your wheel model and firmware (for Android Auto, the car
screen model and a photo). A Service Mode log beats a description; if it
crashed, Share crash log on the About screen.
