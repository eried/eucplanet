# next-experimental

New features land here first. They work on the wheels they were written for
and may not work on yours yet, and settings can move between builds. Ride it
only when you can cut the ride short.

## Worked on

- Everything that was here shipped in 0.22.0 and is on next-version now. New work starts from here.
- Release builds are obfuscated (Play asks for it by Feb 2027). Branch APKs are debug builds and do not change.
- HUD map: keeps working with the phone app in the background and no wheel connected. It used to say "Map tiles not reachable", now it says when it is waiting for the phone GPS.
- HUD map: no more white "Map data not yet available" screen. Where the map has no detail that deep it shows the closest zoom it has.
- HUD camera: let go of the camera when you leave the Camera screen. It held it while the HUD app was open, so the MotoEye recorder could not always start.

## Please test

- Once a release build of this lands: install it over your current one. Settings, trips, the wheel connection and Android Auto still work?

**HUD (MotoEye)**
- Install the phone and the HUD APK from this build. Open the HUD Map screen, leave the phone app, no wheel connected: does the map stay? And with the wheel connected, on any map style and zoom?
- Visit the Camera screen, go to another screen, then long press to start the MotoEye recorder: does it start every time?

## Reporting back

Open an issue with your wheel model and firmware (for Android Auto, the car
screen model and a photo). A Service Mode log beats a description; if it
crashed, Share crash log on the About screen.
