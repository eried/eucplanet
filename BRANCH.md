# next-experimental

New features land here first. They work on the wheels they were written for
and may not work on yours yet, and settings can move between builds. Ride it
only when you can cut the ride short.

## Worked on

- Crews pairing: scan or type a code from eucstats and the browser flies your colours.
- It lives in Settings then EUC Stats then Crews, and on the `eucplanet://pair` link.
- A code from anywhere but eucstats is refused unless you turned developer mode on yourself.
- Needs eucstats to have crews switched on; the web side says so if it does not.
- InMotion V6 support: connects, live telemetry, PWM, lights and horn.
- Writing to the wheel follows what its Bluetooth allows. Mostly the V6.
- Switch the light or lock by hand and that automation pauses. Fix in Needs attention hands it back.
- Branch builds install over each other. One last uninstall if you have an older one.
- Service Mode logs GPS speed next to the wheel's.
- Begode: current no longer gets stuck at 0 A after one noisy reading (#26).

## Please test

**Crews pairing**
- Camera at the QR on the eucstats Crews panel: does the app open on the pass screen?
- App did not open by itself? Settings, EUC Stats, Crews, type the six characters in.
- Does the browser flip to your crew within a couple of seconds of you approving?
- A QR from anywhere else should refuse and name the host it pointed at.
- The pass screen should say what it grants BEFORE you approve anything.

**InMotion V6**
- Speed matches GPS? Service Mode on, ride 2 minutes at a steady speed, share the log.
- Stays connected all ride? Lights and horn work?

**Begode**
- Current (A) stays live all ride instead of dropping to 0 A? Master v3 especially.

**Any wheel**
- Connects and responds like before: lights, horn, lock, alarms.
- Light or lock by hand: the automation stays out until you tap Fix?

## Reporting back

Open an issue with your wheel model and firmware. A Service Mode log beats a
description; if it crashed, Share crash log on the About screen.
