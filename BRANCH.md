# next-experimental

New features land here first. They work on the wheels they were written for
and may not work on yours yet, and settings can move between builds. Ride it
only when you can cut the ride short.

## Worked on

- InMotion V6 support: connects, live telemetry, PWM, lights and horn.
- Writing to the wheel follows what its Bluetooth allows. Mostly the V6.
- Switch the light or lock by hand and that automation pauses. Fix in Needs attention hands it back.
- Branch builds install over each other. One last uninstall if you have an older one.
- Service Mode logs GPS speed next to the wheel's.
- Begode: current no longer gets stuck at 0 A after one noisy reading (#26).

## Please test

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
